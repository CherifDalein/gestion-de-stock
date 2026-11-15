package org.example.stock.compta;

import org.example.stock.compta.PostesOhada.Colonne;
import org.example.stock.compta.PostesOhada.Cote;
import org.example.stock.compta.PostesOhada.Regle;
import org.example.stock.compta.PostesOhada.RegleCr;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calcul PUR (sans base de données) du compte de résultat, du bilan, du tableau des flux de trésorerie et des états du système minimal de
 * trésorerie, à partir des écritures de la comptabilité de l'étape 5a.
 *
 * <p>Une période {@code [du, au]} se calcule avec deux jeux d'écritures : celles arrêtées à {@code au} et celles arrêtées la veille de {@code du}
 * (chacun avec sa propre régularisation de stock). Le résultat de la période est la différence des soldes des comptes de gestion entre les deux,
 * donc la variation de stock d'une période est bien (stock final - stock initial). Les <b>reports d'ouverture</b> (écritures qui touchent le compte de
 * report à nouveau : soldes de départ des tiers, fonds de départ, stock de départ) sont rangés dans le bilan d'ouverture et jamais dans un flux.</p>
 */
public final class EtatsFinanciersCalcul {
    private EtatsFinanciersCalcul() {}

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /** Compte de bilan que le tableau de correspondance ne cite pas, ou compte de gestion sans poste, avec son solde et le poste de repli. */
    public record NonRattache(String compte, BigDecimal solde, String poste) {}

    /** Bilan : montants par poste. À l'actif, {@code brut} et {@code amort} (net = brut - amort) ; au passif, {@code passif}. Les totaux sont inclus. */
    public record Bilan(Map<String, BigDecimal> brut, Map<String, BigDecimal> amort, Map<String, BigDecimal> passif, List<NonRattache> nonRattaches) {
        public BigDecimal brut(String ref) { return brut.getOrDefault(ref, ZERO); }
        public BigDecimal amort(String ref) { return amort.getOrDefault(ref, ZERO); }
        public BigDecimal netActif(String ref) { return brut(ref).subtract(amort(ref)); }
        public BigDecimal passif(String ref) { return passif.getOrDefault(ref, ZERO); }
        public BigDecimal totalActif() { return netActif("BZ"); }
        public BigDecimal totalPassif() { return passif("DZ"); }
        public BigDecimal ecart() { return totalActif().subtract(totalPassif()); }
        /** Trésorerie nette du bilan : trésorerie-actif moins trésorerie-passif. */
        public BigDecimal tresorerieNette() { return netActif("BT").subtract(passif("DT")); }
    }

    public record CompteResultat(Map<String, BigDecimal> montants, BigDecimal resultatDesComptes, List<NonRattache> nonRattaches) {
        public BigDecimal montant(String ref) { return montants.getOrDefault(ref, ZERO); }
        /** Résultat net du modèle (XI) moins la somme directe des comptes de gestion : doit être nul. */
        public BigDecimal ecart() { return montant("XI").subtract(resultatDesComptes); }
    }

    /** États du système minimal de trésorerie : lignes du bilan SMT et du compte de résultat SMT, et écart du bilan SMT. */
    public record Smt(Map<String, BigDecimal> bilan, Map<String, BigDecimal> resultat) {
        public BigDecimal ecart() { return bilan.get("TOTAL_ACTIF").subtract(bilan.get("TOTAL_PASSIF")); }
    }

    /** Tout ce qui se calcule pour une période. */
    public record Exercice(LocalDate du, LocalDate au, CompteResultat compteResultat, Bilan bilan, Map<String, BigDecimal> flux, BigDecimal tresorerieBalance, Smt smt, boolean aDesDonnees) {
        /** Écart entre la trésorerie finale du tableau des flux (ZH) et la balance (classe 5 hors 59). */
        public BigDecimal ecartFlux() { return flux.get("ZH").subtract(tresorerieBalance); }
    }

    // ---- Période ----------------------------------------------------------------------------------------------------------------------

