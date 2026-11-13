package org.example.stock.releve;

/** Fichier de relevé refusé (taille, type, contenu illisible, correspondance incomplète). Le message est destiné à l'écran, en français. */
public class ImportReleveException extends RuntimeException {
    public ImportReleveException(String message) { super(message); }
}
