package org.example.stock.config;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;

/** Empêche qu'un suffixe soit ignoré au-delà de la limite BCrypt. */
public class LengthCheckedBcryptPasswordEncoder extends BCryptPasswordEncoder {
    private static final int MAX_PASSWORD_BYTES = 72;

    @Override
    public String encode(CharSequence rawPassword) {
        if (rawPassword != null && exceedsLimit(rawPassword)) {
            throw new IllegalArgumentException("Le mot de passe est trop long (maximum 72 octets en UTF-8).");
        }
        return super.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        // Calculer aussi pour une saisie trop longue : le fournisseur d'authentification
        // effectue cette même vérification contre une empreinte factice si le compte est absent.
        boolean matches = super.matches(rawPassword, encodedPassword);
        return !exceedsLimit(rawPassword) && matches;
    }

    private boolean exceedsLimit(CharSequence password) {
        return password.toString().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
    }
}
