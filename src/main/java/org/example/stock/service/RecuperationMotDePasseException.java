package org.example.stock.service;

public final class RecuperationMotDePasseException extends RuntimeException {
    private final String champ;

    public RecuperationMotDePasseException(String champ, String message) {
        super(message);
        this.champ = champ;
    }

    public String getChamp() { return champ; }
}
