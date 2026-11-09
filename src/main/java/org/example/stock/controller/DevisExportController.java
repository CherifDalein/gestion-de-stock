package org.example.stock.controller;

import org.example.stock.enums.EtatDevis;
import org.example.stock.export.LivraisonExport;
import org.example.stock.export.ParametresListe;
import org.example.stock.service.DevisExportService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Exports Excel et PDF de la liste des devis : mêmes droits que /devis (gérant et caissier), filtre d'état {@code etat=} et recherche {@code q=}. */
@Controller
@RequestMapping("/devis/export")
public class DevisExportController {
    private static final Set<String> ETATS = Arrays.stream(EtatDevis.values()).map(EtatDevis::getCode).collect(Collectors.toSet());

    private final DevisExportService export;
    private final LivraisonExport livraison;

    public DevisExportController(DevisExportService export, LivraisonExport livraison) {
        this.export = export; this.livraison = livraison;
    }

    @GetMapping("/excel")
    public Object excel(@RequestParam(name = "etat", required = false) List<String> etat, @RequestParam(name = "q", required = false) List<String> q,
                        RedirectAttributes redirection) {
        EtatDevis filtre = EtatDevis.depuisCode(ParametresListe.choix("etat", etat, ETATS));
        String recherche = ParametresListe.recherche(q);
        return livraison.excel("devis", "/devis", retour(filtre), redirection, () -> export.preparer(filtre, recherche));
    }

    @GetMapping("/pdf")
    public Object pdf(@RequestParam(name = "etat", required = false) List<String> etat, @RequestParam(name = "q", required = false) List<String> q,
                      RedirectAttributes redirection) {
        EtatDevis filtre = EtatDevis.depuisCode(ParametresListe.choix("etat", etat, ETATS));
        String recherche = ParametresListe.recherche(q);
        return livraison.pdf("devis", "/devis", retour(filtre), redirection, () -> export.preparer(filtre, recherche));
    }

    private static Map<String, Object> retour(EtatDevis filtre) {
        Map<String, Object> filtres = new LinkedHashMap<>();
        if (filtre != null) filtres.put("etat", filtre.getCode());
        return filtres;
    }
}
