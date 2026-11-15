package org.example.stock.compta;

import org.example.stock.compta.EtatsFinanciersCalcul.Exercice;
import org.example.stock.compta.EtatsFinanciersService.Etats;
import org.example.stock.compta.PostesOhada.Genre;
import org.example.stock.export.Colonne;
import org.example.stock.export.FormatPapier;
import org.example.stock.export.TableauExport;
import org.example.stock.export.Textes;
import org.example.stock.service.Etat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Met les états financiers sous la forme d'un {@link Etat} : la page, le PDF et l'Excel montrent les mêmes lignes. Les références, les
 * libellés et l'ordre des lignes sont ceux des modèles officiels de {@link PostesOhada} ; les montants sont en GNF, arrondis au franc.
 * Les écarts de contrôle (actif différent du passif, trésorerie différente de la balance) sont écrits en tête des notes et repris à l'écran.
 */
public final class EtatsFinanciersEtats {
    private static final String MENTION = ComptabiliteEtats.MENTION;

    private EtatsFinanciersEtats() {}

    public static String periode(Etats e) {
        return "Exercice N : du " + Textes.date(e.du()) + " au " + Textes.date(e.au()) + (e.n1() == null ? " · exercice N-1 : aucune donnée"
                : " · exercice N-1 : du " + Textes.date(e.du1()) + " au " + Textes.date(e.au1()));
    }

    private static String filtres(Etats e) { return periode(e) + " · montants en GNF · " + MENTION; }

    private static BigDecimal r(BigDecimal x) { return x == null ? null : x.setScale(0, RoundingMode.HALF_UP); }

    private static BigDecimal n1(Etats e, Function<Exercice, BigDecimal> f) { return e.n1() == null ? null : r(f.apply(e.n1())); }

    private static List<String> notes(List<String> alertes, String... plus) {
        List<String> n = new ArrayList<>(alertes);
        n.add(MENTION);
        n.addAll(List.of(plus));
        return n;
    }

    /** Écarts à montrer en rouge sur la page et en tête du PDF. Vide quand les contrôles sont conformes. */
    public static List<String> alertes(Etats e) {
        List<String> a = new ArrayList<>();
        Exercice n = e.n();
        BigDecimal ecart = r(n.bilan().ecart());
        if (ecart.signum() != 0) a.add("ÉCART : le total de l'actif (" + Textes.montant(r(n.bilan().totalActif())) + ") diffère du total du passif (" + Textes.montant(r(n.bilan().totalPassif())) + ") de " + Textes.montant(ecart) + " GNF.");
        BigDecimal ecartResultat = r(n.bilan().passif("CJ").subtract(n.compteResultat().montant("XI")));
        if (ecartResultat.signum() != 0) a.add("ÉCART : le résultat du bilan diffère du résultat du compte de résultat de " + Textes.montant(ecartResultat) + " GNF.");
        BigDecimal ecartCr = r(n.compteResultat().ecart());
        if (ecartCr.signum() != 0) a.add("ÉCART : le compte de résultat ne reprend pas tous les comptes de gestion (" + Textes.montant(ecartCr) + " GNF).");
        BigDecimal ecartFlux = r(n.ecartFlux());
        if (ecartFlux.signum() != 0) a.add("ÉCART : la trésorerie du tableau des flux diffère de celle de la balance de " + Textes.montant(ecartFlux) + " GNF.");
        return a;
    }

    // ---- Compte de résultat ---------------------------------------------------------------------------------------------------------

