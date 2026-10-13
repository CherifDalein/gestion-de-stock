package org.example.stock.service;

public class AbonnementException extends IllegalArgumentException {
    private final String champ;

    public AbonnementException(String champ, String message) {
        super(message);
        this.champ = champ;
    }

    public String getChamp() { return champ; }
}
