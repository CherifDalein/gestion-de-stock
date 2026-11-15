package org.example.stock.compta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Modèles officiels des états financiers du SYSCOHADA révisé (Système normal) et rattachement des comptes à leurs postes.
 *
 * <p>Tout vient du texte officiel de l'Acte uniforme relatif au droit comptable et à l'information financière et du Système comptable OHADA :
 * <ul>
 *   <li>Bilan : modèle 2 (actif et passif), pages 981 et 982 ; références AD à BZ (actif) et CA à DZ (passif) ;</li>
 *   <li>Compte de résultat : modèle en liste, page 986 ; références TA à XI ;</li>
 *   <li>Tableau des flux de trésorerie : modèle, page 997 ; références ZA à ZH et FA à FQ ;</li>
 *   <li>Tableau de correspondance Postes/Comptes : pages 1049 (bilan) et 1050 (compte de résultat) ;</li>
 *   <li>Non-compensation et classement des comptes à double sens : tome des comptes, fournisseurs (p. 385), clients (p. 389), autres créances et autres dettes (p. 406), classe 5 (p. 414), et règle générale (p. 965).</li>
 * </ul>
 * Ce sont des <b>tables internes en lecture seule</b> : l'utilisateur ne les modifie pas ; le comptable peut les faire corriger ici.
 * Une ligne de {@link Regle} dit : tels préfixes de comptes (sauf tels autres), au sens débiteur ou créditeur, vont à tel poste.</p>
 */
public final class PostesOhada {
    private PostesOhada() {}

    // ---- Types ------------------------------------------------------------------------------------------------------------------------

    /** Côté du bilan. */
    public enum Cote { ACTIF, PASSIF }

    /** À l'actif, la colonne « brut » ou la colonne « amortissements et dépréciations » ; au passif, seule la colonne « brut » sert (le montant). */
    public enum Colonne { BRUT, AMORT }

    /** Sens du solde d'un compte (ou d'un compte d'un tiers) pour lequel la règle s'applique. {@code TOUS} : quel que soit le solde. */
    public enum Sens { DEBITEUR, CREDITEUR, TOUS }

    /** Genre d'une ligne du modèle : un poste (alimenté par des comptes), un total calculé, un titre de rubrique. */
    public enum Genre { POSTE, TOTAL, TITRE }

    /**
     * Un rattachement : les comptes dont le numéro commence par un des {@code prefixes} (et par aucun des {@code sauf}) vont au {@code poste} (dans la colonne
     * {@code colonne} à l'actif), lorsque leur solde a le {@code sens}. {@code officiel} : vrai si le texte officiel le dit tel quel ; faux pour un choix de
     * l'application là où le texte ne dit rien (compte à solde de sens inhabituel, compte absent du tableau).
     */
    public record Regle(Cote cote, String poste, Colonne colonne, Sens sens, List<String> prefixes, List<String> sauf, boolean officiel, String remarque) {
        public boolean correspond(String compte) {
            boolean ok = false;
            for (String p : prefixes) if (compte.startsWith(p)) { ok = true; break; }
            if (!ok) return false;
            for (String s : sauf) if (compte.startsWith(s)) return false;
            return true;
        }

        public boolean accepte(java.math.BigDecimal solde) {
            return switch (sens) {
                case TOUS -> true;
                case DEBITEUR -> solde.signum() > 0;
                case CREDITEUR -> solde.signum() < 0;
            };
        }
    }

    /** Une ligne d'un modèle (bilan actif, bilan passif, compte de résultat, tableau des flux). */
    public record Ligne(String ref, String libelle, String signe, String note, Genre genre) {}

    // ---- Bilan : actif (modèle 2, page 981) --------------------------------------------------------------------------------------------

    private static Ligne l(String ref, String libelle, String note, Genre genre) { return new Ligne(ref, libelle, "", note, genre); }

