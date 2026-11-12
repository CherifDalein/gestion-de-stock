package org.example.stock.enums;

/**
 * Nature d'une opération de trésorerie. TRANSFERT et DEPENSE datent du lot 3a ; les autres sont les opérations de financement
 * de l'étape 3b1 (apports et retraits des associés, déblocage et remboursement d'un emprunt ou d'un prêt, produits et charges
 * hors activité courante).
 */
public enum TypeOperationTresorerie {
    TRANSFERT("Transfert"),
    DEPENSE("Dépense"),
    APPORT_CAPITAL("Apport en capital"),
    APPORT_COMPTE_COURANT("Apport en compte courant"),
    RETRAIT_CAPITAL("Retrait de capital"),
    RETRAIT_COMPTE_COURANT("Retrait en compte courant"),
    PRODUIT_FINANCIER("Produit financier"),
    CHARGE_FINANCIERE("Charge financière"),
    AUTRE_PRODUIT("Autre produit"),
    AUTRE_CHARGE("Autre charge"),
    DEBLOCAGE("Déblocage"),
    FRAIS_DOSSIER("Frais de dossier"),
    REMBOURSEMENT("Remboursement");

    private final String libelle;

    TypeOperationTresorerie(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }

    /** Vrai pour un apport ou un retrait d'associé. */
    public boolean estApportOuRetrait() {
        return this == APPORT_CAPITAL || this == APPORT_COMPTE_COURANT || this == RETRAIT_CAPITAL || this == RETRAIT_COMPTE_COURANT;
    }

    /** Vrai pour un produit ou une charge hors activité courante saisi à part. */
    public boolean estProduitOuCharge() {
        return this == PRODUIT_FINANCIER || this == CHARGE_FINANCIERE || this == AUTRE_PRODUIT || this == AUTRE_CHARGE;
    }
}
