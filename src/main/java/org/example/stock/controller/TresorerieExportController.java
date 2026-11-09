package org.example.stock.controller;

import org.example.stock.enums.CompteTresorerie;
import org.example.stock.export.LivraisonExport;
import org.example.stock.export.ParametresListe;
import org.example.stock.service.TresorerieExportService;
import org.example.stock.service.TresorerieExportService.FiltreJournal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exports Excel et PDF du journal de trésorerie. Réservés au gérant (règle de sécurité « /caisse/** ») : le caissier reçoit 403.
 * Filtres : {@code compte=CAISSE|ORANGE_MONEY|BANQUE}, {@code source=<libellé affiché>}, {@code sens=entree|sortie|neutre|inconnu},
 * {@code du=} et {@code au=} (jours inclus) et {@code q=}. Trop volumineux : retour au journal complet.
 */
@Controller
@RequestMapping("/caisse/export")
public class TresorerieExportController {
    private static final Set<String> COMPTES = Arrays.stream(CompteTresorerie.values()).map(Enum::name).collect(Collectors.toSet());

    private final TresorerieExportService export;
    private final LivraisonExport livraison;

    public TresorerieExportController(TresorerieExportService export, LivraisonExport livraison) {
        this.export = export; this.livraison = livraison;
    }

    @GetMapping("/excel")
    public Object excel(@RequestParam(name = "compte", required = false) List<String> compte, @RequestParam(name = "source", required = false) List<String> source,
                        @RequestParam(name = "sens", required = false) List<String> sens, @RequestParam(name = "du", required = false) List<String> du,
                        @RequestParam(name = "au", required = false) List<String> au, @RequestParam(name = "q", required = false) List<String> q,
                        RedirectAttributes redirection) {
        FiltreJournal filtre = filtre(compte, source, sens, du, au);
        String recherche = ParametresListe.recherche(q);
        return livraison.excel("tresorerie", filtre.du(), filtre.au(), "/caisse/journal", Map.of(), redirection, () -> export.preparer(filtre, recherche));
    }

    @GetMapping("/pdf")
    public Object pdf(@RequestParam(name = "compte", required = false) List<String> compte, @RequestParam(name = "source", required = false) List<String> source,
                      @RequestParam(name = "sens", required = false) List<String> sens, @RequestParam(name = "du", required = false) List<String> du,
                      @RequestParam(name = "au", required = false) List<String> au, @RequestParam(name = "q", required = false) List<String> q,
                      RedirectAttributes redirection) {
        FiltreJournal filtre = filtre(compte, source, sens, du, au);
        String recherche = ParametresListe.recherche(q);
        return livraison.pdf("tresorerie", filtre.du(), filtre.au(), "/caisse/journal", Map.of(), redirection, () -> export.preparer(filtre, recherche));
    }

    private static FiltreJournal filtre(List<String> compte, List<String> source, List<String> sens, List<String> du, List<String> au) {
        String nomCompte = ParametresListe.choix("compte", compte, COMPTES);
        LocalDate debut = ParametresListe.date(du), fin = ParametresListe.date(au);
        ParametresListe.periodeCoherente(debut, fin);
        return new FiltreJournal(nomCompte == null ? null : CompteTresorerie.valueOf(nomCompte), ParametresListe.unique("source", source),
                ParametresListe.choix("sens", sens, TresorerieExportService.SENS), debut, fin);
    }
}