    public static final List<Ligne> BILAN_ACTIF = List.of(
            l("AD", "IMMOBILISATIONS INCORPORELLES", "3", Genre.TOTAL),
            l("AE", "Frais de développement et de prospection", "", Genre.POSTE),
            l("AF", "Brevets, licences, logiciels, et droits similaires", "", Genre.POSTE),
            l("AG", "Fonds commercial et droit au bail", "", Genre.POSTE),
            l("AH", "Autres immobilisations incorporelles", "", Genre.POSTE),
            l("AI", "IMMOBILISATIONS CORPORELLES", "3", Genre.TOTAL),
            l("AJ", "Terrains", "", Genre.POSTE),
            l("AK", "Bâtiments", "", Genre.POSTE),
            l("AL", "Aménagements, agencements et installations", "", Genre.POSTE),
            l("AM", "Matériel, mobilier et actifs biologiques", "", Genre.POSTE),
            l("AN", "Matériel de transport", "", Genre.POSTE),
            l("AP", "Avances et acomptes versés sur immobilisations", "3", Genre.POSTE),
            l("AQ", "IMMOBILISATIONS FINANCIERES", "4", Genre.TOTAL),
            l("AR", "Titres de participation", "", Genre.POSTE),
            l("AS", "Autres immobilisations financières", "", Genre.POSTE),
            l("AZ", "TOTAL ACTIF IMMOBILISE", "", Genre.TOTAL),
            l("BA", "ACTIF CIRCULANT HAO", "5", Genre.POSTE),
            l("BB", "STOCKS ET ENCOURS", "6", Genre.POSTE),
            l("BG", "CREANCES ET EMPLOIS ASSIMILES", "", Genre.TOTAL),
            l("BH", "Fournisseurs avances versées", "17", Genre.POSTE),
            l("BI", "Clients", "7", Genre.POSTE),
            l("BJ", "Autres créances", "8", Genre.POSTE),
            l("BK", "TOTAL ACTIF CIRCULANT", "", Genre.TOTAL),
            l("BQ", "Titres de placement", "9", Genre.POSTE),
            l("BR", "Valeurs à encaisser", "10", Genre.POSTE),
            l("BS", "Banques, chèques postaux, caisse et assimilés", "11", Genre.POSTE),
            l("BT", "TOTAL TRESORERIE-ACTIF", "", Genre.TOTAL),
            l("BU", "Ecart de conversion-Actif", "12", Genre.POSTE),
            l("BZ", "TOTAL GENERAL", "", Genre.TOTAL));

    /** Composition des totaux de l'actif (références de postes ou de totaux à additionner). */
    public static final java.util.Map<String, List<String>> TOTAUX_ACTIF = java.util.Map.of(
            "AD", List.of("AE", "AF", "AG", "AH"),
            "AI", List.of("AJ", "AK", "AL", "AM", "AN"),
            "AQ", List.of("AR", "AS"),
            "AZ", List.of("AD", "AI", "AP", "AQ"),
            "BG", List.of("BH", "BI", "BJ"),
            "BK", List.of("BA", "BB", "BG"),
            "BT", List.of("BQ", "BR", "BS"),
            "BZ", List.of("AZ", "BK", "BT", "BU"));

    // ---- Bilan : passif (modèle 2, page 982) ------------------------------------------------------------------------------------------

    public static final List<Ligne> BILAN_PASSIF = List.of(
            l("CA", "Capital", "13", Genre.POSTE),
            l("CB", "Apporteurs capital non appelé (-)", "13", Genre.POSTE),
            l("CD", "Primes liées au capital social", "14", Genre.POSTE),
            l("CE", "Ecarts de réévaluation", "3e", Genre.POSTE),
            l("CF", "Réserves indisponibles", "14", Genre.POSTE),
            l("CG", "Réserves libres", "14", Genre.POSTE),
            l("CH", "Report à nouveau (+ ou -)", "14", Genre.POSTE),
            l("CJ", "Résultat net de l'exercice (bénéfice + ou perte -)", "", Genre.POSTE),
            l("CL", "Subventions d'investissement", "15", Genre.POSTE),
            l("CM", "Provisions réglementées", "15", Genre.POSTE),
            l("CP", "TOTAL CAPITAUX PROPRES ET RESSOURCES ASSIMILEES", "", Genre.TOTAL),
            l("DA", "Emprunts et dettes financières diverses", "16", Genre.POSTE),
            l("DB", "Dettes de location acquisition", "16", Genre.POSTE),
            l("DC", "Provisions pour risques et charges", "16", Genre.POSTE),
            l("DD", "TOTAL DETTES FINANCIERES ET RESSOURCES ASSIMILEES", "", Genre.TOTAL),
            l("DF", "TOTAL RESSOURCES STABLES", "", Genre.TOTAL),
            l("DH", "Dettes circulantes HAO", "5", Genre.POSTE),
            l("DI", "Clients, avances reçues", "7", Genre.POSTE),
            l("DJ", "Fournisseurs d'exploitation", "17", Genre.POSTE),
            l("DK", "Dettes fiscales et sociales", "18", Genre.POSTE),
            l("DM", "Autres dettes", "19", Genre.POSTE),
            l("DN", "Provisions pour risques à court terme", "19", Genre.POSTE),
            l("DP", "TOTAL PASSIF CIRCULANT", "", Genre.TOTAL),
            l("DQ", "Banques, crédits d'escompte", "20", Genre.POSTE),
            l("DR", "Banques, établissements financiers et crédits de trésorerie", "20", Genre.POSTE),
            l("DT", "TOTAL TRESORERIE-PASSIF", "", Genre.TOTAL),
            l("DV", "Ecart de conversion-Passif", "12", Genre.POSTE),
            l("DZ", "TOTAL GENERAL", "", Genre.TOTAL));

