package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ExtendedServletRequestDataBinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Liste blanche du binding des formulaires de financement : en POST, tout paramètre inconnu ou répété donne 400 ; les en-têtes ne sont
 * jamais liés ; seuls les champs déclarés (et les adresses {@code id} et {@code operationId}) sont acceptés par le binder.
 * Les champs indexés ({@code dates[0]}) se déclarent par leur préfixe.
 */
final class LiaisonFinancements {
    private static final Pattern INDEXE = Pattern.compile("([a-zA-Z]+)\\[(\\d{1,3})\\]");

    private LiaisonFinancements() { }

    static void limiter(WebDataBinder binder, HttpServletRequest request, Set<String> champs, Set<String> indexes) {
        if (binder instanceof ExtendedServletRequestDataBinder servlet) servlet.addHeaderPredicate(nom -> false);
        if (request.getMethod().equals("POST")) {
            for (var parametre : request.getParameterMap().entrySet()) {
                String nom = parametre.getKey();
                boolean indexe = false;
                var m = INDEXE.matcher(nom);
                if (m.matches()) indexe = indexes.contains(m.group(1)) && Integer.parseInt(m.group(2)) < 120;
                if (!nom.equals("_csrf") && !champs.contains(nom) && !indexe) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champ de formulaire non autorisé");
                }
                if (parametre.getValue().length != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Une seule valeur peut être envoyée pour ce champ");
            }
        }
        List<String> autorises = new ArrayList<>(champs);
        for (String index : indexes) autorises.add(index + "[*]");
        autorises.addAll(Arrays.asList("_csrf", "id", "operationId"));
        binder.setAllowedFields(autorises.toArray(String[]::new));
    }
}
