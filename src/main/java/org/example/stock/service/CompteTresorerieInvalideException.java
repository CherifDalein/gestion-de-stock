package org.example.stock.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Compte de trésorerie refusé : inconnu, d'une autre boutique, archivé ou non autorisé à l'utilisateur. Réponse 400, sans
 * jamais préciser lequel de ces cas s'applique (pas de fuite sur les comptes des autres boutiques) ; rien n'est enregistré.
 */
public class CompteTresorerieInvalideException extends ResponseStatusException {
    public CompteTresorerieInvalideException() {
        super(HttpStatus.BAD_REQUEST, "Ce compte de trésorerie n'est pas disponible. Choisissez un compte de la liste.");
    }
}
