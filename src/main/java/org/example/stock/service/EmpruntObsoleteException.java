package org.example.stock.service;

/** L'échéancier a changé depuis l'affichage du formulaire (autre remboursement, annulation) : rien n'est enregistré (409). */
public class EmpruntObsoleteException extends IllegalArgumentException {
    public EmpruntObsoleteException() {
        super("L'échéancier a changé depuis l'affichage de cette page (un autre remboursement a été enregistré ou annulé). "
                + "Rien n'a été enregistré : rechargez la page et vérifiez l'emprunt avant de réessayer.");
    }
}
