package org.example.stock.enums;

/**
 * Sens du nouveau solde de départ saisi dans un ajustement, du point de vue de la boutique.
 *
 * <ul>
 *   <li>{@link #IL_ME_DOIT} : le partenaire doit de l'argent à la boutique (solde positif) ;</li>
 *   <li>{@link #JE_LUI_DOIS} : la boutique doit de l'argent au partenaire (solde négatif) ;</li>
 *   <li>{@link #AVANCE} : ancienne formulation, qui faisait doublon et que le formulaire ne propose plus. Le serveur l'accepte
 *       encore et la traite comme son équivalent, voir {@link #equivalent(boolean)} : pour un <b>client</b>, l'argent qu'il a avancé
 *       à la boutique (c'est « je lui dois ») ; pour un <b>fournisseur</b>, l'argent que la boutique lui a avancé (c'est « il me doit »).</li>
 * </ul>
 */
public enum SensAjustement {
    IL_ME_DOIT("Il me doit"),
    JE_LUI_DOIS("Je lui dois"),
    AVANCE("Avance");

    private final String libelle;

    SensAjustement(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }

    /** Le sens réellement appliqué : {@code AVANCE} devient {@code JE_LUI_DOIS} pour un client et {@code IL_ME_DOIT} pour un fournisseur. */
    public SensAjustement equivalent(boolean client) {
        return this != AVANCE ? this : (client ? JE_LUI_DOIS : IL_ME_DOIT);
    }

    /** Solde de départ signé (convention de {@code solde_initial} : positif, le partenaire me doit) pour un montant positif ou nul. */
    public java.math.BigDecimal signe(java.math.BigDecimal montant, boolean client) {
        if (montant.signum() == 0) return montant;
        boolean negatif = equivalent(client) == JE_LUI_DOIS;
        return negatif ? montant.negate() : montant;
    }
}
