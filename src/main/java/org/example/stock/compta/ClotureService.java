package org.example.stock.compta;

import jakarta.persistence.EntityManager;
import org.example.stock.compta.ComptabiliteService.Controle;
import org.example.stock.compta.ComptabiliteService.Livres;
import org.example.stock.compta.ComptabiliteService.Statut;
import org.example.stock.compta.Ecriture.TypeTiers;
import org.example.stock.compta.EtatsFinanciersService.Etats;
import org.example.stock.enums.StatutInstantane;
import org.example.stock.enums.SystemeComptable;
import org.example.stock.export.ContexteDocument;
import org.example.stock.export.ContexteDocumentService;
import org.example.stock.export.DocumentPdfService;
import org.example.stock.export.ExportExcelService;
import org.example.stock.export.TelechargementExport;
import org.example.stock.model.AffectationResultat;
import org.example.stock.model.ExerciceComptable;
import org.example.stock.model.InstantaneDocument;
import org.example.stock.model.InstantaneExercice;
import org.example.stock.model.SoldeOuverture;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.AffectationResultatRepository;
import org.example.stock.repository.ExerciceComptableRepository;
import org.example.stock.repository.InstantaneDocumentRepository;
import org.example.stock.repository.InstantaneExerciceRepository;
import org.example.stock.repository.SoldeOuvertureRepository;
import org.example.stock.repository.StockLockRepository;
import org.example.stock.service.BoutiqueService;
import org.example.stock.service.CaisseService;
import org.example.stock.service.EcheanceService;
import org.example.stock.service.Etat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Clôture d'exercice (étape 5c), réservée au gérant : vérifications de pré-clôture, clôture (instantané des états, report à nouveau, verrou),
 * annulation de la clôture et affectation du résultat. Chaque écriture prend la boutique, puis l'exercice, en écriture : aucune opération ne se glisse
 * pendant la clôture, et celles qui attendaient relisent l'état validé (voir {@link VerrouExercice}).
 */
@Service
public class ClotureService {
    public static final int MOTIF_MAXIMUM = 255, MOTIF_MINIMUM_ANNULATION = 3;
    /** Comptes où le résultat peut être affecté : report à nouveau, réserves, associés dividendes à payer. */
    public static final List<String> DESTINATIONS = List.of("12", "11", "465");

    public enum Niveau { BLOQUANT("Bloquant"), RECOMMANDE("Recommandé");
        private final String libelle; Niveau(String libelle) { this.libelle = libelle; } public String getLibelle() { return libelle; } }

    /** Une vérification de pré-clôture : son niveau, son résultat, le détail et le lien où la corriger. */
    public record Verification(String code, String libelle, Niveau niveau, boolean ok, String detail, String lien) {
        public boolean bloque() { return niveau == Niveau.BLOQUANT && !ok; }
    }

    public record PreCloture(ExercicesService.Exercice exercice, List<Verification> verifications) {
        public boolean peutCloturer() { return verifications.stream().noneMatch(Verification::bloque); }
        public long bloquants() { return verifications.stream().filter(Verification::bloque).count(); }
    }

    private final ExercicesService exercices;
    private final ExerciceComptableRepository exerciceRepo;
    private final SoldeOuvertureRepository soldesRepo;
    private final InstantaneExerciceRepository instantaneRepo;
    private final InstantaneDocumentRepository documentRepo;
    private final AffectationResultatRepository affectationRepo;
    private final StockLockRepository verrous;
    private final VerrouExercice verrouExercice;
    private final ComptabiliteService compta;
    private final EtatsFinanciersService financiers;
    private final CaisseService caisse;
    private final EcheanceService horloge;
    private final BoutiqueService boutiques;
    private final DocumentPdfService pdf;
    private final ExportExcelService excel;
    private final ContexteDocumentService contextes;
    private final MentionComptable mentions;
    private final EntityManager em;