    public static final java.util.Map<String, List<String>> TOTAUX_PASSIF = java.util.Map.of(
            "CP", List.of("CA", "CB", "CD", "CE", "CF", "CG", "CH", "CJ", "CL", "CM"),
            "DD", List.of("DA", "DB", "DC"),
            "DF", List.of("CP", "DD"),
            "DP", List.of("DH", "DI", "DJ", "DK", "DM", "DN"),
            "DT", List.of("DQ", "DR"),
            "DZ", List.of("DF", "DP", "DT", "DV"));

    // ---- Rattachement des comptes au bilan (tableau de correspondance, page 1049) -----------------------------------------------------

    private static List<String> p(String liste) { return liste.isBlank() ? List.of() : Arrays.stream(liste.split(",")).map(String::strip).toList(); }

    private static Regle a(String poste, Colonne col, Sens sens, String prefixes, String sauf, boolean officiel, String remarque) {
        return new Regle(Cote.ACTIF, poste, col, sens, p(prefixes), p(sauf), officiel, remarque);
    }

    private static Regle pa(String poste, Sens sens, String prefixes, String sauf, boolean officiel, String remarque) {
        return new Regle(Cote.PASSIF, poste, Colonne.BRUT, sens, p(prefixes), p(sauf), officiel, remarque);
    }

    private static final String NON_COMPENSATION = "Non-compensation : les comptes à solde débiteur vont à l'actif, ceux à solde créditeur au passif (p. 385 et 389).";

    /**
     * Les règles, dans l'ordre d'essai : la première qui correspond au compte et au sens de son solde gagne. L'actif vient avant le passif : un compte à
     * double sens se range donc à l'actif s'il est débiteur et au passif s'il est créditeur.
     */
    public static final List<Regle> REGLES_BILAN;