    public static Etat compteResultat(Etats e) {
        TableauExport t = TableauExport.titre("Compte de résultat").nomFeuille("Compte de résultat").filtres(filtres(e))
                .colonnes(Colonne.texteCourt("Réf"), Colonne.texte("Libellés"), Colonne.texteCourt("Signe"), Colonne.texteCourt("Note"),
                        Colonne.montant("Exercice N"), Colonne.montant("Exercice N-1"));
        for (PostesOhada.Ligne l : PostesOhada.COMPTE_RESULTAT) {
            BigDecimal n = r(e.n().compteResultat().montant(l.ref()));
            BigDecimal p = n1(e, x -> x.compteResultat().montant(l.ref()));
            if (l.genre() == Genre.TOTAL) t.sousTotal(l.ref(), l.libelle(), l.signe(), l.note(), n, p);
            else t.ligne(l.ref(), l.libelle(), l.signe(), l.note(), n, p);
        }
        t.note("Les produits sont en positif, les charges en négatif. Les charges et produits comptables viennent des comptes de gestion de la balance, après la régularisation du stock.");
        List<String> choix = new ArrayList<>();
        for (EtatsFinanciersCalcul.NonRattache x : e.n().compteResultat().nonRattaches()) choix.add(x.compte() + (x.poste().isEmpty() ? " : aucun poste du modèle" : " : poste " + x.poste()));
        if (!choix.isEmpty()) t.note("Comptes que le modèle officiel ne cite pas, rangés par choix de l'application : " + String.join(" ; ", choix) + ".");
        return new Etat("compte-resultat", "Compte de résultat", periode(e), notes(alertes(e), "Modèle du système normal, SYSCOHADA révisé (Acte uniforme, p. 986), références et libellés officiels."),
                List.of(t), FormatPapier.A4_PORTRAIT, "compte-de-resultat", e.du(), e.au());
    }

    // ---- Bilan -------------------------------------------------------------------------------------------------------------------------

    public static Etat bilan(Etats e) {
        TableauExport actif = TableauExport.titre("Bilan - Actif").nomFeuille("Actif").filtres(filtres(e))
                .colonnes(Colonne.texteCourt("Réf"), Colonne.texte("Actif"), Colonne.texteCourt("Note"), Colonne.montant("Brut"), Colonne.montant("Amort. et dépréc."),
                        Colonne.montant("Net N"), Colonne.montant("Net N-1"));
        for (PostesOhada.Ligne l : PostesOhada.BILAN_ACTIF) {
            BigDecimal brut = r(e.n().bilan().brut(l.ref())), amort = r(e.n().bilan().amort(l.ref())), net = r(e.n().bilan().netActif(l.ref()));
            BigDecimal p = n1(e, x -> x.bilan().netActif(l.ref()));
            if (l.genre() == Genre.TOTAL) actif.sousTotal(l.ref(), l.libelle(), l.note(), brut, amort, net, p);
            else actif.ligne(l.ref(), l.libelle(), l.note(), brut, amort, net, p);
        }
        TableauExport passif = TableauExport.titre("Bilan - Passif").nomFeuille("Passif").filtres(filtres(e))
                .colonnes(Colonne.texteCourt("Réf"), Colonne.texte("Passif"), Colonne.texteCourt("Note"), Colonne.montant("Exercice N"), Colonne.montant("Exercice N-1"));
        for (PostesOhada.Ligne l : PostesOhada.BILAN_PASSIF) {
            BigDecimal n = r(e.n().bilan().passif(l.ref()));
            BigDecimal p = n1(e, x -> x.bilan().passif(l.ref()));
            if (l.genre() == Genre.TOTAL) passif.sousTotal(l.ref(), l.libelle(), l.note(), n, p);
            else passif.ligne(l.ref(), l.libelle(), l.note(), n, p);
        }
        passif.note("Un compte à double sens est classé selon le signe de son solde, tiers par tiers, sans compensation (Acte uniforme, p. 385 et 389) : un client créditeur "
                + "(avance reçue) va au passif, un fournisseur débiteur (avance versée) à l'actif.");
        List<String> choix = new ArrayList<>();
        for (EtatsFinanciersCalcul.NonRattache x : e.n().bilan().nonRattaches()) choix.add(x.compte() + " : poste " + x.poste());
        if (!choix.isEmpty()) passif.note("Comptes que le modèle officiel ne range pas dans un poste précis, placés par choix de l'application : " + String.join(" ; ", choix) + ".");
        return new Etat("bilan", "Bilan", periode(e), notes(alertes(e), "Modèle du système normal, SYSCOHADA révisé (Acte uniforme, p. 981 et 982), références et libellés officiels."),
                List.of(actif, passif), FormatPapier.A4_PORTRAIT, "bilan", e.du(), e.au());
    }

    // ---- Tableau des flux de trésorerie ------------------------------------------------------------------------------------------------