    /**
     * Calcule une période.
     *
     * @param fin           écritures arrêtées à {@code au}
     * @param avant         écritures arrêtées la veille de {@code du} (vides s'il n'y avait rien avant)
     * @param compteReport  compte de report à nouveau de la correspondance (12 par défaut)
     */
    public static Exercice calculer(List<Ecriture> fin, List<Ecriture> avant, LocalDate du, LocalDate au, String compteReport) {
        SoldesComptables sFin = SoldesComptables.de(fin);
        SoldesComptables sAvant = SoldesComptables.de(avant);
        LocalDateTime debut = du.atStartOfDay();
        SoldesComptables reports = new SoldesComptables();
        List<Ecriture> periode = new ArrayList<>();
        for (Ecriture e : fin) {
            if (e.date().isBefore(debut)) continue;
            if (e.lignes().stream().anyMatch(l -> l.compte().equals(compteReport))) reports.ajouter(e); else periode.add(e);
        }
        SoldesComptables sBase = sAvant.plus(reports);

        SoldesComptables gestionPeriode = sFin.moins(sAvant).filtrer(SoldesComptables::estGestion);
        BigDecimal resultatPeriode = gestionPeriode.parCompte().values().stream().reduce(ZERO, BigDecimal::add).negate();
        BigDecimal resultatAnterieur = sAvant.filtrer(SoldesComptables::estGestion).parCompte().values().stream().reduce(ZERO, BigDecimal::add).negate();

        CompteResultat cr = compteResultat(gestionPeriode);
        SoldesComptables bilanFin = sFin.filtrer(SoldesComptables::estBilan), bilanBase = sBase.filtrer(SoldesComptables::estBilan);
        Bilan bilan = bilan(bilanFin, resultatAnterieur, resultatPeriode);
        Map<String, BigDecimal> flux = flux(bilanBase, bilanFin, gestionPeriode, cr);
        BigDecimal tresorerie = bilanFin.filtrer(c -> c.startsWith("5") && !c.startsWith("59")).parCompte().values().stream().reduce(ZERO, BigDecimal::add);
        Smt smt = smt(bilanBase, bilanFin, periode, resultatAnterieur, gestionPeriode.solde("68"));
        return new Exercice(du, au, cr, bilan, flux, tresorerie, smt, !fin.isEmpty());
    }

    // ---- Compte de résultat -----------------------------------------------------------------------------------------------------------

