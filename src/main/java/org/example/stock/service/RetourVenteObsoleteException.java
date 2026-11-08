package org.example.stock.service;

/**
 * Le formulaire de retour a été ouvert avant un autre retour sur la même vente (ou il est envoyé une seconde fois) :
 * rien n'est enregistré, les quantités restantes ont changé.
 */
public class RetourVenteObsoleteException extends RuntimeException {
    public RetourVenteObsoleteException(String message) {
        super(message);
    }
}
