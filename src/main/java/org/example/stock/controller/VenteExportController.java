package org.example.stock.controller;

import org.example.stock.export.*;
import org.example.stock.model.Utilisateur;
import org.example.stock.service.EcheanceService;
import org.example.stock.service.FiltreVentes;
import org.example.stock.service.VenteExportService;
import org.example.stock.service.VenteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/**
 * Exports Excel et PDF de la liste des ventes. Pilote du socle d'export (docs/lot-4.md) : lecture seule (GET), mêmes filtres
 * et mêmes droits que /ventes (gérant et caissier, règle de sécurité « /ventes/** »), boutique du compte connecté.
 */
@Controller
@RequestMapping("/ventes/export")
public class VenteExportController {
    @Autowired private VenteExportService export;
    @Autowired private VenteService venteService;
    @Autowired private ExportExcelService excel;
    @Autowired private DocumentPdfService pdf;
    @Autowired private ContexteDocumentService contextes;
    @Autowired private EcheanceService echeances;

    @GetMapping("/excel")
    public Object excel(@RequestParam(required = false) String filtre, @RequestParam(name = "vendeur", required = false) List<String> vendeur,
                        @RequestParam(name = "du", required = false) List<String> du, @RequestParam(name = "au", required = false) List<String> au,
                        @RequestParam(name = "q", required = false) List<String> q, RedirectAttributes redirection) {
        return produire(filtre, vendeur, du, au, q, redirection,
                (t) -> TelechargementExport.excel(nom(t.filtres, "xlsx"), excel.ecrire(t.tableau, t.contexte)));
    }

    @GetMapping("/pdf")
    public Object pdf(@RequestParam(required = false) String filtre, @RequestParam(name = "vendeur", required = false) List<String> vendeur,
                      @RequestParam(name = "du", required = false) List<String> du, @RequestParam(name = "au", required = false) List<String> au,
                      @RequestParam(name = "q", required = false) List<String> q, RedirectAttributes redirection) {
        // Onze ou douze colonnes : A4 paysage.
        return produire(filtre, vendeur, du, au, q, redirection,
                (t) -> TelechargementExport.pdf(nom(t.filtres, "pdf"), pdf.rendreTableau(t.tableau, t.contexte, FormatPapier.A4_PAYSAGE)));
    }

    private record Pret(TableauExport tableau, ContexteDocument contexte, FiltreVentes filtres) { }

    private Object produire(String filtre, List<String> vendeur, List<String> du, List<String> au, List<String> q,
                            RedirectAttributes redirection, Function<Pret, Object> fabrique) {
        FiltreVentes filtres = lireFiltres(filtre, vendeur, du, au);
        String recherche = lireRecherche(q);
        try {
            return fabrique.apply(new Pret(export.preparer(filtres, recherche), contextes.courant(), filtres));
        } catch (ExportTropVolumineuxException e) {
            // Retour à la liste avec le message : les mêmes filtres restent appliqués.
            redirection.addFlashAttribute("error", e.getMessage());
            UriComponentsBuilder retour = UriComponentsBuilder.fromPath("/ventes");
            if (filtres.echues()) retour.queryParam("filtre", "echues");
            if (filtres.vendeurId() != null) retour.queryParam("vendeur", filtres.vendeurId());
            if (filtres.du() != null) retour.queryParam("du", filtres.du());
            if (filtres.au() != null) retour.queryParam("au", filtres.au());
            return "redirect:" + retour.build().toUriString();
        }
    }

    private String nom(FiltreVentes f, String extension) {
        LocalDate du = f.du(), au = f.au();
        return du == null && au == null ? TelechargementExport.nomFichier("ventes", echeances.aujourdHui(), null, extension)
                : TelechargementExport.nomFichier("ventes", du, au, extension);
    }

    /** Mêmes règles que l'écran, mais un paramètre invalide est refusé (400) : un export ne doit jamais porter sur une autre liste. */
    private FiltreVentes lireFiltres(String filtre, List<String> vendeur, List<String> du, List<String> au) {
        LocalDate debut, fin;
        try {
            debut = FiltreVentes.analyserDate(du);
            fin = FiltreVentes.analyserDate(au);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Période invalide");
        }
        if (!FiltreVentes.periodeCoherente(debut, fin)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Période invalide");
        Long vendeurId = null;
        // Comme à l'écran, le filtre par vendeur est réservé au gérant : pour un caissier le paramètre est ignoré.
        if (DroitsExport.estGerant() && vendeur != null && !(vendeur.size() == 1 && vendeur.getFirst().isBlank())) {
            if (vendeur.size() != 1 || !vendeur.getFirst().strip().matches("\\d{1,18}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vendeur invalide");
            long id = Long.parseLong(vendeur.getFirst().strip());
            if (venteService.listerVendeursPossibles().stream().map(Utilisateur::getId).noneMatch(i -> i == id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vendeur invalide");
            }
            vendeurId = id;
        }
        return new FiltreVentes("echues".equals(filtre), vendeurId, debut, fin);
    }

    private static String lireRecherche(List<String> q) {
        if (q == null || q.isEmpty()) return "";
        if (q.size() != 1 || q.getFirst().length() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recherche invalide");
        return q.getFirst().strip();
    }
}
