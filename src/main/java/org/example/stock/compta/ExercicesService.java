package org.example.stock.compta;

import jakarta.persistence.EntityManager;
import org.example.stock.compta.Ecriture.TypeTiers;
import org.example.stock.enums.StatutExercice;
import org.example.stock.enums.SystemeComptable;
import org.example.stock.model.AffectationResultat;
import org.example.stock.model.ExerciceComptable;
import org.example.stock.model.ReglageComptableBoutique;
import org.example.stock.model.SoldeOuverture;
import org.example.stock.repository.AffectationResultatRepository;
import org.example.stock.repository.ExerciceComptableRepository;
import org.example.stock.repository.ReglageComptableBoutiqueRepository;
import org.example.stock.repository.SoldeOuvertureRepository;
import org.example.stock.repository.StockLockRepository;
import org.example.stock.service.BoutiqueService;
import org.example.stock.service.EcheanceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Calendrier des exercices d'une boutique (étape 5c). Un exercice dure 12 mois ; le premier commence à la date réglée par le gérant (le 1er janvier
 * de l'année de la première opération par défaut), les suivants se suivent sans trou. Seuls les exercices clôturés sont stockés ; les autres se calculent.
 */
@Service
public class ExercicesService {
    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ExerciceComptableRepository exercices;
    private final ReglageComptableBoutiqueRepository reglages;
    private final SoldeOuvertureRepository soldes;
    private final AffectationResultatRepository affectations;
    private final DonneesComptablesService donnees;
    private final EcheanceService horloge;
    private final BoutiqueService boutiques;
    private final StockLockRepository verrous;
    private final EntityManager em;

    public ExercicesService(ExerciceComptableRepository exercices, ReglageComptableBoutiqueRepository reglages, SoldeOuvertureRepository soldes,
                            AffectationResultatRepository affectations, DonneesComptablesService donnees, EcheanceService horloge, BoutiqueService boutiques,
                            StockLockRepository verrous, EntityManager em) {
        this.exercices = exercices; this.reglages = reglages; this.soldes = soldes; this.affectations = affectations; this.donnees = donnees;
        this.horloge = horloge; this.boutiques = boutiques; this.verrous = verrous; this.em = em;
    }

    /** Un exercice du calendrier : son rang (0 pour le premier), ses dates et, s'il a été clôturé, sa ligne. */
    public record Exercice(int rang, LocalDate debut, LocalDate fin, ExerciceComptable ligne) {
        public String libelle() { return ExercicesService.libelle(debut, fin); }
        public boolean estCloture() { return ligne != null && ligne.estCloture(); }
        public StatutExercice statut() { return estCloture() ? StatutExercice.CLOTURE : StatutExercice.OUVERT; }
        public boolean contient(LocalDate jour) { return !jour.isBefore(debut) && !jour.isAfter(fin); }
    }

    /** « 2026 » pour une année civile, « 2025-2026 » pour un exercice à cheval sur deux années. */
    public static String libelle(LocalDate debut, LocalDate fin) {
        return debut.getYear() == fin.getYear() ? String.valueOf(debut.getYear()) : debut.getYear() + "-" + fin.getYear();
    }

    public static String jour(LocalDate d) { return d == null ? "" : JOUR.format(d); }

    // ---- Calendrier --------------------------------------------------------------------------------------------------------------

    /** Date de la plus ancienne opération de la boutique, ou {@code null} s'il n'y en a aucune. */
    @Transactional(readOnly = true)
    public LocalDate premiereOperation(Long boutiqueId) {
        LocalDateTime min = null;
        for (String jpql : List.of("select min(v.dateVente) from Vente v where v.boutique.id = :b", "select min(a.dateAchat) from Achat a where a.boutique.id = :b",
                "select min(r.dateRetour) from Retour r where r.boutique.id = :b", "select min(m.dateMouvement) from MouvementCaisse m where m.boutique.id = :b",
                "select min(a.dateAjustement) from AjustementSoldeDepart a where a.boutique.id = :b", "select min(s.creeLe) from MouvementStock s where s.boutique.id = :b")) {
            Object o = em.createQuery(jpql).setParameter("b", boutiqueId).getSingleResult();
            if (o instanceof LocalDateTime d && (min == null || d.isBefore(min))) min = d;
        }
        return min == null ? null : min.toLocalDate();
    }

