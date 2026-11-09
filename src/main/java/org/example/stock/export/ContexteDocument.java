package org.example.stock.export;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Identité de la boutique et circonstances d'édition d'un document : ce qui figure dans l'en-tête et le pied de tous
 * les PDF et dans l'en-tête de tous les Excel. Toujours construit par {@link ContexteDocumentService#courant()} à partir
 * du compte connecté, jamais à partir de paramètres du navigateur.
 *
 * @param logoDataUri logo PNG de la boutique en {@code data:image/png;base64,…} ; null s'il n'y en a pas
 * @param editeur     nom du compte qui édite le document
 */
public record ContexteDocument(String nomBoutique, String adresse, String telephones, String email, String logoDataUri,
                               String editeur, LocalDateTime editeLe) {

    /** Les lignes de coordonnées non vides, dans l'ordre d'affichage : adresse, téléphones, email. */
    public List<String> coordonnees() {
        return java.util.stream.Stream.of(adresse, telephones, email).filter(t -> t != null && !t.isBlank()).toList();
    }

    public boolean aUnLogo() { return logoDataUri != null && !logoDataUri.isBlank(); }

    public String editeLeTexte() { return Textes.dateHeure(editeLe); }

    /** Texte du pied de page, protégé pour être placé dans une chaîne CSS entre guillemets. */
    public String piedCss() {
        String texte = Textes.pourXml(nomBoutique);
        StringBuilder sb = new StringBuilder();
        for (char c : texte.toCharArray()) {
            if (c == '\\' || c == '"') sb.append('\\').append(c);
            else if (c == '\n' || c == '\r' || c == '\t' || c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }
}
