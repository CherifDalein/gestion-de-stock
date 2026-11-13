package org.example.stock.releve;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;

/** Empreinte d'une ligne de relevé (date, montant, libellé, référence), insensible à la casse et aux espaces multiples : sert aux doublons. */
public final class Empreinte {
    private Empreinte() { }

    public static String calculer(LocalDate date, BigDecimal montant, String libelle, String reference) {
        String texte = date + "|" + montant.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString() + "|" + normaliser(libelle) + "|" + normaliser(reference);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texte.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normaliser(String texte) {
        return texte == null ? "" : texte.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