    public static Etat tableauFlux(Etats e) {
        TableauExport t = TableauExport.titre("Tableau des flux de trésorerie").nomFeuille("Flux de trésorerie").filtres(filtres(e))
                .colonnes(Colonne.texteCourt("Réf"), Colonne.texte("Libellés"), Colonne.texteCourt("Signe"), Colonne.texteCourt("Note"),
                        Colonne.montant("Exercice N"), Colonne.montant("Exercice N-1"));
        for (PostesOhada.Ligne l : PostesOhada.FLUX) {
            if (l.genre() == Genre.TITRE) { t.sousTotal("", l.libelle(), "", "", null, null); continue; }
            BigDecimal n = r(e.n().flux().get(l.ref()));
            BigDecimal p = n1(e, x -> x.flux().get(l.ref()));
            if (l.genre() == Genre.TOTAL) t.sousTotal(l.ref(), l.libelle(), l.signe(), l.note(), n, p);
            else t.ligne(l.ref(), l.libelle(), l.signe(), l.note(), n, p);
        }
        t.note("Méthode indirecte : la capacité d'autofinancement part de l'excédent brut d'exploitation, puis les variations du besoin de financement et les flux d'investissement "
                + "et de financement se lisent sur les variations des soldes de la balance. La trésorerie finale doit égaler la classe 5 hors 59 de la balance : "
                + Textes.montant(r(e.n().tresorerieBalance())) + " GNF.");
        return new Etat("flux", "Tableau des flux de trésorerie", periode(e), notes(alertes(e), "Modèle du système normal, SYSCOHADA révisé (Acte uniforme, p. 997)."),
                List.of(t), FormatPapier.A4_PORTRAIT, "tableau-des-flux", e.du(), e.au());
    }

    // ---- Système minimal de trésorerie -------------------------------------------------------------------------------------------------

    public static Etat smt(Etats e) {
        var s = e.n().smt();
        TableauExport rd = TableauExport.titre("Compte de résultat SMT - recettes et dépenses").nomFeuille("Recettes et dépenses").filtres(filtres(e))
                .colonnes(Colonne.texte("Rubriques"), Colonne.texteCourt("Note"), Colonne.montant("Exercice N"), Colonne.montant("Exercice N-1"));
        String[][] lignes = {
                {"VENTES", "Recettes sur ventes ou prestations de services", "4", "L"}, {"AUTRES_RECETTES", "Autres recettes sur activités", "4", "L"},
                {"A", "TOTAL DES RECETTES SUR PRODUITS (A)", "A", "T"},
                {"ACHATS", "Dépenses sur achats", "4", "L"}, {"LOYERS", "Dépenses sur loyers", "4", "L"}, {"SALAIRES", "Dépenses sur salaires", "4", "L"},
                {"IMPOTS", "Dépenses sur impôts et taxes", "4", "L"}, {"INTERETS", "Charges d'intérêts", "", "L"}, {"AUTRES_DEPENSES", "Autres dépenses sur activités", "4", "L"},
                {"B", "TOTAL DEPENSES SUR CHARGES (B)", "B", "T"},
                {"C", "SOLDE : excédent (+) ou insuffisance (-) de recettes (C = A-B)", "C", "T"},
                {"STOCKS", "Variation des stocks N / N-1 (effet sur le résultat)", "2", "L"}, {"CREANCES", "Variation des créances N / N-1 (effet sur le résultat)", "3", "L"},
                {"DETTES", "Variation des dettes d'exploitation N / N-1 (effet sur le résultat)", "3", "L"},
                {"F", "DOTATIONS AMORTISSEMENTS (F)", "F", "L"}, {"G", "RESULTAT EXERCICE (G)", "G", "T"}};
        for (String[] l : lignes) {
            BigDecimal n = r(s.resultat().get(l[0]));
            BigDecimal p = n1(e, x -> x.smt().resultat().get(l[0]));
            if (l[3].equals("T")) rd.sousTotal(l[1], l[2], n, p); else rd.ligne(l[1], l[2], n, p);
        }
        rd.note("Les recettes et les dépenses sont les flux de trésorerie de l'exercice, rangés selon le compte de contrepartie. Les trois variations sont présentées avec leur effet sur le résultat ; "
                + "le sens exact des signes du modèle officiel (G = C - D + E - F) est une question pour l'expert-comptable.");
        TableauExport bi = TableauExport.titre("Bilan SMT").nomFeuille("Bilan SMT").filtres(filtres(e))
                .colonnes(Colonne.texte("Rubriques"), Colonne.texteCourt("Note"), Colonne.montant("Exercice N"), Colonne.montant("Exercice N-1"));
        String[][] actif = {{"IMMOBILISATIONS", "Immobilisations", "1", "L"}, {"STOCKS", "Stocks", "2", "L"}, {"CLIENTS", "Clients et débiteurs divers", "3", "L"},
                {"CAISSE", "Caisse", "", "L"}, {"BANQUE", "Banque (en + ou en -)", "", "L"}, {"TOTAL_ACTIF", "Total actif", "", "T"},
                {"EXPLOITANT", "Compte exploitant", "", "L"}, {"RESULTAT", "Résultat exercice", "", "L"}, {"EMPRUNT", "Emprunt", "", "L"},
                {"FOURNISSEURS", "Fournisseurs et créditeurs divers", "3", "L"}, {"TOTAL_PASSIF", "Total passif", "", "T"}};
        for (String[] l : actif) {
            BigDecimal n = r(s.bilan().get(l[0]));
            BigDecimal p = n1(e, x -> x.smt().bilan().get(l[0]));
            if (l[3].equals("T")) bi.sousTotal(l[1], l[2], n, p); else bi.ligne(l[1], l[2], n, p);
        }
        List<String> alertes = new ArrayList<>(alertes(e));
        BigDecimal ecart = r(s.ecart());
        if (ecart.signum() != 0) alertes.add("ÉCART : le total de l'actif SMT diffère du total du passif SMT de " + Textes.montant(ecart) + " GNF.");
        return new Etat("smt", "Système minimal de trésorerie", periode(e), notes(alertes, "Modèle du système minimal de trésorerie, SYSCOHADA révisé (Acte uniforme, p. 1056)."),
                List.of(rd, bi), FormatPapier.A4_PORTRAIT, "systeme-minimal-tresorerie", e.du(), e.au());
    }