    static {
        List<Regle> r = new ArrayList<>();
        // -- Actif immobilisé : brut et amortissements/dépréciations (p. 1049) ; « p » du texte = compte partagé entre deux postes, rangé au premier cité.
        r.add(a("AE", Colonne.BRUT, Sens.TOUS, "211,2181,2191", "", true, ""));
        r.add(a("AE", Colonne.AMORT, Sens.TOUS, "2811,2911", "", true, ""));
        r.add(a("AF", Colonne.BRUT, Sens.TOUS, "212,213,214,2193", "", true, ""));
        r.add(a("AF", Colonne.AMORT, Sens.TOUS, "2812,2813,2814,2912,2913,2914", "", true, ""));
        r.add(a("AG", Colonne.BRUT, Sens.TOUS, "215,216", "", true, ""));
        r.add(a("AG", Colonne.AMORT, Sens.TOUS, "2815,2816,2915,2916", "", true, ""));
        r.add(a("AH", Colonne.BRUT, Sens.TOUS, "217,218,2198", "2181", true, ""));
        r.add(a("AH", Colonne.AMORT, Sens.TOUS, "2817,2818,2917,2918,2919", "", true, "2818, 2918 et 2919 sont partagés avec AE et AF dans le texte (« p ») : rangés ici."));
        r.add(a("AJ", Colonne.BRUT, Sens.TOUS, "22", "", true, ""));
        r.add(a("AJ", Colonne.AMORT, Sens.TOUS, "282,292", "", true, ""));
        r.add(a("AK", Colonne.BRUT, Sens.TOUS, "231,232,233,237,2391", "", true, ""));
        r.add(a("AK", Colonne.AMORT, Sens.TOUS, "2831,2832,2833,2837,2931,2932,2933,2937", "", true, ""));
        r.add(a("AL", Colonne.BRUT, Sens.TOUS, "234,235,238,239", "2391", true, "2394, 2395 et 2398 (en cours) ne sont pas cités au tableau : rangés avec 2392 et 2393."));
        r.add(a("AL", Colonne.AMORT, Sens.TOUS, "2834,2835,2838,2934,2935,2938,2939", "", true, "2939 est partagé avec AK dans le texte (« p ») : rangé ici."));
        r.add(a("AN", Colonne.BRUT, Sens.TOUS, "245,2495", "", true, ""));
        r.add(a("AN", Colonne.AMORT, Sens.TOUS, "2845,2945", "", true, "2949 (« p ») est rangé avec AM."));
        r.add(a("AM", Colonne.BRUT, Sens.TOUS, "24", "245,2495", true, ""));
        r.add(a("AM", Colonne.AMORT, Sens.TOUS, "284,294", "2845,2945", true, ""));
        r.add(a("AP", Colonne.BRUT, Sens.TOUS, "251,252", "", true, ""));
        r.add(a("AP", Colonne.AMORT, Sens.TOUS, "2951,2952", "", true, ""));
        r.add(a("AR", Colonne.BRUT, Sens.TOUS, "26", "", true, ""));
        r.add(a("AR", Colonne.AMORT, Sens.TOUS, "296", "", true, ""));
        r.add(a("AS", Colonne.BRUT, Sens.TOUS, "27", "", true, ""));
        r.add(a("AS", Colonne.AMORT, Sens.TOUS, "297", "", true, ""));
        // -- Actif circulant
        r.add(a("BA", Colonne.BRUT, Sens.DEBITEUR, "485,488", "", true, ""));
        r.add(a("BA", Colonne.AMORT, Sens.TOUS, "498", "", true, ""));
        r.add(a("BA", Colonne.BRUT, Sens.DEBITEUR, "481,482,484", "", false, "Solde débiteur d'un compte de dette HAO : rangé à l'actif HAO (choix, le texte ne le dit pas)."));
        r.add(a("BB", Colonne.BRUT, Sens.TOUS, "31,32,33,34,35,36,37,38", "", true, ""));
        r.add(a("BB", Colonne.AMORT, Sens.TOUS, "39", "", true, ""));
        r.add(a("BH", Colonne.BRUT, Sens.DEBITEUR, "40", "", true, "Fournisseurs débiteurs (409 et tout compte fournisseur à solde débiteur). " + NON_COMPENSATION));
        r.add(a("BH", Colonne.AMORT, Sens.TOUS, "490", "", true, ""));
        r.add(a("BI", Colonne.BRUT, Sens.DEBITEUR, "41", "", true, "Clients à solde débiteur (419 débiteur compris). " + NON_COMPENSATION));
        r.add(a("BI", Colonne.AMORT, Sens.TOUS, "491", "", true, ""));
        r.add(a("BJ", Colonne.BRUT, Sens.DEBITEUR, "185,42,43,44,45,46,47", "478", true, "Soldes débiteurs de ces comptes (le texte précise « soldes débiteurs » pour 185 et 42 à 47)."));
        r.add(a("BJ", Colonne.AMORT, Sens.TOUS, "492,493,494,495,496,497", "", true, ""));
        // -- Trésorerie-actif
        r.add(a("BQ", Colonne.BRUT, Sens.DEBITEUR, "50", "", true, ""));
        r.add(a("BQ", Colonne.AMORT, Sens.TOUS, "590", "", true, ""));
        r.add(a("BR", Colonne.BRUT, Sens.DEBITEUR, "51", "", true, ""));
        r.add(a("BR", Colonne.AMORT, Sens.TOUS, "591", "", true, ""));
        r.add(a("BS", Colonne.BRUT, Sens.DEBITEUR, "52,53,54,55,57,581,582", "", true, "Soldes débiteurs ; aucune compensation entre comptes de classe 5 (p. 414)."));
        r.add(a("BS", Colonne.AMORT, Sens.TOUS, "592,593,594", "", true, ""));
        r.add(a("BS", Colonne.BRUT, Sens.DEBITEUR, "56,58", "", false, "Comptes de classe 5 débiteurs absents du tableau (56, 585, 588) : rangés avec la trésorerie-actif (choix)."));
        r.add(a("BU", Colonne.BRUT, Sens.TOUS, "478", "", true, ""));
        // -- Capitaux propres et ressources assimilées (signés : crédit positif)
        r.add(pa("CA", Sens.TOUS, "101,102,103,104", "", true, "Le compte de l'exploitant (104) est rangé au capital."));
        r.add(pa("CB", Sens.TOUS, "109", "", true, "Montant déduit (-)."));
        r.add(pa("CD", Sens.TOUS, "105", "", true, ""));
        r.add(pa("CE", Sens.TOUS, "106", "", true, ""));
        r.add(pa("CF", Sens.TOUS, "111,112,113", "", true, ""));
        r.add(pa("CG", Sens.TOUS, "118", "", true, ""));
        r.add(pa("CH", Sens.TOUS, "12", "", true, "121 créditeur ou 129 débiteur. S'y ajoutent les résultats des exercices antérieurs non encore clôturés (la clôture est l'étape 5c)."));
        r.add(pa("CJ", Sens.TOUS, "13", "", true, "Compte 13 (vide tant qu'il n'y a pas de clôture) plus le résultat de l'exercice calculé au compte de résultat."));
        r.add(pa("CL", Sens.TOUS, "14", "", true, ""));
        r.add(pa("CM", Sens.TOUS, "15", "", true, ""));
        // -- Dettes financières et ressources assimilées
        r.add(pa("DA", Sens.TOUS, "16,181,182,183,184", "", true, ""));
        r.add(pa("DB", Sens.TOUS, "17", "", true, ""));
        r.add(pa("DC", Sens.TOUS, "19", "", true, ""));
        // -- Passif circulant : soldes créditeurs
        r.add(pa("DH", Sens.CREDITEUR, "481,482,484,4998", "", true, ""));
        r.add(pa("DH", Sens.CREDITEUR, "485,488", "", false, "Solde créditeur d'une créance HAO : rangé au passif HAO (choix)."));
        r.add(pa("DI", Sens.CREDITEUR, "41", "", true, "419 (le texte) et tout client à solde créditeur (règle de non-compensation, p. 389) : avances reçues."));
        r.add(pa("DJ", Sens.CREDITEUR, "40", "", true, "Fournisseurs à solde créditeur (409 créditeur compris)."));
        r.add(pa("DK", Sens.CREDITEUR, "42,43,44", "", true, "Soldes créditeurs."));
        r.add(pa("DM", Sens.CREDITEUR, "185,45,46,47", "479", true, "Soldes créditeurs (dont le compte courant des associés, 46)."));
        r.add(pa("DN", Sens.TOUS, "499,599", "4998", true, ""));
        // -- Trésorerie-passif
        r.add(pa("DQ", Sens.TOUS, "564,565", "", true, ""));
        r.add(pa("DR", Sens.CREDITEUR, "52,53,561,566", "", true, "Soldes créditeurs (découverts) ; aucune compensation avec les comptes débiteurs (p. 414)."));
        r.add(pa("DR", Sens.CREDITEUR, "5", "", false, "Autre compte de classe 5 à solde créditeur (caisse, monnaie électronique, virements) : rangé en trésorerie-passif (choix)."));
        r.add(pa("DV", Sens.TOUS, "479", "", true, ""));
        REGLES_BILAN = List.copyOf(r);
    }