    public ClotureService(ExercicesService exercices, ExerciceComptableRepository exerciceRepo, SoldeOuvertureRepository soldesRepo, InstantaneExerciceRepository instantaneRepo,
                          InstantaneDocumentRepository documentRepo, AffectationResultatRepository affectationRepo, StockLockRepository verrous, VerrouExercice verrouExercice,
                          ComptabiliteService compta, EtatsFinanciersService financiers, CaisseService caisse, EcheanceService horloge, BoutiqueService boutiques,
                          DocumentPdfService pdf, ExportExcelService excel, ContexteDocumentService contextes, MentionComptable mentions, EntityManager em) {
        this.exercices = exercices; this.exerciceRepo = exerciceRepo; this.soldesRepo = soldesRepo; this.instantaneRepo = instantaneRepo; this.documentRepo = documentRepo;
        this.affectationRepo = affectationRepo; this.verrous = verrous; this.verrouExercice = verrouExercice; this.compta = compta; this.financiers = financiers;
        this.caisse = caisse; this.horloge = horloge; this.boutiques = boutiques; this.pdf = pdf; this.excel = excel; this.contextes = contextes; this.mentions = mentions; this.em = em;
    }

    // ---- Pré-clôture --------------------------------------------------------------------------------------------------------------

    /** Les vérifications avant de clôturer l'exercice qui commence le {@code debut}. Lecture seule. */
    @Transactional(readOnly = true)
    public PreCloture preCloture(LocalDate debut) {
        exigerGerant();
        Long b = boutiques.idBoutiqueCourante();
        return preCloture(b, exerciceQuiCommence(b, debut));
    }

