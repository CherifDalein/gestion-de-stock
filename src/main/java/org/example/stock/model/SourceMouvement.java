package org.example.stock.model;

/**
 * Valeurs de {@code mouvement_caisse.source} qui décident de ce qu'un mouvement peut devenir.
 *
 * <p>Le paiement saisi à la <b>création</b> d'une vente ou d'un achat garde la source {@link #VENTE} ou {@link #ACHAT} ; un
 * <b>règlement ultérieur</b>, fait depuis une page de règlement, porte {@link #REGLEMENT_VENTE} ou {@link #REGLEMENT_ACHAT}.
 * Seuls ces règlements ultérieurs et ceux d'un solde de départ ({@link #SOLDE_CLIENT}, {@link #SOLDE_FOURNISSEUR}) sont
 * annulables ; l'annulation est elle-même un mouvement de source {@link #ANNULATION}, jamais annulable.</p>
 */
public final class SourceMouvement {
    public static final String VENTE = "VENTE";
    public static final String ACHAT = "ACHAT";
    public static final String REGLEMENT_VENTE = "REGLEMENT_VENTE";
    public static final String REGLEMENT_ACHAT = "REGLEMENT_ACHAT";
    public static final String SOLDE_CLIENT = "SOLDE_CLIENT";
    public static final String SOLDE_FOURNISSEUR = "SOLDE_FOURNISSEUR";
    public static final String ANNULATION = "ANNULATION";

    // Étape 3b1 : financements et flux hors ventes et achats.
    public static final String APPORT_CAPITAL = "APPORT_CAPITAL";
    public static final String APPORT_COMPTE_COURANT = "APPORT_COMPTE_COURANT";
    public static final String RETRAIT_CAPITAL = "RETRAIT_CAPITAL";
    public static final String RETRAIT_COMPTE_COURANT = "RETRAIT_COMPTE_COURANT";
    public static final String DEBLOCAGE_EMPRUNT = "DEBLOCAGE_EMPRUNT";
    public static final String DEBLOCAGE_PRET = "DEBLOCAGE_PRET";
    public static final String REMBOURSEMENT_EMPRUNT = "REMBOURSEMENT_EMPRUNT";
    public static final String REMBOURSEMENT_PRET = "REMBOURSEMENT_PRET";
    public static final String INTERETS_EMPRUNT = "INTERETS_EMPRUNT";
    public static final String INTERETS_PRET = "INTERETS_PRET";
    public static final String FRAIS_DOSSIER = "FRAIS_DOSSIER";
    public static final String PRODUIT_FINANCIER = "PRODUIT_FINANCIER";
    public static final String CHARGE_FINANCIERE = "CHARGE_FINANCIERE";
    public static final String AUTRE_PRODUIT = "AUTRE_PRODUIT";
    public static final String AUTRE_CHARGE = "AUTRE_CHARGE";

    private static final java.util.Map<String, String> LIBELLES = java.util.Map.ofEntries(
            java.util.Map.entry(APPORT_CAPITAL, "Apport en capital"),
            java.util.Map.entry(APPORT_COMPTE_COURANT, "Apport en compte courant"),
            java.util.Map.entry(RETRAIT_CAPITAL, "Retrait de capital"),
            java.util.Map.entry(RETRAIT_COMPTE_COURANT, "Retrait en compte courant"),
            java.util.Map.entry(DEBLOCAGE_EMPRUNT, "Emprunt reçu"),
            java.util.Map.entry(DEBLOCAGE_PRET, "Prêt accordé"),
            java.util.Map.entry(REMBOURSEMENT_EMPRUNT, "Remboursement d'emprunt (capital)"),
            java.util.Map.entry(REMBOURSEMENT_PRET, "Remboursement de prêt reçu (capital)"),
            java.util.Map.entry(INTERETS_EMPRUNT, "Intérêts d'emprunt payés"),
            java.util.Map.entry(INTERETS_PRET, "Intérêts de prêt reçus"),
            java.util.Map.entry(FRAIS_DOSSIER, "Frais de dossier d'emprunt"),
            java.util.Map.entry(PRODUIT_FINANCIER, "Produit financier"),
            java.util.Map.entry(CHARGE_FINANCIERE, "Charge financière"),
            java.util.Map.entry(AUTRE_PRODUIT, "Autre produit"),
            java.util.Map.entry(AUTRE_CHARGE, "Autre charge"));

    private SourceMouvement() {}

    /**
     * Libellé français d'une source de mouvement pour le journal et les exports ; les sources plus anciennes (VENTE, ACHAT...)
     * sont affichées telles quelles, comme avant.
     */
    public static String libelle(String source) {
        return source == null ? "" : LIBELLES.getOrDefault(source, source);
    }

    /** Source brute d'un libellé de {@link #libelle(String)} (filtre du journal) ; le texte reçu s'il n'est pas un libellé connu. */
    public static String depuisLibelle(String texte) {
        if (texte == null) return null;
        for (var e : LIBELLES.entrySet()) if (e.getValue().equals(texte)) return e.getKey();
        return texte;
    }

    /** Sources des financements : jamais annulables par la page des règlements, mais par les écrans de financement. */
    public static boolean estFinancement(String source) {
        return source != null && LIBELLES.containsKey(source);
    }

    /** Vrai pour les quatre sources de règlement que le gérant peut annuler. */
    public static boolean estAnnulable(String source) {
        return REGLEMENT_VENTE.equals(source) || REGLEMENT_ACHAT.equals(source)
                || SOLDE_CLIENT.equals(source) || SOLDE_FOURNISSEUR.equals(source);
    }
}
