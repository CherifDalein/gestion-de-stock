package org.example.stock.compta;

import jakarta.persistence.EntityManager;
import org.example.stock.compta.Ecriture.Ligne;
import org.example.stock.compta.Ecriture.TypeTiers;
import org.example.stock.service.EcheanceService;
import org.example.stock.service.SoldePartenaire;
import org.example.stock.service.SoldePartenaireService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Journal, grand livre, balance et contrôles de cohérence d'une boutique, calculés à la volée à partir des opérations. */
@Service
public class ComptabiliteService {
    private final DonneesComptablesService donnees;
    private final CorrespondanceService correspondances;
    private final PlanComptesService plan;
    private final SoldePartenaireService soldes;
    private final EcheanceService horloge;
    private final EntityManager em;

    public ComptabiliteService(DonneesComptablesService donnees, CorrespondanceService correspondances, PlanComptesService plan,
                               SoldePartenaireService soldes, EcheanceService horloge, EntityManager em) {
        this.donnees = donnees;
        this.correspondances = correspondances;
        this.plan = plan;
        this.soldes = soldes;
        this.horloge = horloge;
        this.em = em;
    }

    // ---- Résultats -------------------------------------------------------------------------------------------------

    /** Écritures de l'ouverture à la fin de la période, avec la table de correspondance qui les a produites. */
    public record Livres(Long boutiqueId, LocalDate debut, LocalDate fin, List<Ecriture> ecritures, Correspondance correspondance, Map<String, String> intitules) {
        public List<Ecriture> dansLaPeriode() {
            LocalDateTime d = debut.atStartOfDay();
            return ecritures.stream().filter(e -> !e.date().isBefore(d)).toList();
        }

        public String intitule(String compte) { return intitules.getOrDefault(compte, "Compte " + compte); }
    }

    public record LigneJournal(Ecriture ecriture, Ligne ligne, boolean premiere) {}

    public record LigneCompte(LocalDateTime date, Journal journal, String piece, String libelle, String tiers, BigDecimal debit, BigDecimal credit, BigDecimal solde) {}

    public record CompteGrandLivre(String numero, String intitule, BigDecimal ouverture, List<LigneCompte> lignes, BigDecimal totalDebit, BigDecimal totalCredit, BigDecimal soldeFin) {}

    public record LigneBalance(String numero, String intitule, BigDecimal ouvertureDebit, BigDecimal ouvertureCredit, BigDecimal debit, BigDecimal credit,
                               BigDecimal finDebit, BigDecimal finCredit) {}

    public record Balance(List<LigneBalance> lignes, LigneBalance total) {}

    public enum Statut { OK, ECART, NON_COMPARE }

    public record Controle(int numero, String libelle, Statut statut, String detail, List<String> lignes) {}

    // ---- Calcul ----------------------------------------------------------------------------------------------------

    @Transactional
    public Livres calculer(Long boutiqueId, LocalDate debut, LocalDate fin) {
        LocalDate aujourdhui = horloge.aujourdHui();
        LocalDate f = fin == null ? aujourdhui : fin;
        LocalDate d = debut == null ? f.withDayOfMonth(1) : debut;
        if (d.isAfter(f)) throw new IllegalArgumentException("La date de début doit précéder la date de fin.");
        Correspondance corr = correspondances.effective(boutiqueId);
        LocalDateTime limite = f.atTime(LocalTime.MAX);
        DonneesComptables data = donnees.charger(boutiqueId, limite, horloge.maintenant(), true);
        List<Ecriture> ecritures = new ConstructeurEcritures(data, corr).construire();
        return new Livres(boutiqueId, d, f, ecritures, corr, plan.intitules(boutiqueId));
    }

    // ---- Journal ---------------------------------------------------------------------------------------------------

    /** Écritures de la période, filtrées par journal, par compte (numéro ou début de numéro) et par texte libre (pièce ou libellé). */
    public List<Ecriture> journal(Livres l, Journal journal, String compte, String texte) {
        String t = texte == null ? "" : Normalisation.mots(texte);
        List<Ecriture> r = new ArrayList<>();
        for (Ecriture e : l.dansLaPeriode()) {
            if (journal != null && e.journal() != journal) continue;
            if (compte != null && !compte.isBlank() && e.lignes().stream().noneMatch(x -> x.compte().startsWith(compte.strip()))) continue;
            if (!t.isEmpty() && !(" " + Normalisation.mots(e.piece() + " " + e.libelle())).contains(t)) continue;
            r.add(e);
        }
        return r;
    }

