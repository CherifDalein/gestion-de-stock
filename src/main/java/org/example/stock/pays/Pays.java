package org.example.stock.pays;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Pays d'une boutique, choisi à sa création. Il fixe la {@link Monnaie} et ne change jamais.
 *
 * <p><b>Ajouter un pays = ajouter une ligne</b> ci-dessous : code ISO 3166-1 alpha-2, nom, indicatif téléphonique, monnaie, puis les
 * opérateurs de mobile money du pays (le premier est proposé par défaut). L'option « Autre » avec un nom libre est toujours ajoutée
 * par l'application. Rien d'autre à modifier : formulaires d'inscription, libellés, billetage et indicatifs s'en déduisent.
 * Voir {@code docs/pays-monnaie.md}.</p>
 */
public enum Pays {
    GUINEE("GN", "Guinée", "+224", Monnaie.GNF, "Orange Money", "MTN MoMo"),
    // UEMOA (XOF)
    SENEGAL("SN", "Sénégal", "+221", Monnaie.XOF, "Orange Money", "Wave", "Free Money"),
    COTE_D_IVOIRE("CI", "Côte d'Ivoire", "+225", Monnaie.XOF, "Orange Money", "MTN MoMo", "Moov Money", "Wave"),
    MALI("ML", "Mali", "+223", Monnaie.XOF, "Orange Money", "Moov Money"),
    BURKINA_FASO("BF", "Burkina Faso", "+226", Monnaie.XOF, "Orange Money", "Moov Money"),
    NIGER("NE", "Niger", "+227", Monnaie.XOF, "Airtel Money", "Moov Money", "Orange Money"),
    TOGO("TG", "Togo", "+228", Monnaie.XOF, "T-Money", "Moov Money"),
    BENIN("BJ", "Bénin", "+229", Monnaie.XOF, "MTN MoMo", "Moov Money", "Celtiis Cash"),
    GUINEE_BISSAU("GW", "Guinée-Bissau", "+245", Monnaie.XOF, "Orange Money", "MTN MoMo"),
    // CEMAC (XAF)
    CAMEROUN("CM", "Cameroun", "+237", Monnaie.XAF, "Orange Money", "MTN MoMo"),
    GABON("GA", "Gabon", "+241", Monnaie.XAF, "Airtel Money", "Moov Money"),
    CONGO("CG", "Congo", "+242", Monnaie.XAF, "Airtel Money", "MTN MoMo"),
    TCHAD("TD", "Tchad", "+235", Monnaie.XAF, "Airtel Money", "Moov Money"),
    CENTRAFRIQUE("CF", "Centrafrique", "+236", Monnaie.XAF, "Orange Money", "Moov Money"),
    GUINEE_EQUATORIALE("GQ", "Guinée équatoriale", "+240", Monnaie.XAF, "Muni Dinero");

    /** Valeur proposée par défaut dans les formulaires et donnée aux boutiques existantes par la migration. */
    public static final Pays PAR_DEFAUT = GUINEE;
    /** Valeur du choix « Autre » dans la liste des opérateurs d'un formulaire ; le nom libre est saisi à côté. */
    public static final String AUTRE = "AUTRE";
    /** Libellé affiché du choix « Autre ». */
    public static final String LIBELLE_AUTRE = "Autre";

    private final String code, nom, indicatif;
    private final Monnaie monnaie;
    private final List<String> operateurs;

    Pays(String code, String nom, String indicatif, Monnaie monnaie, String... operateurs) {
        this.code = code; this.nom = nom; this.indicatif = indicatif; this.monnaie = monnaie; this.operateurs = List.of(operateurs);
    }

