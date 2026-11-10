package org.example.stock.recu;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.example.stock.enums.FormatRecu;
import org.example.stock.export.ContexteDocument;
import org.example.stock.export.DocumentPdfService;
import org.example.stock.export.FormatPapier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * Met un reçu au format de la boutique : A4 portrait comme la facture, ou ticket de 80 mm dont la hauteur épouse le contenu.
 *
 * <p>Un ticket n'a pas de hauteur naturelle : le gabarit est d'abord rendu sur une page très haute, la position du dernier
 * texte est mesurée avec PDFBox, puis le même gabarit est rendu sur une page juste assez haute (contenu, marge basse et
 * une petite réserve). Si le ticket dépasse malgré tout une page de 2 m, la première version est conservée.</p>
 */
@Component
public class RecuPdf {
    /** Zone imprimable d'un ticket : 80 mm moins 3 mm de marge de chaque côté. */
    static final int LARGEUR_TICKET_MM = 80;
    private static final int HAUTEUR_MAXIMALE_MM = 2000;
    private static final int HAUTEUR_MINIMALE_MM = 60;
    private static final double MARGE_BASSE_MM = 4;
    private static final double RESERVE_MM = 3;
    private static final double MM_PAR_POINT = 25.4 / 72;
    /** Au-delà, le logo alourdit le ticket (la plupart des imprimantes de caisse impriment l'image point par point) : il est omis. */
    static final int LOGO_TICKET_MAX_OCTETS = 100 * 1024;

    private final DocumentPdfService pdf;

    public RecuPdf(DocumentPdfService pdf) { this.pdf = pdf; }

    public byte[] rendre(String gabarit, Map<String, Object> variables, ContexteDocument contexte, FormatRecu format) {
        variables = new java.util.HashMap<>(variables);
        variables.put("cssGabarit", css(gabarit));
        if (format != FormatRecu.TICKET_80) return pdf.rendre(gabarit, variables, contexte, FormatPapier.A4_PORTRAIT);
        ContexteDocument ticket = sansLogoLourd(contexte);
        byte[] haut = pdf.rendre(gabarit, variables, ticket, ticket(HAUTEUR_MAXIMALE_MM));
        Double bas = basDuContenuMm(haut);
        if (bas == null) return haut;
        int hauteur = (int) Math.max(HAUTEUR_MINIMALE_MM, Math.ceil(bas + MARGE_BASSE_MM + RESERVE_MM));
        // La mise en page d'une page courte peut différer d'un millimètre : on agrandit jusqu'à tenir sur une seule page.
        for (int essai = 0; essai < 6; essai++, hauteur += 2) {
            byte[] ajuste = pdf.rendre(gabarit, variables, ticket, ticket(hauteur));
            if (nombrePages(ajuste) == 1) return ajuste;
        }
        return haut;
    }

    /** Feuille de style du gabarit ({@code pdf/<nom>.css}), injectée dans l'en-tête par {@code pdf/base.html}. */
    private static String css(String gabarit) {
        String nom = "/pdf/" + gabarit.substring(gabarit.lastIndexOf('/') + 1) + ".css";
        try (var flux = RecuPdf.class.getResourceAsStream(nom)) {
            if (flux == null) throw new IllegalStateException("Feuille de style introuvable : " + nom);
            return new String(flux.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture de la feuille de style impossible", e);
        }
    }

    static ContexteDocument sansLogoLourd(ContexteDocument c) {
        if (!c.aUnLogo() || tailleLogo(c.logoDataUri()) <= LOGO_TICKET_MAX_OCTETS) return c;
        return new ContexteDocument(c.nomBoutique(), c.adresse(), c.telephones(), c.email(), null, c.editeur(), c.editeLe());
    }

    /** Taille du logo décodé, estimée sur le texte base64 (3 octets pour 4 caractères). */
    private static long tailleLogo(String dataUri) {
        int virgule = dataUri.indexOf(',');
        return (long) ((dataUri.length() - virgule - 1) * 0.75);
    }

    /** Ticket de 80 mm de large dont la hauteur est donnée en millimètres (le socle limite {@code ticket(int)} à 100 mm au minimum). */
    static FormatPapier ticket(int hauteurMm) {
        return hauteurMm >= 100 ? FormatPapier.ticket(hauteurMm) : new FormatPapier("Ticket 80 mm", LARGEUR_TICKET_MM, hauteurMm, "4mm 3mm 4mm 3mm", false);
    }

    /** Bas du dernier texte, en millimètres depuis le haut de la première page ; null si le document a plusieurs pages ou aucun texte. */
    static Double basDuContenuMm(byte[] contenu) {
        try (PDDocument document = Loader.loadPDF(contenu)) {
            if (document.getNumberOfPages() != 1) return null;
            double[] bas = {0};
            PDFTextStripper lecteur = new PDFTextStripper() {
                @Override
                protected void writeString(String texte, List<TextPosition> positions) {
                    for (TextPosition p : positions) bas[0] = Math.max(bas[0], p.getYDirAdj() + 0.3 * p.getFontSizeInPt());
                }
            };
            lecteur.getText(document);
            return bas[0] == 0 ? null : bas[0] * MM_PAR_POINT;
        } catch (IOException e) {
            throw new UncheckedIOException("Mesure du reçu impossible", e);
        }
    }

    static int nombrePages(byte[] contenu) {
        try (PDDocument document = Loader.loadPDF(contenu)) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture du reçu impossible", e);
        }
    }
}
