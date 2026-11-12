package org.example.stock.controller;

import org.example.stock.repository.UtilisateurRepository;
import org.example.stock.repository.VenteRepository;
import org.example.stock.service.CaisseService;
import org.example.stock.service.IncoherenceAvanceException;
import org.example.stock.service.BoutiqueService;
import org.example.stock.service.EssaiBoutiqueService;
import org.example.stock.service.EmpruntService;
import org.example.stock.service.TableauDeBordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.core.Authentication;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Controller
public class HomeController {
    private static final Logger LOG = LoggerFactory.getLogger(HomeController.class);


    @Autowired UtilisateurRepository utilisateurRepository;
    @Autowired CaisseService caisseService;
    @Autowired VenteRepository venteRepository;
    @Autowired BoutiqueService boutiqueService;
    @Autowired EssaiBoutiqueService essais;
    @Autowired TableauDeBordService tableauDeBord;
    @Autowired EmpruntService emprunts;

    private static final DateTimeFormatter MOIS_EN_COURS = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRENCH);

    @GetMapping("/accueil")
    public String accueil(Model model) {
        model.addAttribute("dureeEssaiJours", essais.dureeEssaiJours());
        return "accueil";
    }

    @GetMapping("/plateforme")
    public String plateforme() {
        return "redirect:/plateforme/boutiques";
    }

    @GetMapping("/")
    public String dashboard(@RequestParam(name = "annee", required = false) String annee,
                            Model model, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) return accueil(model);
        if (authentication.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                || "ROLE_CAISSIER".equals(a.getAuthority()))) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        }
        Long boutiqueId = boutiqueService.idBoutiqueCourante();
        // Même convention que la caisse : la journée locale du serveur, en bornes [début, fin).
        LocalDate aujourdHui = LocalDate.now();
        LocalDateTime debutJournee = aujourdHui.atTime(LocalTime.MIN);
        model.addAttribute("view", "dashboard");
        // Chiffres du jour sans marge, partagés avec le caissier.
        model.addAttribute("chiffreAffairesJour", tableauDeBord.chiffreAffairesDuJour(aujourdHui));
        model.addAttribute("ventesDuJour", venteRepository.calculerTotalVentesDepuis(debutJournee, boutiqueId));
        model.addAttribute("produitsASurveiller", tableauDeBord.produitsASurveiller());
        if (authentication.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            // Le caissier ne reçoit ni marge, ni dépense, ni valeur du stock, ni évolution ; « annee » est ignoré.
            return "dashboard";
        }

        model.addAttribute("indicateursJour", tableauDeBord.indicateursDuJour(aujourdHui));
        model.addAttribute("indicateursMois", tableauDeBord.indicateursDuMois(aujourdHui));
        model.addAttribute("libelleMois", MOIS_EN_COURS.format(aujourdHui));
        model.addAttribute("valeurStock", tableauDeBord.valeurStock());
        // Créances et dettes : calculées pour le gérant seulement.
        // Une incohérence de solde ne doit pas priver le gérant du reste du tableau de bord.
        try {
            model.addAttribute("creancesDettes", tableauDeBord.creancesEtDettes());
        } catch (IncoherenceAvanceException e) {
            LOG.error("Créances et dettes incohérentes pour la boutique courante", e);
            model.addAttribute("creancesDettesIncoherentes", true);
        }
        // Emprunts et prêts en retard : alerte du gérant seulement.
        model.addAttribute("retardsEmprunts", emprunts.retards(5));
        TableauDeBordService.AnneeChoisie choix = TableauDeBordService.choisirAnnee(annee, aujourdHui);
        model.addAttribute("anneeCorrigee", choix.corrigee());
        model.addAttribute("evolution", tableauDeBord.evolutionMensuelle(choix.annee(), aujourdHui));

        model.addAttribute("soldeCaisse", caisseService.getSoldeActuel());
        model.addAttribute("soldeOuverture", caisseService.getSoldeOuverture(aujourdHui));
        model.addAttribute("entreesJour", caisseService.getEntreesDuJour(aujourdHui));
        model.addAttribute("sortiesJour", caisseService.getSortiesDuJour(aujourdHui));
        model.addAttribute("bilanJour", caisseService.getNetDuJour(aujourdHui));
        model.addAttribute("caisseDuJour", caisseService.getCaisseDuJour(aujourdHui));
        model.addAttribute("soldeCloture", caisseService.getSoldeCloture(aujourdHui));
        return "dashboard";
    }
}
