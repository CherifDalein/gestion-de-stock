package org.example.stock.export;

import org.example.stock.service.EcheanceService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Fabrique les réponses d'export Excel et PDF d'une liste : le contrôleur n'a qu'à lire ses filtres et à fournir le
 * {@link TableauExport}. Choix communs :
 * <ul>
 *   <li>PDF en <b>paysage</b> au-delà de {@value #COLONNES_PORTRAIT_MAXIMUM} colonnes, en portrait sinon ;</li>
 *   <li>nom de fichier {@code <base>_<date du jour>.xlsx|pdf} (ou avec une période, voir {@code TelechargementExport.nomFichier}) ;</li>
 *   <li>au-delà de {@link LimiteExport} : retour à la liste (mêmes filtres) avec le message en alerte, rien n'est fabriqué.</li>
 * </ul>
 */
@Component
public class LivraisonExport {
    /** Un tableau de plus de 7 colonnes s'imprime en A4 paysage. */
    public static final int COLONNES_PORTRAIT_MAXIMUM = 7;

    private final ExportExcelService excel;
    private final DocumentPdfService pdf;
    private final ContexteDocumentService contextes;
    private final EcheanceService echeances;

    public LivraisonExport(ExportExcelService excel, DocumentPdfService pdf, ContexteDocumentService contextes, EcheanceService echeances) {
        this.excel = excel; this.pdf = pdf; this.contextes = contextes; this.echeances = echeances;
    }

    public static FormatPapier formatPour(TableauExport tableau) {
        return tableau.getColonnes().size() > COLONNES_PORTRAIT_MAXIMUM ? FormatPapier.A4_PAYSAGE : FormatPapier.A4_PORTRAIT;
    }

    /**
     * @param base      début du nom de fichier (« achats »)
     * @param liste     chemin de la liste à laquelle revenir si l'export est trop volumineux (« /achats »)
     * @param filtres   paramètres de la liste à reprendre dans ce retour (valeurs nulles ignorées)
     * @param preparer  construit le tableau (appelle le service d'export, qui lève {@link ExportTropVolumineuxException})
     * @return la réponse de téléchargement, ou « redirect:… » quand l'export est trop volumineux
     */
    public Object excel(String base, String liste, Map<String, ?> filtres, RedirectAttributes redirection, Supplier<TableauExport> preparer) {
        return produire(liste, filtres, redirection, preparer, (t, c) ->
                TelechargementExport.excel(TelechargementExport.nomFichier(base, echeances.aujourdHui(), null, "xlsx"), excel.ecrire(t, c)));
    }

    public Object pdf(String base, String liste, Map<String, ?> filtres, RedirectAttributes redirection, Supplier<TableauExport> preparer) {
        return produire(liste, filtres, redirection, preparer, (t, c) ->
                TelechargementExport.pdf(TelechargementExport.nomFichier(base, echeances.aujourdHui(), null, "pdf"), pdf.rendreTableau(t, c, formatPour(t))));
    }

    /** Comme {@link #excel} mais avec une période dans le nom du fichier (au moins une borne non nulle, sinon la date du jour). */
    public Object excel(String base, LocalDate du, LocalDate au, String liste, Map<String, ?> filtres, RedirectAttributes redirection,
                        Supplier<TableauExport> preparer) {
        if (du == null && au == null) return excel(base, liste, filtres, redirection, preparer);
        return produire(liste, filtres, redirection, preparer, (t, c) ->
                TelechargementExport.excel(TelechargementExport.nomFichier(base, du, au, "xlsx"), excel.ecrire(t, c)));
    }

    public Object pdf(String base, LocalDate du, LocalDate au, String liste, Map<String, ?> filtres, RedirectAttributes redirection,
                      Supplier<TableauExport> preparer) {
        if (du == null && au == null) return pdf(base, liste, filtres, redirection, preparer);
        return produire(liste, filtres, redirection, preparer, (t, c) ->
                TelechargementExport.pdf(TelechargementExport.nomFichier(base, du, au, "pdf"), pdf.rendreTableau(t, c, formatPour(t))));
    }

    private interface Fabrique { Object faire(TableauExport tableau, ContexteDocument contexte); }

    private Object produire(String liste, Map<String, ?> filtres, RedirectAttributes redirection, Supplier<TableauExport> preparer, Fabrique fabrique) {
        try {
            return fabrique.faire(preparer.get(), contextes.courant());
        } catch (ExportTropVolumineuxException e) {
            redirection.addFlashAttribute("error", e.getMessage());
            UriComponentsBuilder retour = UriComponentsBuilder.fromPath(liste);
            filtres.forEach((nom, valeur) -> { if (valeur != null && !valeur.toString().isEmpty()) retour.queryParam(nom, valeur); });
            return "redirect:" + retour.build().toUriString();
        }
    }
}
