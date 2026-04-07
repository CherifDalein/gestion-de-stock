package org.example.stock.controller;

import org.example.stock.model.Client;
import org.example.stock.model.Vente;
import org.example.stock.repository.ClientRepository;
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

    @Autowired
    private ClientRepository clientRepository;

    @GetMapping("/vente/{id}")
    public String voirFactureVente(@PathVariable Long id, Model model) {
        Vente vente = venteRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Vente non trouvee"));

        model.addAttribute("vente", vente);
        model.addAttribute("view", "factures/template_vente");
        return "dashboard";
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

        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new RuntimeException("Client non trouve"));

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

        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new RuntimeException("Client non trouve"));

        List<Vente> ventes = filtrerVentesClientParPeriode(clientId, periode);

        model.addAttribute("client", client);
        model.addAttribute("clientId", clientId);
        model.addAttribute("ventes", ventes);
        model.addAttribute("view", "factures/template_client_cumule");
        return "dashboard";
    }

    private List<Vente> filtrerVentesParPeriode(String periode) {
        LocalDateTime debut = calculerDebutPeriode(periode);
        if (debut == null) {
            return venteRepository.findAllByOrderByDateVenteDesc();
        }
        return venteRepository.findByDateVenteAfter(debut);
    }

    private List<Vente> filtrerVentesClientParPeriode(Long clientId, String periode) {
        LocalDateTime debut = calculerDebutPeriode(periode);
        if (debut == null) {
            return venteRepository.findByClientIdOrderByDateVenteDesc(clientId);
        }
        return venteRepository.findByClientIdAndDateVenteAfterOrderByDateVenteDesc(clientId, debut);
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
