package org.example.stock.service;

public class InscriptionBoutiqueException extends RuntimeException {
    private final String champ;

    public InscriptionBoutiqueException(String champ, String message) {
        super(message);
        this.champ = champ;
    }

    public String getChamp() { return champ; }
}