    /** Montants par poste du compte de résultat : produits en positif, charges en négatif (la variation de stock porte son signe). */
    public static CompteResultat compteResultat(SoldesComptables gestionPeriode) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (PostesOhada.Ligne l : PostesOhada.COMPTE_RESULTAT) m.put(l.ref(), ZERO);
        List<NonRattache> non = new ArrayList<>();
        BigDecimal total = ZERO;
        for (var e : gestionPeriode.parCompte().entrySet()) {
            if (!SoldesComptables.estGestion(e.getKey())) continue;
            BigDecimal montant = e.getValue().negate();
            total = total.add(montant);
            RegleCr regle = null;
            for (RegleCr r : PostesOhada.REGLES_COMPTE_RESULTAT) if (r.correspond(e.getKey())) { regle = r; break; }
            if (regle == null) {
                // Aucun poste ne le cite : il reste dans le résultat réel (somme directe) mais pas dans le modèle ; l'écart est signalé.
                non.add(new NonRattache(e.getKey(), e.getValue(), ""));
                continue;
            }
            m.merge(regle.poste(), montant, BigDecimal::add);
            if (!regle.officiel()) non.add(new NonRattache(e.getKey(), e.getValue(), regle.poste()));
        }
        m.put("XA", somme(m, "TA", "RA", "RB"));
        m.put("XB", somme(m, "TA", "TB", "TC", "TD"));
        m.put("XC", somme(m, "XB", "RA", "RB", "TE", "TF", "TG", "TH", "TI", "RC", "RD", "RE", "RF", "RG", "RH", "RI", "RJ"));
        m.put("XD", somme(m, "XC", "RK"));
        m.put("XE", somme(m, "XD", "TJ", "RL"));
        m.put("XF", somme(m, "TK", "TL", "TM", "RM", "RN"));
        m.put("XG", somme(m, "XE", "XF"));
        m.put("XH", somme(m, "TN", "TO", "RO", "RP"));
        m.put("XI", somme(m, "XG", "XH", "RQ", "RS"));
        return new CompteResultat(m, total, non);
    }

    private static BigDecimal somme(Map<String, BigDecimal> m, String... refs) {
        BigDecimal t = ZERO;
        for (String r : refs) t = t.add(m.getOrDefault(r, ZERO));
        return t;
    }

    // ---- Bilan ------------------------------------------------------------------------------------------------------------------------

    private static final List<String> ORDRE_TOTAUX_ACTIF = List.of("AD", "AI", "AQ", "AZ", "BG", "BK", "BT", "BZ");
    private static final List<String> ORDRE_TOTAUX_PASSIF = List.of("CP", "DD", "DF", "DP", "DT", "DZ");

    /** Classe les comptes de bilan (soldes à une date) dans les postes, avec les règles de non-compensation, sans le résultat ni les totaux. */
    static Bilan classer(SoldesComptables soldes) {
        Map<String, BigDecimal> brut = new LinkedHashMap<>(), amort = new LinkedHashMap<>(), passif = new LinkedHashMap<>();
        List<NonRattache> non = new ArrayList<>();
        for (var e : soldes.atomes().entrySet()) {
            String compte = e.getKey().compte();
            BigDecimal solde = e.getValue();
            if (!SoldesComptables.estBilan(compte)) continue;
            Regle regle = null;
            for (Regle r : PostesOhada.REGLES_BILAN) if (r.correspond(compte) && r.accepte(solde)) { regle = r; break; }
            if (regle == null) {
                // Compte absent du tableau officiel : repli en « Autres créances » (débit) ou « Autres dettes » (crédit), signalé.
                if (solde.signum() > 0) { brut.merge(PostesOhada.REPLI_ACTIF, solde, BigDecimal::add); non.add(new NonRattache(compte, solde, PostesOhada.REPLI_ACTIF)); }
                else { passif.merge(PostesOhada.REPLI_PASSIF, solde.negate(), BigDecimal::add); non.add(new NonRattache(compte, solde, PostesOhada.REPLI_PASSIF)); }
                continue;
            }
            if (regle.cote() == Cote.ACTIF) {
                if (regle.colonne() == Colonne.BRUT) brut.merge(regle.poste(), solde, BigDecimal::add);
                else amort.merge(regle.poste(), solde.negate(), BigDecimal::add);
            } else {
                passif.merge(regle.poste(), solde.negate(), BigDecimal::add);
            }
            if (!regle.officiel()) non.add(new NonRattache(compte, solde, regle.poste()));
        }
        return new Bilan(brut, amort, passif, non);
    }

    /** Bilan avec le résultat de l'exercice (CJ) et les résultats antérieurs non clôturés (ajoutés au report à nouveau, CH), puis les totaux. */
    static Bilan bilan(SoldesComptables soldesBilan, BigDecimal resultatAnterieur, BigDecimal resultatPeriode) {
        Bilan b = classer(soldesBilan);
        // Résultat d'exercices clôturés en attente d'affectation (compte 13, issu du report à nouveau) : il reste dans les capitaux propres avec le report à nouveau.
        // CJ ne porte que le résultat de l'exercice en cours.
        BigDecimal enInstance = b.passif.getOrDefault("CJ", ZERO);
        b.passif.put("CJ", ZERO);
        b.passif.merge("CH", enInstance, BigDecimal::add);
        b.passif.merge("CH", resultatAnterieur, BigDecimal::add);
        b.passif.merge("CJ", resultatPeriode, BigDecimal::add);
        for (String total : ORDRE_TOTAUX_ACTIF) {
            BigDecimal br = ZERO, am = ZERO;
            for (String f : PostesOhada.TOTAUX_ACTIF.get(total)) { br = br.add(b.brut(f)); am = am.add(b.amort(f)); }
            b.brut.put(total, br); b.amort.put(total, am);
        }
        for (String total : ORDRE_TOTAUX_PASSIF) {
            BigDecimal t = ZERO;
            for (String f : PostesOhada.TOTAUX_PASSIF.get(total)) t = t.add(b.passif(f));
            b.passif.put(total, t);
        }
        return b;
    }

    // ---- Tableau des flux de trésorerie (méthode indirecte) -------------------------------------------------------------------------

    private static BigDecimal delta(BigDecimal fin, BigDecimal base) { return fin.subtract(base); }

    private static BigDecimal positif(BigDecimal x) { return x.signum() > 0 ? x : ZERO; }

    private static BigDecimal negatif(BigDecimal x) { return x.signum() < 0 ? x : ZERO; }

    /** Soldes de bilan sans les comptes que le texte exclut du besoin de financement lié à l'exploitation (apporteurs, immobilisations, écarts de conversion, provisions). */
    private static SoldesComptables pourBesoin(SoldesComptables s) {
        return s.filtrer(c -> (c.startsWith("3") || c.startsWith("4")) && !c.startsWith("46") && !c.startsWith("481") && !c.startsWith("482") && !c.startsWith("485")
                && !c.startsWith("478") && !c.startsWith("479") && !c.startsWith("499") && !c.startsWith("599"));
    }

    static Map<String, BigDecimal> flux(SoldesComptables base, SoldesComptables fin, SoldesComptables gestion, CompteResultat cr) {
        Map<String, BigDecimal> f = new LinkedHashMap<>();
        Bilan cBase = classer(base), cFin = classer(fin);
        // Trésorerie nette d'ouverture : trésorerie-actif moins trésorerie-passif du bilan d'ouverture.
        BigDecimal za = tresorerieNette(cBase);

        // CAFG, à partir de l'EBE (p. 989) : + valeur comptable des cessions courantes (654) - produits des cessions courantes (754), puis les éléments financiers et HAO encaissables.
        BigDecimal v654 = gestion.solde("654"), p754 = gestion.solde("754").negate();
        BigDecimal capaciteExploitation = cr.montant("XD").add(v654).subtract(p754);
        BigDecimal produitsHao = gestion.solde("84", "88").negate();
        BigDecimal chargesHao = gestion.solde("83").subtract(gestion.solde("839")).negate();
        BigDecimal cafg = capaciteExploitation.add(cr.montant("TK")).add(cr.montant("TM")).add(produitsHao)
                .add(cr.montant("RM")).add(cr.montant("RQ")).add(cr.montant("RS")).add(chargesHao);

        // Variation du besoin de financement lié aux activités opérationnelles (p. 991).
        Bilan bBase = classer(pourBesoin(base)), bFin = classer(pourBesoin(fin));
        BigDecimal fb = delta(bFin.brut("BA"), bBase.brut("BA")).negate();
        BigDecimal fc = delta(bFin.brut("BB"), bBase.brut("BB")).negate();
        BigDecimal fd = delta(bFin.brut("BH").add(bFin.brut("BI")).add(bFin.brut("BJ")), bBase.brut("BH").add(bBase.brut("BI")).add(bBase.brut("BJ"))).negate();
        BigDecimal fe = delta(passifCirculant(bFin), passifCirculant(bBase));
        BigDecimal vbf = fb.add(fc).add(fd).add(fe);
        BigDecimal zb = cafg.add(vbf);

        // Investissement : acquisitions (hausse du brut, diminuée des dettes sur acquisitions) et cessions (prix de cession moins créances sur cessions).
        BigDecimal incorporelles = delta(brutImmo(cFin, "AD"), brutImmo(cBase, "AD"));
        BigDecimal corporelles = delta(brutImmo(cFin, "AI").add(cFin.brut("AP")), brutImmo(cBase, "AI").add(cBase.brut("AP")));
        BigDecimal financieres = delta(brutImmo(cFin, "AQ"), brutImmo(cBase, "AQ"));
        BigDecimal dettesAcquisitions = delta(fin.solde("481", "482").negate(), base.solde("481", "482").negate());
        BigDecimal creancesCessions = delta(fin.solde("485"), base.solde("485"));
        BigDecimal ff = positif(incorporelles).negate();
        BigDecimal fg = positif(corporelles).subtract(dettesAcquisitions).negate();
        BigDecimal fh = positif(financieres).negate();
        BigDecimal fi = gestion.solde("82").negate().subtract(creancesCessions);
        BigDecimal fj = negatif(financieres).negate();
        BigDecimal zc = ff.add(fg).add(fh).add(fi).add(fj);

        // Financement par les capitaux propres.
        BigDecimal capital = delta(fin.solde("101", "102", "103", "104", "105", "109").negate(), base.solde("101", "102", "103", "104", "105", "109").negate());
        BigDecimal fk = positif(capital);
        BigDecimal fm = negatif(capital);
        BigDecimal fl = positif(delta(fin.solde("14").negate(), base.solde("14").negate()));
        BigDecimal fn = ZERO;
        BigDecimal zd = fk.add(fl).add(fm).add(fn);
        // Financement par les capitaux étrangers : emprunts, autres dettes financières (apporteurs et comptes courants d'associés, location-acquisition).
        BigDecimal emprunts = delta(fin.solde("16", "181", "182", "183", "184").negate(), base.solde("16", "181", "182", "183", "184").negate());
        // Les dividendes à payer (465), issus de l'affectation du résultat, ne sont pas un flux : seul leur paiement en sera un.
        BigDecimal autres = delta(autresDettesFinancieres(fin), autresDettesFinancieres(base));
        BigDecimal fo = positif(emprunts);
        BigDecimal fp = positif(autres);
        BigDecimal fq = negatif(emprunts).add(negatif(autres));
        BigDecimal ze = fo.add(fp).add(fq);
        BigDecimal zf = zd.add(ze);
        BigDecimal zg = zb.add(zc).add(zf);

        f.put("ZA", za); f.put("FA", cafg); f.put("FB", fb); f.put("FC", fc); f.put("FD", fd); f.put("FE", fe); f.put("VBF", vbf); f.put("ZB", zb);
        f.put("FF", ff); f.put("FG", fg); f.put("FH", fh); f.put("FI", fi); f.put("FJ", fj); f.put("ZC", zc);
        f.put("FK", fk); f.put("FL", fl); f.put("FM", fm); f.put("FN", fn); f.put("ZD", zd);
        f.put("FO", fo); f.put("FP", fp); f.put("FQ", fq); f.put("ZE", ze); f.put("ZF", zf);
        f.put("ZG", zg); f.put("ZH", za.add(zg));
        return f;
    }

    private static BigDecimal autresDettesFinancieres(SoldesComptables s) { return s.solde("46").subtract(s.solde("465")).add(s.solde("17")).negate(); }

    private static BigDecimal passifCirculant(Bilan b) {
        return b.passif("DH").add(b.passif("DI")).add(b.passif("DJ")).add(b.passif("DK")).add(b.passif("DM"));
    }

    /** Somme des bruts des postes d'immobilisations d'un groupe (AD, AI ou AQ), sans passer par les totaux (non calculés ici). */
    private static BigDecimal brutImmo(Bilan b, String groupe) {
        BigDecimal t = ZERO;
        for (String ref : PostesOhada.TOTAUX_ACTIF.get(groupe)) t = t.add(b.brut(ref));
        return t;
    }

    /** Trésorerie-actif moins trésorerie-passif, d'après un classement (totaux de trésorerie recalculés). */
    static BigDecimal tresorerieNette(Bilan b) {
        BigDecimal actif = ZERO;
        for (String r : PostesOhada.TOTAUX_ACTIF.get("BT")) actif = actif.add(b.netActif(r));
        return actif.subtract(b.passif("DQ")).subtract(b.passif("DR"));
    }

    // ---- Système minimal de trésorerie ------------------------------------------------------------------------------------------------

    private enum Categorie { VENTES, AUTRES_RECETTES, ACHATS, LOYERS, SALAIRES, IMPOTS, INTERETS, AUTRES_DEPENSES, HORS }

    /** Rubrique du compte de résultat SMT où va le flux de trésorerie qui a pour contrepartie ce compte. */
    private static Categorie categorie(String c) {
        if (c.startsWith("41") || c.startsWith("70")) return Categorie.VENTES;
        if (c.startsWith("7") || c.startsWith("82") || c.startsWith("84") || c.startsWith("86") || c.startsWith("88")) return Categorie.AUTRES_RECETTES;
        if (c.startsWith("40") || c.startsWith("60")) return Categorie.ACHATS;
        if (c.startsWith("622") || c.startsWith("623")) return Categorie.LOYERS;
        if (c.startsWith("66") || c.startsWith("42") || c.startsWith("43")) return Categorie.SALAIRES;
        if (c.startsWith("64") || c.startsWith("44")) return Categorie.IMPOTS;
        if (c.startsWith("67")) return Categorie.INTERETS;
        if (c.startsWith("6") || c.startsWith("8")) return Categorie.AUTRES_DEPENSES;
        return Categorie.HORS;
    }

    private static boolean tresorerie(String compte) { return compte.startsWith("5") && !compte.startsWith("59"); }

    /**
     * Bilan et compte de résultat du SMT (p. 1056). Les recettes et dépenses sont les flux de trésorerie de la période, rangés d'après le compte de
     * contrepartie ; le résultat ajoute les variations de stocks, de créances et de dettes d'exploitation (effet sur le résultat).
     */
    static Smt smt(SoldesComptables base, SoldesComptables fin, List<Ecriture> periode, BigDecimal resultatAnterieur, BigDecimal dotations) {
        Map<Categorie, BigDecimal> flux = new java.util.EnumMap<>(Categorie.class);
        for (Categorie c : Categorie.values()) flux.put(c, ZERO);
        for (Ecriture e : periode) {
            if (e.lignes().stream().noneMatch(l -> tresorerie(l.compte()))) continue;
            for (Ecriture.Ligne l : e.lignes()) if (!l.compte().startsWith("5")) flux.merge(categorie(l.compte()), l.solde().negate(), BigDecimal::add);
        }
        Map<String, BigDecimal> r = new LinkedHashMap<>();
        BigDecimal ventes = flux.get(Categorie.VENTES), autresRecettes = flux.get(Categorie.AUTRES_RECETTES);
        BigDecimal achats = flux.get(Categorie.ACHATS).negate(), loyers = flux.get(Categorie.LOYERS).negate(), salaires = flux.get(Categorie.SALAIRES).negate();
        BigDecimal impots = flux.get(Categorie.IMPOTS).negate(), interets = flux.get(Categorie.INTERETS).negate(), autresDepenses = flux.get(Categorie.AUTRES_DEPENSES).negate();
        BigDecimal a = ventes.add(autresRecettes), b = achats.add(loyers).add(salaires).add(impots).add(interets).add(autresDepenses), c = a.subtract(b);
        // Variations (N moins N-1, ou moins l'ouverture) : stocks, créances et dettes des comptes dont les encaissements et paiements sont comptés ci-dessus.
        Bilan bBase = classer(base.filtrer(x -> x.startsWith("3") && !x.startsWith("39"))), bFin = classer(fin.filtrer(x -> x.startsWith("3") && !x.startsWith("39")));
        BigDecimal stocks = delta(bFin.brut("BB"), bBase.brut("BB"));
        SoldesComptables baseT = base.filtrer(x -> x.startsWith("40") || x.startsWith("41") || x.startsWith("42") || x.startsWith("43") || x.startsWith("44"));
        SoldesComptables finT = fin.filtrer(x -> x.startsWith("40") || x.startsWith("41") || x.startsWith("42") || x.startsWith("43") || x.startsWith("44"));
        Bilan tBase = classer(baseT), tFin = classer(finT);
        BigDecimal creances = delta(tFin.brut("BH").add(tFin.brut("BI")).add(tFin.brut("BJ")), tBase.brut("BH").add(tBase.brut("BI")).add(tBase.brut("BJ")));
        BigDecimal dettes = delta(tFin.passif("DI").add(tFin.passif("DJ")).add(tFin.passif("DK")), tBase.passif("DI").add(tBase.passif("DJ")).add(tBase.passif("DK")));
        BigDecimal g = c.add(stocks).add(creances).subtract(dettes).subtract(dotations);
        r.put("VENTES", ventes); r.put("AUTRES_RECETTES", autresRecettes); r.put("A", a);
        r.put("ACHATS", achats); r.put("LOYERS", loyers); r.put("SALAIRES", salaires); r.put("IMPOTS", impots); r.put("INTERETS", interets);
        r.put("AUTRES_DEPENSES", autresDepenses); r.put("B", b); r.put("C", c);
        r.put("STOCKS", stocks); r.put("CREANCES", creances); r.put("DETTES", dettes.negate()); r.put("F", dotations); r.put("G", g);

        // Bilan SMT à la date de fin.
        Bilan nf = classer(fin.filtrer(x -> !x.startsWith("46")));
        BigDecimal immobilisations = ZERO;
        for (String ref : feuilles("AZ")) immobilisations = immobilisations.add(nf.netActif(ref));
        BigDecimal stocksBilan = nf.netActif("BB");
        BigDecimal clients = nf.netActif("BH").add(nf.netActif("BI")).add(nf.netActif("BJ")).add(nf.netActif("BA")).add(nf.netActif("BU"));
        BigDecimal caisse = fin.solde("57");
        BigDecimal banque = fin.filtrer(SoldesComptables::estBilan).filtrer(x -> x.startsWith("5")).parCompte().values().stream().reduce(ZERO, BigDecimal::add).subtract(caisse);
        BigDecimal totalActif = immobilisations.add(stocksBilan).add(clients).add(caisse).add(banque);
        BigDecimal exploitant = fin.solde("10", "11", "12", "13", "14", "15", "46").negate().add(resultatAnterieur);
        BigDecimal emprunt = nf.passif("DA").add(nf.passif("DB")).add(nf.passif("DC"));
        BigDecimal fournisseurs = nf.passif("DH").add(nf.passif("DI")).add(nf.passif("DJ")).add(nf.passif("DK")).add(nf.passif("DM")).add(nf.passif("DN")).add(nf.passif("DV"));
        Map<String, BigDecimal> bi = new LinkedHashMap<>();
        bi.put("IMMOBILISATIONS", immobilisations); bi.put("STOCKS", stocksBilan); bi.put("CLIENTS", clients); bi.put("CAISSE", caisse); bi.put("BANQUE", banque);
        bi.put("TOTAL_ACTIF", totalActif);
        bi.put("EXPLOITANT", exploitant); bi.put("RESULTAT", g); bi.put("EMPRUNT", emprunt); bi.put("FOURNISSEURS", fournisseurs);
        bi.put("TOTAL_PASSIF", exploitant.add(g).add(emprunt).add(fournisseurs));
        return new Smt(bi, r);
    }

    /** Postes de base (sans totaux) qui composent un total de l'actif. */
    private static List<String> feuilles(String ref) {
        List<String> r = new ArrayList<>();
        List<String> enfants = PostesOhada.TOTAUX_ACTIF.get(ref);
        if (enfants == null) { r.add(ref); return r; }
        for (String e : enfants) r.addAll(feuilles(e));
        return r;
    }
}
