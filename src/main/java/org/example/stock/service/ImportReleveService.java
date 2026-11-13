package org.example.stock.service;

import org.example.stock.enums.FormatDateReleve;
import org.example.stock.enums.SeparateurDecimal;
import org.example.stock.export.DroitsExport;
import org.example.stock.model.CorrespondanceImport;
import org.example.stock.model.ReleveBancaire;
import org.example.stock.releve.AnalyseReleve;
import org.example.stock.releve.AnalyseurReleve;
import org.example.stock.releve.Correspondance;
import org.example.stock.releve.ImportReleveException;
import org.example.stock.releve.LecteurFichierReleve;
import org.example.stock.releve.TableauFichier;
import org.example.stock.repository.UtilisateurRepository;
import org.example.stock.model.Emails;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * Import d'un fichier de relevé (CSV ou .xlsx) en deux temps : dépôt (lecture et contrôles du fichier, gardé en mémoire seulement, jamais sur le
 * disque) puis confirmation avec la correspondance des colonnes choisie. Seules les lignes confirmées sont enregistrées.
 */
@Service
public class ImportReleveService {
    /** Lignes du fichier montrées à l'écran de correspondance (en-têtes décalés compris). */
    public static final int LIGNES_BRUTES = 12;
    /** Lignes analysées montrées dans l'aperçu. */
    public static final int APERCU = 10;

    private final ReleveBancaireService releves;
    private final ImportsEnAttente enAttente;
    private final BoutiqueService boutiques;
    private final UtilisateurRepository utilisateurs;

    public ImportReleveService(ReleveBancaireService releves, ImportsEnAttente enAttente, BoutiqueService boutiques, UtilisateurRepository utilisateurs) {
        this.releves = releves; this.enAttente = enAttente; this.boutiques = boutiques; this.utilisateurs = utilisateurs;
    }

    /**
     * @param correspondance choix appliqué (celui du gérant, sinon celui mémorisé pour le compte, sinon une proposition d'après les titres) ;
     *                       {@code null} si aucun
     * @param origine        {@code "choisie"}, {@code "memorisee"}, {@code "proposee"} ou {@code "aucune"}
     * @param analyse        {@code null} tant que la correspondance est absente ou invalide ({@code erreur} dit pourquoi)
     */
    public record Etape(String jeton, String nomFichier, TableauFichier tableau, Correspondance correspondance, String origine, AnalyseReleve analyse, String erreur) { }

    @Transactional(readOnly = true)
    public String deposer(Long releveId, String nomFichier, byte[] contenu) {
        ReleveBancaire r = releves.trouver(releveId);
        ReleveBancaireService.exigerBrouillon(r);
        TableauFichier tableau = LecteurFichierReleve.lire(nomFichier, contenu);
        String nom = nomFichier == null ? "" : nomFichier.replaceAll("[\\p{Cntrl}]", " ").strip();
        return enAttente.deposer(boutiques.idBoutiqueCourante(), releveId, utilisateurId(), nom.length() > 80 ? nom.substring(0, 80) : nom, tableau);
    }

    @Transactional(readOnly = true)
    public Etape etape(Long releveId, String jeton, Correspondance choisie) {
        ReleveBancaire r = releves.trouver(releveId);
        ImportsEnAttente.Entree e = retrouver(jeton, releveId);
        Correspondance corr = choisie; String origine = choisie == null ? "aucune" : "choisie";
        if (corr == null) {
            Optional<CorrespondanceImport> memo = releves.correspondanceMemorisee(r.getCompte().getId());
            if (memo.isPresent() && memoApplicable(memo.get(), e.tableau())) { corr = enCorrespondance(memo.get()); origine = "memorisee"; }
            else { corr = AnalyseurReleve.proposer(e.tableau()); if (corr != null) origine = "proposee"; }
        }
        if (corr == null) return new Etape(jeton, e.nomFichier(), e.tableau(), null, origine, null, null);
        try {
            AnalyseReleve analyse = releves.marquerDoublons(r.getCompte().getId(), AnalyseurReleve.analyser(e.tableau(), corr).verifierPeriode(r.getDateDebut(), r.getDateFin()));
            return new Etape(jeton, e.nomFichier(), e.tableau(), corr, origine, analyse, null);
        } catch (ImportReleveException ex) {
            return new Etape(jeton, e.nomFichier(), e.tableau(), corr, origine, null, ex.getMessage());
        }
    }

    /** Enregistre les lignes valides de l'analyse, mémorise la correspondance pour le compte et efface le fichier de la mémoire. */
    public int confirmer(Long releveId, String jeton, Correspondance corr, boolean avecDoublons) {
        ImportsEnAttente.Entree e = retrouver(jeton, releveId);
        AnalyseReleve analyse;
        try { analyse = AnalyseurReleve.analyser(e.tableau(), corr); }
        catch (ImportReleveException ex) { throw new IllegalArgumentException(ex.getMessage()); }
        int n = releves.importer(releveId, analyse, avecDoublons, corr);
        enAttente.retirer(jeton);
        return n;
    }

    public void abandonner(Long releveId, String jeton) {
        releves.trouver(releveId);
        retrouver(jeton, releveId);
        enAttente.retirer(jeton);
    }

    private ImportsEnAttente.Entree retrouver(String jeton, Long releveId) {
        DroitsExport.exigerGerant();
        return enAttente.retrouver(jeton, boutiques.idBoutiqueCourante(), releveId, utilisateurId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ce fichier n'est plus en attente : renvoyez-le (il n'est gardé que 30 minutes, en mémoire)."));
    }

    private Long utilisateurId() {
        DroitsExport.exigerGerant();
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return utilisateurs.findByEmail(Emails.normaliser(auth.getName())).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN)).getId();
    }

    private static boolean memoApplicable(CorrespondanceImport m, TableauFichier t) {
        int max = Math.max(m.getColonneDate(), Math.max(m.getColonneLibelle(), Math.max(nul(m.getColonneReference()), Math.max(nul(m.getColonneMontant()), Math.max(nul(m.getColonneDebit()), nul(m.getColonneCredit()))))));
        return max <= t.colonnes() && m.getLigneEntete() <= t.lignes().size();
    }

    private static int nul(Integer v) { return v == null ? 0 : v; }

    static Correspondance enCorrespondance(CorrespondanceImport m) {
        return new Correspondance(m.getLigneEntete(), m.getColonneDate(), m.getColonneLibelle(), m.getColonneReference(), m.getColonneMontant(),
                m.getColonneDebit(), m.getColonneCredit(), m.getFormatDate(), m.getSeparateurDecimal());
    }

    public static FormatDateReleve format(String nom) { return nom == null || nom.isBlank() ? null : FormatDateReleve.valueOf(nom); }

    public static SeparateurDecimal separateur(String nom) { return nom == null || nom.isBlank() ? null : SeparateurDecimal.valueOf(nom); }
}
