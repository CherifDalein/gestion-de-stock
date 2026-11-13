package org.example.stock.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Le relevé n'est plus dans l'état attendu (déjà validé ou dévalidé par une autre requête) : réponse 409, rien n'est enregistré. */
public class ReleveDejaTraiteException extends ResponseStatusException {
    public ReleveDejaTraiteException(String numero, String etat) {
        super(HttpStatus.CONFLICT, "Le relevé " + numero + " est déjà « " + etat + " » : une autre opération l'a modifié. Rechargez la page.");
    }
}
