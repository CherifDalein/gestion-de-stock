package org.example.stock.controller;

import org.example.stock.repository.UtilisateurRepository;
import org.example.stock.repository.VenteRepository;
import org.example.stock.service.CaisseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Controller
public class HomeController {

    @Autowired UtilisateurRepository utilisateurRepository;
    @Autowired CaisseService caisseService;
    @Autowired VenteRepository venteRepository;

//    @GetMapping("/")
//    public String dashboard(Model model, Authentication authentication) {
//        model.addAttribute("pageTitle", "Tableau de Bord - Stock Pro");
//        return "dashboard";
//    }

    @GetMapping("/")
    public String dashboard(Model model) {
        LocalDateTime debutJournee = LocalDateTime.now().with(LocalTime.MIN);

        // On récupère le solde de la caisse et le total des ventes du jour
        Double soldeCaisse = caisseService.getSoldeActuel();
        Double ventesJour = venteRepository.calculerTotalVentesDepuis(debutJournee);

        model.addAttribute("soldeCaisse", caisseService.getSoldeActuel());
        model.addAttribute("ventesDuJour", ventesJour);
        model.addAttribute("view", "dashboard");
        return "dashboard";
    }

}
