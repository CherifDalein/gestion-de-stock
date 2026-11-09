package org.example.stock.controller;

import org.example.stock.export.LivraisonExport;
import org.example.stock.export.ParametresListe;
import org.example.stock.service.PartenaireExportService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Exports Excel et PDF des répertoires de clients et de fournisseurs. Les droits sont ceux de l'écran, posés par SecurityConfig sur le
 * préfixe de la route : « /clients/** » (gérant et caissier), « /fournisseurs/** » (gérant seulement). Filtres : {@code contact=}
 * ({@code telephone}, {@code email}, {@code sans-contact}) et {@code q=}.
 */
@Controller
public class PartenaireExportController {
    private final PartenaireExportService export;
    private final LivraisonExport livraison;

    public PartenaireExportController(PartenaireExportService export, LivraisonExport livraison) {
        this.export = export; this.livraison = livraison;
    }

    @GetMapping("/clients/export/excel")
    public Object clientsExcel(@RequestParam(name = "contact", required = false) List<String> contact, @RequestParam(name = "q", required = false) List<String> q,
                               RedirectAttributes redirection) {
        return produire(true, "clients", "/clients", contact, q, redirection, export::preparerClients);
    }

    @GetMapping("/clients/export/pdf")
    public Object clientsPdf(@RequestParam(name = "contact", required = false) List<String> contact, @RequestParam(name = "q", required = false) List<String> q,
                             RedirectAttributes redirection) {
        return produire(false, "clients", "/clients", contact, q, redirection, export::preparerClients);
    }

    @GetMapping("/fournisseurs/export/excel")
    public Object fournisseursExcel(@RequestParam(name = "contact", required = false) List<String> contact, @RequestParam(name = "q", required = false) List<String> q,
                                    RedirectAttributes redirection) {
        return produire(true, "fournisseurs", "/fournisseurs", contact, q, redirection, export::preparerFournisseurs);
    }

    @GetMapping("/fournisseurs/export/pdf")
    public Object fournisseursPdf(@RequestParam(name = "contact", required = false) List<String> contact, @RequestParam(name = "q", required = false) List<String> q,
                                  RedirectAttributes redirection) {
        return produire(false, "fournisseurs", "/fournisseurs", contact, q, redirection, export::preparerFournisseurs);
    }

    private Object produire(boolean excel, String base, String liste, List<String> contactBrut, List<String> q, RedirectAttributes redirection,
                            BiFunction<String, String, org.example.stock.export.TableauExport> preparer) {
        String contact = ParametresListe.choix("contact", contactBrut, PartenaireExportService.CONTACTS);
        String recherche = ParametresListe.recherche(q);
        Map<String, Object> retour = new LinkedHashMap<>();
        if (contact != null) retour.put("contact", contact);
        return excel ? livraison.excel(base, liste, retour, redirection, () -> preparer.apply(contact, recherche))
                : livraison.pdf(base, liste, retour, redirection, () -> preparer.apply(contact, recherche));
    }
}