    private ExercicesService.Exercice exerciceQuiCommence(Long boutiqueId, LocalDate debut) {
        ExercicesService.Exercice e = exercices.exercicePour(boutiqueId, debut);
        if (!e.debut().equals(debut)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exercice introuvable");
        return e;
    }

    PreCloture preCloture(Long b, ExercicesService.Exercice ex) {
        List<Verification> v = new ArrayList<>();
        LocalDate du = ex.debut(), au = ex.fin();
        String periode = "?du=" + du + "&au=" + au;
        boolean termine = au.isBefore(horloge.aujourdHui());
        v.add(new Verification("termine", "L'exercice est terminé", Niveau.BLOQUANT, termine,
                termine ? "Il s'est terminé le " + ExercicesService.jour(au) + "." : "Il se termine le " + ExercicesService.jour(au) + " : on ne clôture qu'un exercice terminé.", "/comptabilite/exercices"));
        boolean precedent = ex.rang() == 0 || exercices.exercicePour(b, du.minusDays(1)).estCloture();
        v.add(new Verification("precedent", "L'exercice précédent est clôturé", Niveau.BLOQUANT, precedent,
                ex.rang() == 0 ? "C'est le premier exercice." : precedent ? "L'exercice précédent est clôturé." : "Clôturez d'abord l'exercice précédent : ses soldes ouvrent celui-ci.", "/comptabilite/exercices"));

        Livres livres = compta.calculer(b, du, au);
        List<Controle> controles = new ArrayList<>(compta.controles(livres));
        Etats etats = financiers.calculerEnDirect(b, du, au);
        controles.addAll(EtatsFinanciersService.controles(etats));
        v.add(verificationControles("controles-compta", "Contrôles de la comptabilité (équilibre, trésorerie, clients, fournisseurs, stock)", controles, 1, 5, "/comptabilite/controles" + periode));
        v.add(verificationControles("controles-etats", "Contrôles des états financiers (bilan, résultat, flux, SMT)", controles, 6, 9, "/comptabilite/controles" + periode));

        LocalDateTime finJour = au.atTime(LocalTime.MAX);
        long comptages = nombre("select count(c) from ComptageCaisse c where c.boutique.id = :b and c.statut = org.example.stock.enums.StatutComptage.EN_ATTENTE and c.dateComptage <= :fin", b, "fin", finJour);
        v.add(new Verification("comptages", "Aucun comptage de caisse en attente", Niveau.BLOQUANT, comptages == 0,
                comptages == 0 ? "Aucun comptage en attente de décision." : comptages + " comptage(s) de caisse attendent votre décision : validez-les ou refusez-les.", "/controles/comptages/en-attente"));
        long releves = nombre("select count(r) from ReleveBancaire r where r.boutique.id = :b and r.statut = org.example.stock.enums.StatutReleve.BROUILLON and r.dateFin >= :du and r.dateDebut <= :au", b, "du", du, "au", au);
        v.add(new Verification("releves", "Aucun relevé bancaire en brouillon sur la période", Niveau.BLOQUANT, releves == 0,
                releves == 0 ? "Aucun relevé en cours sur la période." : releves + " relevé(s) en cours (brouillon) recouvrent l'exercice : validez-les ou supprimez-les.", "/rapprochements"));

        List<?> sansCout = em.createNativeQuery("select nom from produit where boutique_id = :b and supprime_le is null and (nature is null or nature = 'STOCK') and quantite > 0 and (cout_moyen is null or cout_moyen <= 0) order by nom")
                .setParameter("b", b).setMaxResults(6).getResultList();
        v.add(new Verification("stock", "Le stock est compté et valorisé", Niveau.BLOQUANT, sansCout.isEmpty(),
                sansCout.isEmpty() ? "Chaque produit en stock a une quantité et un coût : le stock des états est celui de l'application." : "Produit(s) en stock sans coût : " + String.join(", ", sansCout.stream().map(Object::toString).toList()) + ".", "/produits"));
        long inventaires = nombre("select count(s) from MouvementStock s where s.boutique.id = :b and s.creeLe >= :depuis and s.creeLe <= :fin", b, "depuis", au.minusDays(90).atStartOfDay(), "fin", finJour);
        v.add(new Verification("inventaire", "Un inventaire récent du stock", Niveau.RECOMMANDE, inventaires > 0,
                inventaires > 0 ? "Une entrée de stock ou un inventaire date des 90 derniers jours de l'exercice." : "Aucun inventaire ni stock de départ saisi dans les 90 derniers jours de l'exercice : comptez le stock avant de clôturer.", "/produits"));

        if (ex.rang() > 0) {
            ExercicesService.Exercice prec = exercices.exercicePour(b, du.minusDays(1));
            boolean affecte = !prec.estCloture() || prec.ligne().getResultat() == null || prec.ligne().getResultat().signum() == 0 || affectationRepo.findByExerciceId(prec.ligne().getId()).isPresent();
            v.add(new Verification("affectation", "Le résultat de l'exercice précédent est affecté", Niveau.RECOMMANDE, affecte,
                    affecte ? "Rien à affecter, ou l'affectation est saisie." : "Le résultat de l'exercice " + prec.libelle() + " n'est pas affecté : il restera en compte 13 et ne pourra plus l'être une fois cet exercice clôturé.", "/comptabilite/exercices"));
        }
        boolean valide = mentions.pour(b).startsWith("États validés");
        v.add(new Verification("validation", "États validés par un expert-comptable", Niveau.RECOMMANDE, valide,
                valide ? mentions.pour(b) : "Les états portent encore la mention « en cours de validation » : renseignez la validation de l'expert-comptable quand elle est faite.", "/comptabilite/exercices#validation"));
        return new PreCloture(ex, v);
    }

    private static Verification verificationControles(String code, String libelle, List<Controle> controles, int de, int a, String lien) {
        List<Controle> concernes = controles.stream().filter(c -> c.numero() >= de && c.numero() <= a).toList();
        List<String> ecarts = concernes.stream().filter(c -> c.statut() == Statut.ECART).map(c -> "contrôle " + c.numero() + " (" + c.libelle() + ")").toList();
        long nonCompares = concernes.stream().filter(c -> c.statut() == Statut.NON_COMPARE).count();
        String detail = ecarts.isEmpty() ? concernes.size() - nonCompares + " contrôle(s) conforme(s)" + (nonCompares == 0 ? "." : ", " + nonCompares + " non comparable(s) : l'application ne conserve pas ses soldes à une date passée.")
                : "Écart : " + String.join(" ; ", ecarts) + ".";
        return new Verification(code, libelle, Niveau.BLOQUANT, ecarts.isEmpty(), detail, lien);
    }

    private long nombre(String jpql, Long b, Object... parametres) {
        var q = em.createQuery(jpql, Long.class).setParameter("b", b);
        for (int i = 0; i < parametres.length; i += 2) q.setParameter((String) parametres[i], parametres[i + 1]);
        return q.getSingleResult();
    }

    // ---- Clôture ---------------------------------------------------------------------------------------------------------------

    /**
     * Clôture l'exercice qui commence le {@code debut}. Sous le verrou de la boutique puis de l'exercice : les vérifications bloquantes sont revues, les états
     * sont calculés et figés (montants, PDF, Excel, empreinte SHA-256), les soldes des comptes de bilan deviennent les soldes d'ouverture de l'exercice suivant
     * (le résultat net va en 131 ou 139) et l'exercice est verrouillé.
     */
    @Transactional
    public ExerciceComptable cloturer(LocalDate debut, String motifSaisi) {
        exigerGerant();
        String motif = motifFacultatif(motifSaisi);
        verrous.verrouillerBoutiqueCourante();
        Long b = boutiques.idBoutiqueCourante();
        ExercicesService.Exercice ex = exerciceQuiCommence(b, debut);
        ExerciceComptable ligne = verrouillerLigne(b, ex);
        if (ligne.estCloture()) throw new IllegalArgumentException("L'exercice " + ex.libelle() + " est déjà clôturé.");
        Utilisateur acteur = caisse.gerantPourEcriture();
        PreCloture pre = preCloture(b, ex);
        if (!pre.peutCloturer()) {
            Verification premiere = pre.verifications().stream().filter(Verification::bloque).findFirst().orElseThrow();
            throw new IllegalArgumentException("La clôture de l'exercice " + ex.libelle() + " est impossible : " + premiere.libelle() + " n'est pas satisfait (" + premiere.detail() + ")");
        }

        LocalDate du = ex.debut(), au = ex.fin();
        LocalDateTime maintenant = horloge.maintenant();
        String nomActeur = nomDe(acteur);
        Livres livres = compta.calculer(b, du, au);
        Etats etats = financiers.calculerEnDirect(b, du, au);
        ComptabiliteService.Balance balance = compta.balance(livres);

        // Soldes des comptes de bilan à la clôture, arrondis au centime ; le résultat net équilibre le tout (131 : bénéfice, 139 : perte).
        SoldesComptables soldes = SoldesComptables.de(livres.ecritures());
        Map<String, String> noms = nomsDesTiers(livres);
        Map<SoldesComptables.Cle, BigDecimal> bilan = new LinkedHashMap<>();
        BigDecimal somme = BigDecimal.ZERO;
        for (var e : soldes.atomes().entrySet()) {
            if (!SoldesComptables.estBilan(e.getKey().compte())) continue;
            BigDecimal s = e.getValue().setScale(2, RoundingMode.HALF_UP);
            if (s.signum() == 0) continue;
            bilan.merge(e.getKey(), s, BigDecimal::add);
            somme = somme.add(s);
        }
        BigDecimal resultat = somme.setScale(2, RoundingMode.HALF_UP);
        if (resultat.signum() != 0) bilan.merge(new SoldesComptables.Cle(resultat.signum() > 0 ? "131" : "139", null, null), resultat.negate(), BigDecimal::add);
        BigDecimal total = bilan.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() != 0) throw new IllegalStateException("Les soldes de clôture ne s'équilibrent pas (écart " + total.toPlainString() + ") : la clôture est annulée.");
        BigDecimal valeurStock = soldes.solde(livres.correspondance().compte(CleCompta.STOCK)).setScale(2, RoundingMode.HALF_UP);
        long plafondClients = plafond("client"), plafondFournisseurs = plafond("fournisseur");

        ligne.cloturer(maintenant, nomActeur, motif, resultat, valeurStock, plafondClients, plafondFournisseurs);
        exerciceRepo.saveAndFlush(ligne);
        List<SoldeOuverture> ouverture = new ArrayList<>();
        for (var e : bilan.entrySet()) {
            var cle = e.getKey();
            ouverture.add(new SoldeOuverture(ligne, cle.compte(), cle.tiersType() == null ? "" : cle.tiersType().name(), cle.tiersId(), noms.get(cle.tiersType() + ":" + cle.tiersId()), e.getValue()));
        }
        soldesRepo.saveAll(ouverture);

        // Instantané : montants calculés, puis PDF et Excel de chaque document, puis empreinte.
        SystemeComptable systeme = financiers.systeme(b);
        ContenuInstantane contenu = new ContenuInstantane(ContenuInstantane.FORMAT, du, au, systeme.name(), etats.n(), etats.n1(), balance);
        byte[] octets = contenu.enOctets();
        InstantaneExercice instantane = new InstantaneExercice(boutiques.boutiqueCourante(), ligne, instantaneRepo.countByExerciceId(ligne.getId()) + 1, systeme.name(), resultat, "0".repeat(64), octets, maintenant, nomActeur, motif);
        instantane = instantaneRepo.saveAndFlush(instantane);
        String mention = mentions.pourExerciceFige(b, maintenant, nomActeur);
        ContexteDocument contexte = contextes.courant();
        List<Etat> etatsFiges = new ArrayList<>();
        etatsFiges.add(ComptabiliteEtats.balance(livres, balance));
        if (systeme == SystemeComptable.NORMAL) { etatsFiges.add(EtatsFinanciersEtats.compteResultat(etats)); etatsFiges.add(EtatsFinanciersEtats.bilan(etats)); etatsFiges.add(EtatsFinanciersEtats.tableauFlux(etats)); }
        else etatsFiges.add(EtatsFinanciersEtats.smt(etats));
        List<InstantaneDocument> documents = new ArrayList<>();
        for (Etat brut : etatsFiges) {
            Etat e = MentionComptable.avecMention(brut, mention);
            byte[] fichierPdf = pdf.rendreEtat(e.titre(), e.parametres(), e.notes(), e.sections(), contexte, e.format());
            byte[] fichierExcel = excel.ecrire(e.sections(), contexte);
            documents.add(new InstantaneDocument(instantane, e.code(), e.titre(), TelechargementExport.nomFichier(e.base(), e.du(), e.au(), "pdf"), fichierPdf, fichierExcel));
        }
        documentRepo.saveAll(documents);
        instantane.fixerEmpreinte(InstantanesService.empreinte(octets, documents));
        instantaneRepo.saveAndFlush(instantane);
        return ligne;
    }

