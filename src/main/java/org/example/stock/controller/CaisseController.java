package org.example.stock.controller;

import org.example.stock.repository.MouvementCaisseRepository;
import org.example.stock.service.CaisseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.LocalDate;

@Controller
@RequestMapping("/caisse")
public class CaisseController {

    @Autowired
    private CaisseService caisseService;

    @Autowired
    private MouvementCaisseRepository mouvementRepo;

    @GetMapping("/journal")
    public String afficherJournal(Model model) {
        LocalDate aujourdHui = LocalDate.now();

        model.addAttribute("mouvements", mouvementRepo.findAllByOrderByDateMouvementDesc());
        model.addAttribute("mouvementsDuJour", caisseService.getMouvementsDuJour(aujourdHui));
        model.addAttribute("soldeTotal", caisseService.getSoldeActuel());
        model.addAttribute("soldeOuverture", caisseService.getSoldeOuverture(aujourdHui));
        model.addAttribute("entreesJour", caisseService.getEntreesDuJour(aujourdHui));
        model.addAttribute("sortiesJour", caisseService.getSortiesDuJour(aujourdHui));
        model.addAttribute("bilanJour", caisseService.getNetDuJour(aujourdHui));
        model.addAttribute("soldeCloture", caisseService.getSoldeCloture(aujourdHui));
        model.addAttribute("view", "caisse/journal");
        return "dashboard";
    }
}