    /** Début du premier exercice : le réglage du gérant, sinon le premier exercice clôturé, sinon le 1er janvier de l'année de la première opération. */
    @Transactional(readOnly = true)
    public LocalDate debutPremierExercice(Long boutiqueId) {
        List<ExerciceComptable> clotures = exercices.findByBoutiqueIdAndStatutOrderByDateDebut(boutiqueId, StatutExercice.CLOTURE);
        if (!clotures.isEmpty()) return clotures.getFirst().getDateDebut();
        Optional<ReglageComptableBoutique> r = reglages.findByBoutiqueId(boutiqueId);
        if (r.isPresent() && r.get().getDebutPremierExercice() != null) return r.get().getDebutPremierExercice();
        LocalDate premiere = premiereOperation(boutiqueId);
        return LocalDate.of((premiere == null ? horloge.aujourdHui() : premiere).getYear(), 1, 1);
    }

    private static LocalDate debutDuRang(LocalDate premier, int rang) { return premier.plusMonths(12L * rang); }

    private static LocalDate finDuRang(LocalDate premier, int rang) { return premier.plusMonths(12L * (rang + 1)).minusDays(1); }

    static int rangDe(LocalDate premier, LocalDate jour) {
        if (jour.isBefore(premier)) return 0;
        int k = (int) (ChronoUnit.MONTHS.between(premier, jour) / 12);
        while (finDuRang(premier, k).isBefore(jour)) k++;
        while (k > 0 && debutDuRang(premier, k).isAfter(jour)) k--;
        return k;
    }

    private Exercice exercice(Map<LocalDate, ExerciceComptable> lignes, LocalDate premier, int rang) {
        LocalDate debut = debutDuRang(premier, rang);
        return new Exercice(rang, debut, finDuRang(premier, rang), lignes.get(debut));
    }

    private Map<LocalDate, ExerciceComptable> lignes(Long boutiqueId) {
        Map<LocalDate, ExerciceComptable> m = new HashMap<>();
        for (ExerciceComptable e : exercices.findByBoutiqueIdOrderByDateDebut(boutiqueId)) m.put(e.getDateDebut(), e);
        return m;
    }

    /** L'exercice qui contient {@code jour} (une date avant le premier exercice appartient au premier). */
    @Transactional(readOnly = true)
    public Exercice exercicePour(Long boutiqueId, LocalDate jour) {
        LocalDate premier = debutPremierExercice(boutiqueId);
        return exercice(lignes(boutiqueId), premier, rangDe(premier, jour));
    }

    /** Les exercices du premier à celui qui contient aujourd'hui (ou celui qui suit le dernier exercice clôturé, s'il est plus loin). Du plus ancien au plus récent. */
    @Transactional(readOnly = true)
    public List<Exercice> liste(Long boutiqueId) {
        LocalDate premier = debutPremierExercice(boutiqueId);
        Map<LocalDate, ExerciceComptable> lignes = lignes(boutiqueId);
        int dernier = rangDe(premier, horloge.aujourdHui());
        LocalDate derniereCloture = exercices.derniereDateCloturee(boutiqueId);
        if (derniereCloture != null) dernier = Math.max(dernier, rangDe(premier, derniereCloture.plusDays(1)));
        List<Exercice> r = new ArrayList<>();
        for (int k = 0; k <= dernier; k++) r.add(exercice(lignes, premier, k));
        return r;
    }

    /** L'exercice dont les dates sont exactement {@code du} et {@code au}, s'il existe dans le calendrier. */
    @Transactional(readOnly = true)
    public Optional<Exercice> exact(Long boutiqueId, LocalDate du, LocalDate au) {
        Exercice e = exercicePour(boutiqueId, du);
        return e.debut().equals(du) && e.fin().equals(au) ? Optional.of(e) : Optional.empty();
    }

    // ---- Premier exercice ------------------------------------------------------------------------------------------------------