    private long plafond(String table) {
        return ((Number) em.createNativeQuery("select coalesce(max(id), 0) from " + table).getSingleResult()).longValue();
    }

    private static Map<String, String> nomsDesTiers(Livres livres) {
        Map<String, String> noms = new LinkedHashMap<>();
        for (Ecriture e : livres.ecritures()) for (Ecriture.Ligne l : e.lignes()) if (l.tiersType() != null && l.tiersId() != null && l.tiersNom() != null) noms.put(l.tiersType() + ":" + l.tiersId(), l.tiersNom());
        return noms;
    }

    // ---- Annulation de la clôture -----------------------------------------------------------------------------------------

    /**
     * Annule la clôture du dernier exercice clôturé : supprime ses soldes d'ouverture et rouvre l'exercice, mais garde son instantané, marqué « annulé », avec la
     * date, l'auteur et le motif. Refusée s'il n'est pas le dernier clôturé, si son résultat est déjà affecté ou si le motif manque.
     */
    @Transactional
    public void annulerCloture(LocalDate debut, String motifSaisi) {
        exigerGerant();
        String motif = motifSaisi == null ? "" : motifSaisi.strip();
        if (motif.length() < MOTIF_MINIMUM_ANNULATION) throw new IllegalArgumentException("Le motif de l'annulation est obligatoire (au moins " + MOTIF_MINIMUM_ANNULATION + " caractères).");
        if (motif.length() > MOTIF_MAXIMUM) throw new IllegalArgumentException("Le motif est trop long (" + MOTIF_MAXIMUM + " caractères au plus).");
        verrous.verrouillerBoutiqueCourante();
        Long b = boutiques.idBoutiqueCourante();
        ExercicesService.Exercice ex = exerciceQuiCommence(b, debut);
        if (ex.ligne() == null) throw new IllegalArgumentException("L'exercice " + ex.libelle() + " n'est pas clôturé.");
        ExerciceComptable ligne = verrous.verrouillerExercice(ex.ligne().getId());
        if (!ligne.estCloture()) throw new IllegalArgumentException("L'exercice " + ex.libelle() + " n'est pas clôturé.");
        for (ExerciceComptable autre : exerciceRepo.findByBoutiqueIdAndStatutOrderByDateDebut(b, org.example.stock.enums.StatutExercice.CLOTURE))
            if (autre.getDateDebut().isAfter(ligne.getDateDebut()))
                throw new IllegalArgumentException("Le dernier exercice clôturé est " + ExercicesService.libelle(autre.getDateDebut(), autre.getDateFin()) + " : annulez d'abord sa clôture, puis celle de l'exercice " + ex.libelle() + ".");
        if (affectationRepo.findByExerciceId(ligne.getId()).isPresent())
            throw new IllegalArgumentException("Le résultat de l'exercice " + ex.libelle() + " est déjà affecté : supprimez d'abord l'affectation pour pouvoir annuler la clôture.");
        Utilisateur acteur = caisse.gerantPourEcriture();
        InstantaneExercice instantane = instantaneRepo.findByExerciceIdAndStatut(ligne.getId(), StatutInstantane.ACTIF)
                .orElseThrow(() -> new IllegalStateException("L'instantané de l'exercice est introuvable."));
        instantane.annuler(horloge.maintenant(), nomDe(acteur), motif);
        instantaneRepo.saveAndFlush(instantane);
        soldesRepo.supprimerDe(ligne.getId());
        ligne.rouvrir();
        exerciceRepo.saveAndFlush(ligne);
    }

