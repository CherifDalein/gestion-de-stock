package org.example.stock.service;

/** Un achat qui a au moins un retour d'achat ne peut plus être modifié : ses lignes, ses prix et ses quantités sont figés. */
public class ModificationAchatRefuseeException extends IllegalStateException {
    public static final String MESSAGE = "Cet achat a déjà fait l'objet d'un retour au fournisseur : il ne peut plus être modifié. "
            + "Ses lignes, ses prix et ses quantités sont figés.";

    public ModificationAchatRefuseeException() {
        super(MESSAGE);
    }
}
