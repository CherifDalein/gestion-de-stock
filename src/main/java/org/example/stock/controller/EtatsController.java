package org.example.stock.controller;

import org.example.stock.export.*;
import org.example.stock.service.*;
import org.example.stock.service.EtatsPartenairesService.Cote;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

/**
 * Section « États » du gérant (lot 4, tâche C) : bilan de la période, créances, dettes, relevé d'un partenaire, stock, dépenses,
 * encaissements et export de « Ventes par vendeur » (la page de ce dernier reste dans {@link VentesParVendeurController}).
 *
 * <p>Tout est réservé au gérant par la règle de sécurité {@code /etats/**} (403 pour le caissier) ; les services répètent le
 * contrôle. Tout est en lecture seule (GET). La boutique vient toujours du compte connecté. À l'écran une période ou une date
 * invalide donne un avertissement et la valeur par défaut (mois en cours, jour) ; pour un export elle donne 400 : un fichier ne
 * porte jamais sur une autre période que celle demandée. La page, le PDF et l'Excel d'un état viennent du MÊME objet {@link Etat}.</p>
 */
@Controller
@RequestMapping("/etats")
public class EtatsController {
    @Autowired private BilanService bilan;
    @Autowired private EtatsPartenairesService partenaires;
    @Autowired private EtatsListesService listes;
    @Autowired private org.example.stock.service.FinancementsExportService financements;
    @Autowired private ExportExcelService excel;
    @Autowired private DocumentPdfService pdf;
    @Autowired private ContexteDocumentService contextes;
    @Autowired private EcheanceService echeances;

    /** Comment l'état se règle : une période, une date, ou rien. */
    private enum Forme { PERIODE, DATE, AUCUNE }

    /** Une carte de la page d'accueil des états. */
    public record Carte(String code, String titre, String description) { }

    private static final List<Carte> CARTES = List.of(
            new Carte("bilan", "Bilan de la période", "Chiffre d'affaires net, coût des ventes, bénéfices, dépenses, trésorerie par compte, créances, dettes et valeur du stock."),
            new Carte("creances", "État des créances", "Par client : factures non soldées, reste du solde de départ, avance disponible, jours de retard."),
            new Carte("dettes", "État des dettes", "Par fournisseur : factures d'achat non soldées, reste du solde de départ, avance, jours de retard."),
            new Carte("stock", "État du stock", "Quantité, coût moyen, valeur, seuil et état de chaque produit, avec les totaux par catégorie."),
            new Carte("depenses", "Liste des dépenses", "Les dépenses de la période par compte et par motif, avec les totaux."),
            new Carte("encaissements", "Liste des encaissements", "Tout l'argent reçu, par compte, avec sa source, son document et les annulations."),
            new Carte("ventes-par-vendeur", "Ventes par vendeur", "Chiffre d'affaires, bénéfice brut et panier moyen de chaque compte."),
            new Carte("emprunts-prets", "Emprunts et prêts", "Capital restant dû, intérêts payés, prochaine échéance et retards de chaque emprunt et prêt, avec les remboursements de la période."),
            new Carte("justificatifs", "Justificatifs de solde", "Documents PDF officiels attestant le solde d'un client, d'un fournisseur ou de la trésorerie, avec signatures."));

    @GetMapping({"", "/"})
    public String accueil(Model model) {
        model.addAttribute("cartes", CARTES);
        model.addAttribute("view", "etats/accueil");
        return "dashboard";
    }

    // ---- Bilan, dépenses, encaissements, ventes par vendeur (période) ------------------------------------------

    @GetMapping("/bilan")
    public String bilan(@RequestParam(name = "du", required = false) List<String> du, @RequestParam(name = "au", required = false) List<String> au, Model model) {
        return ecranPeriode("bilan", "Bilan de la période", "Résultat, trésorerie, créances et dettes, stock.", du, au, bilan::etat, model);
    }

    @GetMapping("/bilan/export/{format}")
    public Object bilanExport(@PathVariable String format, @RequestParam(name = "du", required = false) List<String> du,
                              @RequestParam(name = "au", required = false) List<String> au, RedirectAttributes redirection) {
        return exportPeriode("bilan", format, du, au, bilan::etat, redirection);
    }

