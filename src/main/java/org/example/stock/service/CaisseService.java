package org.example.stock.service;

import org.example.stock.model.MouvementCaisse;
import org.example.stock.model.Utilisateur;
import org.example.stock.model.Achat;
import org.example.stock.model.Vente;
import org.example.stock.model.Montants;
import org.example.stock.repository.MouvementCaisseRepository;
import org.example.stock.repository.AchatRepository;
import org.example.stock.repository.VenteRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validator;
import org.example.stock.form.OuvertureCaisseForm;
import org.example.stock.enums.Role;
import org.example.stock.model.Emails;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.time.ZoneId;

@Service
public class CaisseService {

    @Autowired
    private MouvementCaisseRepository mouvementRepo;
    @Autowired private BoutiqueService boutiqueService;
    @Autowired private AchatRepository achatRepository;
    @Autowired private VenteRepository venteRepository;
    @Autowired private UtilisateurRepository utilisateurRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private Validator validator;
    @Autowired @Qualifier("recoveryClock") private Clock clock;
    @Autowired private InitialisationBoutiqueService initialisation;

    @Transactional(readOnly=true)
    public boolean peutInitialiser() {
        gerant();
        return initialisation.estOuverte() && !mouvementRepo.existsByBoutiqueId(boutiqueService.idBoutiqueCourante());
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void initialiser(OuvertureCaisseForm formulaire) {
        Utilisateur acteur=gerant();
        entityManager.refresh(acteur,LockModeType.PESSIMISTIC_WRITE);
        if(!acteur.isActif()||acteur.getRole()!=Role.ADMIN) throw interdit();
        var boutique=initialisation.verrouillerEtExigerOuverte();
        if(formulaire==null||!validator.validate(formulaire).isEmpty()) throw new IllegalArgumentException("Le montant et le motif du solde initial doivent être valides.");
        if(mouvementRepo.existsByBoutiqueId(boutique.getId())) throw new IllegalArgumentException("Le solde initial se renseigne une seule fois, avant le premier mouvement de caisse.");
        MouvementCaisse mouvement=new MouvementCaisse();
        mouvement.setBoutique(boutique);mouvement.setUtilisateur(acteur);
        mouvement.setMontant(Montants.positifOuNul(formulaire.getMontant(),"Le solde initial"));
        mouvement.setType("OUVERTURE");mouvement.setSource("SOLDE_INITIAL");mouvement.setMotif(formulaire.getMotif());
        mouvement.setDateMouvement(LocalDateTime.now(clock.withZone(ZoneId.systemDefault())));
        mouvementRepo.saveAndFlush(mouvement);
    }

    private Utilisateur gerant() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!auth.isAuthenticated()||auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_ADMIN"))) throw interdit();
        var compte=utilisateurRepository.findByEmail(Emails.normaliser(auth.getName())).orElseThrow(CaisseService::interdit);
        if(!compte.isActif()||compte.getRole()!=Role.ADMIN) throw interdit();
        boutiqueService.exigerAppartenance(compte.getBoutique());return compte;
    }
    private static ResponseStatusException interdit(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"Ouverture de caisse réservée au gérant");}

    public BigDecimal getSoldeActuel() {
        return normaliser(mouvementRepo.calculerSoldeTotal(boutiqueService.idBoutiqueCourante()));
    }

    public BigDecimal getSoldeOuverture(LocalDate date) {
        Long boutiqueId=boutiqueService.idBoutiqueCourante();
        return normaliser(mouvementRepo.calculerSoldeAvant(date.atStartOfDay(), boutiqueId))
                .add(normaliser(mouvementRepo.calculerInitialDuJour(date.atStartOfDay(),date.plusDays(1).atStartOfDay(),boutiqueId)));
    }

    public BigDecimal getEntreesDuJour(LocalDate date) {
        return normaliser(mouvementRepo.calculerEntreesDepuis(date.atStartOfDay(), boutiqueService.idBoutiqueCourante()));
    }

    public BigDecimal getSortiesDuJour(LocalDate date) {
        return normaliser(mouvementRepo.calculerSortiesDepuis(date.atStartOfDay(), boutiqueService.idBoutiqueCourante())).abs();
    }

    public BigDecimal getNetDuJour(LocalDate date) {
        return normaliser(mouvementRepo.calculerFluxTotalDepuis(date.atStartOfDay(), boutiqueService.idBoutiqueCourante()));
    }

    public BigDecimal getSoldeCloture(LocalDate date) {
        return getSoldeOuverture(date).add(getNetDuJour(date));
    }

    public BigDecimal getCaisseDuJour(LocalDate date) {
        return getEntreesDuJour(date).subtract(getSortiesDuJour(date));
    }

    public List<MouvementCaisse> getMouvementsDuJour(LocalDate date) {
        return mouvementRepo.findByDateMouvementGreaterThanEqualAndBoutiqueIdOrderByDateMouvementDesc(date.atStartOfDay(), boutiqueService.idBoutiqueCourante());
    }

    public List<MouvementCaisse> listerTous() {
        return mouvementRepo.findByBoutiqueIdOrderByDateMouvementDesc(boutiqueService.idBoutiqueCourante());
    }

    @Transactional
    public void enregistrerEntree(BigDecimal montant, String motif, String source, Utilisateur utilisateur) {
        enregistrerEntree(montant, motif, source, utilisateur, null);
    }

    @Transactional
    public void enregistrerEntreeVente(BigDecimal montant, String motif, Vente vente, Utilisateur utilisateur) {
        enregistrerEntree(montant, motif, "VENTE", utilisateur, vente);
    }

    public List<MouvementCaisse> listerReglementsVente(Long venteId) {
        return mouvementRepo.findByVenteIdAndBoutiqueIdOrderByDateMouvementDescIdDesc(venteId, boutiqueService.idBoutiqueCourante());
    }

    private void enregistrerEntree(BigDecimal montant, String motif, String source, Utilisateur utilisateur, Vente vente) {
        verifierSourceOperationnelle(source);
        utilisateur = utilisateurDeLaBoutique(utilisateur);
        if (vente != null) {
            vente = venteRepository.findByIdAndBoutiqueId(vente.getId(), boutiqueService.idBoutiqueCourante())
                    .orElseThrow(() -> new IllegalArgumentException("Vente introuvable"));
        }
        if (montant == null || montant.signum() == 0) {
            return;
        }
        if (montant.signum() < 0) {
            throw new IllegalArgumentException("Le montant d'une entree de caisse doit etre positif.");
        }

        montant = Montants.valider(montant, "Le montant de caisse");
        initialisation.verrouillerEtClorePourActivite();
        MouvementCaisse mouvement = new MouvementCaisse();
        mouvement.setBoutique(boutiqueService.boutiqueCourante());
        mouvement.setDateMouvement(LocalDateTime.now());
        mouvement.setMontant(montant.abs());
        mouvement.setType("ENTREE");
        mouvement.setMotif(motif);
        mouvement.setSource(source);
        mouvement.setUtilisateur(utilisateur);
        mouvement.setVente(vente);

        mouvementRepo.save(mouvement);
    }

    @Transactional
    public void enregistrerSortie(BigDecimal montant, String motif, String source, Utilisateur utilisateur) {
        enregistrerSortie(montant, motif, source, utilisateur, null);
    }

    @Transactional
    public void enregistrerSortieAchat(BigDecimal montant, String motif, Achat achat, Utilisateur utilisateur) {
        enregistrerSortie(montant, motif, "ACHAT", utilisateur, achat);
    }

    public List<MouvementCaisse> listerReglementsAchat(Long achatId) {
        return mouvementRepo.findByAchatIdAndBoutiqueIdOrderByDateMouvementDescIdDesc(achatId, boutiqueService.idBoutiqueCourante());
    }

    private void enregistrerSortie(BigDecimal montant, String motif, String source, Utilisateur utilisateur, Achat achat) {
        verifierSourceOperationnelle(source);
        utilisateur = utilisateurDeLaBoutique(utilisateur);
        if (achat != null) {
            achat = achatRepository.findByIdAndBoutiqueId(achat.getId(), boutiqueService.idBoutiqueCourante())
                    .orElseThrow(() -> new IllegalArgumentException("Achat introuvable"));
        }
        if (montant == null || montant.signum() == 0) {
            return;
        }

        montant = Montants.valider(montant, "Le montant de caisse");
        initialisation.verrouillerEtClorePourActivite();
        MouvementCaisse mouvement = new MouvementCaisse();
        mouvement.setBoutique(boutiqueService.boutiqueCourante());
        mouvement.setDateMouvement(LocalDateTime.now());

        if (montant.signum() > 0) {
            mouvement.setMontant(montant.abs().negate());
            mouvement.setType("SORTIE");
        } else {
            mouvement.setMontant(montant.abs());
            mouvement.setType("CORRECTION_ENTREE");
        }

        mouvement.setMotif(motif);
        mouvement.setSource(source);
        mouvement.setUtilisateur(utilisateur);
        mouvement.setAchat(achat);

        mouvementRepo.save(mouvement);
    }

    private BigDecimal normaliser(BigDecimal valeur) {
        return Montants.ouZero(valeur);
    }

    private void verifierSourceOperationnelle(String source) {
        if (source != null && "SOLDE_INITIAL".equalsIgnoreCase(source.strip())) {
            throw new IllegalArgumentException("Le solde initial se renseigne uniquement par le formulaire d'ouverture de caisse, pendant le démarrage de la boutique.");
        }
    }

    private Utilisateur utilisateurDeLaBoutique(Utilisateur utilisateur) {
        // La boutique et les relations sont relues côté serveur, même pour un appel hors formulaire.
        boutiqueService.idBoutiqueCourante();
        if (utilisateur == null) return null;
        if (utilisateur.getId() == null) throw new IllegalArgumentException("Utilisateur introuvable");
        Utilisateur enregistre = utilisateurRepository.findById(utilisateur.getId())
                .orElseThrow(() -> new IllegalArgumentException("Utilisateur introuvable"));
        boutiqueService.exigerAppartenance(enregistre.getBoutique());
        return enregistre;
    }
}