    /** Code ISO 3166-1 alpha-2 : valeur stockée en base (« GN », « SN »…). */
    public String getCode() { return code; }
    public String getNom() { return nom; }
    /** Indicatif téléphonique international avec le plus : « +224 ». */
    public String getIndicatif() { return indicatif; }
    public Monnaie getMonnaie() { return monnaie; }
    /** Opérateurs de mobile money proposés dans ce pays (sans « Autre »), le plus répandu en premier. */
    public List<String> getOperateurs() { return operateurs; }
    /** Opérateur du compte de mobile money créé avec la boutique. */
    public String operateurParDefaut() { return operateurs.get(0); }

    /** Tous les pays, triés par nom (accents et casse ignorés) pour les listes déroulantes. */
    public static List<Pays> tries() {
        java.text.Collator collator = java.text.Collator.getInstance(Locale.FRANCE);
        return Arrays.stream(values()).sorted(Comparator.comparing(Pays::getNom, collator)).toList();
    }

    public static List<Pays> de(Monnaie monnaie) { return tries().stream().filter(p -> p.monnaie == monnaie).toList(); }

    /** Lit un code ISO (« SN ») ou le nom de l'énumération (« SENEGAL ») ; {@code null} si inconnu. */
    public static Pays depuisCode(String saisie) {
        if (saisie == null) return null;
        String valeur = saisie.strip();
        for (Pays pays : values()) if (pays.code.equalsIgnoreCase(valeur) || pays.name().equalsIgnoreCase(valeur)) return pays;
        return null;
    }

    /**
     * Début d'un numéro de téléphone proposé par défaut dans les formulaires : l'indicatif suivi d'une espace (« +224 »). Les numéros
     * déjà saisis ne sont jamais modifiés.
     */
    public String indicatifSaisie() { return indicatif + " "; }

    /**
     * Numéro pour un lien {@code tel:} : chiffres et plus seulement ; un numéro sans indicatif reçoit celui du pays, « 00 » devient « + ».
     * Un numéro vide donne une chaîne vide.
     */
    public String numeroInternational(String saisie) {
        if (saisie == null) return "";
        String propre = saisie.replaceAll("[^0-9+]", "");
        if (propre.isEmpty()) return "";
        if (propre.startsWith("+")) return "+" + propre.substring(1).replace("+", "");
        propre = propre.replace("+", "");
        if (propre.startsWith("00")) return "+" + propre.substring(2);
        return indicatif + propre;
    }

    /** Numéro à enregistrer : {@code null} si la saisie est vide ou ne contient que l'indicatif proposé par défaut ; sinon la saisie nettoyée des espaces de bord. */
    public String sansIndicatifSeul(String saisie) {
        if (saisie == null || saisie.isBlank()) return null;
        String chiffres = saisie.replaceAll("[^0-9+]", "");
        return chiffres.equals(indicatif) || chiffres.equals(indicatif.replace("+", "00")) ? null : saisie.strip();
    }

    /**
     * Opérateur à enregistrer d'après le choix d'un formulaire : le nom de la liste du pays, ou, pour « Autre », le nom libre.
     *
     * @throws IllegalArgumentException si l'opérateur n'est pas dans la liste du pays, ou si le nom libre est vide ou trop long
     */
    public String operateurValide(String choix, String nomLibre, int tailleMax) {
        String valeur = choix == null ? "" : choix.strip();
        if (valeur.isEmpty()) throw new IllegalArgumentException("Choisissez l'opérateur du compte mobile money.");
        if (valeur.equals(AUTRE)) {
            String libre = nomLibre == null ? "" : nomLibre.strip().replaceAll("\\s+", " ");
            if (libre.isEmpty()) throw new IllegalArgumentException("Saisissez le nom de l'opérateur mobile money.");
            if (libre.length() > tailleMax) throw new IllegalArgumentException("Le nom de l'opérateur accepte au maximum " + tailleMax + " caractères.");
            return libre;
        }
        for (String operateur : operateurs) if (operateur.equalsIgnoreCase(valeur)) return operateur;
        throw new IllegalArgumentException("Cet opérateur n'est pas proposé dans le pays de la boutique : choisissez-en un dans la liste ou « Autre ».");
    }
}