    /** Règle le début du premier exercice de la boutique courante : possible tant qu'aucun exercice n'est clôturé, et jamais après la première opération. */
    @Transactional
    public void fixerDebutPremierExercice(LocalDate debut) {
        if (debut == null) throw new IllegalArgumentException("Choisissez une date de début.");
        if (debut.getYear() < 2000 || debut.getYear() > 9000) throw new IllegalArgumentException("La date de début doit être comprise entre 2000 et 9000.");
        var boutique = verrous.verrouillerBoutiqueCourante();
        if (exercices.derniereDateCloturee(boutique.getId()) != null)
            throw new IllegalArgumentException("Le début du premier exercice ne se change plus : un exercice est déjà clôturé. Annulez d'abord ses clôtures.");
        LocalDate premiere = premiereOperation(boutique.getId());
        if (premiere != null && debut.isAfter(premiere))
            throw new IllegalArgumentException("Le premier exercice doit commencer au plus tard à la date de la première opération (" + jour(premiere) + ").");
        ReglageComptableBoutique r = reglages.findByBoutiqueId(boutique.getId()).orElseGet(() -> reglages.save(new ReglageComptableBoutique(boutique, SystemeComptable.NORMAL)));
        r.fixerDebutPremierExercice(debut);
    }

    // ---- Report à nouveau ----------------------------------------------------------------------------------------------------

    /**
     * Le report à nouveau d'où part un calcul qui commence le {@code jourDebut} : celui du dernier exercice clôturé qui se termine avant ce jour.
     * Vide s'il n'y en a pas (on calcule alors depuis l'origine).
     */
    @Transactional(readOnly = true)
    public Optional<Reprise> reprisePourDebut(Long boutiqueId, LocalDate jourDebut) {
        ExerciceComptable base = null;
        for (ExerciceComptable e : exercices.findByBoutiqueIdAndStatutOrderByDateDebut(boutiqueId, StatutExercice.CLOTURE)) if (e.getDateFin().isBefore(jourDebut)) base = e;
        return base == null ? Optional.empty() : Optional.of(reprise(boutiqueId, base));
    }

    private Reprise reprise(Long boutiqueId, ExerciceComptable e) {
        List<Reprise.Solde> liste = new ArrayList<>();
        for (SoldeOuverture s : soldes.findByExerciceIdOrderByCompteAscTiersTypeAscTiersIdAsc(e.getId()))
            liste.add(new Reprise.Solde(s.getCompte(), s.getTiersType().isEmpty() ? null : TypeTiers.valueOf(s.getTiersType()), s.getTiersId() == 0 ? null : s.getTiersId(), s.getTiersNom(), s.getSolde()));
        List<Reprise.Affectation> aff = new ArrayList<>();
        for (AffectationResultat a : affectations.findByBoutiqueId(boutiqueId))
            if (a.getExercice().getId().equals(e.getId())) aff.add(new Reprise.Affectation(a.getId(), a.getDateAffectation().atStartOfDay(), a.getCompteDestination(), a.getMontant(), a.getLibelle()));
        String libelle = "Report à nouveau de l'exercice " + libelle(e.getDateDebut(), e.getDateFin());
        return new Reprise(e.getId(), e.getDateFin(), libelle, liste, e.getValeurStock(), donnees.stockDepart(boutiqueId, e.getDateFin().atTime(java.time.LocalTime.MAX)),
                e.getPlafondClientId() == null ? 0 : e.getPlafondClientId(), e.getPlafondFournisseurId() == null ? 0 : e.getPlafondFournisseurId(), aff);
    }

    /** Valeur du stock figée à la fin de chaque exercice clôturé : à cette date, le stock des écritures est celui de la clôture, jamais un nouveau calcul. */
    @Transactional(readOnly = true)
    public Map<LocalDate, BigDecimal> stocksFiges(Long boutiqueId) {
        Map<LocalDate, BigDecimal> m = new HashMap<>();
        for (ExerciceComptable e : exercices.findByBoutiqueIdAndStatutOrderByDateDebut(boutiqueId, StatutExercice.CLOTURE)) if (e.getValeurStock() != null) m.put(e.getDateFin(), e.getValeurStock());
        return m;
    }
}