    // ---- Affectation du résultat ------------------------------------------------------------------------------------------

    /**
     * Affecte le résultat du dernier exercice clôturé : le compte 13 est soldé vers le report à nouveau (12), les réserves (11) ou les associés, dividendes à payer
     * (465, pour un bénéfice seulement). L'écriture est une OD de l'exercice suivant, à {@code dateAffectation} (le premier jour de cet exercice par défaut).
     */
    @Transactional
    public AffectationResultat affecter(LocalDate debut, String destination, LocalDate dateAffectation) {
        exigerGerant();
        verrous.verrouillerBoutiqueCourante();
        Long b = boutiques.idBoutiqueCourante();
        ExercicesService.Exercice ex = exerciceQuiCommence(b, debut);
        if (ex.ligne() == null) throw new IllegalArgumentException("L'exercice " + ex.libelle() + " n'est pas clôturé : on n'affecte que le résultat d'un exercice clôturé.");
        ExerciceComptable ligne = verrous.verrouillerExercice(ex.ligne().getId());
        if (!ligne.estCloture()) throw new IllegalArgumentException("L'exercice " + ex.libelle() + " n'est pas clôturé : on n'affecte que le résultat d'un exercice clôturé.");
        ExercicesService.Exercice suivant = exercices.exercicePour(b, ex.fin().plusDays(1));
        if (suivant.estCloture()) throw new IllegalArgumentException("L'exercice suivant (" + suivant.libelle() + ") est clôturé : le résultat de l'exercice " + ex.libelle() + " ne peut plus être affecté.");
        if (affectationRepo.findByExerciceId(ligne.getId()).isPresent()) throw new IllegalArgumentException("Le résultat de l'exercice " + ex.libelle() + " est déjà affecté.");
        BigDecimal resultat = ligne.getResultat() == null ? BigDecimal.ZERO : ligne.getResultat();
        if (resultat.signum() == 0) throw new IllegalArgumentException("Le résultat de l'exercice " + ex.libelle() + " est nul : il n'y a rien à affecter.");
        if (destination == null || !DESTINATIONS.contains(destination.strip())) throw new IllegalArgumentException("Choisissez où affecter le résultat : report à nouveau (12), réserves (11) ou associés, dividendes à payer (465).");
        String choix = destination.strip();
        String compte = choix.equals("11") ? "118" : choix;   // les réserves se portent au compte 118 (réserves libres) : un compte du plan, rattaché au bilan
        if (resultat.signum() < 0 && compte.equals("465")) throw new IllegalArgumentException("Une perte ne se verse pas en dividendes : affectez-la au report à nouveau (12) ou aux réserves (11).");
        LocalDate date = dateAffectation == null ? suivant.debut() : dateAffectation;
        if (!suivant.contient(date)) throw new IllegalArgumentException("La date d'affectation doit être dans l'exercice " + suivant.libelle() + " (du " + ExercicesService.jour(suivant.debut()) + " au " + ExercicesService.jour(suivant.fin()) + ").");
        if (date.isAfter(horloge.aujourdHui())) throw new IllegalArgumentException("La date d'affectation ne peut pas être dans le futur.");
        verrouExercice.exigerOuvert(date);
        Utilisateur acteur = caisse.gerantPourEcriture();
        String libelle = "Affectation du résultat de l'exercice " + ex.libelle() + " : " + libelleDestination(compte, resultat);
        return affectationRepo.saveAndFlush(new AffectationResultat(boutiques.boutiqueCourante(), ligne, date, compte, resultat, libelle, horloge.maintenant(), nomDe(acteur)));
    }

