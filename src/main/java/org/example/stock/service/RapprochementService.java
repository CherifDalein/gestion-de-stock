package org.example.stock.service;

import org.example.stock.enums.TypeOperationTresorerie;
import org.example.stock.enums.TypeRapprochement;
import org.example.stock.export.DroitsExport;
import org.example.stock.export.Textes;
import org.example.stock.model.LigneReleve;
import org.example.stock.model.Montants;
import org.example.stock.model.MouvementCaisse;
import org.example.stock.model.OperationTresorerie;
import org.example.stock.model.Rapprochement;
import org.example.stock.model.RapprochementMouvement;
import org.example.stock.model.RefusProposition;
import org.example.stock.model.ReleveBancaire;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.LigneReleveRepository;
import org.example.stock.repository.MouvementCaisseRepository;
import org.example.stock.repository.RapprochementMouvementRepository;
import org.example.stock.repository.RapprochementRepository;
import org.example.stock.repository.RefusPropositionRepository;
import org.example.stock.repository.ReleveBancaireRepository;
import org.example.stock.repository.StockLockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Pointage d'un relevé (étape 3c) : propositions automatiques, rapprochements manuels, enregistrement dans l'appli d'une ligne sans mouvement,
 * exclusion d'un mouvement annulé avec sa contre-passation, et retrait d'un rapprochement. Toutes les écritures sont réservées au gérant et
 * à un relevé non validé.
 *
 * <p>Règles : un mouvement n'est rapproché qu'une fois (contrainte d'unicité) ; la somme des lignes d'un rapprochement est égale à celle de ses
 * mouvements, sans écart toléré ; un mouvement annulé et sa contre-passation vont toujours ensemble. Ordre des verrous : boutique, relevé, comptes,
 * mouvements par identifiant croissant (docs/lot-2.md, « Ordre des verrous »).</p>
 */
@Service
public class RapprochementService {
    /** Marge, en jours, autour de la période du relevé pour les mouvements proposés au pointage. */
    public static final int MARGE_JOURS = 7;
    /** Lignes et mouvements affichés par colonne ; au-delà, l'écran le signale. */
    public static final int AFFICHAGE_MAX = 1_000;
    /** Éléments sélectionnables d'un seul rapprochement. */
    public static final int SELECTION_MAX = 100;

    public static final Set<TypeOperationTresorerie> NATURES_CREDIT = EnumSet.of(TypeOperationTresorerie.PRODUIT_FINANCIER, TypeOperationTresorerie.AUTRE_PRODUIT);
    public static final Set<TypeOperationTresorerie> NATURES_DEBIT = EnumSet.of(TypeOperationTresorerie.CHARGE_FINANCIERE, TypeOperationTresorerie.AUTRE_CHARGE);

    private final StockLockRepository verrous;
    private final CaisseService caisse;
    private final ComptesTresorerieService comptes;
    private final BoutiqueService boutiques;
    private final InitialisationBoutiqueService initialisation;
    private final EcrituresFinancieres ecritures;
    private final EcheanceService horloge;
    private final ReleveBancaireRepository releves;
    private final LigneReleveRepository lignes;
    private final MouvementCaisseRepository mouvements;
    private final RapprochementRepository rapprochements;
    private final RapprochementMouvementRepository liens;
    private final RefusPropositionRepository refus;

    public RapprochementService(StockLockRepository verrous, CaisseService caisse, ComptesTresorerieService comptes, BoutiqueService boutiques,
                                InitialisationBoutiqueService initialisation, EcrituresFinancieres ecritures, EcheanceService horloge,
                                ReleveBancaireRepository releves, LigneReleveRepository lignes, MouvementCaisseRepository mouvements,
                                RapprochementRepository rapprochements, RapprochementMouvementRepository liens, RefusPropositionRepository refus) {
        this.verrous = verrous; this.caisse = caisse; this.comptes = comptes; this.boutiques = boutiques; this.initialisation = initialisation;
        this.ecritures = ecritures; this.horloge = horloge; this.releves = releves; this.lignes = lignes; this.mouvements = mouvements;
        this.rapprochements = rapprochements; this.liens = liens; this.refus = refus;
    }

    // ---- Lecture : l'écran de pointage ----------------------------------------------------------------------

    /** Un mouvement proposé au pointage ; {@code annule} : il a été annulé ; {@code contrePassation} : c'est l'annulation d'un autre. */
    public record MouvementVue(MouvementCaisse mouvement, boolean annule, boolean contrePassation) {
        public boolean seulImpossible() { return annule || contrePassation; }
    }

    public record GroupeVue(Rapprochement rapprochement, List<LigneReleve> lignes, List<MouvementCaisse> mouvements, boolean annulationRompue) { }

    public record Ecran(ReleveBancaire releve, ControleSoldes controle, List<LigneReleve> lignesLibres, List<MouvementVue> mouvementsLibres,
                        List<Appariement.Proposition> propositions, List<GroupeVue> groupes, LocalDate du, LocalDate au, boolean tousLesMouvements,
                        boolean lignesTronquees, boolean mouvementsTronques, long refusees, BigDecimal totalLignesLibres, BigDecimal totalMouvementsLibres) { }

    /**
     * @param tous vrai pour afficher tous les mouvements non rapprochés du compte jusqu'à 7 jours après la clôture, y compris ceux d'avant la
     *             fenêtre habituelle (un chèque émis il y a plusieurs semaines et débité ce mois-ci)
     */
    @Transactional(readOnly = true)
    public Ecran ecran(Long releveId, boolean tous) {
        DroitsExport.exigerGerant();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        ReleveBancaire r = releves.findByIdAndBoutiqueId(releveId, boutiqueId).orElseThrow(ReleveBancaireService::introuvable);
        Long compteId = r.getCompte().getId();
        LocalDate du = tous ? plusTot(releves.premierDebut(compteId), r.getDateDebut()) : r.getDateDebut().minusDays(MARGE_JOURS);
        LocalDate au = r.getDateFin().plusDays(MARGE_JOURS);
        List<LigneReleve> libres = lignes.nonRapprochees(releveId);
        List<MouvementCaisse> candidats = mouvements.nonRapproches(boutiqueId, compteId, du.atStartOfDay(), au.plusDays(1).atStartOfDay());
        Map<Long, MouvementCaisse> annulations = new HashMap<>();
        Set<Long> annules = new HashSet<>();
        if (!candidats.isEmpty()) {
            for (MouvementCaisse a : mouvements.findAnnulationsDe(boutiqueId, candidats.stream().map(MouvementCaisse::getId).toList())) {
                annules.add(a.getAnnulationDe().getId());
            }
        }
        List<MouvementVue> vues = new ArrayList<>();
        Set<Long> exclus = new HashSet<>();
        for (MouvementCaisse m : candidats) {
            boolean annule = annules.contains(m.getId()), contre = m.getAnnulationDe() != null;
            if (annule || contre) exclus.add(m.getId());
            vues.add(new MouvementVue(m, annule, contre));
        }
        Set<String> refusees = new HashSet<>();
        for (RefusProposition p : refus.duReleve(releveId)) refusees.add(p.getLigne().getId() + ":" + p.getMouvement().getId());
        List<Appariement.Proposition> propositions = Appariement.proposer(libres, candidats, exclus, refusees);
        BigDecimal totalLignes = libres.stream().map(LigneReleve::getMontant).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalMouvements = candidats.stream().map(MouvementCaisse::getMontant).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean lignesTronquees = libres.size() > AFFICHAGE_MAX, mouvementsTronques = vues.size() > AFFICHAGE_MAX;
        return new Ecran(r, new ControleSoldes(r.getSoldeOuverture(), Montants.ouZero(lignes.somme(releveId)), r.getSoldeCloture()),
                lignesTronquees ? libres.subList(0, AFFICHAGE_MAX) : libres, mouvementsTronques ? vues.subList(0, AFFICHAGE_MAX) : vues,
                propositions, groupes(r), du, au, tous, lignesTronquees, mouvementsTronques, refusees.size(), totalLignes, totalMouvements);
    }

    private static LocalDate plusTot(LocalDate a, LocalDate b) { return a == null || b.isBefore(a) ? b : a; }

    /** Rapprochements d'un relevé avec leurs lignes et leurs mouvements. */
    @Transactional(readOnly = true)
    public List<GroupeVue> groupes(ReleveBancaire r) {
        Map<Long, List<LigneReleve>> lignesParGroupe = new HashMap<>();
        for (LigneReleve l : lignes.duReleve(r.getId())) if (l.getRapprochement() != null) lignesParGroupe.computeIfAbsent(l.getRapprochement().getId(), k -> new ArrayList<>()).add(l);
        Map<Long, List<MouvementCaisse>> mouvementsParGroupe = new HashMap<>();
        Set<Long> ids = new HashSet<>();
        for (RapprochementMouvement rm : liens.duReleve(r.getId())) {
            mouvementsParGroupe.computeIfAbsent(rm.getRapprochement().getId(), k -> new ArrayList<>()).add(rm.getMouvement());
            ids.add(rm.getMouvement().getId());
        }
        Set<Long> annulesHorsGroupe = ids.isEmpty() ? Set.of() : annulationsRompues(r.getBoutique().getId(), mouvementsParGroupe);
        List<GroupeVue> resultat = new ArrayList<>();
        for (Rapprochement g : rapprochements.duReleve(r.getId())) {
            List<MouvementCaisse> ms = mouvementsParGroupe.getOrDefault(g.getId(), List.of());
            resultat.add(new GroupeVue(g, lignesParGroupe.getOrDefault(g.getId(), List.of()), ms, ms.stream().anyMatch(m -> annulesHorsGroupe.contains(m.getId()))));
        }
        return resultat;
    }

    /** Mouvements pointés dont l'annulation (ou l'origine, pour une contre-passation) n'est pas dans le même rapprochement. */
    private Set<Long> annulationsRompues(Long boutiqueId, Map<Long, List<MouvementCaisse>> parGroupe) {
        Set<Long> rompus = new HashSet<>();
        Set<Long> tous = new HashSet<>();
        parGroupe.values().forEach(l -> l.forEach(m -> tous.add(m.getId())));
        Map<Long, Long> groupeDe = new HashMap<>();
        parGroupe.forEach((g, l) -> l.forEach(m -> groupeDe.put(m.getId(), g)));
        for (MouvementCaisse a : mouvements.findAnnulationsDe(boutiqueId, tous)) {
            Long origine = a.getAnnulationDe().getId();
            if (!java.util.Objects.equals(groupeDe.get(a.getId()), groupeDe.get(origine))) rompus.add(origine);
        }
        for (var e : parGroupe.entrySet()) for (MouvementCaisse m : e.getValue()) {
            if (m.getAnnulationDe() != null && !java.util.Objects.equals(groupeDe.get(m.getAnnulationDe().getId()), e.getKey())) rompus.add(m.getId());
        }
        return rompus;
    }

    /** Natures proposées pour « Enregistrer dans l'appli » selon le sens de la ligne. */
    public static Set<TypeOperationTresorerie> naturesPour(BigDecimal montant) {
        return montant.signum() > 0 ? NATURES_CREDIT : NATURES_DEBIT;
    }

    // ---- Écritures ------------------------------------------------------------------------------------------

    private record Contexte(Utilisateur gerant, ReleveBancaire releve, Long boutiqueId) { }

    private Contexte ouvrir(Long releveId, boolean compteActif) {
        verrous.verrouillerBoutiqueCourante();
        Utilisateur gerant = caisse.gerantPourEcriture();
        ReleveBancaire r = verrous.verrouillerReleve(releveId);
        ReleveBancaireService.exigerBrouillon(r);
        Long boutiqueId = boutiques.idBoutiqueCourante();
        comptes.verrouiller(boutiqueId, gerant, false, compteActif, List.of(r.getCompte().getId()));
        return new Contexte(gerant, r, boutiqueId);
    }

    /** Accepte une proposition : la ligne et le mouvement sont rapprochés (montant identique exigé). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void accepter(Long releveId, Long ligneId, Long mouvementId) {
        Contexte c = ouvrir(releveId, false);
        creer(c, TypeRapprochement.AUTO, Set.of(ligneId), Set.of(mouvementId));
    }

    /** Accepte toutes les propositions en cours (hors paires refusées). Retourne leur nombre. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int toutAccepter(Long releveId) {
        Contexte c = ouvrir(releveId, false);
        Long compteId = c.releve().getCompte().getId();
        LocalDate du = c.releve().getDateDebut().minusDays(MARGE_JOURS), au = c.releve().getDateFin().plusDays(MARGE_JOURS);
        List<LigneReleve> libres = lignes.nonRapprochees(releveId);
        List<MouvementCaisse> candidats = mouvements.nonRapproches(c.boutiqueId(), compteId, du.atStartOfDay(), au.plusDays(1).atStartOfDay());
        Set<Long> exclus = nonAppariables(c.boutiqueId(), candidats);
        Set<String> refusees = new HashSet<>();
        for (RefusProposition p : refus.duReleve(releveId)) refusees.add(p.getLigne().getId() + ":" + p.getMouvement().getId());
        List<Appariement.Proposition> propositions = Appariement.proposer(libres, candidats, exclus, refusees);
        // Les mouvements sont verrouillés tous ensemble, par identifiant croissant, avant la première écriture.
        liens.verrouillerMouvements(new TreeSet<>(propositions.stream().map(p -> p.mouvement().getId()).toList()));
        for (Appariement.Proposition p : propositions) creer(c, TypeRapprochement.AUTO, Set.of(p.ligne().getId()), Set.of(p.mouvement().getId()), false);
        return propositions.size();
    }

    /** Refuse une proposition : la paire n'est plus proposée pour ce relevé. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void refuser(Long releveId, Long ligneId, Long mouvementId) {
        Contexte c = ouvrir(releveId, false);
        LigneReleve l = lignes.findByIdAndReleveId(ligneId, releveId).orElseThrow(ReleveBancaireService::introuvable);
        MouvementCaisse m = mouvements.duCompteParIds(c.boutiqueId(), c.releve().getCompte().getId(), List.of(mouvementId)).stream().findFirst()
                .orElseThrow(ReleveBancaireService::introuvable);
        if (!refus.existsByLigneIdAndMouvementId(ligneId, mouvementId)) {
            RefusProposition p = new RefusProposition();
            p.setLigne(l); p.setMouvement(m);
            refus.saveAndFlush(p);
        }
    }

    /** Les propositions refusées reviennent. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void reinitialiserRefus(Long releveId) {
        ouvrir(releveId, false);
        refus.supprimerDuReleve(releveId);
    }

    /** Rapprochement manuel : une ou plusieurs lignes avec un ou plusieurs mouvements, de sommes égales. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void rapprocher(Long releveId, Collection<Long> ligneIds, Collection<Long> mouvementIds) {
        Contexte c = ouvrir(releveId, false);
        creer(c, TypeRapprochement.MANUEL, new TreeSet<>(ligneIds), new TreeSet<>(mouvementIds));
    }

    /** Exclut ensemble un mouvement annulé et sa contre-passation : ils se compensent, aucune ligne de relevé n'est concernée. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void exclure(Long releveId, Long mouvementId) {
        Contexte c = ouvrir(releveId, false);
        List<MouvementCaisse> trouves = mouvements.duCompteParIds(c.boutiqueId(), c.releve().getCompte().getId(), List.of(mouvementId));
        if (trouves.isEmpty()) throw new IllegalArgumentException("Mouvement introuvable sur ce compte.");
        MouvementCaisse m = trouves.get(0);
        Long origineId, annulationId;
        if (m.getAnnulationDe() != null) { origineId = m.getAnnulationDe().getId(); annulationId = m.getId(); }
        else {
            List<MouvementCaisse> a = mouvements.findAnnulationsDe(c.boutiqueId(), List.of(m.getId()));
            if (a.isEmpty()) throw new IllegalArgumentException("Ce mouvement n'a pas été annulé : il se rapproche avec une ligne du relevé.");
            origineId = m.getId(); annulationId = a.get(0).getId();
        }
        creer(c, TypeRapprochement.EXCLUSION, Set.of(), new TreeSet<>(List.of(origineId, annulationId)));
    }

    /**
     * Retire un rapprochement : lignes et mouvements redeviennent libres. Un mouvement créé par « Enregistrer dans l'appli » reste dans le
     * journal (il s'annule par l'écran des opérations financières si c'était une erreur).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void defaire(Long releveId, Long rapprochementId) {
        Contexte c = ouvrir(releveId, false);
        Rapprochement g = rapprochements.findByIdAndReleveId(rapprochementId, releveId).orElseThrow(ReleveBancaireService::introuvable);
        List<Long> ids = liens.duReleve(releveId).stream().filter(rm -> rm.getRapprochement().getId().equals(rapprochementId)).map(rm -> rm.getMouvement().getId()).sorted().toList();
        if (!ids.isEmpty()) liens.verrouillerMouvements(ids);
        for (LigneReleve l : lignes.duReleve(releveId)) if (l.getRapprochement() != null && l.getRapprochement().getId().equals(rapprochementId)) l.setRapprochement(null);
        lignes.flush();
        liens.supprimerDuRapprochement(rapprochementId);
        rapprochements.delete(g);
        rapprochements.flush();
    }

    /**
     * « Enregistrer dans l'appli » : crée sur le compte du relevé l'opération adaptée à une ligne sans mouvement (frais bancaires, agios,
     * intérêts, frais Orange Money, virement reçu inconnu), à la date de la ligne, avec la classification de l'étape 3b1, puis la rapproche
     * aussitôt. Aucun contrôle de solde pour une sortie : la banque a déjà débité, l'appli ne fait que le constater.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void enregistrerDansAppli(Long releveId, Long ligneId, TypeOperationTresorerie nature) {
        Contexte c = ouvrir(releveId, true);
        LigneReleve l = lignes.findByIdAndReleveId(ligneId, releveId).orElseThrow(ReleveBancaireService::introuvable);
        if (l.estRapprochee()) throw new IllegalArgumentException("Cette ligne est déjà rapprochée.");
        if (nature == null || !naturesPour(l.getMontant()).contains(nature)) {
            throw new IllegalArgumentException(l.getMontant().signum() > 0
                    ? "Une ligne au crédit s'enregistre comme produit financier ou autre produit."
                    : "Une ligne au débit s'enregistre comme charge financière ou autre charge.");
        }
        initialisation.verrouillerEtClorePourActivite();
        var compte = c.releve().getCompte();
        BigDecimal montant = l.getMontant().abs();
        String motif = tronquer("Relevé " + c.releve().getNumero() + " : " + l.getLibelle());
        OperationTresorerie op = ecritures.operation(boutiques.boutiqueCourante(), c.gerant(), UUID.randomUUID().toString(), nature, compte, montant, motif,
                ecritures.dateDe(l.getDateOperation()), null, null);
        ecritures.mouvement(op, compte, l.getMontant(), OperationsFinancieresService.source(nature), motif);
        mouvements.flush();
        MouvementCaisse cree = mouvements.findByOperationTresorerieIdOrderByIdAsc(op.getId()).get(0);
        liens.verrouillerMouvements(List.of(cree.getId()));
        creerGroupe(c, TypeRapprochement.ENREGISTREMENT, List.of(l), List.of(cree));
    }

    // ---- Règles communes ------------------------------------------------------------------------------------

    private void creer(Contexte c, TypeRapprochement type, Set<Long> ligneIds, Set<Long> mouvementIds) { creer(c, type, ligneIds, mouvementIds, true); }

    private void creer(Contexte c, TypeRapprochement type, Set<Long> ligneIds, Set<Long> mouvementIds, boolean verrouiller) {
        if (mouvementIds.isEmpty() || (type != TypeRapprochement.EXCLUSION && ligneIds.isEmpty())) {
            throw new IllegalArgumentException("Choisissez au moins une ligne du relevé et au moins un mouvement de l'appli.");
        }
        if (ligneIds.size() > SELECTION_MAX || mouvementIds.size() > SELECTION_MAX) throw new IllegalArgumentException("Un rapprochement comporte au plus " + SELECTION_MAX + " éléments de chaque côté.");
        Long releveId = c.releve().getId(), compteId = c.releve().getCompte().getId();
        List<LigneReleve> ls = ligneIds.isEmpty() ? List.of() : lignes.duReleveParIds(releveId, ligneIds);
        if (ls.size() != ligneIds.size()) throw new IllegalArgumentException("Une ligne choisie n'appartient pas à ce relevé.");
        for (LigneReleve l : ls) if (l.estRapprochee()) throw new IllegalArgumentException("La ligne « " + l.getLibelle() + " » est déjà rapprochée.");
        List<MouvementCaisse> ms = mouvements.duCompteParIds(c.boutiqueId(), compteId, mouvementIds);
        if (ms.size() != mouvementIds.size()) throw new IllegalArgumentException("Un mouvement choisi n'appartient pas à ce compte.");
        if (verrouiller) liens.verrouillerMouvements(new TreeSet<>(mouvementIds));
        if (!liens.deMouvements(mouvementIds).isEmpty()) throw new IllegalArgumentException("Un des mouvements choisis est déjà rapproché.");
        LocalDate limite = c.releve().getDateFin().plusDays(MARGE_JOURS);
        for (MouvementCaisse m : ms) {
            if (m.getDateMouvement().toLocalDate().isAfter(limite)) throw new IllegalArgumentException("Le mouvement du " + Textes.date(m.getDateMouvement().toLocalDate()) + " est postérieur de plus de " + MARGE_JOURS + " jours à la clôture du relevé.");
        }
        exigerAnnulationsEnsemble(c.boutiqueId(), ms, type);
        creerGroupe(c, type, ls, ms);
    }

    /** Un mouvement annulé et sa contre-passation se rapprochent ensemble ou pas du tout ; l'exclusion est faite pour eux. */
    private void exigerAnnulationsEnsemble(Long boutiqueId, List<MouvementCaisse> ms, TypeRapprochement type) {
        Set<Long> ids = new HashSet<>();
        for (MouvementCaisse m : ms) ids.add(m.getId());
        for (MouvementCaisse a : mouvements.findAnnulationsDe(boutiqueId, ids)) {
            if (!ids.contains(a.getId())) throw new IllegalArgumentException("Un mouvement choisi a été annulé : il ne peut pas être rapproché seul. Excluez-le ensemble avec son annulation.");
        }
        for (MouvementCaisse m : ms) {
            if (m.getAnnulationDe() != null && !ids.contains(m.getAnnulationDe().getId())) throw new IllegalArgumentException("Un mouvement choisi est l'annulation d'un autre : il ne peut pas être rapproché seul. Excluez-les ensemble.");
        }
        if (type == TypeRapprochement.EXCLUSION) {
            for (MouvementCaisse m : ms) if (!ids.contains(m.getAnnulationDe() == null ? -1L : m.getAnnulationDe().getId()) && mouvements.findAnnulationsDe(boutiqueId, List.of(m.getId())).isEmpty()) {
                throw new IllegalArgumentException("Seuls un mouvement annulé et son annulation s'excluent ensemble.");
            }
        }
    }

    private void creerGroupe(Contexte c, TypeRapprochement type, List<LigneReleve> ls, List<MouvementCaisse> ms) {
        BigDecimal sommeLignes = ls.stream().map(LigneReleve::getMontant).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sommeMouvements = ms.stream().map(MouvementCaisse::getMontant).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sommeLignes.compareTo(sommeMouvements) != 0) {
            throw new IllegalArgumentException("Les sommes ne sont pas égales : lignes du relevé " + Textes.montant(sommeLignes) + " GNF, mouvements de l'appli "
                    + Textes.montant(sommeMouvements) + " GNF (écart " + Textes.montant(sommeLignes.subtract(sommeMouvements)) + " GNF). Aucun écart n'est toléré dans un rapprochement.");
        }
        Rapprochement g = new Rapprochement();
        g.setReleve(c.releve()); g.setType(type); g.setMontant(Montants.valider(sommeLignes, "Le montant")); g.setUtilisateur(c.gerant()); g.setDateCreation(horloge.maintenant());
        rapprochements.saveAndFlush(g);
        for (MouvementCaisse m : ms) {
            RapprochementMouvement rm = new RapprochementMouvement();
            rm.setRapprochement(g); rm.setMouvement(m);
            liens.save(rm);
        }
        for (LigneReleve l : ls) l.setRapprochement(g);
        liens.flush();
        lignes.flush();
    }

    private Set<Long> nonAppariables(Long boutiqueId, List<MouvementCaisse> candidats) {
        Set<Long> exclus = new HashSet<>();
        for (MouvementCaisse m : candidats) if (m.getAnnulationDe() != null) exclus.add(m.getId());
        if (!candidats.isEmpty()) for (MouvementCaisse a : mouvements.findAnnulationsDe(boutiqueId, candidats.stream().map(MouvementCaisse::getId).toList())) exclus.add(a.getAnnulationDe().getId());
        return exclus;
    }

    private static String tronquer(String texte) { return texte.length() <= 255 ? texte : texte.substring(0, 254) + "…"; }
}
