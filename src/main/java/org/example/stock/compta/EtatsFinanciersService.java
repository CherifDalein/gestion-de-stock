package org.example.stock.compta;

import org.example.stock.compta.ComptabiliteService.Controle;
import org.example.stock.compta.ComptabiliteService.Livres;
import org.example.stock.compta.ComptabiliteService.Statut;
import org.example.stock.compta.EtatsFinanciersCalcul.Exercice;
import org.example.stock.enums.SystemeComptable;
import org.example.stock.model.ReglageComptableBoutique;
import org.example.stock.repository.ReglageComptableBoutiqueRepository;
import org.example.stock.service.BoutiqueService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * États financiers d'une boutique pour une période (étape 5b) : lit les écritures de la comptabilité de l'étape 5a, arrêtées à la fin de la
 * période et à la veille de son début, puis les confie au calcul pur {@link EtatsFinanciersCalcul}. L'exercice précédent (N-1) est la même période
 * un an plus tôt ; il n'est calculé que s'il existe des écritures à sa date.
 */
@Service
public class EtatsFinanciersService {
    private final ComptabiliteService compta;
    private final ReglageComptableBoutiqueRepository reglages;
    private final BoutiqueService boutiques;

    public EtatsFinanciersService(ComptabiliteService compta, ReglageComptableBoutiqueRepository reglages, BoutiqueService boutiques) {
        this.compta = compta; this.reglages = reglages; this.boutiques = boutiques;
    }

    /** Système des états financiers de la boutique : le système normal tant que le gérant n'a rien choisi. */
    @Transactional(readOnly = true)
    public SystemeComptable systeme(Long boutiqueId) {
        return reglages.findByBoutiqueId(boutiqueId).map(ReglageComptableBoutique::getSysteme).orElse(SystemeComptable.NORMAL);
    }

    /** Enregistre le choix du gérant pour la boutique connectée (jamais pour une autre). */
    @Transactional
    public void choisirSysteme(SystemeComptable systeme) {
        if (systeme == null) throw new IllegalArgumentException("Choisissez un système comptable.");
        var boutique = boutiques.boutiqueCourante();
        ReglageComptableBoutique r = reglages.findByBoutiqueId(boutique.getId()).orElse(null);
        if (r == null) reglages.save(new ReglageComptableBoutique(boutique, systeme));
        else r.choisir(systeme);
    }

    /** Résultat : l'exercice N, l'exercice N-1 (ou {@code null} s'il n'y a pas de données), et la correspondance qui a produit les écritures. */
    public record Etats(Long boutiqueId, LocalDate du, LocalDate au, Exercice n, Exercice n1, Correspondance correspondance, Map<String, String> intitules) {
        public LocalDate du1() { return du.minusYears(1); }
        public LocalDate au1() { return au.minusYears(1); }
    }

    @Transactional
    public Etats calculer(Long boutiqueId, LocalDate du, LocalDate au) {
        if (du == null || au == null) throw new IllegalArgumentException("La période est incomplète.");
        if (du.isAfter(au)) throw new IllegalArgumentException("La date de début doit précéder la date de fin.");
        Map<LocalDate, Livres> cache = new HashMap<>();
        Livres fin = livres(cache, boutiqueId, au);
        String report = fin.correspondance().compte(CleCompta.REPORT_A_NOUVEAU);
        Exercice n = EtatsFinanciersCalcul.calculer(fin.ecritures(), livres(cache, boutiqueId, du.minusDays(1)).ecritures(), du, au, report);
        LocalDate du1 = du.minusYears(1), au1 = au.minusYears(1);
        Livres fin1 = livres(cache, boutiqueId, au1);
        Exercice n1 = fin1.ecritures().isEmpty() ? null
                : EtatsFinanciersCalcul.calculer(fin1.ecritures(), livres(cache, boutiqueId, du1.minusDays(1)).ecritures(), du1, au1, report);
        return new Etats(boutiqueId, du, au, n, n1, fin.correspondance(), fin.intitules());
    }

    private Livres livres(Map<LocalDate, Livres> cache, Long boutiqueId, LocalDate fin) {
        return cache.computeIfAbsent(fin, d -> compta.calculer(boutiqueId, d.withDayOfMonth(1), d));
    }

    // ---- Contrôles de l'étape 5b (ajoutés à la page Contrôles de l'étape 5a) ------------------------------------------------------------

