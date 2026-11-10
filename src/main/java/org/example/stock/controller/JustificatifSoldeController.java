package org.example.stock.controller;

import org.example.stock.export.ContexteDocument;
import org.example.stock.export.ContexteDocumentService;
import org.example.stock.export.TelechargementExport;
import org.example.stock.model.Client;
import org.example.stock.model.Fournisseur;
import org.example.stock.repository.ClientRepository;
import org.example.stock.repository.FournisseurRepository;
import org.example.stock.service.BoutiqueService;
import org.example.stock.service.DroitsJustificatifs;
import org.example.stock.service.EcheanceService;
import org.example.stock.service.IncoherenceAvanceException;
import org.example.stock.service.JustificatifSoldePdfService;
import org.example.stock.service.JustificatifSoldeService;
import org.example.stock.service.JustificatifSoldeService.JustificatifPartenaire;
import org.example.stock.service.PeriodeEtat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Justificatifs de solde (lot 4, tâche D) : PDF officiels de la boutique, réservés au gérant (la règle de sécurité {@code /etats/**} répond
 * 403 au caissier ; {@link JustificatifSoldeService} le revérifie). Lecture seule (GET), boutique du compte connecté, aucun numéro consommé.
 * Un paramètre invalide, répété ou absent donne 400 ; un client ou un fournisseur d'une autre boutique, 404.
 *
 * <ul>
 *   <li>{@code GET /etats/justificatifs} : la page des trois types ;</li>
 *   <li>{@code GET /etats/justificatifs/client/pdf?id=} et {@code /fournisseur/pdf?id=} : arrêtés à la date du jour ;</li>
 *   <li>{@code GET /etats/justificatifs/tresorerie/pdf[?date=AAAA-MM-JJ]} : arrêté à n'importe quel jour passé.</li>
 * </ul>
 */
@Controller
@RequestMapping("/etats/justificatifs")
public class JustificatifSoldeController {
    @Autowired private JustificatifSoldeService justificatifs;
    @Autowired private JustificatifSoldePdfService pdf;
    @Autowired private ContexteDocumentService contextes;
    @Autowired private ClientRepository clients;
    @Autowired private FournisseurRepository fournisseurs;
    @Autowired private BoutiqueService boutiques;
    @Autowired private EcheanceService echeances;

    /** Un partenaire de la liste déroulante de la page. */
    public record Choix(Long id, String nom) { }

    @GetMapping({"", "/"})
    public String page(Model model) {
        DroitsJustificatifs.exigerGerant();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        Comparator<Choix> parNom = Comparator.comparing((Choix c) -> c.nom().toLowerCase(Locale.ROOT)).thenComparing(Choix::id);
        List<Choix> listeClients = clients.findAllByBoutiqueId(boutiqueId).stream().map((Client c) -> new Choix(c.getId(), c.getNom())).sorted(parNom).toList();
        List<Choix> listeFournisseurs = fournisseurs.findAllByBoutiqueId(boutiqueId).stream().map((Fournisseur f) -> new Choix(f.getId(), f.getNom())).sorted(parNom).toList();
        model.addAttribute("clients", listeClients);
        model.addAttribute("fournisseurs", listeFournisseurs);
        model.addAttribute("aujourdHui", echeances.aujourdHui());
        model.addAttribute("view", "etats/justificatifs");
        return "dashboard";
    }

    @GetMapping("/client/pdf")
    public Object client(@RequestParam(name = "id", required = false) List<String> id, RedirectAttributes redirection) {
        Long clientId = identifiant(id);
        return partenaire(() -> justificatifs.client(clientId), redirection);
    }

    @GetMapping("/fournisseur/pdf")
    public Object fournisseur(@RequestParam(name = "id", required = false) List<String> id, RedirectAttributes redirection) {
        Long fournisseurId = identifiant(id);
        return partenaire(() -> justificatifs.fournisseur(fournisseurId), redirection);
    }

    @GetMapping("/tresorerie/pdf")
    public ResponseEntity<byte[]> tresorerie(@RequestParam(name = "date", required = false) List<String> date) {
        DroitsJustificatifs.exigerGerant();
        LocalDate arrete = null;
        if (date != null) {
            arrete = lireDate(date);
            if (arrete == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date d'arrêté invalide");
        }
        JustificatifSoldeService.JustificatifTresorerie j;
        try {
            j = justificatifs.tresorerie(arrete);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        ContexteDocument contexte = contextes.courant();
        return TelechargementExport.pdf(j.numero() + ".pdf", pdf.rendre(j, contexte));
    }

    private Object partenaire(java.util.function.Supplier<JustificatifPartenaire> calcul, RedirectAttributes redirection) {
        JustificatifPartenaire j;
        try {
            j = calcul.get();
        } catch (IncoherenceAvanceException e) {
            redirection.addFlashAttribute("error", e.getMessage());
            return "redirect:/etats/justificatifs";
        }
        return TelechargementExport.pdf(j.numero() + ".pdf", pdf.rendre(j, contextes.courant()));
    }

    private static Long identifiant(List<String> valeurs) {
        if (valeurs == null || valeurs.size() != 1 || !valeurs.getFirst().strip().matches("\\d{1,18}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identifiant invalide");
        }
        return Long.valueOf(valeurs.getFirst().strip());
    }

    /** Une seule date AAAA-MM-JJ réelle, de 2000 à 9998 ; sinon vide. */
    private static LocalDate lireDate(List<String> valeurs) {
        if (valeurs.size() != 1 || !valeurs.getFirst().strip().matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        try {
            LocalDate date = LocalDate.parse(valeurs.getFirst().strip());
            return date.getYear() < PeriodeEtat.ANNEE_MINIMALE || date.getYear() > PeriodeEtat.ANNEE_MAXIMALE ? null : date;
        } catch (DateTimeException e) {
            return null;
        }
    }
}