    @GetMapping("/depenses")
    public String depenses(@RequestParam(name = "du", required = false) List<String> du, @RequestParam(name = "au", required = false) List<String> au, Model model) {
        return ecranPeriode("depenses", "Liste des dépenses", "Date, compte, motif, montant et auteur de chaque dépense, avec les totaux par compte.", du, au, listes::depenses, model);
    }

    @GetMapping("/depenses/export/{format}")
    public Object depensesExport(@PathVariable String format, @RequestParam(name = "du", required = false) List<String> du,
                                 @RequestParam(name = "au", required = false) List<String> au, RedirectAttributes redirection) {
        return exportPeriode("depenses", format, du, au, listes::depenses, redirection);
    }

    @GetMapping("/encaissements")
    public String encaissements(@RequestParam(name = "du", required = false) List<String> du, @RequestParam(name = "au", required = false) List<String> au, Model model) {
        return ecranPeriode("encaissements", "Liste des encaissements", "Tout l'argent reçu, par compte, avec sa source, son document et ses annulations.", du, au, listes::encaissements, model);
    }

    @GetMapping("/encaissements/export/{format}")
    public Object encaissementsExport(@PathVariable String format, @RequestParam(name = "du", required = false) List<String> du,
                                      @RequestParam(name = "au", required = false) List<String> au, RedirectAttributes redirection) {
        return exportPeriode("encaissements", format, du, au, listes::encaissements, redirection);
    }

    @GetMapping("/emprunts-prets")
    public String empruntsPrets(@RequestParam(name = "du", required = false) List<String> du, @RequestParam(name = "au", required = false) List<String> au, Model model) {
        return ecranPeriode("emprunts-prets", "Emprunts et prêts", "Situation de chaque emprunt et prêt, avec les remboursements de la période.", du, au, financements::etatEmpruntsEtPrets, model);
    }

    @GetMapping("/emprunts-prets/export/{format}")
    public Object empruntsPretsExport(@PathVariable String format, @RequestParam(name = "du", required = false) List<String> du,
                                      @RequestParam(name = "au", required = false) List<String> au, RedirectAttributes redirection) {
        return exportPeriode("emprunts-prets", format, du, au, financements::etatEmpruntsEtPrets, redirection);
    }

    /** Export seulement : la page « Ventes par vendeur » est celle de {@link VentesParVendeurController}. */
    @GetMapping("/ventes-par-vendeur/export/{format}")
    public Object ventesParVendeurExport(@PathVariable String format, @RequestParam(name = "du", required = false) List<String> du,
                                         @RequestParam(name = "au", required = false) List<String> au, RedirectAttributes redirection) {
        return exportPeriode("ventes-par-vendeur", format, du, au, listes::ventesParVendeur, redirection);
    }

    // ---- Créances et dettes (date de référence) -----------------------------------------------------------------

    @GetMapping("/creances")
    public String creances(@RequestParam(name = "date", required = false) List<String> date, Model model) {
        return ecranDate("creances", "État des créances", "Factures non soldées et soldes de départ des clients, par client.", date,
                jour -> partenaires.creancesOuDettes(Cote.CLIENT, jour), model);
    }

    @GetMapping("/creances/export/{format}")
    public Object creancesExport(@PathVariable String format, @RequestParam(name = "date", required = false) List<String> date, RedirectAttributes redirection) {
        return exportDate("creances", format, date, jour -> partenaires.creancesOuDettes(Cote.CLIENT, jour), redirection);
    }

    @GetMapping("/dettes")
    public String dettes(@RequestParam(name = "date", required = false) List<String> date, Model model) {
        return ecranDate("dettes", "État des dettes", "Factures d'achat non soldées et soldes de départ des fournisseurs, par fournisseur.", date,
                jour -> partenaires.creancesOuDettes(Cote.FOURNISSEUR, jour), model);
    }

    @GetMapping("/dettes/export/{format}")
    public Object dettesExport(@PathVariable String format, @RequestParam(name = "date", required = false) List<String> date, RedirectAttributes redirection) {
        return exportDate("dettes", format, date, jour -> partenaires.creancesOuDettes(Cote.FOURNISSEUR, jour), redirection);
    }

    // ---- Relevé d'un partenaire ---------------------------------------------------------------------------------

