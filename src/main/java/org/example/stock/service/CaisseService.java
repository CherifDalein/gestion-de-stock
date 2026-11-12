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
import java.util.LinkedHashMap;
import java.util.TreeSet;
import java.util.Objects;
import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.model.CompteTresorerieBoutique;
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
    @Autowired private ComptesTresorerieService comptesService;
    @Autowired private OperationTresorerieRepository operations;

    @Transactional(readOnly = true)
    public void verifierGestionTresorerie() { gerant(); }

    @Transactional(readOnly=true)
    public boolean peutInitialiser() {
        gerant();
        return peutInitialiser(comptes.principal(boutiqueService.idBoutiqueCourante(), TypeCompteTresorerie.ESPECES).map(CompteTresorerieBoutique::getId).orElse(null));
    }

    /** Vrai pendant le démarrage si ce compte actif n'a encore aucun mouvement. */
    @Transactional(readOnly = true)
    public boolean peutInitialiser(Long compteId) {
        gerant();
        Long boutiqueId = boutiqueService.idBoutiqueCourante();
        return initialisation.estOuverte() && compteId != null
                && comptes.findByIdAndBoutiqueId(compteId, boutiqueId).filter(CompteTresorerieBoutique::isActif).isPresent()
                && !mouvementRepo.existsByBoutiqueIdAndCompteId(boutiqueId, compteId);
    }

    /** Comptes actifs sans aucun mouvement, tant que le démarrage de la boutique est ouvert. */
    @Transactional(readOnly = true)
    public List<CompteTresorerieBoutique> getComptesInitialisables() {
        gerant();
        if (!initialisation.estOuverte()) return List.of();
        Long boutiqueId = boutiqueService.idBoutiqueCourante();
        return comptes.listerActifs(boutiqueId).stream()
                .filter(compte -> !mouvementRepo.existsByBoutiqueIdAndCompteId(boutiqueId, compte.getId())).toList();
    }

    /** Un solde par compte actif (ou portant encore un solde), dans l'ordre d'affichage : type, ordre, identifiant. */
    public record SoldeCompte(CompteTresorerieBoutique compte, BigDecimal solde) { }

    @Transactional(readOnly = true)
    public List<SoldeCompte> getSoldesParCompte() {
        Long boutiqueId = boutiqueService.idBoutiqueCourante();
        Map<Long, BigDecimal> soldes = comptesService.soldes(boutiqueId);
        List<SoldeCompte> resultat = new java.util.ArrayList<>();
        for (CompteTresorerieBoutique compte : comptes.lister(boutiqueId)) {
            BigDecimal solde = soldes.getOrDefault(compte.getId(), Montants.ZERO);
            if (compte.isActif() || solde.signum() != 0) resultat.add(new SoldeCompte(compte, solde));
        }
        return resultat;
    }

    /** Total de chaque type de compte (tous les comptes, archivés compris) : les trois types sont toujours présents. */
    @Transactional(readOnly = true)
    public Map<TypeCompteTresorerie, BigDecimal> getSoldesParType() {
        Long boutiqueId = boutiqueService.idBoutiqueCourante();
        Map<Long, BigDecimal> soldes = comptesService.soldes(boutiqueId);
        Map<TypeCompteTresorerie, BigDecimal> resultat = new EnumMap<>(TypeCompteTresorerie.class);
        for (TypeCompteTresorerie type : TypeCompteTresorerie.values()) resultat.put(type, Montants.ZERO);
        for (CompteTresorerieBoutique compte : comptes.lister(boutiqueId)) {
            resultat.merge(compte.getType(), soldes.getOrDefault(compte.getId(), Montants.ZERO), BigDecimal::add);
        }
        return resultat;
    }

    /** Nom d'un compte de la boutique courante ; 400 si le compte n'existe pas dans cette boutique. */
    @Transactional(readOnly = true)
    public String nomDuCompte(Long compteId) {
        return comptes.findByIdAndBoutiqueId(compteId, boutiqueService.idBoutiqueCourante()).orElseThrow(CompteTresorerieInvalideException::new).getNom();
    }

    @Transactional(readOnly = true)
    public BigDecimal getSoldeCompte(Long compteId) {
        Long boutiqueId = boutiqueService.idBoutiqueCourante();
        comptes.findByIdAndBoutiqueId(compteId, boutiqueId).orElseThrow(CompteTresorerieInvalideException::new);
        return normaliser(mouvementRepo.calculerSoldeCompte(boutiqueId, compteId));
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public CompteTresorerieBoutique initialiser(OuvertureCaisseForm formulaire) {
        // Ordre global des verrous : la boutique d'abord, puis le compte du gérant (lecture partagée), puis les comptes.
        verrous.verrouillerBoutiqueCourante();
        Utilisateur acteur=gerant();
        entityManager.refresh(acteur,LockModeType.PESSIMISTIC_READ);
        if(!acteur.isActif()||acteur.getRole()!=Role.ADMIN) throw interdit();
        var boutique=initialisation.verrouillerEtExigerOuverte();
        if(formulaire==null||!validator.validate(formulaire).isEmpty()) throw new IllegalArgumentException("Le montant et le motif du solde initial doivent être valides.");
        CompteTresorerieBoutique compte = comptes.findByIdAndBoutiqueId(comptesService.resoudreId(formulaire.getComptePaiement()), boutique.getId())
                .orElseThrow(CompteTresorerieInvalideException::new);
        compte = comptesService.verrouiller(boutique.getId(), acteur, false, true, List.of(compte.getId())).get(compte.getId());
        if(mouvementRepo.existsByBoutiqueIdAndCompteId(boutique.getId(), compte.getId())) throw new IllegalArgumentException("Le solde initial de ce compte se renseigne une seule fois, avant son premier mouvement.");
        MouvementCaisse mouvement=new MouvementCaisse();
        mouvement.setBoutique(boutique);mouvement.setUtilisateur(acteur);mouvement.setCompte(compte);
        mouvement.setMontant(Montants.positifOuNul(formulaire.getMontant(),"Le solde initial"));
        mouvement.setType("OUVERTURE");mouvement.setSource("SOLDE_INITIAL");mouvement.setMotif(formulaire.getMotif());
        mouvement.setDateMouvement(LocalDateTime.now(clock.withZone(ZoneId.systemDefault())));
        mouvementRepo.saveAndFlush(mouvement);
        return compte;
    }

    /**
     * Gérant connecté, relu sous verrou partagé comme pour un transfert ou une dépense (ordre global des verrous : la boutique
     * d'abord, puis le compte du gérant, puis les autres lignes). À appeler dans la transaction d'une écriture de financement.
     */
    public Utilisateur gerantPourEcriture() {
        Utilisateur acteur = gerant();
        entityManager.refresh(acteur, LockModeType.PESSIMISTIC_READ);
        if (!acteur.isActif() || acteur.getRole() != Role.ADMIN) throw interdit();
        boutiqueService.exigerAppartenance(acteur.getBoutique());
        return acteur;
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
        enregistrerEntree(montant, motif, source, utilisateur, null, null, comptesService.principal(TypeCompteTresorerie.ESPECES).getId());
    }

    @Transactional
    public void enregistrerEntreeVente(BigDecimal montant, String motif, Vente vente, Utilisateur utilisateur) {
        enregistrerEntreeVente(montant, motif, vente, utilisateur, comptesService.principal(TypeCompteTresorerie.ESPECES).getId());
    }

    @Transactional
    public void enregistrerEntreeVente(BigDecimal montant, String motif, Vente vente, Utilisateur utilisateur, Long compteId) {
        enregistrerEntree(montant, motif, SourceMouvement.VENTE, utilisateur, vente, null, compteId);
    }

    /**
     * Entrée d'un règlement ultérieur d'une vente (page de règlement) : source {@code REGLEMENT_VENTE}, distincte de
     * {@code VENTE} (paiement saisi à la création) pour que seul le règlement ultérieur soit annulable.
     */
    @Transactional
    public MouvementCaisse enregistrerEntreeReglementVente(BigDecimal montant, String motif, Vente vente, Utilisateur utilisateur, Long compteId) {
        return enregistrerEntree(montant, motif, SourceMouvement.REGLEMENT_VENTE, utilisateur, vente, null, compteId);
    }

    /** Entrée d'un règlement du solde de départ d'un client : mouvement de trésorerie rattaché au client, sans vente. */
    @Transactional
    public MouvementCaisse enregistrerEntreeSoldeClient(BigDecimal montant, String motif, Client client, Utilisateur utilisateur, Long compteId) {
        return enregistrerEntree(montant, motif, SourceMouvement.SOLDE_CLIENT, utilisateur, null, client, compteId);
    }

    /** Sortie d'un règlement du solde de départ d'un fournisseur : mouvement de trésorerie rattaché au fournisseur, sans achat. */
    @Transactional
    public MouvementCaisse enregistrerSortieSoldeFournisseur(BigDecimal montant, String motif, Fournisseur fournisseur, Utilisateur utilisateur, Long compteId) {
        return enregistrerSortie(montant, motif, SourceMouvement.SOLDE_FOURNISSEUR, utilisateur, null, fournisseur, compteId);
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
    public void enregistrerEntreeRetourAchat(BigDecimal montant, String motif, Retour retour, Utilisateur utilisateur, Long compteId) {
        if (retour == null || retour.getId() == null) throw new IllegalArgumentException("Retour introuvable");
        enregistrerEntree(montant, motif, "RETOUR_ACHAT", utilisateur, null, null, retour, compteId);
    }

    private MouvementCaisse enregistrerEntree(BigDecimal montant, String motif, String source, Utilisateur utilisateur, Vente vente, Client client, Long compteId) {
        return enregistrerEntree(montant, motif, source, utilisateur, vente, client, null, compteId);
    }

    private MouvementCaisse enregistrerEntree(BigDecimal montant, String motif, String source, Utilisateur utilisateur, Vente vente, Client client,
                                   Retour retour, Long compteId) {
        // Idempotent : déjà tenu quand l'appel vient d'une vente, d'un règlement ou d'un retour (ordre global des verrous).
        verrous.verrouillerBoutiqueCourante();
        verifierSourceOperationnelle(source);
        exigerCompte(compteId);
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
        // Un encaissement positif ne peut pas épuiser un compte : il reste indépendant des autres ventes. Seules les sorties
        // doivent exclure un contrôle de fonds. Le compte est tout de même verrouillé en lecture, en dernier : un transfert
        // ou un archivage en cours le fait attendre, et un compte archivé est refusé.
        CompteTresorerieBoutique compte = verrouillerCompte(utilisateur, compteId, true);
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
        enregistrerSortie(montant, motif, source, utilisateur, null, null, comptesService.principal(TypeCompteTresorerie.ESPECES).getId());
    }

    @Transactional
    public void enregistrerSortieAchat(BigDecimal montant, String motif, Achat achat, Utilisateur utilisateur) {
        enregistrerSortieAchat(montant, motif, achat, utilisateur, comptesService.principal(TypeCompteTresorerie.ESPECES).getId());
    }

    @Transactional
    public void enregistrerSortieAchat(BigDecimal montant, String motif, Achat achat, Utilisateur utilisateur, Long compteId) {
        enregistrerSortie(montant, motif, SourceMouvement.ACHAT, utilisateur, achat, null, compteId);
    }

    /** Sortie d'un règlement ultérieur d'un achat : source {@code REGLEMENT_ACHAT}, distincte du paiement saisi à la création. */
    @Transactional
    public MouvementCaisse enregistrerSortieReglementAchat(BigDecimal montant, String motif, Achat achat, Utilisateur utilisateur, Long compteId) {
        return enregistrerSortie(montant, motif, SourceMouvement.REGLEMENT_ACHAT, utilisateur, achat, null, compteId);
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
    public void enregistrerSortieRetourVente(BigDecimal montant, String motif, Retour retour, Utilisateur utilisateur, Long compteId) {
        if (retour == null || retour.getId() == null) throw new IllegalArgumentException("Retour introuvable");
        enregistrerSortie(montant, motif, "RETOUR_VENTE", utilisateur, null, null, retour, compteId);
    }

    private MouvementCaisse enregistrerSortie(BigDecimal montant, String motif, String source, Utilisateur utilisateur, Achat achat, Fournisseur fournisseur, Long compteId) {
        return enregistrerSortie(montant, motif, source, utilisateur, achat, fournisseur, null, compteId);
    }

    private MouvementCaisse enregistrerSortie(BigDecimal montant, String motif, String source, Utilisateur utilisateur, Achat achat, Fournisseur fournisseur,
                                   Retour retour, Long compteId) {
        // Idempotent : déjà tenu quand l'appel vient d'un achat, d'un règlement ou d'un retour (ordre global des verrous).
        verrous.verrouillerBoutiqueCourante();
        verifierSourceOperationnelle(source);
        exigerCompte(compteId);
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
        CompteTresorerieBoutique compte = verrouillerCompte(utilisateur, compteId, true);
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
        // La contre-passation se fait sur le compte d'origine, même archivé depuis (le compte garde son historique).
        CompteTresorerieBoutique compte = comptesService.verrouiller(boutiqueId, acteur, false, false, List.of(origine.getCompte().getId()))
                .get(origine.getCompte().getId());
        if (montant.signum() > 0) {
            // L'annulation d'un encaissement sort l'argent du compte : même règle et même message qu'une dépense.
            BigDecimal solde = normaliser(mouvementRepo.calculerSoldeCompte(boutiqueId, compte.getId()));
            if (solde.compareTo(montant) < 0) {
                throw new IllegalArgumentException("Solde insuffisant sur le compte " + compte.getNom() + ".");
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
        // Une opération de financement (apport, remboursement...) se reconnaît annulée à ses mouvements d'annulation.
        annulation.setOperationTresorerie(origine.getOperationTresorerie());
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

    private boolean executerOperation(OperationTresorerieForm formulaire, TypeOperationTresorerie type, String saisieDestination) {
        // Ordre global des verrous : la boutique d'abord, puis le compte du gérant (lecture partagée), puis les comptes.
        verrous.verrouillerBoutiqueCourante();
        Utilisateur acteur = gerant();
        entityManager.refresh(acteur, LockModeType.PESSIMISTIC_READ);
        if (!acteur.isActif() || acteur.getRole() != Role.ADMIN) throw interdit();
        boutiqueService.exigerAppartenance(acteur.getBoutique());
        if (formulaire == null || !validator.validate(formulaire).isEmpty()) {
            throw new IllegalArgumentException("Les comptes, le montant et le motif de l'opération doivent être valides.");
        }
        // Comptes de la boutique, actifs et autorisés ; un compte refusé donne 400 et rien n'est enregistré.
        Long sourceId = comptesService.resoudreId(formulaire.getCompteSource());
        Long destinationId = type == TypeOperationTresorerie.TRANSFERT ? comptesService.resoudreId(saisieDestination) : null;
        if (type == TypeOperationTresorerie.TRANSFERT && sourceId.equals(destinationId)) {
            throw new IllegalArgumentException("Choisissez deux comptes différents pour le transfert.");
        }
        var boutique = boutiqueService.boutiqueCourante();
        if (repetition(formulaire, type, sourceId, destinationId, acteur, boutique.getId())) return true;
        // Clôture du démarrage (boutique déjà verrouillée), puis comptes, dans l'ordre des identifiants.
        initialisation.verrouillerEtClorePourActivite();
        List<Long> aVerrouiller = destinationId == null ? List.of(sourceId) : List.of(sourceId, destinationId);
        var verrouilles = comptesService.verrouiller(boutique.getId(), acteur, false, true, aVerrouiller);
        CompteTresorerieBoutique source = verrouilles.get(sourceId);
        CompteTresorerieBoutique destination = destinationId == null ? null : verrouilles.get(destinationId);
        if (repetition(formulaire, type, sourceId, destinationId, acteur, boutique.getId())) return true;
        BigDecimal montant = Montants.positifOuNul(formulaire.getMontant(), "Le montant");
        if (montant.signum() <= 0) throw new IllegalArgumentException("Le montant doit être supérieur à zéro.");
        // READ_COMMITTED relit le solde après l'attente des comptes verrouillés.
        BigDecimal solde = normaliser(mouvementRepo.calculerSoldeCompte(boutique.getId(), sourceId));
        if (solde.compareTo(montant) < 0) {
            throw new IllegalArgumentException("Solde insuffisant sur le compte " + source.getNom() + ".");
        }
        OperationTresorerie operation = new OperationTresorerie();
        operation.setBoutique(boutique); operation.setUtilisateur(acteur);
        operation.setJeton(formulaire.getJetonOperation()); operation.setType(type);
        operation.setCompteSource(source); operation.setCompteDestination(destination);
        operation.setMontant(montant); operation.setMotif(formulaire.getMotif());
        operation.setDateOperation(LocalDateTime.now(clock.withZone(ZoneId.systemDefault())));
        operations.saveAndFlush(operation);
        enregistrerEcriture(operation, source, montant.negate(), "SORTIE");
        if (destination != null) enregistrerEcriture(operation, destination, montant, "ENTREE");
        mouvementRepo.flush();
        return false;
    }

    private boolean repetition(OperationTresorerieForm formulaire, TypeOperationTresorerie type,
                               Long sourceId, Long destinationId, Utilisateur acteur, Long boutiqueId) {
        var precedent = operations.findByJeton(formulaire.getJetonOperation()).orElse(null);
        if (precedent == null) return false;
        if (!Objects.equals(precedent.getBoutique().getId(), boutiqueId)
                || !Objects.equals(precedent.getUtilisateur().getId(), acteur.getId())
                || precedent.getType() != type || !Objects.equals(precedent.getCompteSource().getId(), sourceId)
                || !Objects.equals(precedent.getCompteDestination() == null ? null : precedent.getCompteDestination().getId(), destinationId)
                || precedent.getMontant().compareTo(formulaire.getMontant()) != 0
                || !Objects.equals(precedent.getMotif(), formulaire.getMotif())) {
            throw new OperationTresorerieConflitException();
        }
        return true;
    }

    private void enregistrerEcriture(OperationTresorerie operation, CompteTresorerieBoutique compte,
                                     BigDecimal montant, String type) {
        MouvementCaisse mouvement = new MouvementCaisse();
        mouvement.setBoutique(operation.getBoutique()); mouvement.setUtilisateur(operation.getUtilisateur());
        mouvement.setCompte(compte); mouvement.setMontant(montant); mouvement.setType(type);
        mouvement.setSource(operation.getType().name()); mouvement.setMotif(operation.getMotif());
        mouvement.setDateMouvement(operation.getDateOperation()); mouvement.setOperationTresorerie(operation);
        mouvementRepo.save(mouvement);
    }

    /** Verrouille le compte (dernier maillon de l'ordre global) et le contrôle pour l'acteur : boutique, actif, autorisé. */
    private CompteTresorerieBoutique verrouillerCompte(Utilisateur acteur, Long compteId, boolean lecture) {
        exigerCompte(compteId);
        return comptesService.verrouiller(boutiqueService.idBoutiqueCourante(), acteur, lecture, true, List.of(compteId)).get(compteId);
    }

    private static void exigerCompte(Long compteId) {
        if (compteId == null) throw new IllegalArgumentException("Choisissez un compte de paiement.");
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