    /** Supprime l'affectation du résultat d'un exercice clôturé, tant que l'exercice suivant n'est pas clôturé (pour la corriger ou pour pouvoir annuler la clôture). */
    @Transactional
    public void supprimerAffectation(LocalDate debut) {
        exigerGerant();
        verrous.verrouillerBoutiqueCourante();
        Long b = boutiques.idBoutiqueCourante();
        ExercicesService.Exercice ex = exerciceQuiCommence(b, debut);
        if (ex.ligne() == null) throw new IllegalArgumentException("L'exercice " + ex.libelle() + " n'est pas clôturé.");
        ExerciceComptable ligne = verrous.verrouillerExercice(ex.ligne().getId());
        ExercicesService.Exercice suivant = exercices.exercicePour(b, ex.fin().plusDays(1));
        if (suivant.estCloture()) throw new IllegalArgumentException("L'exercice suivant (" + suivant.libelle() + ") est clôturé : l'affectation ne peut plus être supprimée.");
        AffectationResultat a = affectationRepo.findByExerciceId(ligne.getId()).orElseThrow(() -> new IllegalArgumentException("Le résultat de l'exercice " + ex.libelle() + " n'est pas affecté."));
        verrouExercice.exigerOuvert(a.getDateAffectation());
        affectationRepo.delete(a);
        affectationRepo.flush();
    }