    @GetMapping("/releve/fournisseur/{id}")
    public String releveFournisseur(@PathVariable Long id, @RequestParam(name = "du", required = false) List<String> du,
                                    @RequestParam(name = "au", required = false) List<String> au, Model model) {
        return ecranPeriode("releve/fournisseur/" + id, "Relevé des dettes d'un fournisseur", "Factures, règlements, retours, avoirs et annulations, avec le solde progressif.",
                du, au, periode -> partenaires.releve(Cote.FOURNISSEUR, id, periode), model);
    }

    @GetMapping("/releve/fournisseur/{id}/export/{format}")
    public Object releveFournisseurExport(@PathVariable Long id, @PathVariable String format, @RequestParam(name = "du", required = false) List<String> du,
                                          @RequestParam(name = "au", required = false) List<String> au, RedirectAttributes redirection) {
        return exportPeriode("releve/fournisseur/" + id, format, du, au, periode -> partenaires.releve(Cote.FOURNISSEUR, id, periode), redirection);
    }

    @GetMapping("/releve/client/{id}")
    public String releveClient(@PathVariable Long id, @RequestParam(name = "du", required = false) List<String> du,
                               @RequestParam(name = "au", required = false) List<String> au, Model model) {
        return ecranPeriode("releve/client/" + id, "Relevé des créances d'un client", "Factures, règlements, retours, avoirs et annulations, avec le solde progressif.",
                du, au, periode -> partenaires.releve(Cote.CLIENT, id, periode), model);
    }

    @GetMapping("/releve/client/{id}/export/{format}")
    public Object releveClientExport(@PathVariable Long id, @PathVariable String format, @RequestParam(name = "du", required = false) List<String> du,
                                     @RequestParam(name = "au", required = false) List<String> au, RedirectAttributes redirection) {
        return exportPeriode("releve/client/" + id, format, du, au, periode -> partenaires.releve(Cote.CLIENT, id, periode), redirection);
    }

    // ---- État du stock (sans paramètre) -------------------------------------------------------------------------

    @GetMapping("/stock")
    public String stock(Model model) {
        return afficher("stock", "État du stock", "Quantité, coût moyen, valeur et état de chaque produit, avec les totaux par catégorie.", Forme.AUCUNE,
                null, null, null, listes::stock, model);
    }

    @GetMapping("/stock/export/{format}")
    public Object stockExport(@PathVariable String format, RedirectAttributes redirection) {
        return exporter("stock", format, null, null, listes::stock, redirection);
    }

    // ---- Mécanique commune --------------------------------------------------------------------------------------

    private String ecranPeriode(String chemin, String titre, String sousTitre, List<String> du, List<String> au,
                                java.util.function.Function<PeriodeEtat, Etat> calcul, Model model) {
        String avertissement = null;
        PeriodeEtat periode = PeriodeEtat.moisDe(echeances.aujourdHui());
        if (du != null || au != null) {
            PeriodeEtat demandee = lirePeriode(du, au);
            if (demandee == null) avertissement = "La période demandée est invalide : le mois en cours est affiché.";
            else periode = demandee;
        }
        PeriodeEtat retenue = periode;
        return afficher(chemin, titre, sousTitre, Forme.PERIODE, retenue, null, avertissement, () -> calcul.apply(retenue), model);
    }

    private String ecranDate(String chemin, String titre, String sousTitre, List<String> date,
                             java.util.function.Function<LocalDate, Etat> calcul, Model model) {
        String avertissement = null;
        LocalDate jour = echeances.aujourdHui();
        if (date != null) {
            LocalDate demandee = lireDate(date);
            if (demandee == null) avertissement = "La date demandée est invalide : la date du jour est utilisée.";
            else jour = demandee;
        }
        LocalDate retenue = jour;
        return afficher(chemin, titre, sousTitre, Forme.DATE, null, retenue, avertissement, () -> calcul.apply(retenue), model);
    }

