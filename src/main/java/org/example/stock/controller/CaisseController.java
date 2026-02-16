package org.example.stock.controller;

import org.example.stock.repository.MouvementCaisseRepository;
import org.example.stock.service.CaisseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/caisse")
public class CaisseController {

    @Autowired
    private CaisseService caisseService;

    @Autowired
    private MouvementCaisseRepository mouvementRepo;

    @GetMapping("/journal")
    public String afficherJournal(Model model) {
        model.addAttribute("mouvements", mouvementRepo.findAllByOrderByDateMouvementDesc());
        model.addAttribute("soldeTotal", caisseService.getSoldeActuel());
        model.addAttribute("view", "caisse/journal");
        return "dashboard";
    }
}
