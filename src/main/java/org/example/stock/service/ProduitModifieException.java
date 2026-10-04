package org.example.stock.service;

public class ProduitModifieException extends RuntimeException {
    public ProduitModifieException() {
        super("Ce produit a changé depuis l'ouverture du formulaire. Rechargez la fiche avant de recommencer.");
    }
}
