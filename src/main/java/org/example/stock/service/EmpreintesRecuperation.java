package org.example.stock.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class EmpreintesRecuperation {
    private EmpreintesRecuperation() {}

    static String calculer(String valeur) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(valeur.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Algorithme d'empreinte indisponible", exception);
        }
    }

    static boolean identiques(String gauche, String droite) {
        return gauche != null && droite != null && MessageDigest.isEqual(
                gauche.getBytes(StandardCharsets.US_ASCII), droite.getBytes(StandardCharsets.US_ASCII));
    }
}
