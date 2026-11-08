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

    private SourceMouvement() {}

    /** Vrai pour les quatre sources de règlement que le gérant peut annuler. */
    public static boolean estAnnulable(String source) {
        return REGLEMENT_VENTE.equals(source) || REGLEMENT_ACHAT.equals(source)
                || SOLDE_CLIENT.equals(source) || SOLDE_FOURNISSEUR.equals(source);
    }
}