    /** Poste d'accueil d'un compte de classe 1 à 5 que le tableau ne cite pas : « Autres créances » (débit) ou « Autres dettes » (crédit), signalé. */
    public static final String REPLI_ACTIF = "BJ", REPLI_PASSIF = "DM";

    // ---- Compte de résultat (modèle, page 986 ; correspondance, page 1050) ------------------------------------------------------------

    private static Ligne c(String ref, String libelle, String signe, String note, Genre genre) { return new Ligne(ref, libelle, signe, note, genre); }

    public static final List<Ligne> COMPTE_RESULTAT = List.of(
            c("TA", "Ventes de marchandises", "A +", "21", Genre.POSTE),
            c("RA", "Achats de marchandises", "-", "22", Genre.POSTE),
            c("RB", "Variation de stocks de marchandises", "-/+", "6", Genre.POSTE),
            c("XA", "MARGE COMMERCIALE (Somme TA à RB)", "", "", Genre.TOTAL),
            c("TB", "Ventes de produits fabriqués", "B +", "21", Genre.POSTE),
            c("TC", "Travaux, services vendus", "C +", "21", Genre.POSTE),
            c("TD", "Produits accessoires", "D +", "21", Genre.POSTE),
            c("XB", "CHIFFRE D'AFFAIRES (A + B + C + D)", "", "", Genre.TOTAL),
            c("TE", "Production stockée (ou déstockage)", "-/+", "6", Genre.POSTE),
            c("TF", "Production immobilisée", "", "21", Genre.POSTE),
            c("TG", "Subventions d'exploitation", "", "21", Genre.POSTE),
            c("TH", "Autres produits", "+", "21", Genre.POSTE),
            c("TI", "Transferts de charges d'exploitation", "+", "12", Genre.POSTE),
            c("RC", "Achats de matières premières et fournitures liées", "-", "22", Genre.POSTE),
            c("RD", "Variation de stocks de matières premières et fournitures liées", "-/+", "6", Genre.POSTE),
            c("RE", "Autres achats", "-", "22", Genre.POSTE),
            c("RF", "Variation de stocks d'autres approvisionnements", "-/+", "6", Genre.POSTE),
            c("RG", "Transports", "-", "23", Genre.POSTE),
            c("RH", "Services extérieurs", "-", "24", Genre.POSTE),
            c("RI", "Impôts et taxes", "-", "25", Genre.POSTE),
            c("RJ", "Autres charges", "-", "26", Genre.POSTE),
            c("XC", "VALEUR AJOUTEE (XB +RA+RB) + (somme TE à RJ)", "", "", Genre.TOTAL),
            c("RK", "Charges de personnel", "-", "27", Genre.POSTE),
            c("XD", "EXCEDENT BRUT D'EXPLOITATION (XC+RK)", "", "28", Genre.TOTAL),
            c("TJ", "Reprises d'amortissements, provisions et dépréciations", "+", "28", Genre.POSTE),
            c("RL", "Dotations aux amortissements, aux provisions et dépréciations", "-", "3C&28", Genre.POSTE),
            c("XE", "RESULTAT D'EXPLOITATION (XD+TJ+ RL)", "", "", Genre.TOTAL),
            c("TK", "Revenus financiers et assimilés", "+", "29", Genre.POSTE),
            c("TL", "Reprises de provisions et dépréciations financières", "+", "28", Genre.POSTE),
            c("TM", "Transferts de charges financières", "+", "12", Genre.POSTE),
            c("RM", "Frais financiers et charges assimilées", "-", "29", Genre.POSTE),
            c("RN", "Dotations aux provisions et aux dépréciations financières", "-", "3C&28", Genre.POSTE),
            c("XF", "RESULTAT FINANCIER (somme TK à RN)", "", "", Genre.TOTAL),
            c("XG", "RESULTAT DES ACTIVITES ORDINAIRES (XE+XF)", "", "", Genre.TOTAL),
            c("TN", "Produits des cessions d'immobilisations", "+", "3D", Genre.POSTE),
            c("TO", "Autres Produits HAO", "+", "30", Genre.POSTE),
            c("RO", "Valeurs comptables des cessions d'immobilisations", "-", "3D", Genre.POSTE),
            c("RP", "Autres Charges HAO", "-", "30", Genre.POSTE),
            c("XH", "RESULTAT HORS ACTIVITES ORDINAIRES (somme TN à RP)", "", "", Genre.TOTAL),
            c("RQ", "Participation des travailleurs", "-", "30", Genre.POSTE),
            c("RS", "Impôts sur le résultat", "-", "", Genre.POSTE),
            c("XI", "RESULTAT NET (XG+XH+RQ+RS)", "", "", Genre.TOTAL));

