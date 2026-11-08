package org.example.stock.controller;

import org.example.stock.enums.TypeRetour;
import org.example.stock.model.Retour;
import org.example.stock.service.RetourAchatService;
import org.example.stock.service.RetourVenteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Liste et bons imprimables des retours de la boutique, de vente et d'achat : réservés au gérant (voir SecurityConfig).
 * Un retour d'une autre boutique est introuvable (404).
 */
@Controller
public class RetourController {
    @Autowired private RetourVenteService retourVenteService;
    @Autowired private RetourAchatService retourAchatService;

    /** Tous les retours, ou seulement ceux de vente ({@code type=vente}) ou d'achat ({@code type=achat}). */
    @GetMapping("/retours")
    public String liste(@RequestParam(required = false) String type, Model model) {
        TypeRetour filtre = "vente".equals(type) ? TypeRetour.VENTE : "achat".equals(type) ? TypeRetour.ACHAT : null;
        List<Retour> retours = new ArrayList<>();
        if (filtre != TypeRetour.ACHAT) retours.addAll(retourVenteService.lister());
        if (filtre != TypeRetour.VENTE) retours.addAll(retourAchatService.lister());
        retours.sort(Comparator.comparing(Retour::getDateRetour).thenComparing(Retour::getId).reversed());
        model.addAttribute("retours", retours);
        model.addAttribute("filtreType", filtre == null ? "tous" : filtre == TypeRetour.VENTE ? "vente" : "achat");
        model.addAttribute("view", "retours/liste");
        return "dashboard";
    }

    @GetMapping("/retours/{id}/imprimer")
    public String imprimer(@PathVariable Long id, Model model) {
        // Le service qui trouve le retour est celui de son type ; l'autre répond 404.
        try {
            model.addAttribute("retour", retourVenteService.trouver(id));
            return "retours/impression";
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().value() != HttpStatus.NOT_FOUND.value()) throw e;
        }
        model.addAttribute("retour", retourAchatService.trouver(id));
        return "retours/impression_achat";
    }
}
