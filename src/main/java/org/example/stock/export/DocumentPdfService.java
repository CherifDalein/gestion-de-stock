package org.example.stock.export;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rend un gabarit Thymeleaf en VRAI fichier PDF (Thymeleaf produit du XHTML, openhtmltopdf le met en page).
 *
 * <ul>
 *   <li>{@link #rendre(String, Map, FormatPapier)} : n'importe quel gabarit de {@code templates/pdf/} (reçu, justificatif…).
 *       Le gabarit reçoit en plus {@code doc} ({@link ContexteDocument}) et {@code cssPage} (règle {@code @page}).</li>
 *   <li>{@link #rendreTableau(TableauExport, FormatPapier)} : le gabarit {@code pdf/tableau} pour une liste ou un état.</li>
 * </ul>
 *
 * <p>La police DejaVu Sans (normale et grasse, {@code resources/polices}) est embarquée dans chaque PDF : accents, « œ »,
 * espaces insécables, « ’ », « € » et « — » s'impriment partout de la même façon. Aucune requête sortante : le logo est
 * une image {@code data:} et toute autre URL de ressource est refusée. Pas de JavaScript, pas de Bootstrap : voir
 * {@code docs/lot-4.md} pour les contraintes du XHTML.</p>
 */
@Service
public class DocumentPdfService {
    private final SpringTemplateEngine moteur;
    private final ContexteDocumentService contextes;
    private final byte[] policeNormale, policeGrasse;

    public DocumentPdfService(SpringTemplateEngine moteur, ContexteDocumentService contextes) {
        this.moteur = moteur;
        this.contextes = contextes;
        this.policeNormale = lire("polices/DejaVuSans.ttf");
        this.policeGrasse = lire("polices/DejaVuSans-Bold.ttf");
    }

    /** Rend un tableau (liste, état) au format A4 portrait. */
    public byte[] rendreTableau(TableauExport tableau) {
        return rendreTableau(tableau, FormatPapier.A4_PORTRAIT);
    }

    public byte[] rendreTableau(TableauExport tableau, FormatPapier format) {
        return rendreTableau(tableau, contextes.courant(), format);
    }

    /** Variante avec un contexte déjà construit (un seul accès base pour produire Excel et PDF, ou pour les tests). */
    public byte[] rendreTableau(TableauExport tableau, ContexteDocument contexte, FormatPapier format) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("titre", tableau.getTitre());
        variables.put("tableau", vue(tableau));
        return rendre("pdf/tableau", variables, contexte, format);
    }

    /** Rend le gabarit {@code templates/<gabarit>.html} (ex. « pdf/recu-vente ») avec les variables données. */
    public byte[] rendre(String gabarit, Map<String, Object> variables, FormatPapier format) {
        return rendre(gabarit, variables, contextes.courant(), format);
    }

    public byte[] rendre(String gabarit, Map<String, Object> variables, ContexteDocument contexte, FormatPapier format) {
        if (gabarit == null || !gabarit.startsWith("pdf/")) {
            throw new IllegalArgumentException("Un gabarit PDF se trouve dans templates/pdf/ : " + gabarit);
        }
        Context thymeleaf = new Context(Locale.FRANCE);
        thymeleaf.setVariables(variables);
        thymeleaf.setVariable("doc", contexte);
        thymeleaf.setVariable("format", format);
        thymeleaf.setVariable("cssPage", cssPage(contexte, format));
        String xhtml = Textes.pourXml(moteur.process(gabarit, thymeleaf));
        return enPdf(xhtml);
    }

    private byte[] enPdf(String xhtml) {
        try (ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            PdfRendererBuilder constructeur = new PdfRendererBuilder();
            constructeur.useFastMode();
            constructeur.useFont(() -> new ByteArrayInputStream(policeNormale), "DejaVu Sans", 400, BaseRendererBuilder.FontStyle.NORMAL, true);
            constructeur.useFont(() -> new ByteArrayInputStream(policeGrasse), "DejaVu Sans", 700, BaseRendererBuilder.FontStyle.NORMAL, true);
            // Seules les images data: sont chargées : jamais de requête réseau ni de lecture de fichier depuis un gabarit.
            constructeur.useUriResolver((base, uri) -> uri != null && uri.startsWith("data:") ? uri : null);
            constructeur.withHtmlContent(xhtml, "about:blank");
            constructeur.toStream(sortie);
            constructeur.run();
            return sortie.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Génération du PDF impossible", e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Génération du PDF impossible : le gabarit n'est probablement pas du XHTML valide", e);
        }
    }

    /** Règle {@code @page} : taille, marges et pied « Page X / Y ». Aucune donnée saisie par l'utilisateur hors nom de boutique, échappé. */
    String cssPage(ContexteDocument contexte, FormatPapier format) {
        StringBuilder css = new StringBuilder("@page { size: ").append(format.tailleCss()).append("; margin: ").append(format.margesCss()).append(";");
        if (format.pied()) {
            css.append(" @bottom-left { content: \"").append(contexte.piedCss()).append("\"; font-family: 'DejaVu Sans'; font-size: 7.5pt; color: #6b7280; }")
               .append(" @bottom-right { content: \"Page \" counter(page) \" / \" counter(pages); font-family: 'DejaVu Sans'; font-size: 7.5pt; color: #6b7280; }");
        }
        return css.append(" }").toString();
    }

    // ----- Vue du tableau pour le gabarit -----

    public record ColonneVue(String titre, boolean numerique, boolean sansRetour) { }

    public record TableauVue(String titre, String filtres, List<String> notes, List<ColonneVue> colonnes, List<List<String>> lignes,
                             List<String> totaux, boolean aDesMontants, boolean dense) { }

    private static TableauVue vue(TableauExport t) {
        List<Colonne> colonnes = t.getColonnes();
        List<List<String>> lignes = new ArrayList<>();
        for (List<Object> ligne : t.getLignes()) lignes.add(textes(colonnes, ligne, false));
        return new TableauVue(t.getTitre(), t.getFiltres(), t.getNotes(),
                colonnes.stream().map(c -> new ColonneVue(c.titre(), c.estNumerique(), c.estNumerique() || c.sansRetour()
                        || c.type() == TypeColonne.DATE || c.type() == TypeColonne.DATE_HEURE)).toList(),
                lignes, t.getTotaux() == null ? null : textes(colonnes, t.getTotaux(), true), t.aDesMontants(), colonnes.size() > 8);
    }

    private static List<String> textes(List<Colonne> colonnes, List<Object> valeurs, boolean totaux) {
        List<String> textes = new ArrayList<>(colonnes.size());
        for (int i = 0; i < colonnes.size(); i++) textes.add(texte(colonnes.get(i), valeurs.get(i), totaux));
        return textes;
    }

    static String texte(Colonne colonne, Object valeur, boolean totaux) {
        if (valeur == null) return "";
        if (totaux && valeur instanceof CharSequence s && colonne.type() != TypeColonne.TEXTE) return s.toString(); // libellé de totaux
        try {
            return switch (colonne.type()) {
                case TEXTE -> valeur.toString();
                case ENTIER -> Textes.entier((Number) valeur);
                case MONTANT -> Textes.montant((Number) valeur);
                case POURCENTAGE -> Textes.pourcentage((Number) valeur);
                case DATE -> Textes.date(valeur instanceof java.time.LocalDateTime t ? t.toLocalDate() : (java.time.LocalDate) valeur);
                case DATE_HEURE -> Textes.dateHeure((java.time.LocalDateTime) valeur);
            };
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("La colonne « " + colonne.titre() + " » (" + colonne.type() + ") ne peut pas recevoir une valeur de type "
                    + valeur.getClass().getSimpleName(), e);
        }
    }

    private static byte[] lire(String chemin) {
        try (var flux = new ClassPathResource(chemin).getInputStream()) {
            return flux.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Police introuvable : " + chemin, e);
        }
    }
}