    // ---- Table de rattachement ---------------------------------------------------------------------------------------------------------

    public static Etat rattachements(java.time.LocalDate jour) {
        TableauExport bilan = TableauExport.titre("Bilan : comptes rattachés à chaque poste").nomFeuille("Bilan")
                .filtres("Table interne, non modifiable · " + MENTION)
                .colonnes(Colonne.texteCourt("Poste"), Colonne.texte("Libellé"), Colonne.texteCourt("Colonne"), Colonne.texte("Comptes"), Colonne.texteCourt("Sens du solde"), Colonne.texte("Origine"));
        for (PostesOhada.RattachementBilan p : PostesOhada.rattachementsBilan()) {
            if (p.regles().isEmpty()) { bilan.ligne(p.ref(), p.libelle(), "", "Résultat net du compte de résultat", "", "Officiel"); continue; }
            for (PostesOhada.Regle g : p.regles())
                bilan.ligne(p.ref(), p.libelle(), g.cote() == PostesOhada.Cote.PASSIF ? "Passif" : g.colonne() == PostesOhada.Colonne.AMORT ? "Amortissements" : "Brut",
                        PostesOhada.comptes(g), PostesOhada.sensLibelle(g.sens()), g.officiel() ? "Officiel (p. 1049)" : "Choix : " + g.remarque());
        }
        TableauExport cr = TableauExport.titre("Compte de résultat : comptes rattachés à chaque poste").nomFeuille("Compte de résultat")
                .filtres("Table interne, non modifiable · " + MENTION)
                .colonnes(Colonne.texteCourt("Poste"), Colonne.texte("Libellé"), Colonne.texte("Comptes"), Colonne.texte("Origine"));
        for (PostesOhada.RegleCr g : PostesOhada.REGLES_COMPTE_RESULTAT) {
            String libelle = PostesOhada.COMPTE_RESULTAT.stream().filter(x -> x.ref().equals(g.poste())).map(PostesOhada.Ligne::libelle).findFirst().orElse(g.poste());
            cr.ligne(g.poste(), libelle, String.join(", ", g.prefixes()), g.officiel() ? "Officiel (p. 1050)" : "Choix : " + g.remarque());
        }
        return new Etat("rattachements", "Rattachement des comptes aux postes", "Table interne de l'application", notes(List.of(),
                "Cette table est fixée par l'application d'après la correspondance officielle des comptes (Acte uniforme, p. 1049 et 1050) : elle n'est pas modifiable. Les lignes « Choix » sont des décisions de l'application, soumises à l'expert-comptable."),
                List.of(bilan, cr), FormatPapier.A4_PORTRAIT, "rattachement-comptes-postes", jour, jour);
    }
}
