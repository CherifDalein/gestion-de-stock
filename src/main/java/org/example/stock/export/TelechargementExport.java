package org.example.stock.export;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * Réponses de téléchargement des exports : type de contenu exact, {@code Content-Disposition: attachment} avec un nom de
 * fichier sûr (ASCII, sans espace ni séparateur de chemin) et {@code Cache-Control: no-store} (ces documents contiennent
 * des données de la boutique : ni cache du navigateur ni cache intermédiaire).
 *
 * <pre>{@code
 * return TelechargementExport.excel(TelechargementExport.nomFichier("ventes", du, au, "xlsx"), octets);
 * }</pre>
 */
public final class TelechargementExport {
    public static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    public static final MediaType PDF = MediaType.APPLICATION_PDF;
    private static final Pattern MARQUES = Pattern.compile("\\p{M}+");
    private static final Pattern INTERDITS = Pattern.compile("[^A-Za-z0-9._-]+");

    private TelechargementExport() { }

    public static ResponseEntity<byte[]> excel(String nomFichier, byte[] contenu) {
        return reponse(XLSX, nomFichier, contenu);
    }

    public static ResponseEntity<byte[]> pdf(String nomFichier, byte[] contenu) {
        return reponse(PDF, nomFichier, contenu);
    }

    private static ResponseEntity<byte[]> reponse(MediaType type, String nomFichier, byte[] contenu) {
        return ResponseEntity.ok()
                .contentType(type)
                .contentLength(contenu.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(nomSur(nomFichier)).build().toString())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .header("X-Content-Type-Options", "nosniff")
                .body(contenu);
    }

    /**
     * {@code <base>_<du>_<au>.<extension>} avec une période, {@code <base>_<du>.<extension>} avec une seule date
     * (en général la date d'édition), {@code <base>.<extension>} sans date. Exemple : {@code ventes_2026-11-01_2026-11-30.xlsx}.
     */
    public static String nomFichier(String base, LocalDate du, LocalDate au, String extension) {
        StringBuilder nom = new StringBuilder(nomSur(base));
        if (du != null) nom.append('_').append(du);
        if (au != null) nom.append('_').append(au);
        return nom.append('.').append(nomSur(extension).replace(".", "")).toString();
    }

    /** Nom de fichier sûr : accents retirés, tout caractère hors lettres, chiffres, point, tiret et souligné remplacé par « _ ». */
    public static String nomSur(String nom) {
        String sansAccent = MARQUES.matcher(Normalizer.normalize(nom == null ? "" : nom, Normalizer.Form.NFD)).replaceAll("");
        String propre = INTERDITS.matcher(sansAccent.replace("œ", "oe").replace("Œ", "OE").replace("..", ".")).replaceAll("_");
        propre = propre.replaceAll("^[._-]+", "").replaceAll("_{2,}", "_");
        if (propre.length() > 100) propre = propre.substring(0, 100);
        return propre.isEmpty() ? "export" : propre;
    }
}
