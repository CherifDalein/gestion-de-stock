package org.example.stock.service;

import org.example.stock.enums.StatutComptage;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Le comptage n'est plus dans l'état attendu (déjà validé, refusé ou annulé, par une autre requête) : réponse 409, rien n'est enregistré. */
public class ComptageDejaTraiteException extends ResponseStatusException {
    public ComptageDejaTraiteException(StatutComptage statut, String attendu) {
        super(HttpStatus.CONFLICT, "Ce comptage est déjà « " + statut.getLibelle() + " » : seul un comptage " + attendu + " peut être traité. Rechargez la page.");
    }
}
