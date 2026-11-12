package org.example.stock.service;

/** Le règlement a déjà été annulé (second envoi ou annulation simultanée) : rien n'est enregistré, l'état est inchangé. */
public class AnnulationDejaFaiteException extends RuntimeException {
    public AnnulationDejaFaiteException(String message) {
        super(message);
    }

    public AnnulationDejaFaiteException() {
        super("Ce règlement a déjà été annulé. Rien n'a été enregistré une seconde fois : vérifiez le journal de caisse.");
    }
}
