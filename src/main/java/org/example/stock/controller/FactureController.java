package org.example.stock.controller;

import org.example.stock.model.Vente;
import org.example.stock.repository.VenteRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Controller
@RequestMapping("/factures")
public class FactureController {

    @Autowired
    private VenteRepository venteRepository;

    @GetMapping("/vente/{id}")
    public String voirFactureVente(@PathVariable Long id, Model model) {
        Vente vente = venteRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Vente non trouvée"));

        model.addAttribute("vente", vente);
        model.addAttribute("view", "factures/template_vente");
        return "dashboard";
    }

    @GetMapping("/liste")
    public String listeFactures(
            @RequestParam(required = false) String periode,
            Model model) {

        List<Vente> ventes;
        LocalDateTime debut = LocalDateTime.now();

        // Logique de filtrage temporel
        if ("jour".equals(periode)) {
            ventes = venteRepository.findByDateVenteAfter(debut.with(LocalTime.MIN));
        } else if ("semaine".equals(periode)) {
            ventes = venteRepository.findByDateVenteAfter(debut.minusWeeks(1));
        } else if ("mois".equals(periode)) {
            ventes = venteRepository.findByDateVenteAfter(debut.withDayOfMonth(1).with(LocalTime.MIN));
        } else {
            ventes = venteRepository.findAllByOrderByDateVenteDesc();
        }

        model.addAttribute("ventes", ventes);
        model.addAttribute("view", "factures/liste");
        return "dashboard";
    }

    @GetMapping("/client/{clientId}")
    public String facturesParClient(
            @PathVariable Long clientId,
            @RequestParam(required = false) String periode,
            Model model) {

        List<Vente> ventes;
        LocalDateTime debut = LocalDateTime.now();

        // Logique de filtrage identique mais limitée au client
        if ("jour".equals(periode)) {
            ventes = venteRepository.findByClientIdAndDateVenteAfterOrderByDateVenteDesc(clientId, debut.with(LocalTime.MIN));
        } else if ("semaine".equals(periode)) {
            ventes = venteRepository.findByClientIdAndDateVenteAfterOrderByDateVenteDesc(clientId, debut.minusWeeks(1));
        } else if ("mois".equals(periode)) {
            ventes = venteRepository.findByClientIdAndDateVenteAfterOrderByDateVenteDesc(clientId, debut.withDayOfMonth(1).with(LocalTime.MIN));
        } else {
            ventes = venteRepository.findByClientIdOrderByDateVenteDesc(clientId);
        }

        // On récupère les infos du client pour l'affichage (via la 1ere vente ou un repo client)
        model.addAttribute("ventes", ventes);
        model.addAttribute("clientId", clientId);
        model.addAttribute("view", "factures/liste_client");
        return "dashboard";
    }
}
