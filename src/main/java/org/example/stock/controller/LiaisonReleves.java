package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ExtendedServletRequestDataBinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Liste blanche du binding des formulaires du rapprochement bancaire (étape 3c) : en POST, tout paramètre inconnu donne 400 ; un champ « simple »
 * répété donne 400 ; un champ « multiple » (cases à cocher d'un pointage) accepte au plus {@value #MULTIPLE_MAX} valeurs ; les en-têtes ne sont
 * jamais liés ; le binder n'accepte que les champs déclarés (et l'identifiant d'adresse).
 */
final class LiaisonReleves {
    static final int MULTIPLE_MAX = 100;

    private LiaisonReleves() { }

    static void limiter(WebDataBinder binder, HttpServletRequest request, Set<String> simples, Set<String> multiples) {
        if (binder instanceof ExtendedServletRequestDataBinder servlet) servlet.addHeaderPredicate(nom -> false);
        exiger(request, simples, multiples);
        List<String> autorises = new ArrayList<>(simples);
        autorises.addAll(multiples);
        autorises.addAll(List.of("_csrf", "id", "ligneId", "rapprochementId", "jeton"));
        binder.setAllowedFields(autorises.toArray(String[]::new));
    }

    /** Refuse (400) un POST qui porte un paramètre inconnu, un champ simple répété ou trop de valeurs ; sans effet sur un GET. */
    static void exiger(HttpServletRequest request, Set<String> simples, Set<String> multiples) {
        if (request.getMethod().equals("POST")) {
            for (var parametre : request.getParameterMap().entrySet()) {
                String nom = parametre.getKey();
                if (nom.equals("_csrf")) continue;
                if (multiples.contains(nom)) {
                    if (parametre.getValue().length > MULTIPLE_MAX) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trop de valeurs pour ce champ");
                } else if (simples.contains(nom)) {
                    if (parametre.getValue().length != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Une seule valeur peut être envoyée pour ce champ");
                } else {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champ de formulaire non autorisé");
                }
            }
        }
    }
}