    /** Rattachement des comptes de gestion (classes 6, 7 et 8) aux postes du compte de résultat : préfixes, par poste (page 1050). */
    public record RegleCr(String poste, List<String> prefixes, boolean officiel, String remarque) {
        public boolean correspond(String compte) { for (String p : prefixes) if (compte.startsWith(p)) return true; return false; }
    }

    private static RegleCr cr(String poste, String prefixes, boolean officiel, String remarque) { return new RegleCr(poste, p(prefixes), officiel, remarque); }

    public static final List<RegleCr> REGLES_COMPTE_RESULTAT = List.of(
            cr("TA", "701", true, ""), cr("RA", "601", true, ""), cr("RB", "6031", true, ""),
            cr("TB", "702,703,704", true, ""), cr("TC", "705,706", true, ""), cr("TD", "707", true, ""),
            cr("TE", "73", true, ""), cr("TF", "72", true, ""), cr("TG", "71", true, ""), cr("TH", "75", true, ""), cr("TI", "781", true, ""),
            cr("RC", "602", true, ""), cr("RD", "6032", true, ""), cr("RE", "604,605,608", true, ""), cr("RF", "6033", true, ""),
            cr("RG", "61", true, ""), cr("RH", "62,63", true, ""), cr("RI", "64", true, ""), cr("RJ", "65", true, ""),
            cr("RK", "66", true, ""),
            cr("TJ", "791,798,799", true, ""), cr("RL", "681,691", true, ""),
            cr("TK", "77", true, ""), cr("TL", "797", true, ""), cr("TM", "787", true, ""), cr("RM", "67", true, ""), cr("RN", "697", true, ""),
            cr("TN", "82", true, ""), cr("TO", "84,86,88", true, ""), cr("RO", "81", true, ""), cr("RP", "83,85", true, ""),
            cr("RQ", "87", true, ""), cr("RS", "89", true, ""),
            // Comptes que le tableau officiel ne cite pas : rangés au poste de leur famille, signalés (choix de l'application).
            cr("RB", "603", false, "Variation de stocks de biens achetés : 6031 est cité au tableau, les autres comptes 603 sont rangés ici."),
            cr("RL", "68,69", false, "Dotations hors 681, 691, 697 (687, 689, 698, 699) : rangées avec les dotations d'exploitation."),
            cr("TI", "78", false, "Transferts de charges autres que 781 et 787 : rangés avec ceux d'exploitation."),
            cr("TJ", "79", false, "Reprises autres que 791, 797, 798, 799 : rangées avec celles d'exploitation."),
            cr("TA", "70", false, "Ventes autres que 701 à 707 : rangées avec les ventes de marchandises."),
            cr("RE", "60", false, "Achats non cités au tableau (le compte 60 lui-même, 609 rabais) : rangés avec les autres achats."));

