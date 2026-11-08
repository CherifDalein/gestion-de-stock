package org.example.stock.service;

/**
 * Le formulaire de retour d'achat a été ouvert avant un autre retour sur le même achat (ou il est envoyé une seconde fois) :
 * rien n'est enregistré, les quantités restantes ont changé.
 */
public class RetourAchatObsoleteException extends RuntimeException {
    public RetourAchatObsoleteException(String message) {
        super(message);
    }
}
