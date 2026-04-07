package org.example.stock.controller;

import org.example.stock.repository.UtilisateurRepository;
import org.example.stock.repository.VenteRepository;
import org.example.stock.service.CaisseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Controller
public class HomeController {

    @Autowired UtilisateurRepository utilisateurRepository;
    @Autowired CaisseService caisseService;
    @Autowired VenteRepository venteRepository;

    @GetMapping("/")
    public String dashboard(Model model) {
        LocalDate aujourdHui = LocalDate.now();
        LocalDateTime debutJournee = aujourdHui.atTime(LocalTime.MIN);

        model.addAttribute("soldeCaisse", caisseService.getSoldeActuel());
        model.addAttribute("ventesDuJour", venteRepository.calculerTotalVentesDepuis(debutJournee));
        model.addAttribute("soldeOuverture", caisseService.getSoldeOuverture(aujourdHui));
        model.addAttribute("entreesJour", caisseService.getEntreesDuJour(aujourdHui));
        model.addAttribute("sortiesJour", caisseService.getSortiesDuJour(aujourdHui));
        model.addAttribute("bilanJour", caisseService.getNetDuJour(aujourdHui));
        model.addAttribute("caisseDuJour", caisseService.getCaisseDuJour(aujourdHui));
        model.addAttribute("soldeCloture", caisseService.getSoldeCloture(aujourdHui));
        model.addAttribute("view", "dashboard");
        return "dashboard";
    }
}
