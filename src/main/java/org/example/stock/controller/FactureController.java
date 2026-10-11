package org.example.stock.controller;

import org.example.stock.model.Achat;
import org.example.stock.service.BoutiqueService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.example.stock.model.Client;
import org.example.stock.model.Fournisseur;
import org.example.stock.model.Vente;
import org.example.stock.repository.AchatRepository;
import org.example.stock.repository.ClientRepository;
import org.example.stock.repository.FournisseurRepository;
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

    @Autowired private BoutiqueService boutiqueService;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private AchatRepository achatRepository;

    @Autowired
    private FournisseurRepository fournisseurRepository;

    @GetMapping("/vente/{id}")
    public String voirFactureVente(@PathVariable Long id, Model model) {
        Vente vente = venteRepository.findByIdAndBoutiqueId(id, boutiqueService.idBoutiqueCourante())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Élément introuvable"));

        model.addAttribute("vente", vente);
        return "factures/template_vente";
    }

    @GetMapping("/achat/{id}")
    public String voirFactureAchat(@PathVariable Long id, Model model) {
        Achat achat = achatRepository.findByIdAndBoutiqueId(id, boutiqueService.idBoutiqueCourante())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Élément introuvable"));

        model.addAttribute("achat", achat);
        return "factures/template_achat";
    }

    @GetMapping("/liste")
    public String listeFactures(@RequestParam(required = false) String periode, Model model) {
        List<Vente> ventes = filtrerVentesParPeriode(periode);
        model.addAttribute("ventes", ventes);
        model.addAttribute("view", "factures/liste");
        return "dashboard";
    }

    @GetMapping("/client/{clientId}")
    public String facturesParClient(
            @PathVariable Long clientId,
            @RequestParam(required = false) String periode,
            Model model) {

        Client client = clientRepository.findByIdAndBoutiqueId(clientId, boutiqueService.idBoutiqueCourante())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Élément introuvable"));

        List<Vente> ventes = filtrerVentesClientParPeriode(clientId, periode);

        model.addAttribute("ventes", ventes);
        model.addAttribute("client", client);
        model.addAttribute("clientId", clientId);
        model.addAttribute("view", "factures/liste_client");
        return "dashboard";
    }

    @GetMapping("/client/{clientId}/cumule")
    public String factureCumuleeClient(
            @PathVariable Long clientId,
            @RequestParam(required = false) String periode,
            Model model) {

        Client client = clientRepository.findByIdAndBoutiqueId(clientId, boutiqueService.idBoutiqueCourante())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Élément introuvable"));

        List<Vente> ventes = filtrerVentesClientParPeriode(clientId, periode);

        model.addAttribute("client", client);
        model.addAttribute("clientId", clientId);
        model.addAttribute("ventes", ventes);
        return "factures/template_client_cumule";
    }

    @GetMapping("/fournisseur/{fournisseurId}")
    public String achatsParFournisseur(
            @PathVariable Long fournisseurId,
            @RequestParam(required = false) String periode,
            Model model) {

        Fournisseur fournisseur = fournisseurRepository.findByIdAndBoutiqueId(fournisseurId, boutiqueService.idBoutiqueCourante())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Élément introuvable"));

        List<Achat> achats = filtrerAchatsFournisseurParPeriode(fournisseurId, periode);

        model.addAttribute("achats", achats);
        model.addAttribute("fournisseur", fournisseur);
        model.addAttribute("fournisseurId", fournisseurId);
        model.addAttribute("view", "factures/liste_fournisseur");
        return "dashboard";
    }

    @GetMapping("/fournisseur/{fournisseurId}/cumule")
    public String factureCumuleeFournisseur(
            @PathVariable Long fournisseurId,
            @RequestParam(required = false) String periode,
            Model model) {

        Fournisseur fournisseur = fournisseurRepository.findByIdAndBoutiqueId(fournisseurId, boutiqueService.idBoutiqueCourante())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Élément introuvable"));

        List<Achat> achats = filtrerAchatsFournisseurParPeriode(fournisseurId, periode);

        model.addAttribute("fournisseur", fournisseur);
        model.addAttribute("fournisseurId", fournisseurId);
        model.addAttribute("achats", achats);
        return "factures/template_fournisseur_cumule";
    }

    private List<Vente> filtrerVentesParPeriode(String periode) {
        LocalDateTime debut = calculerDebutPeriode(periode);
        if (debut == null) {
            return venteRepository.findByBoutiqueIdOrderByDateVenteDesc(boutiqueService.idBoutiqueCourante());
        }
        return venteRepository.findByDateVenteAfterAndBoutiqueId(debut, boutiqueService.idBoutiqueCourante());
    }

    private List<Vente> filtrerVentesClientParPeriode(Long clientId, String periode) {
        LocalDateTime debut = calculerDebutPeriode(periode);
        if (debut == null) {
            return venteRepository.findByClientIdAndBoutiqueIdOrderByDateVenteDesc(clientId, boutiqueService.idBoutiqueCourante());
        }
        return venteRepository.findByClientIdAndDateVenteAfterAndBoutiqueIdOrderByDateVenteDesc(clientId, debut, boutiqueService.idBoutiqueCourante());
    }

    private List<Achat> filtrerAchatsFournisseurParPeriode(Long fournisseurId, String periode) {
        LocalDateTime debut = calculerDebutPeriode(periode);
        if (debut == null) {
            return achatRepository.findByFournisseurIdAndBoutiqueIdOrderByDateAchatDesc(fournisseurId, boutiqueService.idBoutiqueCourante());
        }
        return achatRepository.findByFournisseurIdAndDateAchatAfterAndBoutiqueIdOrderByDateAchatDesc(fournisseurId, debut, boutiqueService.idBoutiqueCourante());
    }

    private LocalDateTime calculerDebutPeriode(String periode) {
        LocalDateTime maintenant = LocalDateTime.now();

        if ("jour".equals(periode)) {
            return maintenant.with(LocalTime.MIN);
        }
        if ("semaine".equals(periode)) {
            return maintenant.minusWeeks(1);
        }
        if ("mois".equals(periode)) {
            return maintenant.withDayOfMonth(1).with(LocalTime.MIN);
        }
        if ("annee".equals(periode)) {
            return maintenant.withDayOfYear(1).with(LocalTime.MIN);
        }
        return null;
    }
}
