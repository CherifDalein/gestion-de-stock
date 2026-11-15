package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.example.stock.compta.ClotureService;
import org.example.stock.compta.ExercicesService;
import org.example.stock.compta.ExercicesService.Exercice;
import org.example.stock.compta.InstantanesService;
import org.example.stock.compta.MentionComptable;
import org.example.stock.export.TelechargementExport;
import org.example.stock.model.AffectationResultat;
import org.example.stock.model.InstantaneDocument;
import org.example.stock.model.InstantaneExercice;
import org.example.stock.model.ReglageComptableBoutique;
import org.example.stock.repository.AffectationResultatRepository;
import org.example.stock.repository.ReglageComptableBoutiqueRepository;
import org.example.stock.service.BoutiqueService;
import org.example.stock.service.EcheanceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Exercices et clôture (lot 5, étape c). Réservé au gérant par la règle {@code /comptabilite/**} (403 pour le caissier) ; la boutique vient toujours du compte
 * connecté. Chaque formulaire n'accepte que ses champs, une seule fois chacun : tout autre champ est refusé (400).
 */
@Controller
@RequestMapping("/comptabilite/exercices")
public class ExercicesController {
    private final ExercicesService exercices;
    private final ClotureService cloture;
    private final InstantanesService instantanes;
    private final MentionComptable mentions;
    private final BoutiqueService boutiques;
    private final EcheanceService horloge;
    private final ReglageComptableBoutiqueRepository reglages;
    private final AffectationResultatRepository affectations;

    public ExercicesController(ExercicesService exercices, ClotureService cloture, InstantanesService instantanes, MentionComptable mentions, BoutiqueService boutiques,
                               EcheanceService horloge, ReglageComptableBoutiqueRepository reglages, AffectationResultatRepository affectations) {
        this.exercices = exercices; this.cloture = cloture; this.instantanes = instantanes; this.mentions = mentions; this.boutiques = boutiques; this.horloge = horloge;
        this.reglages = reglages; this.affectations = affectations;
    }

    /** Une ligne de la liste : l'exercice, son résultat et l'adresse de ses états. */
    public record Ligne(Exercice exercice, String statut, java.math.BigDecimal resultat, String cloture, String lien, String etats, boolean dernierCloture) { }

    @GetMapping({"", "/"})
    public String liste(Model model) {
        Long b = boutiques.idBoutiqueCourante();
        List<Exercice> tous = exercices.liste(b);
        Exercice dernierCloture = tous.stream().filter(Exercice::estCloture).reduce((x, y) -> y).orElse(null);
        List<Ligne> lignes = tous.stream().map(e -> new Ligne(e, e.statut().getLibelle(), e.estCloture() ? e.ligne().getResultat() : null,
                e.estCloture() ? "Clôturé le " + ExercicesService.jour(e.ligne().getClotureLe().toLocalDate()) + " par " + e.ligne().getCloturePar() : null,
                "/comptabilite/exercices/" + e.debut(), "/comptabilite/etats-financiers?exercice=" + e.debut(), e == dernierCloture)).toList();
        model.addAttribute("lignes", lignes.reversed());
        model.addAttribute("historique", instantanes.historique().reversed());
        Optional<ReglageComptableBoutique> reglage = reglages.findByBoutiqueId(b);
        model.addAttribute("debutPremier", exercices.debutPremierExercice(b));
        model.addAttribute("premierModifiable", tous.stream().noneMatch(Exercice::estCloture));
        model.addAttribute("cabinet", reglage.map(ReglageComptableBoutique::getValideCabinet).orElse(null));
        model.addAttribute("valideLe", reglage.map(ReglageComptableBoutique::getValideLe).orElse(null));
        model.addAttribute("aujourdhui", horloge.aujourdHui());
        return page("Exercices et clôture", "Les exercices de la boutique, leur résultat, la clôture et l'affectation du résultat. Réservé au gérant.", "compta/exercices", model, b);
    }

    @GetMapping("/{debut}")
    public String detail(@PathVariable String debut, Model model) {
        Long b = boutiques.idBoutiqueCourante();
        Exercice e = exerciceDe(b, debut);
        model.addAttribute("exercice", e);
        model.addAttribute("cloture", e.estCloture());
        if (!e.estCloture()) model.addAttribute("pre", cloture.preCloture(e.debut()));
        else {
            ExercicesService.Exercice dernierCloture = exercices.liste(b).stream().filter(Exercice::estCloture).reduce((x, y) -> y).orElse(null);
            Optional<AffectationResultat> aff = affectations.findByExerciceId(e.ligne().getId());
            Exercice suivant = exercices.exercicePour(b, e.fin().plusDays(1));
            model.addAttribute("affectation", aff.orElse(null));
            model.addAttribute("peutAnnuler", dernierCloture != null && dernierCloture.debut().equals(e.debut()) && aff.isEmpty());
            model.addAttribute("peutAffecter", aff.isEmpty() && !suivant.estCloture() && e.ligne().getResultat() != null && e.ligne().getResultat().signum() != 0);
            model.addAttribute("peutSupprimerAffectation", aff.isPresent() && !suivant.estCloture());
            model.addAttribute("perte", e.ligne().getResultat() != null && e.ligne().getResultat().signum() < 0);
            model.addAttribute("suivant", suivant);
            model.addAttribute("destinations", ClotureService.DESTINATIONS);
            model.addAttribute("dateAffectationDefaut", suivant.debut().isAfter(horloge.aujourdHui()) ? horloge.aujourdHui() : suivant.debut());
            InstantaneExercice actif = instantanes.historique().stream().filter(i -> i.getExercice().getId().equals(e.ligne().getId()) && i.getStatut() == org.example.stock.enums.StatutInstantane.ACTIF).findFirst().orElse(null);
            model.addAttribute("instantane", actif);
            if (actif != null) {
                model.addAttribute("documents", instantanes.documentsDe(actif.getId()));
                model.addAttribute("conforme", instantanes.empreinteConforme(actif));
            }
        }
        model.addAttribute("etats", "/comptabilite/etats-financiers?exercice=" + e.debut());
        return page("Exercice " + e.libelle(), "Du " + ExercicesService.jour(e.debut()) + " au " + ExercicesService.jour(e.fin()) + ".", "compta/exercice", model, b);
    }

    private String page(String titre, String sousTitre, String vue, Model model, Long boutiqueId) {
        model.addAttribute("titre", titre);
        model.addAttribute("sousTitre", sousTitre);
        model.addAttribute("mention", mentions.pour(boutiqueId));
        model.addAttribute("view", vue);
        return "dashboard";
    }

    // ---- Documents de l'instantané ----------------------------------------------------------------------------------

    @GetMapping("/instantanes/{id}/{code}/{format}")
    public Object telecharger(@PathVariable Long id, @PathVariable String code, @PathVariable String format) {
        if (!"pdf".equals(format) && !"excel".equals(format)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Format inconnu");
        InstantaneExercice i = instantanes.trouver(id);
        InstantaneDocument d = instantanes.document(id, code);
        boolean pdf = "pdf".equals(format);
        String nom = pdf ? d.getNomFichier() : d.getNomFichier().replaceAll("\\.pdf$", ".xlsx");
        return pdf ? TelechargementExport.pdf(nom, d.getPdf()) : TelechargementExport.excel(nom, d.getExcel());
    }

    // ---- Écritures ---------------------------------------------------------------------------------------------------

    @PostMapping("/premier-exercice")
    public String premierExercice(HttpServletRequest requete, RedirectAttributes redirect) {
        champs(requete, Set.of("debut"));
        return agir(redirect, "/comptabilite/exercices", () -> {
            exercices.fixerDebutPremierExercice(date(requete.getParameter("debut"), "La date de début"));
            return "Début du premier exercice enregistré.";
        });
    }

    @PostMapping("/validation")
    public String validation(HttpServletRequest requete, RedirectAttributes redirect) {
        champs(requete, Set.of("cabinet", "le"));
        return agir(redirect, "/comptabilite/exercices", () -> {
            String le = requete.getParameter("le");
            exercices.enregistrerValidation(requete.getParameter("cabinet"), le == null || le.isBlank() ? null : date(le, "La date de validation"));
            return "Validation enregistrée.";
        });
    }

    @PostMapping("/{debut}/cloturer")
    public String cloturer(@PathVariable String debut, HttpServletRequest requete, RedirectAttributes redirect) {
        champs(requete, Set.of("motif"));
        LocalDate d = date(debut, "L'exercice");
        return agir(redirect, "/comptabilite/exercices/" + d, () -> {
            var e = cloture.cloturer(d, requete.getParameter("motif"));
            return "Exercice " + ExercicesService.libelle(e.getDateDebut(), e.getDateFin()) + " clôturé. Ses états sont figés.";
        });
    }

    @PostMapping("/{debut}/annuler")
    public String annuler(@PathVariable String debut, HttpServletRequest requete, RedirectAttributes redirect) {
        champs(requete, Set.of("motif"));
        LocalDate d = date(debut, "L'exercice");
        return agir(redirect, "/comptabilite/exercices/" + d, () -> {
            cloture.annulerCloture(d, requete.getParameter("motif"));
            return "Clôture annulée. L'instantané est conservé, marqué « annulé ».";
        });
    }

    @PostMapping("/{debut}/affecter")
    public String affecter(@PathVariable String debut, HttpServletRequest requete, RedirectAttributes redirect) {
        champs(requete, Set.of("destination", "date"));
        LocalDate d = date(debut, "L'exercice");
        return agir(redirect, "/comptabilite/exercices/" + d, () -> {
            String date = requete.getParameter("date");
            cloture.affecter(d, requete.getParameter("destination"), date == null || date.isBlank() ? null : date(date, "La date d'affectation"));
            return "Résultat affecté : l'écriture est enregistrée dans l'exercice suivant.";
        });
    }

    @PostMapping("/{debut}/affectation/supprimer")
    public String supprimerAffectation(@PathVariable String debut, HttpServletRequest requete, RedirectAttributes redirect) {
        champs(requete, Set.of());
        LocalDate d = date(debut, "L'exercice");
        return agir(redirect, "/comptabilite/exercices/" + d, () -> {
            cloture.supprimerAffectation(d);
            return "Affectation supprimée.";
        });
    }

    // ---- Outils -------------------------------------------------------------------------------------------------------

    private interface Action { String faire(); }

    private static String agir(RedirectAttributes redirect, String retour, Action action) {
        try {
            redirect.addFlashAttribute("success", action.faire());
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + retour;
    }

    private Exercice exerciceDe(Long boutiqueId, String debut) {
        LocalDate d = date(debut, "L'exercice");
        Exercice e = exercices.exercicePour(boutiqueId, d);
        if (!e.debut().equals(d)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exercice introuvable");
        return e;
    }

    private static LocalDate date(String valeur, String quoi) {
        if (valeur == null || !valeur.strip().matches("\\d{4}-\\d{2}-\\d{2}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, quoi + " : date invalide");
        try { return LocalDate.parse(valeur.strip()); } catch (DateTimeException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, quoi + " : date invalide"); }
    }

    /** Liste blanche : seuls les champs prévus (et le jeton CSRF), chacun une seule fois. */
    private static void champs(HttpServletRequest requete, Set<String> permis) {
        for (var p : requete.getParameterMap().entrySet())
            if (!(permis.contains(p.getKey()) || p.getKey().equals("_csrf")) || p.getValue().length != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champ de formulaire non autorisé");
    }
}
