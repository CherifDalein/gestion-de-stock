package org.example.stock.service;

public class ReglementVenteObsoleteException extends RuntimeException {
    public ReglementVenteObsoleteException() {
        super("Un versement a déjà été enregistré depuis l'ouverture de ce formulaire. Vérifiez le montant déjà payé avant de réessayer.");
    }
}
