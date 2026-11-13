package org.example.stock.releve;

import java.util.Locale;

/**
 * Point d'entrée de la lecture d'un fichier de relevé (CSV ou Excel .xlsx). Contrôles : taille (2 Mo), extension, contenu réel (un classeur
 * est une archive ZIP, un CSV est un texte sans octet nul) et nombre de colonnes. Le fichier n'est jamais écrit sur le disque.
 */
public final class LecteurFichierReleve {
    public static final int TAILLE_MAX = 2 * 1024 * 1024;
    /** Lignes de données importables par fichier. */
    public static final int LIGNES_MAX = 5_000;
    /** Lignes lues au plus, vides et en-têtes compris, avant de refuser le fichier. */
    static final int LIGNES_LUES_MAX = 20_000;
    static final int COLONNES_MAX = 60;

    private LecteurFichierReleve() { }

    public static TableauFichier lire(String nomFichier, byte[] contenu) {
        if (contenu == null || contenu.length == 0) throw new ImportReleveException("Ce fichier est vide.");
        if (contenu.length > TAILLE_MAX) throw new ImportReleveException("Ce fichier dépasse 2 Mo. Divisez le relevé en plusieurs fichiers.");
        String nom = nomFichier == null ? "" : nomFichier.toLowerCase(Locale.ROOT).strip();
        boolean archive = contenu.length >= 4 && contenu[0] == 'P' && contenu[1] == 'K' && contenu[2] == 3 && contenu[3] == 4;
        boolean pdf = contenu.length >= 4 && contenu[0] == '%' && contenu[1] == 'P' && contenu[2] == 'D' && contenu[3] == 'F';
        boolean ancienExcel = contenu.length >= 4 && (contenu[0] & 0xFF) == 0xD0 && (contenu[1] & 0xFF) == 0xCF && (contenu[2] & 0xFF) == 0x11 && (contenu[3] & 0xFF) == 0xE0;
        if (nom.endsWith(".xls") || ancienExcel) throw new ImportReleveException("Le format Excel ancien (.xls) n'est pas pris en charge. Enregistrez le fichier au format .xlsx ou en CSV.");
        if (pdf) throw new ImportReleveException("Un PDF ne peut pas être importé. Choisissez un fichier CSV ou Excel (.xlsx).");
        if (nom.endsWith(".xlsx")) {
            if (!archive) throw new ImportReleveException("Ce fichier n'est pas un classeur Excel (.xlsx) : son contenu ne correspond pas à son extension.");
            return LecteurExcel.lire(contenu, LIGNES_LUES_MAX);
        }
        if (nom.endsWith(".csv") || nom.endsWith(".txt")) {
            if (archive) throw new ImportReleveException("Ce fichier n'est pas un fichier CSV : son contenu ne correspond pas à son extension.");
            return LecteurCsv.lire(contenu, LIGNES_LUES_MAX);
        }
        throw new ImportReleveException("Type de fichier non accepté. Choisissez un fichier CSV (.csv) ou Excel (.xlsx).");
    }
}