    /** Numéros 6 à 9 : actif = passif, résultat du bilan = résultat du compte de résultat, trésorerie du tableau des flux = balance, bilan SMT équilibré. */
    @Transactional
    public List<Controle> controles(Long boutiqueId, LocalDate du, LocalDate au) {
        return controles(calculer(boutiqueId, du, au));
    }

    public static List<Controle> controles(Etats e) {
        Exercice n = e.n();
        List<Controle> r = new ArrayList<>();
        BigDecimal ecartBilan = arrondi(n.bilan().ecart());
        List<String> lignes = new ArrayList<>();
        for (EtatsFinanciersCalcul.NonRattache x : n.bilan().nonRattaches()) lignes.add("Compte " + x.compte() + " (solde " + arrondi(x.solde()).toPlainString() + ") : rangé en poste " + x.poste() + " par choix de l'application.");
        r.add(new Controle(6, "Bilan : actif = passif", ecartBilan.signum() == 0 ? Statut.OK : Statut.ECART,
                ecartBilan.signum() == 0 ? "Total actif " + arrondi(n.bilan().totalActif()).toPlainString() + " = total passif " + arrondi(n.bilan().totalPassif()).toPlainString() + "."
                        : "Total actif " + arrondi(n.bilan().totalActif()).toPlainString() + ", total passif " + arrondi(n.bilan().totalPassif()).toPlainString() + ", écart " + ecartBilan.toPlainString() + ".", lignes));
        BigDecimal cj = n.bilan().passif("CJ"), xi = n.compteResultat().montant("XI");
        BigDecimal ecartResultat = arrondi(cj.subtract(xi)), ecartComptes = arrondi(n.compteResultat().ecart());
        boolean okResultat = ecartResultat.signum() == 0 && ecartComptes.signum() == 0;
        List<String> detailResultat = new ArrayList<>();
        for (EtatsFinanciersCalcul.NonRattache x : n.compteResultat().nonRattaches()) detailResultat.add("Compte " + x.compte() + " (solde " + arrondi(x.solde()).toPlainString() + ") : " + (x.poste().isEmpty() ? "aucun poste du compte de résultat ne le reprend" : "rangé en poste " + x.poste() + " par choix de l'application") + ".");
        r.add(new Controle(7, "Résultat du bilan = résultat du compte de résultat", okResultat ? Statut.OK : Statut.ECART,
                okResultat ? "Résultat net " + arrondi(xi).toPlainString() + " : le bilan (CJ) et le compte de résultat (XI) donnent le même montant, égal à la somme des comptes de gestion."
                        : "Bilan (CJ) " + arrondi(cj).toPlainString() + ", compte de résultat (XI) " + arrondi(xi).toPlainString() + ", somme des comptes de gestion " + arrondi(n.compteResultat().resultatDesComptes()).toPlainString() + ".", detailResultat));
        BigDecimal ecartFlux = arrondi(n.ecartFlux());
        r.add(new Controle(8, "Tableau des flux : trésorerie finale = balance", ecartFlux.signum() == 0 ? Statut.OK : Statut.ECART,
                ecartFlux.signum() == 0 ? "Trésorerie nette finale " + arrondi(n.flux().get("ZH")).toPlainString() + " = classe 5 hors 59 de la balance " + arrondi(n.tresorerieBalance()).toPlainString() + "."
                        : "Tableau des flux " + arrondi(n.flux().get("ZH")).toPlainString() + ", balance " + arrondi(n.tresorerieBalance()).toPlainString() + ", écart " + ecartFlux.toPlainString() + ".", List.of()));
        BigDecimal ecartSmt = arrondi(n.smt().ecart());
        r.add(new Controle(9, "Système minimal de trésorerie : bilan équilibré", ecartSmt.signum() == 0 ? Statut.OK : Statut.ECART,
                ecartSmt.signum() == 0 ? "Total actif SMT " + arrondi(n.smt().bilan().get("TOTAL_ACTIF")).toPlainString() + " = total passif SMT " + arrondi(n.smt().bilan().get("TOTAL_PASSIF")).toPlainString() + "."
                        : "Total actif SMT " + arrondi(n.smt().bilan().get("TOTAL_ACTIF")).toPlainString() + ", total passif SMT " + arrondi(n.smt().bilan().get("TOTAL_PASSIF")).toPlainString() + ", écart " + ecartSmt.toPlainString() + ".", List.of()));
        return r;
    }

    private static BigDecimal arrondi(BigDecimal x) { return x.setScale(2, RoundingMode.HALF_UP); }
}