    // ---- Tableau des flux de trésorerie (modèle, page 997) ----------------------------------------------------------------------------

    public static final List<Ligne> FLUX = List.of(
            c("ZA", "Trésorerie nette au 1er janvier (Trésorerie actif N-1 - Trésorerie passif N-1)", "", "A", Genre.TOTAL),
            c("", "Flux de trésorerie provenant des activités opérationnelles", "", "", Genre.TITRE),
            c("FA", "Capacité d'Autofinancement Globale (CAFG)", "", "", Genre.POSTE),
            c("FB", "Actif circulant HAO (1)", "-", "", Genre.POSTE),
            c("FC", "Variation des stocks", "-", "", Genre.POSTE),
            c("FD", "Variation des créances", "-", "", Genre.POSTE),
            c("FE", "Variation du passif circulant (1)", "+", "", Genre.POSTE),
            c("VBF", "Variation du BF lié aux activités opérationnelles (FB+FC+FD+FE)", "", "", Genre.TOTAL),
            c("ZB", "Flux de trésorerie provenant des activités opérationnelles (somme FA à FE)", "", "B", Genre.TOTAL),
            c("", "Flux de trésorerie provenant des activités d'investissements", "", "", Genre.TITRE),
            c("FF", "Décaissements liés aux acquisitions d'immobilisations incorporelles", "-", "", Genre.POSTE),
            c("FG", "Décaissements liés aux acquisitions d'immobilisations corporelles", "-", "", Genre.POSTE),
            c("FH", "Décaissements liés aux acquisitions d'immobilisations financières", "-", "", Genre.POSTE),
            c("FI", "Encaissements liés aux cessions d'immobilisations incorporelles et corporelles", "+", "", Genre.POSTE),
            c("FJ", "Encaissements liés aux cessions d'immobilisations financières", "+", "", Genre.POSTE),
            c("ZC", "Flux de trésorerie provenant des activités d'investissement (somme FF à FJ)", "", "C", Genre.TOTAL),
            c("", "Flux de trésorerie provenant du financement par les capitaux propres", "", "", Genre.TITRE),
            c("FK", "Augmentations de capital par apports nouveaux", "+", "", Genre.POSTE),
            c("FL", "Subventions d'investissement reçues", "+", "", Genre.POSTE),
            c("FM", "Prélèvements sur le capital", "-", "", Genre.POSTE),
            c("FN", "Dividendes versés", "-", "", Genre.POSTE),
            c("ZD", "Flux de trésorerie provenant des capitaux propres (somme FK à FN)", "", "D", Genre.TOTAL),
            c("", "Trésorerie provenant du financement par les capitaux étrangers", "", "", Genre.TITRE),
            c("FO", "Emprunts", "+", "", Genre.POSTE),
            c("FP", "Autres dettes financières", "+", "", Genre.POSTE),
            c("FQ", "Remboursements des emprunts et autres dettes financières", "-", "", Genre.POSTE),
            c("ZE", "Flux de trésorerie provenant des capitaux étrangers (somme FO à FQ)", "", "E", Genre.TOTAL),
            c("ZF", "Flux de trésorerie provenant des activités de financement (D+E)", "", "F", Genre.TOTAL),
            c("ZG", "VARIATION DE LA TRÉSORERIE NETTE DE LA PÉRIODE (B+C+F)", "", "G", Genre.TOTAL),
            c("ZH", "Trésorerie nette au 31 Décembre (G+A)", "", "H", Genre.TOTAL));