    static String libelleDestination(String compte, BigDecimal resultat) {
        return switch (compte) { case "118" -> "réserves libres"; case "465" -> "associés, dividendes à payer"; default -> "report à nouveau" + (resultat.signum() < 0 ? " (perte reportée)" : ""); };
    }

    // ---- Outils ---------------------------------------------------------------------------------------------------------------------

    /** La ligne de l'exercice, créée si l'exercice n'a jamais été clôturé, puis verrouillée en écriture. */
    private ExerciceComptable verrouillerLigne(Long boutiqueId, ExercicesService.Exercice ex) {
        ExerciceComptable ligne = ex.ligne();
        if (ligne == null) ligne = exerciceRepo.saveAndFlush(new ExerciceComptable(boutiques.boutiqueCourante(), ex.debut(), ex.fin()));
        return verrous.verrouillerExercice(ligne.getId());
    }

    private static String motifFacultatif(String motif) {
        String m = motif == null ? "" : motif.strip();
        if (m.length() > MOTIF_MAXIMUM) throw new IllegalArgumentException("Le motif est trop long (" + MOTIF_MAXIMUM + " caractères au plus).");
        return m.isEmpty() ? null : m;
    }

    private static String nomDe(Utilisateur u) {
        String nom = u.getNom() != null && !u.getNom().isBlank() ? u.getNom().strip() : u.getEmail();
        return nom.length() > 120 ? nom.substring(0, 120) : nom;
    }

    /** Seul le gérant clôture, annule ou affecte (la configuration de sécurité l'impose aussi à l'adresse). */
    private static void exigerGerant() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Réservé au gérant");
    }
}