    private String afficher(String chemin, String titre, String sousTitre, Forme forme, PeriodeEtat periode, LocalDate date,
                            String avertissement, Supplier<Etat> calcul, Model model) {
        try {
            Etat etat = calcul.get();
            model.addAttribute("etat", etat);
            model.addAttribute("vues", etat.sections().stream().map(DocumentPdfService::vue).toList());
        } catch (IncoherenceAvanceException | ExportTropVolumineuxException e) {
            model.addAttribute("erreur", e.getMessage());
        } catch (IllegalArgumentException e) {
            model.addAttribute("erreur", e.getMessage());
        }
        model.addAttribute("titre", titre);
        model.addAttribute("sousTitre", sousTitre);
        model.addAttribute("forme", forme.name());
        model.addAttribute("chemin", "/etats/" + chemin);
        model.addAttribute("du", periode == null ? null : periode.du());
        model.addAttribute("au", periode == null ? null : periode.au());
        model.addAttribute("dateEtat", date);
        model.addAttribute("avertissement", avertissement);
        model.addAttribute("requeteExport", forme == Forme.PERIODE ? "?du=" + periode.du() + "&au=" + periode.au()
                : (forme == Forme.DATE ? "?date=" + date : ""));
        model.addAttribute("genereLe", echeances.maintenant());
        model.addAttribute("view", "etats/etat");
        return "dashboard";
    }

    private Object exportPeriode(String chemin, String format, List<String> du, List<String> au,
                                 java.util.function.Function<PeriodeEtat, Etat> calcul, RedirectAttributes redirection) {
        PeriodeEtat periode = PeriodeEtat.moisDe(echeances.aujourdHui());
        if (du != null || au != null) {
            PeriodeEtat demandee = lirePeriode(du, au);
            if (demandee == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Période invalide");
            periode = demandee;
        }
        PeriodeEtat retenue = periode;
        return exporter(chemin, format, retenue, null, () -> calcul.apply(retenue), redirection);
    }

    private Object exportDate(String chemin, String format, List<String> date, java.util.function.Function<LocalDate, Etat> calcul,
                              RedirectAttributes redirection) {
        LocalDate jour = echeances.aujourdHui();
        if (date != null) {
            jour = lireDate(date);
            if (jour == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date invalide");
        }
        LocalDate retenue = jour;
        return exporter(chemin, format, null, retenue, () -> calcul.apply(retenue), redirection);
    }

    private Object exporter(String chemin, String format, PeriodeEtat periode, LocalDate date, Supplier<Etat> calcul, RedirectAttributes redirection) {
        if (!"pdf".equals(format) && !"excel".equals(format)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Format inconnu");
        Etat etat;
        try {
            etat = calcul.get();
        } catch (IncoherenceAvanceException | ExportTropVolumineuxException e) {
            // Retour à la page de l'état, qui montre le message : l'export n'est pas fabriqué.
            redirection.addFlashAttribute("error", e.getMessage());
            String requete = periode != null ? "?du=" + periode.du() + "&au=" + periode.au() : (date != null ? "?date=" + date : "");
            return "redirect:/etats/" + chemin + requete;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        ContexteDocument contexte = contextes.courant();
        boolean estPdf = "pdf".equals(format);
        String extension = estPdf ? "pdf" : "xlsx";
        String nom = etat.du() != null ? TelechargementExport.nomFichier(etat.base(), etat.du(), etat.au(), extension)
                : TelechargementExport.nomFichier(etat.base(), etat.au() != null ? etat.au() : echeances.aujourdHui(), null, extension);
        ResponseEntity<byte[]> reponse = estPdf
                ? TelechargementExport.pdf(nom, pdf.rendreEtat(etat.titre(), etat.parametres(), etat.notes(), etat.sections(), contexte, etat.format()))
                : TelechargementExport.excel(nom, excel.ecrire(etat.sections(), contexte));
        return reponse;
    }

    /** Une seule période AAAA-MM-JJ à AAAA-MM-JJ, réelle, de 2000 à 9998, « du » au plus tard égal à « au » ; sinon vide. */
    private static PeriodeEtat lirePeriode(List<String> du, List<String> au) {
        LocalDate debut = date(du), fin = date(au);
        if (debut == null || fin == null) return null;
        try {
            return new PeriodeEtat(debut, fin);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static LocalDate lireDate(List<String> valeurs) {
        return date(valeurs);
    }

    private static LocalDate date(List<String> valeurs) {
        if (valeurs == null || valeurs.size() != 1 || !valeurs.getFirst().strip().matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        try {
            LocalDate date = LocalDate.parse(valeurs.getFirst().strip());
            return date.getYear() < PeriodeEtat.ANNEE_MINIMALE || date.getYear() > PeriodeEtat.ANNEE_MAXIMALE ? null : date;
        } catch (DateTimeException e) {
            return null;
        }
    }
}