    // ---- Système minimal de trésorerie (modèle, page 1056) ----------------------------------------------------------------------------

    public static final List<Ligne> SMT_BILAN_ACTIF = List.of(
            c("", "Immobilisations (1)", "", "1", Genre.POSTE), c("", "Stocks", "", "2", Genre.POSTE), c("", "Clients et débiteurs divers", "", "3", Genre.POSTE),
            c("", "Caisse", "", "", Genre.POSTE), c("", "Banque (en + ou en -)", "", "", Genre.POSTE), c("", "Total actif", "", "", Genre.TOTAL));

    public static final List<Ligne> SMT_BILAN_PASSIF = List.of(
            c("", "Compte exploitant", "", "", Genre.POSTE), c("", "Resultat exercice", "", "", Genre.POSTE), c("", "Emprunt", "", "", Genre.POSTE),
            c("", "Founisseurs et crediteurs divers", "", "3", Genre.POSTE), c("", "Total passif", "", "", Genre.TOTAL));

    // ---- Accès commodes ----------------------------------------------------------------------------------------------------------------

    /** Libellé d'un poste du bilan, d'après son identifiant (actif ou passif). */
    public static String libelleBilan(String ref) {
        for (Ligne l : BILAN_ACTIF) if (l.ref().equals(ref)) return l.libelle();
        for (Ligne l : BILAN_PASSIF) if (l.ref().equals(ref)) return l.libelle();
        return ref;
    }

    /** Regroupe les règles du bilan par poste, dans l'ordre du modèle (actif puis passif) : pour la page et la documentation. */
    public static List<RattachementBilan> rattachementsBilan() {
        List<RattachementBilan> r = new ArrayList<>();
        for (Ligne ligne : BILAN_ACTIF) ajouterRattachement(r, ligne);
        for (Ligne ligne : BILAN_PASSIF) ajouterRattachement(r, ligne);
        return r;
    }

    private static void ajouterRattachement(List<RattachementBilan> r, Ligne ligne) {
        if (ligne.genre() != Genre.POSTE) return;
        List<Regle> regles = REGLES_BILAN.stream().filter(x -> x.poste().equals(ligne.ref())).toList();
        if (regles.isEmpty() && !ligne.ref().equals("CJ")) return;
        r.add(new RattachementBilan(ligne.ref(), ligne.libelle(), regles));
    }

    /** Un poste du bilan et ses règles de rattachement. */
    public record RattachementBilan(String ref, String libelle, List<Regle> regles) {}

    /** Écriture lisible d'une liste de comptes avec exclusions : « 24 (sauf 245, 2495) ». */
    public static String comptes(Regle regle) {
        String base = String.join(", ", regle.prefixes());
        return regle.sauf().isEmpty() ? base : base + " (sauf " + String.join(", ", regle.sauf()) + ")";
    }

    public static String sensLibelle(Sens s) { return switch (s) { case DEBITEUR -> "solde débiteur"; case CREDITEUR -> "solde créditeur"; case TOUS -> "tout solde"; }; }
}
