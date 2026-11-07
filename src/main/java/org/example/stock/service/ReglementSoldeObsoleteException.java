package org.example.stock.service;

public class ReglementSoldeObsoleteException extends RuntimeException {
    public ReglementSoldeObsoleteException() {
        super("Un règlement du solde de départ a déjà été enregistré depuis l'ouverture de ce formulaire. Vérifiez le montant déjà réglé avant de réessayer.");
    }
}
