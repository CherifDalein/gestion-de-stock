package org.example.stock.service;

/** Message destiné au champ de logo, sans publier les détails du fichier ou du décodeur. */
public class LogoBoutiqueInvalideException extends IllegalArgumentException {
    public LogoBoutiqueInvalideException(String message) { super(message); }
}
