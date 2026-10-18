package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.example.stock.service.InitialisationBoutiqueService;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Clôture irréversible du démarrage de la seule boutique du gérant connecté. */
@Controller
@RequestMapping("/boutique/demarrage")
public class DemarrageBoutiqueController {
    private final InitialisationBoutiqueService initialisation;

    public DemarrageBoutiqueController(InitialisationBoutiqueService initialisation) {
        this.initialisation = initialisation;
    }

    @PostMapping("/terminer")
    public String terminer(HttpServletRequest request, RedirectAttributes redirect) {
        for (var parametre : request.getParameterMap().entrySet()) {
            if (!parametre.getKey().equals("_csrf") || parametre.getValue().length != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champ de formulaire non autorisé");
            }
        }
        try {
            initialisation.terminer();
            redirect.addFlashAttribute("success", "Le démarrage est terminé. Les quantités évoluent désormais uniquement avec les achats et les ventes.");
        } catch (ConcurrencyFailureException e) {
            redirect.addFlashAttribute("error", "Une autre opération est en cours. Rechargez les paramètres et vérifiez l’état du démarrage.");
        }
        return "redirect:/boutique/parametres";
    }
}