    // ---- Grand livre -----------------------------------------------------------------------------------------------

    /** Grand livre des comptes dont le numéro commence par {@code prefixe} (tous les comptes mouvementés si vide), du plus petit numéro au plus grand. */
    public List<CompteGrandLivre> grandLivre(Livres l, String prefixe, Ecriture.TypeTiers tiersType, Long tiersId) {
        String p = prefixe == null ? "" : prefixe.strip();
        LocalDateTime debut = l.debut().atStartOfDay();
        Map<String, BigDecimal> ouverture = new TreeMap<>();
        Map<String, List<LigneCompte>> lignes = new TreeMap<>();
        Map<String, BigDecimal[]> totaux = new HashMap<>();
        for (Ecriture e : l.ecritures()) {
            for (Ligne x : e.lignes()) {
                if (!x.compte().startsWith(p)) continue;
                if (tiersId != null && !(x.tiersType() == tiersType && tiersId.equals(x.tiersId()))) continue;
                if (e.date().isBefore(debut)) {
                    ouverture.merge(x.compte(), x.solde(), BigDecimal::add);
                    lignes.computeIfAbsent(x.compte(), k -> new ArrayList<>());
                } else {
                    lignes.computeIfAbsent(x.compte(), k -> new ArrayList<>()).add(new LigneCompte(e.date(), e.journal(), e.piece(), e.libelle(),
                            x.tiersNom(), x.debit(), x.credit(), null));
                    BigDecimal[] t = totaux.computeIfAbsent(x.compte(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                    t[0] = t[0].add(x.debit()); t[1] = t[1].add(x.credit());
                }
            }
        }
        List<CompteGrandLivre> r = new ArrayList<>();
        for (var entree : lignes.entrySet()) {
            String compte = entree.getKey();
            BigDecimal ouv = ouverture.getOrDefault(compte, BigDecimal.ZERO);
            if (entree.getValue().isEmpty() && ouv.signum() == 0) continue;
            BigDecimal solde = ouv;
            List<LigneCompte> avecSolde = new ArrayList<>();
            for (LigneCompte c : entree.getValue()) {
                solde = solde.add(c.debit()).subtract(c.credit());
                avecSolde.add(new LigneCompte(c.date(), c.journal(), c.piece(), c.libelle(), c.tiers(), c.debit(), c.credit(), solde));
            }
            BigDecimal[] t = totaux.getOrDefault(compte, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            r.add(new CompteGrandLivre(compte, l.intitule(compte), ouv, avecSolde, t[0], t[1], solde));
        }
        return r;
    }

    // ---- Balance ---------------------------------------------------------------------------------------------------

    public Balance balance(Livres l) {
        LocalDateTime debut = l.debut().atStartOfDay();
        Map<String, BigDecimal[]> m = new TreeMap<>(); // ouverture solde, débit, crédit
        for (Ecriture e : l.ecritures()) {
            for (Ligne x : e.lignes()) {
                BigDecimal[] t = m.computeIfAbsent(x.compte(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                if (e.date().isBefore(debut)) t[0] = t[0].add(x.solde());
                else { t[1] = t[1].add(x.debit()); t[2] = t[2].add(x.credit()); }
            }
        }
        List<LigneBalance> lignes = new ArrayList<>();
        BigDecimal[] somme = new BigDecimal[6];
        java.util.Arrays.fill(somme, BigDecimal.ZERO);
        for (var entree : m.entrySet()) {
            BigDecimal[] t = entree.getValue();
            BigDecimal fin = t[0].add(t[1]).subtract(t[2]);
            if (t[0].signum() == 0 && t[1].signum() == 0 && t[2].signum() == 0) continue;
            LigneBalance b = new LigneBalance(entree.getKey(), l.intitule(entree.getKey()), positif(t[0]), positif(t[0].negate()), t[1], t[2], positif(fin), positif(fin.negate()));
            lignes.add(b);
            somme[0] = somme[0].add(b.ouvertureDebit()); somme[1] = somme[1].add(b.ouvertureCredit());
            somme[2] = somme[2].add(b.debit()); somme[3] = somme[3].add(b.credit());
            somme[4] = somme[4].add(b.finDebit()); somme[5] = somme[5].add(b.finCredit());
        }
        return new Balance(lignes, new LigneBalance("", "Total", somme[0], somme[1], somme[2], somme[3], somme[4], somme[5]));
    }

    // ---- Contrôles -------------------------------------------------------------------------------------------------

    public List<Controle> controles(Livres l) {
        boolean comparable = !l.fin().isBefore(horloge.aujourdHui());
        List<Controle> r = new ArrayList<>();
        r.add(controleEquilibre(l));
        r.add(controleTresorerie(l));
        String nonComparable = "L'application ne conserve pas ses soldes à une date passée : choisissez une période qui se termine aujourd'hui ou plus tard pour comparer.";
        r.add(comparable ? controleTiers(l, 3, "Clients : créances et avances", true) : new Controle(3, "Clients : créances et avances", Statut.NON_COMPARE, nonComparable, List.of()));
        r.add(comparable ? controleTiers(l, 4, "Fournisseurs : dettes et avances", false) : new Controle(4, "Fournisseurs : dettes et avances", Statut.NON_COMPARE, nonComparable, List.of()));
        r.add(comparable ? controleStock(l) : new Controle(5, "Stock", Statut.NON_COMPARE, nonComparable, List.of()));
        return r;
    }

    private Controle controleEquilibre(Livres l) {
        List<String> mauvaises = new ArrayList<>();
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        for (Ecriture e : l.ecritures()) {
            debit = debit.add(e.totalDebit()); credit = credit.add(e.totalCredit());
            if (!e.estEquilibree()) mauvaises.add(e.piece() + " : débit " + e.totalDebit().toPlainString() + ", crédit " + e.totalCredit().toPlainString());
        }
        boolean ok = mauvaises.isEmpty() && debit.compareTo(credit) == 0;
        return new Controle(1, "Équilibre des écritures", ok ? Statut.OK : Statut.ECART,
                ok ? l.ecritures().size() + " écritures équilibrées ; total débit = total crédit." : mauvaises.size() + " écriture(s) déséquilibrée(s).", mauvaises);
    }

    private Controle controleTresorerie(Livres l) {
        Map<Long, BigDecimal> appli = new HashMap<>();
        for (Object o : em.createNativeQuery("select compte_id, coalesce(sum(montant), 0) from mouvement_caisse where boutique_id = :b and date_mouvement <= :fin group by compte_id")
                .setParameter("b", l.boutiqueId()).setParameter("fin", l.fin().atTime(LocalTime.MAX)).getResultList()) {
            Object[] t = (Object[]) o;
            appli.put(((Number) t[0]).longValue(), new BigDecimal(t[1].toString()));
        }
        Map<String, BigDecimal> livre = soldesParCompte(l);
        List<String> ecarts = new ArrayList<>();
        List<Long> ids = new ArrayList<>(appli.keySet());
        int compares = ids.size();
        // Un compte comptable peut recevoir plusieurs comptes de trésorerie (correspondance modifiée) : on compare par compte comptable.
        Map<String, BigDecimal> attendu = new TreeMap<>();
        for (Long id : ids) attendu.merge(l.correspondance().compteTresorerie(id), appli.get(id), BigDecimal::add);
        for (String compte : attendu.keySet()) {
            BigDecimal a = arrondi(attendu.get(compte));
            BigDecimal b = arrondi(livre.getOrDefault(compte, BigDecimal.ZERO));
            if (a.compareTo(b) != 0) ecarts.add(compte + " " + l.intitule(compte) + " : livres " + b.toPlainString() + ", application " + a.toPlainString() + ", écart " + b.subtract(a).toPlainString());
        }
        boolean ok = ecarts.isEmpty();
        return new Controle(2, "Trésorerie : comptes de la boutique", ok ? Statut.OK : Statut.ECART,
                ok ? compares + " compte(s) de trésorerie : le solde des livres égale celui de l'application." : ecarts.size() + " compte(s) en écart.", ecarts);
    }

    private Map<String, BigDecimal> soldesParCompte(Livres l) {
        Map<String, BigDecimal> m = new HashMap<>();
        for (Ecriture e : l.ecritures()) for (Ligne x : e.lignes()) m.merge(x.compte(), x.solde(), BigDecimal::add);
        return m;
    }

    private Controle controleTiers(Livres l, int numero, String libelle, boolean client) {
        Correspondance c = l.correspondance();
        String a = client ? c.compte(CleCompta.CREANCES_CLIENTS) : c.compte(CleCompta.DETTES_FOURNISSEURS);
        String b = client ? c.compte(CleCompta.AVANCES_CLIENTS) : c.compte(CleCompta.AVANCES_FOURNISSEURS);
        TypeTiers type = client ? TypeTiers.CLIENT : TypeTiers.FOURNISSEUR;
        Map<Long, BigDecimal> livre = new LinkedHashMap<>();
        Map<Long, String> noms = new HashMap<>();
        for (Ecriture e : l.ecritures()) for (Ligne x : e.lignes()) {
            if (x.tiersType() != type || x.tiersId() == null || !(x.compte().equals(a) || x.compte().equals(b))) continue;
            livre.merge(x.tiersId(), x.solde(), BigDecimal::add);
            if (x.tiersNom() != null) noms.put(x.tiersId(), x.tiersNom());
        }
        Map<Long, SoldePartenaire> appli = client ? soldes.soldesClients() : soldes.soldesFournisseurs();
        List<String> ecarts = new ArrayList<>();
        Map<Long, String> nomsAppli = new HashMap<>();
        for (Long id : appli.keySet()) {
            BigDecimal attendu = arrondi(appli.get(id).soldeNet());
            BigDecimal reel = arrondi(livre.getOrDefault(id, BigDecimal.ZERO));
            if (attendu.compareTo(reel) != 0) ecarts.add(nom(noms, id) + " : livres " + reel.toPlainString() + ", application " + attendu.toPlainString() + ", écart " + reel.subtract(attendu).toPlainString());
        }
        for (var e : livre.entrySet()) {
            if (!appli.containsKey(e.getKey()) && arrondi(e.getValue()).signum() != 0) ecarts.add(nom(noms, e.getKey()) + " (hors liste) : livres " + arrondi(e.getValue()).toPlainString() + ", application 0");
        }
        boolean ok = ecarts.isEmpty();
        return new Controle(numero, libelle, ok ? Statut.OK : Statut.ECART,
                ok ? appli.size() + (client ? " client(s)" : " fournisseur(s)") + " : le solde des livres (" + a + " et " + b + ") égale le solde net de l'application."
                        : ecarts.size() + (client ? " client(s)" : " fournisseur(s)") + " en écart.", ecarts);
    }

    private static String nom(Map<Long, String> noms, Long id) { return noms.getOrDefault(id, "Tiers " + id); }

    private Controle controleStock(Livres l) {
        String compte = l.correspondance().compte(CleCompta.STOCK);
        BigDecimal livre = arrondi(soldesParCompte(l).getOrDefault(compte, BigDecimal.ZERO));
        BigDecimal appli = donnees.valeurStockAppli(l.boutiqueId());
        boolean ok = livre.compareTo(appli) == 0;
        return new Controle(5, "Stock", ok ? Statut.OK : Statut.ECART,
                ok ? "Le compte " + compte + " (" + livre.toPlainString() + ") égale la valeur du stock de l'application."
                        : "Compte " + compte + " : livres " + livre.toPlainString() + ", application " + appli.toPlainString() + ", écart " + livre.subtract(appli).toPlainString() + ".",
                List.of());
    }

    private static BigDecimal arrondi(BigDecimal x) { return x.setScale(2, RoundingMode.HALF_UP); }

    private static BigDecimal positif(BigDecimal x) { return x.signum() > 0 ? x : BigDecimal.ZERO; }
}
