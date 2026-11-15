package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.example.stock.compta.*;
import org.example.stock.compta.ComptabiliteService.Livres;
import org.example.stock.export.ContexteDocument;
import org.example.stock.export.ContexteDocumentService;
import org.example.stock.export.DocumentPdfService;
import org.example.stock.export.ExportExcelService;
import org.example.stock.export.TelechargementExport;
import org.example.stock.service.BoutiqueService;
import org.example.stock.service.EcheanceService;
import org.example.stock.service.Etat;
import org.example.stock.service.PeriodeEtat;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Section « Comptabilité » du gérant (lot 5, étape a) : journal, grand livre, balance, contrôles, plan de comptes et table de correspondance.
 * Réservée au gérant par la règle de sécurité {@code /comptabilite/**} (403 pour le caissier). Les écritures sont calculées à chaque
 * affichage ; la boutique vient toujours du compte connecté. Une période invalide donne un avertissement et le mois en cours à l'écran,
 * et 400 pour un export. Tout paramètre inconnu ou répété d'un POST donne 400.
 */
@Controller
@RequestMapping("/comptabilite")
public class ComptabiliteController {
    private final ComptabiliteService compta;
    private final PlanComptesService plan;
    private final CorrespondanceService correspondance;
    private final BoutiqueService boutiques;
    private final EcheanceService horloge;
    private final ExportExcelService excel;
    private final DocumentPdfService pdf;
    private final ContexteDocumentService contextes;
    private final EtatsFinanciersService financiers;

    public ComptabiliteController(ComptabiliteService compta, PlanComptesService plan, CorrespondanceService correspondance, BoutiqueService boutiques,
                                  EcheanceService horloge, ExportExcelService excel, DocumentPdfService pdf, ContexteDocumentService contextes,
                                  EtatsFinanciersService financiers) {
        this.financiers = financiers;
        this.compta = compta; this.plan = plan; this.correspondance = correspondance; this.boutiques = boutiques; this.horloge = horloge;
        this.excel = excel; this.pdf = pdf; this.contextes = contextes;
    }

    /** Une carte de la page d'accueil. */
    public record Carte(String chemin, String titre, String description) {}

    @GetMapping({"", "/"})
    public String accueil(Model model) {
        model.addAttribute("cartes", List.of(
                new Carte("/comptabilite/journal", "Journal", "Toutes les écritures de la période, par journal (ventes, achats, trésorerie, opérations diverses)."),
                new Carte("/comptabilite/grand-livre", "Grand livre", "Le détail de chaque compte, avec le report et le solde après chaque ligne."),
                new Carte("/comptabilite/balance", "Balance", "Les soldes et les mouvements de tous les comptes, avec les totaux."),
                new Carte("/comptabilite/etats-financiers", "États financiers", "Compte de résultat, bilan et tableau des flux de trésorerie du SYSCOHADA révisé, ou système minimal de trésorerie."),
                new Carte("/comptabilite/controles", "Contrôles de cohérence", "Neuf vérifications entre les écritures, les soldes de l'application et les états financiers."),
                new Carte("/comptabilite/plan", "Plan de comptes", "Le plan officiel du SYSCOHADA révisé et les sous-comptes de la boutique."),
                new Carte("/comptabilite/correspondance", "Table de correspondance", "Le compte comptable de chaque nature d'opération, modifiable.")));
        model.addAttribute("mention", ComptabiliteEtats.MENTION);
        model.addAttribute("view", "compta/accueil");
        return "dashboard";
    }

    // ---- Journal, grand livre, balance, contrôles --------------------------------------------------------------

    @GetMapping("/journal")
    public String journal(@RequestParam(name = "du", required = false) List<String> du, @RequestParam(name = "au", required = false) List<String> au,
                          @RequestParam(name = "journal", required = false) List<String> journal, @RequestParam(name = "compte", required = false) List<String> compte,
                          @RequestParam(name = "q", required = false) List<String> q, Model model) {
        Filtres f = Filtres.lire(du, au, journal, compte, q, horloge.aujourdHui());
        return ecran("journal", "Journal", "Écritures de la période, calculées à partir des opérations.", f, model, true, l -> etatJournal(l, f));
    }

    @GetMapping("/journal/export/{format}")
    public Object journalExport(@PathVariable String format, @RequestParam(name = "du", required = false) List<String> du, @RequestParam(name = "au", required = false) List<String> au,
                                @RequestParam(name = "journal", required = false) List<String> journal, @RequestParam(name = "compte", required = false) List<String> compte,
                                @RequestParam(name = "q", required = false) List<String> q) {
        Filtres f = Filtres.lireStrict(du, au, journal, compte, q, horloge.aujourdHui());
        return exporter(format, f, l -> etatJournal(l, f));
    }

    @GetMapping("/grand-livre")
    public String grandLivre(@RequestParam(name = "du", required = false) List<String> du, @RequestParam(name = "au", required = false) List<String> au,
                             @RequestParam(name = "compte", required = false) List<String> compte, Model model) {
        Filtres f = Filtres.lire(du, au, null, compte, null, horloge.aujourdHui());
        return ecran("grand-livre", "Grand livre", "Le détail de chaque compte, avec le report à nouveau et le solde après chaque ligne.", f, model, true, l -> etatGrandLivre(l, f));
    }

    @GetMapping("/grand-livre/export/{format}")
    public Object grandLivreExport(@PathVariable String format, @RequestParam(name = "du", required = false) List<String> du, @RequestParam(name = "au", required = false) List<String> au,
                                   @RequestParam(name = "compte", required = false) List<String> compte) {
        Filtres f = Filtres.lireStrict(du, au, null, compte, null, horloge.aujourdHui());
        return exporter(format, f, l -> etatGrandLivre(l, f));
    }

    @GetMapping("/balance")
    public String balance(@RequestParam(name = "du", required = false) List<String> du, @RequestParam(name = "au", required = false) List<String> au, Model model) {
        Filtres f = Filtres.lire(du, au, null, null, null, horloge.aujourdHui());
        return ecran("balance", "Balance", "Soldes et mouvements de tous les comptes de la période.", f, model, false, l -> ComptabiliteEtats.balance(l, compta.balance(l)));
    }

    @GetMapping("/balance/export/{format}")
    public Object balanceExport(@PathVariable String format, @RequestParam(name = "du", required = false) List<String> du, @RequestParam(name = "au", required = false) List<String> au) {
        Filtres f = Filtres.lireStrict(du, au, null, null, null, horloge.aujourdHui());
        return exporter(format, f, l -> ComptabiliteEtats.balance(l, compta.balance(l)));
    }

    @GetMapping("/controles")
    public String controles(@RequestParam(name = "du", required = false) List<String> du, @RequestParam(name = "au", required = false) List<String> au, Model model) {
        Filtres f = Filtres.lire(du, au, null, null, null, horloge.aujourdHui());
        return ecran("controles", "Contrôles de cohérence", "Les écritures comparées aux soldes de l'application.", f, model, false, this::controlesComplets);
    }

    @GetMapping("/controles/export/{format}")
    public Object controlesExport(@PathVariable String format, @RequestParam(name = "du", required = false) List<String> du, @RequestParam(name = "au", required = false) List<String> au) {
        Filtres f = Filtres.lireStrict(du, au, null, null, null, horloge.aujourdHui());
        return exporter(format, f, this::controlesComplets);
    }

    /** Les cinq contrôles de l'étape 5a, puis ceux des états financiers de l'étape 5b, sur la même période. */
    private Etat controlesComplets(Livres l) {
        List<ComptabiliteService.Controle> tous = new ArrayList<>(compta.controles(l));
        tous.addAll(financiers.controles(l.boutiqueId(), l.debut(), l.fin()));
        return ComptabiliteEtats.controles(l, tous);
    }

    private Etat etatJournal(Livres l, Filtres f) {
        Journal j = f.journal.isEmpty() ? null : Journal.depuisCode(f.journal);
        String filtre = (j == null ? "" : "Journal " + j.getLibelle()) + (f.compte.isEmpty() ? "" : (j == null ? "" : " · ") + "Compte " + f.compte)
                + (f.q.isEmpty() ? "" : ((j == null && f.compte.isEmpty()) ? "" : " · ") + "Recherche « " + f.q + " »");
        return ComptabiliteEtats.journal(l, compta.journal(l, j, f.compte, f.q), filtre);
    }

    private Etat etatGrandLivre(Livres l, Filtres f) {
        return ComptabiliteEtats.grandLivre(l, compta.grandLivre(l, f.compte, null, null), f.compte.isEmpty() ? "" : "Comptes commençant par " + f.compte);
    }

    private String ecran(String chemin, String titre, String sousTitre, Filtres f, Model model, boolean avecFiltres, Function<Livres, Etat> calcul) {
        try {
            Livres l = compta.calculer(boutiques.idBoutiqueCourante(), f.du, f.au);
            Etat etat = calcul.apply(l);
            model.addAttribute("etat", etat);
            model.addAttribute("vues", etat.sections().stream().map(DocumentPdfService::vue).toList());
        } catch (IllegalArgumentException e) {
            model.addAttribute("erreur", e.getMessage());
        }
        model.addAttribute("titre", titre);
        model.addAttribute("sousTitre", sousTitre);
        model.addAttribute("chemin", "/comptabilite/" + chemin);
        model.addAttribute("filtres", f);
        model.addAttribute("avecFiltres", avecFiltres);
        model.addAttribute("avecJournal", chemin.equals("journal"));
        model.addAttribute("avecRecherche", chemin.equals("journal"));
        model.addAttribute("journaux", Journal.values());
        model.addAttribute("avertissement", f.avertissement);
        model.addAttribute("requeteExport", f.requete());
        model.addAttribute("mention", ComptabiliteEtats.MENTION);
        model.addAttribute("genereLe", horloge.maintenant());
        model.addAttribute("view", "compta/livre");
        return "dashboard";
    }

    private Object exporter(String format, Filtres f, Function<Livres, Etat> calcul) {
        if (!"pdf".equals(format) && !"excel".equals(format)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Format inconnu");
        Etat etat;
        try {
            etat = calcul.apply(compta.calculer(boutiques.idBoutiqueCourante(), f.du, f.au));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        ContexteDocument contexte = contextes.courant();
        boolean estPdf = "pdf".equals(format);
        String nom = TelechargementExport.nomFichier(etat.base(), etat.du(), etat.au(), estPdf ? "pdf" : "xlsx");
        return estPdf ? TelechargementExport.pdf(nom, pdf.rendreEtat(etat.titre(), etat.parametres(), etat.notes(), etat.sections(), contexte, etat.format()))
                : TelechargementExport.excel(nom, excel.ecrire(etat.sections(), contexte));
    }

    /** Filtres lus de l'adresse : période, journal, début de numéro de compte et texte libre. */
    public static final class Filtres {
        public final LocalDate du, au;
        public final String journal, compte, q;
        public final String avertissement;

        private Filtres(LocalDate du, LocalDate au, String journal, String compte, String q, String avertissement) {
            this.du = du; this.au = au; this.journal = journal; this.compte = compte; this.q = q; this.avertissement = avertissement;
        }

        public LocalDate getDu() { return du; }
        public LocalDate getAu() { return au; }
        public String getJournal() { return journal; }
        public String getCompte() { return compte; }
        public String getQ() { return q; }

        String requete() {
            StringBuilder s = new StringBuilder("?du=" + du + "&au=" + au);
            if (!journal.isEmpty()) s.append("&journal=").append(journal);
            if (!compte.isEmpty()) s.append("&compte=").append(compte);
            if (!q.isEmpty()) s.append("&q=").append(java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8));
            return s.toString();
        }

        static Filtres lire(List<String> du, List<String> au, List<String> journal, List<String> compte, List<String> q, LocalDate aujourdhui) {
            PeriodeEtat mois = PeriodeEtat.moisDe(aujourdhui);
            String avertissement = null;
            LocalDate d = mois.du(), a = mois.au();
            if (du != null || au != null) {
                LocalDate dd = date(du), aa = date(au);
                if (dd == null || aa == null || aa.isBefore(dd)) avertissement = "La période demandée est invalide : le mois en cours est affiché.";
                else { d = dd; a = aa; }
            }
            String j = un(journal), c = un(compte), t = un(q);
            if (!j.isEmpty() && Journal.depuisCode(j) == null) { j = ""; avertissement = "Le journal demandé est inconnu : tous les journaux sont affichés."; }
            if (!c.isEmpty() && !c.matches("\\d{1,8}")) { c = ""; avertissement = "Le numéro de compte demandé est invalide : tous les comptes sont affichés."; }
            if (t.length() > 80) t = t.substring(0, 80);
            return new Filtres(d, a, j, c, t, avertissement);
        }

        static Filtres lireStrict(List<String> du, List<String> au, List<String> journal, List<String> compte, List<String> q, LocalDate aujourdhui) {
            Filtres f = lire(du, au, journal, compte, q, aujourdhui);
            if (f.avertissement != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filtres invalides");
            return f;
        }

        private static String un(List<String> v) { return v == null || v.isEmpty() ? "" : v.getFirst() == null ? "" : v.getFirst().strip(); }

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

    // ---- Plan de comptes -----------------------------------------------------------------------------------------

    @GetMapping("/plan")
    public String plan(@RequestParam(name = "q", required = false) List<String> q, @RequestParam(name = "classe", required = false) List<String> classe, Model model) {
        Long boutiqueId = boutiques.idBoutiqueCourante();
        plan.synchroniserTresorerie(boutiqueId);
        String recherche = q == null || q.isEmpty() || q.getFirst() == null ? "" : q.getFirst().strip();
        if (recherche.length() > 80) recherche = recherche.substring(0, 80);
        String cl = classe == null || classe.isEmpty() || classe.getFirst() == null ? "" : classe.getFirst().strip();
        if (!cl.matches("[1-9]")) cl = "";
        Map<String, List<PlanComptesService.CompteVue>> groupes = new LinkedHashMap<>();
        Map<String, String> libellesClasses = new LinkedHashMap<>();
        for (PlanComptesService.CompteVue c : plan.plan(boutiqueId)) {
            if (!cl.isEmpty() && !c.classe().equals(cl)) continue;
            if (!PlanComptesService.correspond(c, recherche)) continue;
            groupes.computeIfAbsent(c.classe(), k -> new ArrayList<>()).add(c);
            libellesClasses.putIfAbsent(c.classe(), c.libelleClasse());
        }
        model.addAttribute("groupes", groupes);
        model.addAttribute("libellesClasses", libellesClasses);
        model.addAttribute("recherche", recherche);
        model.addAttribute("classe", cl);
        model.addAttribute("ouvert", !recherche.isEmpty() || !cl.isEmpty());
        model.addAttribute("total", groupes.values().stream().mapToInt(List::size).sum());
        model.addAttribute("officiels", plan.getPlan().taille());
        model.addAttribute("mention", ComptabiliteEtats.MENTION);
        model.addAttribute("view", "compta/plan");
        return "dashboard";
    }

    @PostMapping("/plan/sous-comptes")
    public String ajouterSousCompte(@RequestParam String parent, @RequestParam String numero, @RequestParam String intitule, HttpServletRequest requete, RedirectAttributes redirect) {
        exigerParametres(requete, Set.of("parent", "numero", "intitule", "_csrf"));
        return action(redirect, "Sous-compte ajouté.", "/comptabilite/plan", () -> plan.ajouter(boutiques.idBoutiqueCourante(), parent, numero, intitule));
    }

    @PostMapping("/plan/sous-comptes/{id}")
    public String modifierSousCompte(@PathVariable Long id, @RequestParam(required = false) String numero, @RequestParam String intitule, HttpServletRequest requete, RedirectAttributes redirect) {
        exigerParametres(requete, Set.of("numero", "intitule", "_csrf"));
        return action(redirect, "Sous-compte modifié.", "/comptabilite/plan", () -> plan.modifier(boutiques.idBoutiqueCourante(), id, numero, intitule));
    }

    @PostMapping("/plan/sous-comptes/{id}/supprimer")
    public String supprimerSousCompte(@PathVariable Long id, HttpServletRequest requete, RedirectAttributes redirect) {
        exigerParametres(requete, Set.of("_csrf"));
        return action(redirect, "Sous-compte supprimé.", "/comptabilite/plan", () -> plan.supprimer(boutiques.idBoutiqueCourante(), id));
    }

    // ---- Table de correspondance ---------------------------------------------------------------------------------

    @GetMapping("/correspondance")
    public String correspondance(Model model) {
        Long boutiqueId = boutiques.idBoutiqueCourante();
        List<CorrespondanceService.LigneVue> lignes = correspondance.lignes(boutiqueId);
        Map<CleCompta.Groupe, List<CorrespondanceService.LigneVue>> groupes = new LinkedHashMap<>();
        for (CleCompta.Groupe g : CleCompta.Groupe.values()) groupes.put(g, new ArrayList<>());
        lignes.forEach(l -> groupes.get(l.cle().getGroupe()).add(l));
        Map<String, String> intitules = plan.intitules(boutiqueId);
        List<String[]> tresorerie = new ArrayList<>();
        plan.comptesTresorerie(boutiqueId).entrySet().stream().sorted(Map.Entry.comparingByValue())
                .forEach(e -> tresorerie.add(new String[] {e.getValue(), intitules.getOrDefault(e.getValue(), "")}));
        model.addAttribute("groupes", groupes);
        model.addAttribute("tresorerie", tresorerie);
        model.addAttribute("modifiees", lignes.stream().filter(CorrespondanceService.LigneVue::modifie).count());
        model.addAttribute("mention", ComptabiliteEtats.MENTION);
        model.addAttribute("view", "compta/correspondance");
        return "dashboard";
    }

    @PostMapping("/correspondance/defauts")
    public String retablir(HttpServletRequest requete, RedirectAttributes redirect) {
        exigerParametres(requete, Set.of("_csrf"));
        return action(redirect, "Valeurs par défaut rétablies.", "/comptabilite/correspondance", () -> correspondance.retablirDefauts(boutiques.idBoutiqueCourante()));
    }

    @PostMapping("/correspondance/{code}")
    public String modifierLigne(@PathVariable String code, @RequestParam String compte, @RequestParam(required = false) String motsCles, HttpServletRequest requete, RedirectAttributes redirect) {
        exigerParametres(requete, Set.of("compte", "motsCles", "_csrf"));
        return action(redirect, "Correspondance enregistrée.", "/comptabilite/correspondance", () -> correspondance.modifier(boutiques.idBoutiqueCourante(), code, compte, motsCles));
    }

    private String action(RedirectAttributes redirect, String succes, String retour, Runnable travail) {
        try {
            travail.run();
            redirect.addFlashAttribute("success", succes);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        } catch (DataIntegrityViolationException e) {
            redirect.addFlashAttribute("error", "Cette modification entre en conflit avec une autre. Rechargez la page.");
        }
        return "redirect:" + retour;
    }

    /** Liste blanche des paramètres d'un POST : un champ inconnu ou répété donne 400. */
    private static void exigerParametres(HttpServletRequest requete, Set<String> autorises) {
        for (var p : requete.getParameterMap().entrySet()) {
            if (!autorises.contains(p.getKey()) || p.getValue().length != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champ de formulaire non autorisé");
        }
    }
}
