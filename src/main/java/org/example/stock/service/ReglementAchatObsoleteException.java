package org.example.stock.service;

public class ReglementAchatObsoleteException extends RuntimeException {
    public ReglementAchatObsoleteException() {
        super("Un versement a déjà été enregistré depuis l'ouverture de ce formulaire. Vérifiez le montant déjà payé avant de réessayer.");
    }
}
