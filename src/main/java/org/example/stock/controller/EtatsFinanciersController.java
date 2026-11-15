package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.example.stock.compta.ComptabiliteEtats;
import org.example.stock.compta.EtatsFinanciersEtats;
import org.example.stock.compta.EtatsFinanciersService;
import org.example.stock.compta.EtatsFinanciersService.Etats;
import org.example.stock.compta.ExercicesService;
import org.example.stock.compta.InstantanesService;
import org.example.stock.compta.MentionComptable;
import org.example.stock.enums.SystemeComptable;
import org.example.stock.export.ContexteDocument;
import org.example.stock.export.ContexteDocumentService;
import org.example.stock.export.DocumentPdfService;
import org.example.stock.export.ExportExcelService;
import org.example.stock.export.TelechargementExport;
import org.example.stock.service.BoutiqueService;
import org.example.stock.service.EcheanceService;
import org.example.stock.service.Etat;
import org.example.stock.service.PeriodeEtat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * États financiers du gérant (lot 5, étape b) : compte de résultat, bilan, tableau des flux de trésorerie et système minimal de trésorerie,
 * avec la table de rattachement des comptes aux postes. Réservé au gérant par la règle {@code /comptabilite/**} (403 pour le caissier) ; la
 * boutique vient toujours du compte connecté. La période par défaut est l'année civile en cours ; une période invalide donne un avertissement
 * et l'année civile à l'écran, et 400 pour un export.
 */
@Controller
@RequestMapping("/comptabilite/etats-financiers")
public class EtatsFinanciersController {
    private static final Map<String, String> TITRES = Map.of("compte-resultat", "Compte de résultat", "bilan", "Bilan", "flux", "Tableau des flux de trésorerie",
            "smt", "Système minimal de trésorerie");
    private static final Map<String, String> SOUS_TITRES = Map.of(
            "compte-resultat", "Les produits et les charges de la période, avec les soldes intermédiaires de gestion, dans le modèle du système normal.",
            "bilan", "Ce que la boutique possède et ce qu'elle doit à la fin de la période, dans le modèle du système normal.",
            "flux", "D'où vient la trésorerie de la période et où elle est allée (méthode indirecte).",
            "smt", "Les recettes et les dépenses de la période et le bilan simplifié du système minimal de trésorerie.");

    private final EtatsFinanciersService etats;
    private final BoutiqueService boutiques;
    private final EcheanceService horloge;
    private final ExportExcelService excel;
    private final DocumentPdfService pdf;
    private final ContexteDocumentService contextes;
    @org.springframework.beans.factory.annotation.Autowired private MentionComptable mentions;
    @org.springframework.beans.factory.annotation.Autowired private ExercicesService exercices;
    @org.springframework.beans.factory.annotation.Autowired private InstantanesService instantanes;

    public EtatsFinanciersController(EtatsFinanciersService etats, BoutiqueService boutiques, EcheanceService horloge, ExportExcelService excel, DocumentPdfService pdf,
                                     ContexteDocumentService contextes) {
        this.etats = etats; this.boutiques = boutiques; this.horloge = horloge; this.excel = excel; this.pdf = pdf; this.contextes = contextes;
    }

    /** Une carte de la page d'accueil des états financiers. */
    public record Carte(String chemin, String titre, String description) {}

    @GetMapping({"", "/"})
    public String accueil(Model model) {
        SystemeComptable systeme = etats.systeme(boutiques.idBoutiqueCourante());
        List<Carte> cartes = systeme == SystemeComptable.NORMAL
                ? List.of(new Carte("/comptabilite/etats-financiers/compte-resultat", "Compte de résultat", SOUS_TITRES.get("compte-resultat")),
                        new Carte("/comptabilite/etats-financiers/bilan", "Bilan", SOUS_TITRES.get("bilan")),
                        new Carte("/comptabilite/etats-financiers/flux", "Tableau des flux de trésorerie", SOUS_TITRES.get("flux")))
                : List.of(new Carte("/comptabilite/etats-financiers/smt", "Système minimal de trésorerie", SOUS_TITRES.get("smt")));
        model.addAttribute("cartes", cartes);
        model.addAttribute("systeme", systeme);
        model.addAttribute("systemes", SystemeComptable.values());
        model.addAttribute("mention", mentions.pour(boutiques.idBoutiqueCourante()));
        model.addAttribute("exercicesChoix", exercices.liste(boutiques.idBoutiqueCourante()).reversed());
        model.addAttribute("exerciceChoisi", null);
        model.addAttribute("view", "compta/etats-financiers-accueil");
        return "dashboard";
    }

    @PostMapping("/systeme")
    public String choisirSysteme(@RequestParam(name = "systeme") String systeme, HttpServletRequest requete, RedirectAttributes redirect) {
        for (var p : requete.getParameterMap().entrySet()) {
            if (!Set.of("systeme", "_csrf").contains(p.getKey()) || p.getValue().length != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champ de formulaire non autorisé");
        }
        SystemeComptable choisi;
        try { choisi = SystemeComptable.valueOf(systeme); } catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Système inconnu"); }
        etats.choisirSysteme(choisi);
        redirect.addFlashAttribute("success", "Système enregistré : " + choisi.getLibelle() + ".");
        return "redirect:/comptabilite/etats-financiers";
    }

    @GetMapping("/rattachements")
    public String rattachements(Model model) {
        Etat etat = EtatsFinanciersEtats.rattachements(horloge.aujourdHui());
        return afficher("rattachements", "Rattachement des comptes aux postes", "Le poste du bilan et du compte de résultat où va chaque compte. Table interne, non modifiable.",
                Periode.civile(null, null, horloge.aujourdHui()), model, etat, List.of(), null);
    }

    @GetMapping("/rattachements/export/{format}")
    public Object rattachementsExport(@PathVariable String format) { return exporter(format, EtatsFinanciersEtats.rattachements(horloge.aujourdHui())); }

    @GetMapping("/{code}")
    public String ecran(@PathVariable String code, @RequestParam(name = "du", required = false) List<String> du, @RequestParam(name = "au", required = false) List<String> au,
                        @RequestParam(name = "exercice", required = false) List<String> exercice, Model model) {
        Function<Etats, Etat> construire = constructeur(code);
        Long b = boutiques.idBoutiqueCourante();
        Periode p = periode(b, du, au, exercice);
        Etat etat = null;
        List<String> alertes = List.of();
        String mention = mentions.pour(b);
        try {
            Etats e = etats.calculer(b, p.du, p.au);
            if (e.fige() != null) mention = mentions.pourExerciceFige(b, e.fige().clotureLe(), e.fige().cloturePar());
            etat = MentionComptable.avecMention(construire.apply(e), mention);
            alertes = EtatsFinanciersEtats.alertes(e);
            model.addAttribute("fige", e.fige());
        } catch (IllegalArgumentException ex) {
            model.addAttribute("erreur", ex.getMessage());
        }
        model.addAttribute("mentionEtat", mention);
        return afficher(code, TITRES.get(code), SOUS_TITRES.get(code), p, model, etat, alertes, etats.systeme(b));
    }

    @GetMapping("/{code}/export/{format}")
    public Object export(@PathVariable String code, @PathVariable String format, @RequestParam(name = "du", required = false) List<String> du,
                         @RequestParam(name = "au", required = false) List<String> au, @RequestParam(name = "exercice", required = false) List<String> exercice) {
        Function<Etats, Etat> construire = constructeur(code);
        Long b = boutiques.idBoutiqueCourante();
        Periode p = periode(b, du, au, exercice);
        if (p.avertissement != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Période invalide");
        try {
            Etats e = etats.calculer(b, p.du, p.au);
            if (e.fige() != null && ("pdf".equals(format) || "excel".equals(format))) {
                // Période d'un exercice clôturé : le document figé à la clôture, jamais un nouveau calcul.
                var doc = instantanes.documentOuAucun(e.fige().instantaneId(), code);
                if (doc.isPresent()) {
                    boolean estPdf = "pdf".equals(format);
                    String nom = estPdf ? doc.get().getNomFichier() : doc.get().getNomFichier().replaceAll("\\.pdf$", ".xlsx");
                    return estPdf ? TelechargementExport.pdf(nom, doc.get().getPdf()) : TelechargementExport.excel(nom, doc.get().getExcel());
                }
            }
            return exporter(format, MentionComptable.avecMention(construire.apply(e), mentions.pour(b)));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** La période de l'adresse ; {@code exercice=AAAA-MM-JJ} (début d'un exercice) la remplace par les dates exactes de cet exercice. */
    private Periode periode(Long boutiqueId, List<String> du, List<String> au, List<String> exercice) {
        if (exercice != null) {
            if (exercice.size() != 1 || !exercice.getFirst().strip().matches("\\d{4}-\\d{2}-\\d{2}")) return Periode.civile(List.of("x"), List.of("x"), horloge.aujourdHui());
            try {
                LocalDate debut = LocalDate.parse(exercice.getFirst().strip());
                var e = exercices.exercicePour(boutiqueId, debut);
                if (e.debut().equals(debut)) return Periode.exact(e.debut(), e.fin());
            } catch (DateTimeException ignore) { /* période invalide ci-dessous */ }
            return Periode.civile(List.of("x"), List.of("x"), horloge.aujourdHui());
        }
        return Periode.civile(du, au, horloge.aujourdHui());
    }

    private static Function<Etats, Etat> constructeur(String code) {
        return switch (code) {
            case "compte-resultat" -> EtatsFinanciersEtats::compteResultat;
            case "bilan" -> EtatsFinanciersEtats::bilan;
            case "flux" -> EtatsFinanciersEtats::tableauFlux;
            case "smt" -> EtatsFinanciersEtats::smt;
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "État inconnu");
        };
    }

    private String afficher(String code, String titre, String sousTitre, Periode p, Model model, Etat etat, List<String> alertes, SystemeComptable systeme) {
        if (etat != null) {
            model.addAttribute("etat", etat);
            model.addAttribute("vues", etat.sections().stream().map(DocumentPdfService::vue).toList());
        }
        boolean autreSysteme = systeme != null && (systeme == SystemeComptable.NORMAL) == code.equals("smt");
        model.addAttribute("titre", titre);
        model.addAttribute("sousTitre", sousTitre);
        model.addAttribute("chemin", "/comptabilite/etats-financiers/" + code);
        model.addAttribute("avecPeriode", !code.equals("rattachements"));
        model.addAttribute("filtres", p);
        model.addAttribute("avertissement", p.avertissement);
        model.addAttribute("alertes", alertes);
        model.addAttribute("autreSysteme", autreSysteme ? "Votre boutique est réglée sur le " + (systeme == SystemeComptable.NORMAL ? "système normal" : "système minimal de trésorerie")
                + " : cet état est affiché à titre indicatif. Le réglage se change depuis la page des états financiers." : null);
        model.addAttribute("requeteExport", p.requete());
        if (!model.containsAttribute("mentionEtat")) model.addAttribute("mentionEtat", mentions.pour(boutiques.idBoutiqueCourante()));
        model.addAttribute("mention", model.getAttribute("mentionEtat"));
        model.addAttribute("exercicesChoix", exercices.liste(boutiques.idBoutiqueCourante()).reversed());
        model.addAttribute("exerciceChoisi", exercices.exact(boutiques.idBoutiqueCourante(), p.du, p.au).map(ExercicesService.Exercice::debut).orElse(null));
        model.addAttribute("genereLe", horloge.maintenant());
        model.addAttribute("view", "compta/etat-financier");
        return "dashboard";
    }

    private Object exporter(String format, Etat etat) {
        if (!"pdf".equals(format) && !"excel".equals(format)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Format inconnu");
        ContexteDocument contexte = contextes.courant();
        boolean estPdf = "pdf".equals(format);
        String nom = TelechargementExport.nomFichier(etat.base(), etat.du(), etat.au(), estPdf ? "pdf" : "xlsx");
        return estPdf ? TelechargementExport.pdf(nom, pdf.rendreEtat(etat.titre(), etat.parametres(), etat.notes(), etat.sections(), contexte, etat.format()))
                : TelechargementExport.excel(nom, excel.ecrire(etat.sections(), contexte));
    }

    /** Période lue de l'adresse : l'année civile en cours par défaut. */
    public static final class Periode {
        public final LocalDate du, au;
        public final String avertissement;

        private Periode(LocalDate du, LocalDate au, String avertissement) { this.du = du; this.au = au; this.avertissement = avertissement; }

        public LocalDate getDu() { return du; }
        public LocalDate getAu() { return au; }

        String requete() { return "?du=" + du + "&au=" + au; }

        static Periode exact(LocalDate du, LocalDate au) { return new Periode(du, au, null); }

        static Periode civile(List<String> du, List<String> au, LocalDate aujourdhui) {
            LocalDate d = LocalDate.of(aujourdhui.getYear(), 1, 1), a = LocalDate.of(aujourdhui.getYear(), 12, 31);
            if (du == null && au == null) return new Periode(d, a, null);
            LocalDate dd = date(du), aa = date(au);
            if (dd == null || aa == null || aa.isBefore(dd)) return new Periode(d, a, "La période demandée est invalide : l'année civile en cours est affichée.");
            return new Periode(dd, aa, null);
        }

        private static LocalDate date(List<String> valeurs) {
            if (valeurs == null || valeurs.size() != 1 || !valeurs.getFirst().strip().matches("\\d{4}-\\d{2}-\\d{2}")) return null;
            try {
                LocalDate d = LocalDate.parse(valeurs.getFirst().strip());
                return d.getYear() < PeriodeEtat.ANNEE_MINIMALE || d.getYear() > PeriodeEtat.ANNEE_MAXIMALE ? null : d;
            } catch (DateTimeException e) {
                return null;
            }
        }
    }
}
