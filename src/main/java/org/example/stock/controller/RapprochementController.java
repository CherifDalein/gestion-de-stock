package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.example.stock.enums.TypeOperationTresorerie;
import org.example.stock.export.ContexteDocumentService;
import org.example.stock.export.LivraisonExport;
import org.example.stock.export.ParametresListe;
import org.example.stock.export.TelechargementExport;
import org.example.stock.form.DecisionReleveForm;
import org.example.stock.form.ImportReleveForm;
import org.example.stock.form.LigneReleveForm;
import org.example.stock.form.PointageForm;
import org.example.stock.form.ReleveForm;
import org.example.stock.model.ReleveBancaire;
import org.example.stock.releve.Correspondance;
import org.example.stock.releve.ImportReleveException;
import org.example.stock.service.EtatRapprochementDocumentService;
import org.example.stock.service.EtatRapprochementService;
import org.example.stock.service.ImportReleveService;
import org.example.stock.service.RapprochementService;
import org.example.stock.service.ReleveBancaireService;
import org.example.stock.service.SuiviRapprochementService;
import org.example.stock.service.ValidationReleveService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Rapprochement bancaire (étape 3c), réservé au gérant ({@code SecurityConfig}, « /rapprochements/** » : le caissier reçoit 403). La boutique vient
 * toujours du compte connecté ; un relevé, une ligne ou un mouvement d'une autre boutique donne 404. Les formulaires n'acceptent que leurs champs
 * (liste blanche, 400 sinon) et exigent le jeton CSRF.
 */
@Controller
@RequestMapping("/rapprochements")
public class RapprochementController {
    @Autowired private ReleveBancaireService releves;
    @Autowired private RapprochementService pointage;
    @Autowired private ValidationReleveService validation;
    @Autowired private EtatRapprochementService etats;
    @Autowired private EtatRapprochementDocumentService documents;
    @Autowired private ImportReleveService imports;
    @Autowired private SuiviRapprochementService suivi;
    @Autowired private ContexteDocumentService contextes;
    @Autowired private LivraisonExport livraison;

    @InitBinder({"releveForm", "ligneForm", "importForm", "pointageForm", "decisionReleve"})
    public void limiter(WebDataBinder binder, HttpServletRequest request) {
        switch (binder.getObjectName()) {
            case "releveForm" -> LiaisonReleves.limiter(binder, request, Set.of("compte", "dateDebut", "dateFin", "soldeOuverture", "soldeCloture", "jeton"), Set.of());
            case "ligneForm" -> LiaisonReleves.limiter(binder, request, Set.of("dateOperation", "libelle", "reference", "montant"), Set.of());
            case "importForm" -> LiaisonReleves.limiter(binder, request, Set.of("ligneEntete", "colonneDate", "colonneLibelle", "colonneReference", "modeMontant",
                    "colonneMontant", "colonneDebit", "colonneCredit", "formatDate", "separateur", "avecDoublons"), Set.of());
            case "pointageForm" -> LiaisonReleves.limiter(binder, request, Set.of("ligne", "mouvement", "rapprochement", "nature"), Set.of("lignes", "mouvements"));
            default -> LiaisonReleves.limiter(binder, request, Set.of("motif"), Set.of());
        }
    }

    // ---- Liste et création ----------------------------------------------------------------------------------

    @GetMapping
    public String liste(Model model) {
        model.addAttribute("releves", releves.lister());
        model.addAttribute("suivi", suivi.suivi());
        model.addAttribute("alerteJours", SuiviRapprochementService.ALERTE_JOURS);
        model.addAttribute("view", "rapprochement/liste");
        return "dashboard";
    }

    @GetMapping("/nouveau")
    public String nouveau(@RequestParam(name = "compte", required = false) List<String> compte, Model model) {
        ReleveForm formulaire = new ReleveForm();
        formulaire.setJeton(UUID.randomUUID().toString());
        Long compteId = ParametresListe.identifiant("compte", compte);
        if (compteId != null) proposer(formulaire, compteId);
        model.addAttribute("releveForm", formulaire);
        preparerCreation(model);
        return "dashboard";
    }

    private void proposer(ReleveForm formulaire, Long compteId) {
        formulaire.setCompte(compteId);
        LocalDate debut = releves.debutPropose(compteId);
        formulaire.setDateDebut(debut);
        formulaire.setSoldeOuverture(releves.ouvertureProposee(compteId, debut == null ? LocalDate.now() : debut));
    }

    private void preparerCreation(Model model) {
        model.addAttribute("comptes", releves.comptesRapprochables());
        model.addAttribute("view", "rapprochement/nouveau");
    }

    @PostMapping
    public String creer(@Valid @ModelAttribute("releveForm") ReleveForm formulaire, BindingResult result, Model model, RedirectAttributes redirection) {
        FormBindingAdvice.verifier(result);
        if (!result.hasErrors()) {
            try {
                ReleveBancaire r = releves.creer(formulaire.getCompte(), formulaire.getDateDebut(), formulaire.getDateFin(), formulaire.getSoldeOuverture(),
                        formulaire.getSoldeCloture(), formulaire.getJeton());
                redirection.addFlashAttribute("success", "Relevé " + r.getNumero() + " créé. Ajoutez ses lignes à la main ou importez un fichier.");
                return "redirect:/rapprochements/" + r.getId();
            } catch (IllegalArgumentException e) {
                result.reject("releve.invalide", e.getMessage());
            } catch (ConcurrencyFailureException e) {
                throw conflit();
            }
        }
        preparerCreation(model);
        return "dashboard";
    }

    // ---- Fiche du relevé ------------------------------------------------------------------------------------

    @GetMapping("/{id}")
    public String fiche(@PathVariable Long id, @RequestParam(name = "toutes", required = false) List<String> toutes, Model model) {
        boolean toutesLesLignes = ParametresListe.unique("toutes", toutes) != null;
        ReleveBancaire r = releves.trouver(id);
        var lignes = releves.lignesDe(id);
        model.addAttribute("releve", r);
        model.addAttribute("nombreLignes", lignes.size());
        model.addAttribute("lignes", toutesLesLignes || lignes.size() <= AFFICHAGE_LIGNES ? lignes : lignes.subList(0, AFFICHAGE_LIGNES));
        model.addAttribute("lignesTronquees", !toutesLesLignes && lignes.size() > AFFICHAGE_LIGNES);
        model.addAttribute("controle", releves.controle(r));
        model.addAttribute("historique", validation.historique(id));
        model.addAttribute("peutDevalider", validation.peutEtreDevalide(r));
        model.addAttribute("ligneForm", nouvelleLigne(r));
        model.addAttribute("releveForm", enteteDe(r));
        model.addAttribute("decisionReleve", new DecisionReleveForm());
        model.addAttribute("view", "rapprochement/fiche");
        return "dashboard";
    }

    private static final int AFFICHAGE_LIGNES = 300;

    private static LigneReleveForm nouvelleLigne(ReleveBancaire r) {
        LigneReleveForm f = new LigneReleveForm();
        f.setDateOperation(r.getDateFin());
        return f;
    }

    private static ReleveForm enteteDe(ReleveBancaire r) {
        ReleveForm f = new ReleveForm();
        f.setCompte(r.getCompte().getId()); f.setDateDebut(r.getDateDebut()); f.setDateFin(r.getDateFin());
        f.setSoldeOuverture(r.getSoldeOuverture()); f.setSoldeCloture(r.getSoldeCloture());
        return f;
    }

    @PostMapping("/{id}/entete")
    public String modifierEntete(@PathVariable Long id, @Valid @ModelAttribute("releveForm") ReleveForm formulaire, BindingResult result, RedirectAttributes redirection) {
        FormBindingAdvice.verifier(result);
        if (result.hasErrors()) return erreur(redirection, result.getAllErrors().get(0).getDefaultMessage(), "/rapprochements/" + id);
        return agir(redirection, "/rapprochements/" + id, "Période et soldes du relevé enregistrés.",
                () -> releves.modifierEntete(id, formulaire.getDateDebut(), formulaire.getDateFin(), formulaire.getSoldeOuverture(), formulaire.getSoldeCloture()));
    }

    @PostMapping("/{id}/supprimer")
    public String supprimer(@PathVariable Long id, RedirectAttributes redirection, HttpServletRequest request) {
        LiaisonReleves.exiger(request, Set.of(), Set.of());
        try {
            releves.supprimer(id);
        } catch (IllegalArgumentException e) {
            return erreur(redirection, e.getMessage(), "/rapprochements/" + id);
        } catch (ConcurrencyFailureException e) {
            throw conflit();
        }
        redirection.addFlashAttribute("success", "Relevé supprimé avec ses lignes et ses rapprochements.");
        return "redirect:/rapprochements";
    }

    // ---- Lignes saisies -------------------------------------------------------------------------------------

    @PostMapping("/{id}/lignes")
    public String ajouterLigne(@PathVariable Long id, @Valid @ModelAttribute("ligneForm") LigneReleveForm f, BindingResult result, RedirectAttributes redirection) {
        FormBindingAdvice.verifier(result);
        if (result.hasErrors()) return erreur(redirection, result.getAllErrors().get(0).getDefaultMessage(), "/rapprochements/" + id);
        return agir(redirection, "/rapprochements/" + id, "Ligne ajoutée.", () -> releves.ajouterLigne(id, f.getDateOperation(), f.getLibelle(), f.getReference(), f.getMontant()));
    }

    @PostMapping("/{id}/lignes/{ligneId}")
    public String modifierLigne(@PathVariable Long id, @PathVariable Long ligneId, @Valid @ModelAttribute("ligneForm") LigneReleveForm f, BindingResult result,
                                RedirectAttributes redirection) {
        FormBindingAdvice.verifier(result);
        if (result.hasErrors()) return erreur(redirection, result.getAllErrors().get(0).getDefaultMessage(), "/rapprochements/" + id);
        return agir(redirection, "/rapprochements/" + id, "Ligne modifiée.", () -> releves.modifierLigne(id, ligneId, f.getDateOperation(), f.getLibelle(), f.getReference(), f.getMontant()));
    }

    @PostMapping("/{id}/lignes/{ligneId}/supprimer")
    public String supprimerLigne(@PathVariable Long id, @PathVariable Long ligneId, RedirectAttributes redirection, HttpServletRequest request) {
        LiaisonReleves.exiger(request, Set.of(), Set.of());
        return agir(redirection, "/rapprochements/" + id, "Ligne supprimée.", () -> releves.supprimerLigne(id, ligneId));
    }

    // ---- Import d'un fichier --------------------------------------------------------------------------------

    @GetMapping("/{id}/import")
    public String importer(@PathVariable Long id, Model model) {
        model.addAttribute("releve", releves.trouver(id));
        model.addAttribute("view", "rapprochement/import");
        return "dashboard";
    }

    @PostMapping("/{id}/import")
    public String deposer(@PathVariable Long id, @RequestParam("fichier") MultipartFile fichier, RedirectAttributes redirection, HttpServletRequest request) throws IOException {
        for (String nom : request.getParameterMap().keySet()) if (!nom.equals("_csrf")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champ de formulaire non autorisé");
        if (fichier == null || fichier.isEmpty()) return erreur(redirection, "Choisissez un fichier CSV ou Excel (.xlsx).", "/rapprochements/" + id + "/import");
        try {
            String jeton = imports.deposer(id, fichier.getOriginalFilename(), fichier.getBytes());
            return "redirect:/rapprochements/" + id + "/import/" + jeton;
        } catch (ImportReleveException | IllegalArgumentException e) {
            return erreur(redirection, e.getMessage(), "/rapprochements/" + id + "/import");
        }
    }

    /** Écran de correspondance des colonnes : la correspondance mémorisée pour le compte, sinon une proposition d'après les titres du fichier. */
    @GetMapping("/{id}/import/{jeton}")
    public String correspondance(@PathVariable Long id, @PathVariable String jeton, Model model, RedirectAttributes redirection) {
        return afficherCorrespondance(id, jeton, null, null, false, model, redirection);
    }

    /** Aperçu avec les colonnes choisies : rien n'est enregistré, l'écran est simplement recalculé. */
    @PostMapping("/{id}/import/{jeton}/apercu")
    public String apercu(@PathVariable Long id, @PathVariable String jeton, @ModelAttribute("importForm") ImportReleveForm f, BindingResult result, Model model,
                         RedirectAttributes redirection) {
        FormBindingAdvice.verifier(result);
        Correspondance choisie = null; String avertissement = null;
        try { choisie = lire(f); } catch (IllegalArgumentException | ImportReleveException e) { avertissement = e.getMessage(); }
        return afficherCorrespondance(id, jeton, choisie, avertissement, "oui".equals(f.getAvecDoublons()), model, redirection);
    }

    private String afficherCorrespondance(Long id, String jeton, Correspondance choisie, String avertissement, boolean avecDoublons, Model model, RedirectAttributes redirection) {
        ImportReleveService.Etape etape;
        try { etape = imports.etape(id, jeton, choisie); }
        catch (ResponseStatusException e) {
            if (e.getStatusCode() != HttpStatus.NOT_FOUND) throw e;
            releves.trouver(id);
            return erreur(redirection, "Ce fichier n'est plus en attente : renvoyez-le (il n'est gardé que 30 minutes, en mémoire).", "/rapprochements/" + id + "/import");
        }
        Correspondance affichee = choisie != null ? choisie : etape.correspondance();
        model.addAttribute("releve", releves.trouver(id));
        model.addAttribute("etape", etape);
        model.addAttribute("avertissement", avertissement);
        model.addAttribute("lignesBrutes", etape.tableau().lignes().subList(0, Math.min(ImportReleveService.LIGNES_BRUTES, etape.tableau().lignes().size())));
        model.addAttribute("apercu", etape.analyse() == null ? List.of() : etape.analyse().apercu(ImportReleveService.APERCU));
        model.addAttribute("importForm", formulaireDe(affichee, avecDoublons));
        model.addAttribute("formats", org.example.stock.enums.FormatDateReleve.values());
        model.addAttribute("separateurs", org.example.stock.enums.SeparateurDecimal.values());
        model.addAttribute("view", "rapprochement/correspondance");
        return "dashboard";
    }

    @PostMapping("/{id}/import/{jeton}")
    public String confirmer(@PathVariable Long id, @PathVariable String jeton, @ModelAttribute("importForm") ImportReleveForm f, BindingResult result, RedirectAttributes redirection) {
        FormBindingAdvice.verifier(result);
        String retour = "/rapprochements/" + id + "/import/" + jeton;
        Correspondance corr;
        try { corr = lire(f); } catch (IllegalArgumentException | ImportReleveException e) { return erreur(redirection, e.getMessage(), retour); }
        boolean avecDoublons = "oui".equals(f.getAvecDoublons());
        try {
            int n = imports.confirmer(id, jeton, corr, avecDoublons);
            redirection.addFlashAttribute("success", n + (n > 1 ? " lignes importées." : " ligne importée.") + " La correspondance des colonnes est mémorisée pour ce compte ; le fichier n'est pas conservé.");
            return "redirect:/rapprochements/" + id;
        } catch (IllegalArgumentException e) {
            return erreur(redirection, e.getMessage(), retour);
        } catch (ConcurrencyFailureException e) {
            throw conflit();
        }
    }

    @PostMapping("/{id}/import/{jeton}/abandonner")
    public String abandonner(@PathVariable Long id, @PathVariable String jeton, RedirectAttributes redirection, HttpServletRequest request) {
        for (String nom : request.getParameterMap().keySet()) if (!nom.equals("_csrf")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champ de formulaire non autorisé");
        imports.abandonner(id, jeton);
        redirection.addFlashAttribute("success", "Import abandonné : le fichier a été oublié.");
        return "redirect:/rapprochements/" + id;
    }

    /** Correspondance choisie : un montant signé, ou un débit et un crédit selon le mode ; les colonnes de l'autre mode sont ignorées. */
    private static Correspondance lire(ImportReleveForm f) {
        boolean debitCredit = "DEBIT_CREDIT".equals(f.getModeMontant());
        if (f.getColonneDate() == null || f.getColonneLibelle() == null) throw new IllegalArgumentException("Choisissez la colonne de la date et celle du libellé.");
        if (f.getLigneEntete() == null) throw new IllegalArgumentException("Choisissez la ligne d'en-tête (0 s'il n'y en a pas).");
        try {
            return new Correspondance(f.getLigneEntete(), f.getColonneDate(), f.getColonneLibelle(), f.getColonneReference(),
                    debitCredit ? null : f.getColonneMontant(), debitCredit ? f.getColonneDebit() : null, debitCredit ? f.getColonneCredit() : null,
                    ImportReleveService.format(f.getFormatDate()), ImportReleveService.separateur(f.getSeparateur()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Format de date ou séparateur décimal invalide.");
        }
    }

    private static ImportReleveForm formulaireDe(Correspondance c, boolean avecDoublons) {
        ImportReleveForm f = new ImportReleveForm();
        f.setAvecDoublons(avecDoublons ? "oui" : null);
        if (c == null) { f.setModeMontant("SIGNE"); f.setLigneEntete(1); f.setFormatDate("JJ_MM_AAAA"); f.setSeparateur("VIRGULE"); return f; }
        f.setLigneEntete(c.ligneEntete()); f.setColonneDate(c.colonneDate()); f.setColonneLibelle(c.colonneLibelle()); f.setColonneReference(c.colonneReference());
        f.setModeMontant(c.montantSigne() ? "SIGNE" : "DEBIT_CREDIT"); f.setColonneMontant(c.colonneMontant()); f.setColonneDebit(c.colonneDebit()); f.setColonneCredit(c.colonneCredit());
        f.setFormatDate(c.formatDate().name()); f.setSeparateur(c.separateur().name());
        return f;
    }

    // ---- Pointage -------------------------------------------------------------------------------------------

    @GetMapping("/{id}/pointage")
    public String pointage(@PathVariable Long id, @RequestParam(name = "tous", required = false) List<String> tous, Model model) {
        boolean tousLesMouvements = ParametresListe.unique("tous", tous) != null;
        model.addAttribute("ecran", pointage.ecran(id, tousLesMouvements));
        model.addAttribute("pointageForm", new PointageForm());
        model.addAttribute("naturesCredit", RapprochementService.naturesPour(java.math.BigDecimal.ONE));
        model.addAttribute("naturesDebit", RapprochementService.naturesPour(java.math.BigDecimal.ONE.negate()));
        model.addAttribute("view", "rapprochement/pointage");
        return "dashboard";
    }

    @PostMapping("/{id}/pointage/accepter")
    public String accepter(@PathVariable Long id, @ModelAttribute("pointageForm") PointageForm f, BindingResult result, RedirectAttributes redirection) {
        FormBindingAdvice.verifier(result);
        return agir(redirection, retourPointage(id), "Proposition acceptée : la ligne et le mouvement sont rapprochés.", () -> pointage.accepter(id, f.getLigne(), f.getMouvement()));
    }

    @PostMapping("/{id}/pointage/refuser")
    public String refuser(@PathVariable Long id, @ModelAttribute("pointageForm") PointageForm f, BindingResult result, RedirectAttributes redirection) {
        FormBindingAdvice.verifier(result);
        return agir(redirection, retourPointage(id), "Proposition refusée : elle ne sera plus reproposée pour ce relevé.", () -> pointage.refuser(id, f.getLigne(), f.getMouvement()));
    }

    @PostMapping("/{id}/pointage/tout-accepter")
    public String toutAccepter(@PathVariable Long id, @ModelAttribute("pointageForm") PointageForm f, BindingResult result, RedirectAttributes redirection) {
        FormBindingAdvice.verifier(result);
        try {
            int n = pointage.toutAccepter(id);
            redirection.addFlashAttribute("success", n == 0 ? "Aucune proposition à accepter." : n + (n > 1 ? " propositions acceptées." : " proposition acceptée."));
            return "redirect:" + retourPointage(id);
        } catch (IllegalArgumentException e) {
            return erreur(redirection, e.getMessage(), retourPointage(id));
        } catch (ConcurrencyFailureException e) {
            throw conflit();
        }
    }

    @PostMapping("/{id}/pointage/reinitialiser-refus")
    public String reinitialiserRefus(@PathVariable Long id, @ModelAttribute("pointageForm") PointageForm f, BindingResult result, RedirectAttributes redirection) {
        FormBindingAdvice.verifier(result);
        return agir(redirection, retourPointage(id), "Les propositions refusées reviennent.", () -> pointage.reinitialiserRefus(id));
    }

    @PostMapping("/{id}/pointage/rapprocher")
    public String rapprocher(@PathVariable Long id, @ModelAttribute("pointageForm") PointageForm f, BindingResult result, RedirectAttributes redirection) {
        FormBindingAdvice.verifier(result);
        return agir(redirection, retourPointage(id), "Rapprochement enregistré.", () -> pointage.rapprocher(id, f.getLignes(), f.getMouvements()));
    }

    @PostMapping("/{id}/pointage/exclure")
    public String exclure(@PathVariable Long id, @ModelAttribute("pointageForm") PointageForm f, BindingResult result, RedirectAttributes redirection) {
        FormBindingAdvice.verifier(result);
        return agir(redirection, retourPointage(id), "Le mouvement annulé et son annulation sont exclus ensemble.", () -> pointage.exclure(id, f.getMouvement()));
    }

    @PostMapping("/{id}/pointage/defaire")
    public String defaire(@PathVariable Long id, @ModelAttribute("pointageForm") PointageForm f, BindingResult result, RedirectAttributes redirection) {
        FormBindingAdvice.verifier(result);
        return agir(redirection, retourPointage(id), "Rapprochement défait : la ligne et les mouvements sont de nouveau libres.", () -> pointage.defaire(id, f.getRapprochement()));
    }

    @PostMapping("/{id}/pointage/enregistrer")
    public String enregistrerDansAppli(@PathVariable Long id, @ModelAttribute("pointageForm") PointageForm f, BindingResult result, RedirectAttributes redirection) {
        FormBindingAdvice.verifier(result);
        TypeOperationTresorerie nature;
        try { nature = f.getNature() == null || f.getNature().isBlank() ? null : TypeOperationTresorerie.valueOf(f.getNature()); }
        catch (IllegalArgumentException e) { return erreur(redirection, "Nature inconnue.", retourPointage(id)); }
        return agir(redirection, retourPointage(id), "Opération enregistrée dans l'appli à la date de la ligne et rapprochée aussitôt.", () -> pointage.enregistrerDansAppli(id, f.getLigne(), nature));
    }

    private static String retourPointage(Long id) { return "/rapprochements/" + id + "/pointage"; }

    // ---- État de rapprochement, validation ------------------------------------------------------------------

    @GetMapping("/{id}/etat")
    public String etat(@PathVariable Long id, Model model) {
        EtatRapprochementService.Etat etat = etats.calculer(id);
        model.addAttribute("e", etat);
        model.addAttribute("groupes", pointage.groupes(etat.releve()).size());
        model.addAttribute("peutDevalider", validation.peutEtreDevalide(etat.releve()));
        model.addAttribute("historique", validation.historique(id));
        model.addAttribute("decisionReleve", new DecisionReleveForm());
        model.addAttribute("view", "rapprochement/etat");
        return "dashboard";
    }

    @GetMapping("/{id}/etat/pdf")
    public Object pdf(@PathVariable Long id) {
        ReleveBancaire r = releves.trouver(id);
        return TelechargementExport.pdf(r.getNumero() + ".pdf", documents.pdf(id, contextes.courant()));
    }

    @GetMapping("/{id}/etat/excel")
    public Object excel(@PathVariable Long id, RedirectAttributes redirection) {
        ReleveBancaire r = releves.trouver(id);
        return livraison.excel(r.getNumero().toLowerCase(), "/rapprochements/" + id + "/etat", Map.of(), redirection, () -> documents.tableau(id));
    }

    @PostMapping("/{id}/valider")
    public String valider(@PathVariable Long id, RedirectAttributes redirection, HttpServletRequest request) {
        LiaisonReleves.exiger(request, Set.of(), Set.of());
        return agir(redirection, "/rapprochements/" + id + "/etat", "Relevé validé : il est figé avec ses rapprochements.", () -> validation.valider(id));
    }

    @PostMapping("/{id}/devalider")
    public String devalider(@PathVariable Long id, @Valid @ModelAttribute("decisionReleve") DecisionReleveForm f, BindingResult result, RedirectAttributes redirection) {
        FormBindingAdvice.verifier(result);
        if (result.hasErrors()) return erreur(redirection, result.getAllErrors().get(0).getDefaultMessage(), "/rapprochements/" + id + "/etat");
        return agir(redirection, "/rapprochements/" + id + "/etat", "Relevé dévalidé : il redevient modifiable. L'historique est conservé.", () -> validation.devalider(id, f.getMotif()));
    }

    // ---- Outils ---------------------------------------------------------------------------------------------

    private String agir(RedirectAttributes redirection, String retour, String succes, Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException e) {
            return erreur(redirection, e.getMessage(), retour);
        } catch (ConcurrencyFailureException e) {
            throw conflit();
        }
        redirection.addFlashAttribute("success", succes);
        return "redirect:" + retour;
    }

    private static String erreur(RedirectAttributes redirection, String message, String retour) {
        redirection.addFlashAttribute("error", message);
        return "redirect:" + retour;
    }

    private static ResponseStatusException conflit() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Une autre opération modifie ce relevé ou ce compte. Rechargez la page et réessayez.");
    }
}
