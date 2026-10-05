package org.example.stock.model;

import java.util.Locale;

public final class Emails {
    private Emails() {}

    public static String normaliser(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
