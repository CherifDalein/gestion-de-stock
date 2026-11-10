package org.example.stock.service;

import org.example.stock.model.MouvementCaisse;
import org.example.stock.model.Utilisateur;
import org.example.stock.model.Achat;
import org.example.stock.model.Client;
import org.example.stock.model.Fournisseur;
import org.example.stock.model.Retour;
import org.example.stock.model.Vente;
import org.example.stock.model.Montants;
import org.example.stock.model.SourceMouvement;
import org.example.stock.repository.MouvementCaisseRepository;
import org.example.stock.repository.AchatRepository;
import org.example.stock.repository.VenteRepository;
import org.example.stock.repository.StockLockRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Objects;
import org.example.stock.enums.CompteTresorerie;
import org.example.stock.enums.TypeOperationTresorerie;
import org.example.stock.form.OperationTresorerieForm;
import org.example.stock.form.TransfertTresorerieForm;
import org.example.stock.form.DepenseTresorerieForm;
import org.example.stock.model.OperationTresorerie;
import org.example.stock.repository.CompteTresorerieBoutiqueRepository;
import org.example.stock.repository.OperationTresorerieRepository;
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
    @Autowired private StockLockRepository verrous;
    @Autowired private CompteTresorerieBoutiqueRepository comptes;
    @Autowired private OperationTresorerieRepository operations;

    @Transactional(readOnly = true)
    public void verifierGestionTresorerie() { gerant(); }

    @Transactional(readOnly=true)
    public boolean peutInitialiser() {
        return peutInitialiser(CompteTresorerie.CAISSE);
    }

    @Transactional(readOnly = true)
    public boolean peutInitialiser(CompteTresorerie compte) {
        gerant();
        exigerCompte(compte);
        return initialisation.estOuverte()
                && !mouvementRepo.existsByBoutiqueIdAndCompte(boutiqueService.idBoutiqueCourante(), compte);
    }

    @Transactional(readOnly = true)
    public List<CompteTresorerie> getComptesInitialisables() {
        gerant();
        if (!initialisation.estOuverte()) return List.of();
        Long boutiqueId = boutiqueService.idBoutiqueCourante();
        return java.util.Arrays.stream(CompteTresorerie.values())
                .filter(compte -> !mouvementRepo.existsByBoutiqueIdAndCompte(boutiqueId, compte)).toList();
    }

    @Transactional(readOnly = true)
    public Map<CompteTresorerie, BigDecimal> getSoldesParCompte() {
        Long boutiqueId = boutiqueService.idBoutiqueCourante();
        Map<CompteTresorerie, BigDecimal> soldes = new EnumMap<>(CompteTresorerie.class);
        for (CompteTresorerie compte : CompteTresorerie.values()) {
            soldes.put(compte, normaliser(mouvementRepo.calculerSoldeCompte(boutiqueId, compte)));
        }
        return soldes;
    }

    @Transactional(readOnly = true)
    public BigDecimal getSoldeCompte(CompteTresorerie compte) {
        exigerCompte(compte);
        return normaliser(mouvementRepo.calculerSoldeCompte(boutiqueService.idBoutiqueCourante(), compte));
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void initialiser(OuvertureCaisseForm formulaire) {
        // Ordre global des verrous : la boutique d'abord, puis le compte du gérant (lecture partagée), puis les comptes.
        verrous.verrouillerBoutiqueCourante();
        Utilisateur acteur=gerant();
        entityManager.refresh(acteur,LockModeType.PESSIMISTIC_READ);
        if(!acteur.isActif()||acteur.getRole()!=Role.ADMIN) throw interdit();
        var boutique=initialisation.verrouillerEtExigerOuverte();
        if(formulaire==null||!validator.validate(formulaire).isEmpty()) throw new IllegalArgumentException("Le montant et le motif du solde initial doivent être valides.");
        CompteTresorerie compte = formulaire.getComptePaiement();
        verrouillerComptes(boutique.getId(), compte);
        if(mouvementRepo.existsByBoutiqueIdAndCompte(boutique.getId(), compte)) throw new IllegalArgumentException("Le solde initial de ce compte se renseigne une seule fois, avant son premier mouvement.");
        MouvementCaisse mouvement=new MouvementCaisse();
        mouvement.setBoutique(boutique);mouvement.setUtilisateur(acteur);mouvement.setCompte(compte);
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
    private static ResponseStatusException interdit(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"Gestion de la trésorerie réservée au gérant");}

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
        enregistrerEntree(montant, motif, source, utilisateur, null, null, CompteTresorerie.CAISSE);
    }

    @Transactional
    public void enregistrerEntreeVente(BigDecimal montant, String motif, Vente vente, Utilisateur utilisateur) {
        enregistrerEntreeVente(montant, motif, vente, utilisateur, CompteTresorerie.CAISSE);
    }

    @Transactional
    public void enregistrerEntreeVente(BigDecimal montant, String motif, Vente vente, Utilisateur utilisateur, CompteTresorerie compte) {
        enregistrerEntree(montant, motif, SourceMouvement.VENTE, utilisateur, vente, null, compte);
    }

    /**
     * Entrée d'un règlement ultérieur d'une vente (page de règlement) : source {@code REGLEMENT_VENTE}, distincte de
     * {@code VENTE} (paiement saisi à la création) pour que seul le règlement ultérieur soit annulable.
     */
    @Transactional
    public MouvementCaisse enregistrerEntreeReglementVente(BigDecimal montant, String motif, Vente vente, Utilisateur utilisateur, CompteTresorerie compte) {
        return enregistrerEntree(montant, motif, SourceMouvement.REGLEMENT_VENTE, utilisateur, vente, null, compte);
    }

    /** Entrée d'un règlement du solde de départ d'un client : mouvement de trésorerie rattaché au client, sans vente. */
    @Transactional
    public MouvementCaisse enregistrerEntreeSoldeClient(BigDecimal montant, String motif, Client client, Utilisateur utilisateur, CompteTresorerie compte) {
        return enregistrerEntree(montant, motif, SourceMouvement.SOLDE_CLIENT, utilisateur, null, client, compte);
    }

    /** Sortie d'un règlement du solde de départ d'un fournisseur : mouvement de trésorerie rattaché au fournisseur, sans achat. */
    @Transactional
    public MouvementCaisse enregistrerSortieSoldeFournisseur(BigDecimal montant, String motif, Fournisseur fournisseur, Utilisateur utilisateur, CompteTresorerie compte) {
        return enregistrerSortie(montant, motif, SourceMouvement.SOLDE_FOURNISSEUR, utilisateur, null, fournisseur, compte);
    }

    /** Règlements du solde de départ d'un client et leurs annulations, du plus récent au plus ancien. */
    public List<MouvementCaisse> listerReglementsSoldeClient(Long clientId) {
        return mouvementRepo.findByClientIdAndSourceInAndBoutiqueIdOrderByDateMouvementDescIdDesc(clientId,
                List.of(SourceMouvement.SOLDE_CLIENT, SourceMouvement.ANNULATION), boutiqueService.idBoutiqueCourante());
    }

    public List<MouvementCaisse> listerReglementsSoldeFournisseur(Long fournisseurId) {
        return mouvementRepo.findByFournisseurIdAndSourceInAndBoutiqueIdOrderByDateMouvementDescIdDesc(fournisseurId,
                List.of(SourceMouvement.SOLDE_FOURNISSEUR, SourceMouvement.ANNULATION), boutiqueService.idBoutiqueCourante());
    }

    /**
     * Annulations des mouvements donnés, par identifiant du mouvement annulé : une seule requête pour une liste entière
     * (journal, historique d'un règlement). Un mouvement sans annulation n'a pas d'entrée.
     */
    /** Annulations de toute la boutique, par identifiant du mouvement annulé (journal de caisse). */
    public Map<Long, MouvementCaisse> annulationsDeLaBoutique() {
        Map<Long, MouvementCaisse> resultat = new java.util.HashMap<>();
        for (MouvementCaisse annulation : mouvementRepo.findAnnulationsDeLaBoutique(boutiqueService.idBoutiqueCourante())) {
            resultat.put(annulation.getAnnulationDe().getId(), annulation);
        }
        return resultat;
    }

    public Map<Long, MouvementCaisse> annulationsDe(List<MouvementCaisse> mouvements) {
        List<Long> ids = mouvements.stream().map(MouvementCaisse::getId).filter(Objects::nonNull).toList();
        Map<Long, MouvementCaisse> resultat = new java.util.HashMap<>();
        if (ids.isEmpty()) return resultat;
        for (MouvementCaisse annulation : mouvementRepo.findAnnulationsDe(boutiqueService.idBoutiqueCourante(), ids)) {
            resultat.put(annulation.getAnnulationDe().getId(), annulation);
        }
        return resultat;
    }

    public List<MouvementCaisse> listerReglementsVente(Long venteId) {
        return mouvementRepo.findByVenteIdAndBoutiqueIdOrderByDateMouvementDescIdDesc(venteId, boutiqueService.idBoutiqueCourante());
    }

    /**
     * Entrée de trésorerie qui constate l'argent rendu par un fournisseur après un retour d'achat : source
     * {@code RETOUR_ACHAT}, rattachée au retour (jamais à l'achat, pour ne pas figurer parmi ses versements ni parmi les
     * règlements du solde de départ du fournisseur). À appeler dans la transaction du retour, après les verrous de la boutique,
     * de l'achat, du fournisseur et des produits.
     */
    @Transactional
    public void enregistrerEntreeRetourAchat(BigDecimal montant, String motif, Retour retour, Utilisateur utilisateur, CompteTresorerie compte) {
        if (retour == null || retour.getId() == null) throw new IllegalArgumentException("Retour introuvable");
        enregistrerEntree(montant, motif, "RETOUR_ACHAT", utilisateur, null, null, retour, compte);
    }

    private MouvementCaisse enregistrerEntree(BigDecimal montant, String motif, String source, Utilisateur utilisateur, Vente vente, Client client, CompteTresorerie compte) {
        return enregistrerEntree(montant, motif, source, utilisateur, vente, client, null, compte);
    }

    private MouvementCaisse enregistrerEntree(BigDecimal montant, String motif, String source, Utilisateur utilisateur, Vente vente, Client client,
                                   Retour retour, CompteTresorerie compte) {
        // Idempotent : déjà tenu quand l'appel vient d'une vente, d'un règlement ou d'un retour (ordre global des verrous).
        verrous.verrouillerBoutiqueCourante();
        verifierSourceOperationnelle(source);
        exigerCompte(compte);
        utilisateur = utilisateurDeLaBoutique(utilisateur);
        if (vente != null) {
            vente = venteRepository.findByIdAndBoutiqueId(vente.getId(), boutiqueService.idBoutiqueCourante())
                    .orElseThrow(() -> new IllegalArgumentException("Vente introuvable"));
        }
        if (montant == null || montant.signum() == 0) {
            return null;
        }
        if (montant.signum() < 0) {
            throw new IllegalArgumentException("Le montant d'une entree de caisse doit etre positif.");
        }

        montant = Montants.valider(montant, "Le montant de caisse");
        initialisation.verrouillerEtClorePourActivite();
        // Un encaissement positif ne peut pas épuiser un compte : il reste indépendant
        // des autres ventes. Seules les sorties doivent exclure un contrôle de fonds.
        MouvementCaisse mouvement = new MouvementCaisse();
        mouvement.setCompte(compte);
        mouvement.setBoutique(boutiqueService.boutiqueCourante());
        mouvement.setDateMouvement(LocalDateTime.now());
        mouvement.setMontant(montant.abs());
        mouvement.setType("ENTREE");
        mouvement.setMotif(motif);
        mouvement.setSource(source);
        mouvement.setUtilisateur(utilisateur);
        mouvement.setVente(vente);
        mouvement.setClient(client);
        mouvement.setRetour(retour);

        return mouvementRepo.save(mouvement);
    }

    @Transactional
    public void enregistrerSortie(BigDecimal montant, String motif, String source, Utilisateur utilisateur) {
        enregistrerSortie(montant, motif, source, utilisateur, null, null, CompteTresorerie.CAISSE);
    }

    @Transactional
    public void enregistrerSortieAchat(BigDecimal montant, String motif, Achat achat, Utilisateur utilisateur) {
        enregistrerSortieAchat(montant, motif, achat, utilisateur, CompteTresorerie.CAISSE);
    }

    @Transactional
    public void enregistrerSortieAchat(BigDecimal montant, String motif, Achat achat, Utilisateur utilisateur, CompteTresorerie compte) {
        enregistrerSortie(montant, motif, SourceMouvement.ACHAT, utilisateur, achat, null, compte);
    }

    /** Sortie d'un règlement ultérieur d'un achat : source {@code REGLEMENT_ACHAT}, distincte du paiement saisi à la création. */
    @Transactional
    public MouvementCaisse enregistrerSortieReglementAchat(BigDecimal montant, String motif, Achat achat, Utilisateur utilisateur, CompteTresorerie compte) {
        return enregistrerSortie(montant, motif, SourceMouvement.REGLEMENT_ACHAT, utilisateur, achat, null, compte);
    }

    public List<MouvementCaisse> listerReglementsAchat(Long achatId) {
        return mouvementRepo.findByAchatIdAndBoutiqueIdOrderByDateMouvementDescIdDesc(achatId, boutiqueService.idBoutiqueCourante());
    }

    /**
     * Sortie de trésorerie qui rembourse un client après un retour de vente : source {@code RETOUR_VENTE}, rattachée au
     * retour (jamais à la vente, pour ne pas figurer parmi ses versements). À appeler dans la transaction du retour,
     * après les verrous de la boutique, du client et des produits.
     */
    @Transactional
    public void enregistrerSortieRetourVente(BigDecimal montant, String motif, Retour retour, Utilisateur utilisateur, CompteTresorerie compte) {
        if (retour == null || retour.getId() == null) throw new IllegalArgumentException("Retour introuvable");
        enregistrerSortie(montant, motif, "RETOUR_VENTE", utilisateur, null, null, retour, compte);
    }

    private MouvementCaisse enregistrerSortie(BigDecimal montant, String motif, String source, Utilisateur utilisateur, Achat achat, Fournisseur fournisseur, CompteTresorerie compte) {
        return enregistrerSortie(montant, motif, source, utilisateur, achat, fournisseur, null, compte);
    }

    private MouvementCaisse enregistrerSortie(BigDecimal montant, String motif, String source, Utilisateur utilisateur, Achat achat, Fournisseur fournisseur,
                                   Retour retour, CompteTresorerie compte) {
        // Idempotent : déjà tenu quand l'appel vient d'un achat, d'un règlement ou d'un retour (ordre global des verrous).
        verrous.verrouillerBoutiqueCourante();
        verifierSourceOperationnelle(source);
        exigerCompte(compte);
        utilisateur = utilisateurDeLaBoutique(utilisateur);
        if (achat != null) {
            achat = achatRepository.findByIdAndBoutiqueId(achat.getId(), boutiqueService.idBoutiqueCourante())
                    .orElseThrow(() -> new IllegalArgumentException("Achat introuvable"));
        }
        if (montant == null || montant.signum() == 0) {
            return null;
        }

        montant = Montants.valider(montant, "Le montant de caisse");
        initialisation.verrouillerEtClorePourActivite();
        verrouillerComptesEnLecture(boutiqueService.idBoutiqueCourante(), compte);
        MouvementCaisse mouvement = new MouvementCaisse();
        mouvement.setCompte(compte);
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
        mouvement.setFournisseur(fournisseur);
        mouvement.setRetour(retour);

        return mouvementRepo.save(mouvement);
    }

    /**
     * Annule un mouvement par un mouvement inverse : même compte, montant opposé, source {@code ANNULATION}, lié à l'origine
     * par {@code annulation_de_id}, avec le motif saisi par le gérant. Le mouvement d'origine n'est ni modifié ni supprimé.
     * La contrainte d'unicité de {@code annulation_de_id} refuse une seconde annulation du même mouvement.
     *
     * <p>L'annulation d'un encaissement est une sortie : elle suit la règle des dépenses et des transferts (le compte doit
     * disposer de la somme, même message). L'annulation d'un paiement est une entrée, sans contrôle. À appeler dans la
     * transaction de l'annulation, après les verrous de la boutique, du document et du partenaire ; les comptes sont
     * verrouillés ici, en dernier. Les règles propres à chaque règlement sont contrôlées par
     * {@link AnnulationReglementService}.</p>
     */
    @Transactional
    public MouvementCaisse enregistrerAnnulation(MouvementCaisse origine, String motif, Utilisateur acteur) {
        verrous.verrouillerBoutiqueCourante();
        Long boutiqueId = boutiqueService.idBoutiqueCourante();
        if (origine == null || origine.getId() == null || origine.getBoutique() == null || !Objects.equals(origine.getBoutique().getId(), boutiqueId)) {
            throw new IllegalArgumentException("Mouvement introuvable");
        }
        BigDecimal montant = Montants.valider(origine.getMontant(), "Le montant de caisse");
        if (montant.signum() == 0) throw new IllegalArgumentException("Un mouvement sans montant ne s'annule pas.");
        acteur = utilisateurDeLaBoutique(acteur);
        CompteTresorerie compte = origine.getCompte();
        verrouillerComptes(boutiqueId, compte);
        if (montant.signum() > 0) {
            // L'annulation d'un encaissement sort l'argent du compte : même règle et même message qu'une dépense.
            BigDecimal solde = normaliser(mouvementRepo.calculerSoldeCompte(boutiqueId, compte));
            if (solde.compareTo(montant) < 0) {
                throw new IllegalArgumentException("Solde insuffisant sur le compte " + compte.getLibelle() + ".");
            }
        }
        MouvementCaisse annulation = new MouvementCaisse();
        annulation.setBoutique(boutiqueService.boutiqueCourante());
        annulation.setCompte(compte);
        annulation.setMontant(montant.negate());
        annulation.setType(montant.signum() > 0 ? "SORTIE" : "ENTREE");
        annulation.setSource(SourceMouvement.ANNULATION);
        annulation.setMotif(motif);
        annulation.setDateMouvement(LocalDateTime.now());
        annulation.setUtilisateur(acteur);
        annulation.setVente(origine.getVente());
        annulation.setAchat(origine.getAchat());
        annulation.setClient(origine.getClient());
        annulation.setFournisseur(origine.getFournisseur());
        annulation.setAnnulationDe(origine);
        return mouvementRepo.saveAndFlush(annulation);
    }

    /** Retourne true pour la répétition exacte d'un formulaire déjà traité. */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public boolean transferer(TransfertTresorerieForm formulaire) {
        return executerOperation(formulaire, TypeOperationTresorerie.TRANSFERT,
                formulaire == null ? null : formulaire.getCompteDestination());
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public boolean depenser(DepenseTresorerieForm formulaire) {
        return executerOperation(formulaire, TypeOperationTresorerie.DEPENSE, null);
    }

    private boolean executerOperation(OperationTresorerieForm formulaire, TypeOperationTresorerie type,
                                      CompteTresorerie destination) {
        // Ordre global des verrous : la boutique d'abord, puis le compte du gérant (lecture partagée), puis les comptes.
        verrous.verrouillerBoutiqueCourante();
        Utilisateur acteur = gerant();
        entityManager.refresh(acteur, LockModeType.PESSIMISTIC_READ);
        if (!acteur.isActif() || acteur.getRole() != Role.ADMIN) throw interdit();
        boutiqueService.exigerAppartenance(acteur.getBoutique());
        if (formulaire == null || !validator.validate(formulaire).isEmpty()) {
            throw new IllegalArgumentException("Les comptes, le montant et le motif de l'opération doivent être valides.");
        }
        if (type == TypeOperationTresorerie.TRANSFERT && formulaire.getCompteSource() == destination) {
            throw new IllegalArgumentException("Choisissez deux comptes différents pour le transfert.");
        }
        var boutique = boutiqueService.boutiqueCourante();
        if (repetition(formulaire, type, destination, acteur, boutique.getId())) return true;
        // Clôture du démarrage (boutique déjà verrouillée), puis comptes.
        initialisation.verrouillerEtClorePourActivite();
        verrouillerComptes(boutique.getId(), formulaire.getCompteSource(), destination);
        if (repetition(formulaire, type, destination, acteur, boutique.getId())) return true;
        BigDecimal montant = Montants.positifOuNul(formulaire.getMontant(), "Le montant");
        if (montant.signum() <= 0) throw new IllegalArgumentException("Le montant doit être supérieur à zéro.");
        // READ_COMMITTED relit le solde après l'attente des comptes verrouillés.
        BigDecimal solde = normaliser(mouvementRepo.calculerSoldeCompte(boutique.getId(), formulaire.getCompteSource()));
        if (solde.compareTo(montant) < 0) {
            throw new IllegalArgumentException("Solde insuffisant sur le compte " + formulaire.getCompteSource().getLibelle() + ".");
        }
        OperationTresorerie operation = new OperationTresorerie();
        operation.setBoutique(boutique); operation.setUtilisateur(acteur);
        operation.setJeton(formulaire.getJetonOperation()); operation.setType(type);
        operation.setCompteSource(formulaire.getCompteSource()); operation.setCompteDestination(destination);
        operation.setMontant(montant); operation.setMotif(formulaire.getMotif());
        operation.setDateOperation(LocalDateTime.now(clock.withZone(ZoneId.systemDefault())));
        operations.saveAndFlush(operation);
        enregistrerEcriture(operation, formulaire.getCompteSource(), montant.negate(), "SORTIE");
        if (destination != null) enregistrerEcriture(operation, destination, montant, "ENTREE");
        mouvementRepo.flush();
        return false;
    }

    private boolean repetition(OperationTresorerieForm formulaire, TypeOperationTresorerie type,
                               CompteTresorerie destination, Utilisateur acteur, Long boutiqueId) {
        var precedent = operations.findByJeton(formulaire.getJetonOperation()).orElse(null);
        if (precedent == null) return false;
        if (!Objects.equals(precedent.getBoutique().getId(), boutiqueId)
                || !Objects.equals(precedent.getUtilisateur().getId(), acteur.getId())
                || precedent.getType() != type || precedent.getCompteSource() != formulaire.getCompteSource()
                || precedent.getCompteDestination() != destination
                || precedent.getMontant().compareTo(formulaire.getMontant()) != 0
                || !Objects.equals(precedent.getMotif(), formulaire.getMotif())) {
            throw new OperationTresorerieConflitException();
        }
        return true;
    }

    private void enregistrerEcriture(OperationTresorerie operation, CompteTresorerie compte,
                                     BigDecimal montant, String type) {
        MouvementCaisse mouvement = new MouvementCaisse();
        mouvement.setBoutique(operation.getBoutique()); mouvement.setUtilisateur(operation.getUtilisateur());
        mouvement.setCompte(compte); mouvement.setMontant(montant); mouvement.setType(type);
        mouvement.setSource(operation.getType().name()); mouvement.setMotif(operation.getMotif());
        mouvement.setDateMouvement(operation.getDateOperation()); mouvement.setOperationTresorerie(operation);
        mouvementRepo.save(mouvement);
    }

    private void verrouillerComptes(Long boutiqueId, CompteTresorerie... comptesRequis) {
        verrouillerComptes(boutiqueId, false, comptesRequis);
    }

    private void verrouillerComptesEnLecture(Long boutiqueId, CompteTresorerie... comptesRequis) {
        verrouillerComptes(boutiqueId, true, comptesRequis);
    }

    private void verrouillerComptes(Long boutiqueId, boolean lecture, CompteTresorerie... comptesRequis) {
        EnumSet<CompteTresorerie> ordre = EnumSet.noneOf(CompteTresorerie.class);
        for (CompteTresorerie compte : comptesRequis) if (compte != null) ordre.add(compte);
        for (CompteTresorerie compte : ordre) {
            (lecture ? comptes.verrouillerLecture(boutiqueId, compte) : comptes.verrouiller(boutiqueId, compte)).orElseThrow(() -> new IllegalStateException(
                    "Les comptes de la boutique ne sont pas initialisés. La migration trésorerie est nécessaire."));
        }
    }

    private static void exigerCompte(CompteTresorerie compte) {
        if (compte == null) throw new IllegalArgumentException("Choisissez un compte de paiement.");
    }

    private BigDecimal normaliser(BigDecimal valeur) {
        return Montants.ouZero(valeur);
    }

    private void verifierSourceOperationnelle(String source) {
        if (source != null && "TRANSFERT".equalsIgnoreCase(source.strip())) {
            throw new IllegalArgumentException("Un transfert doit être enregistré avec ses deux écritures liées.");
        }
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
