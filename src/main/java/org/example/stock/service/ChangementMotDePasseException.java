package org.example.stock.service;

/** Une erreur de saisie peut être affichée sur son champ sans conserver la valeur rejetée. */
public class ChangementMotDePasseException extends RuntimeException {
    private final String champ;

    public ChangementMotDePasseException(String champ, String message) {
        super(message);
        this.champ = champ;
    }

    public String getChamp() {
        return champ;
    }
}
