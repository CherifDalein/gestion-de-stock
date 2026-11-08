package org.example.stock.controller;

import org.example.stock.service.TableauDeBordService;
import org.example.stock.service.TableauDeBordService.VentesParVendeur;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;

/**
 * État « Ventes par vendeur », réservé au gérant par la configuration de sécurité (/etats/** : ADMIN).
 * La boutique vient toujours du compte connecté ; seules les bornes « du » et « au » viennent du navigateur.
 */
@Controller
@RequestMapping("/etats/ventes-par-vendeur")
public class VentesParVendeurController {
    @Autowired private TableauDeBordService tableau;

    @GetMapping
    public String afficher(@RequestParam(name = "du", required = false) List<String> du,
                           @RequestParam(name = "au", required = false) List<String> au, Model model) {
        LocalDate aujourdHui = LocalDate.now();
        LocalDate debutMois = aujourdHui.withDayOfMonth(1);
        LocalDate debut = debutMois;
        LocalDate fin = debutMois.plusMonths(1).minusDays(1);
        boolean demande = du != null || au != null;
        LocalDate dateDu = demande ? date(du) : null;
        LocalDate dateAu = demande ? date(au) : null;
        if (demande && (dateDu == null || dateAu == null || dateAu.isBefore(dateDu))) {
            model.addAttribute("avertissementPeriode", "La période demandée est invalide : le mois en cours est affiché.");
        } else if (demande) {
            debut = dateDu;
            fin = dateAu;
        }
        VentesParVendeur etat = tableau.ventesParVendeur(debut, fin);
        model.addAttribute("etat", etat);
        model.addAttribute("du", debut);
        model.addAttribute("au", fin);
        model.addAttribute("genereLe", java.time.LocalDateTime.now());
        model.addAttribute("view", "etats/ventes-par-vendeur");
        return "dashboard";
    }

    /** Une seule date AAAA-MM-JJ valide, de 2000 à 9999 ; sinon vide. */
    private static LocalDate date(List<String> valeurs) {
        if (valeurs == null || valeurs.size() != 1 || !valeurs.getFirst().strip().matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        try {
            LocalDate date = LocalDate.parse(valeurs.getFirst().strip());
            return date.getYear() < TableauDeBordService.ANNEE_MINIMALE || date.getYear() > 9998 ? null : date;
        } catch (DateTimeException e) {
            return null;
        }
    }
}
