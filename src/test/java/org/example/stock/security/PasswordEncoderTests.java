package org.example.stock.security;

import org.example.stock.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PasswordEncoderTests {
    private final PasswordEncoder encoder = new SecurityConfig().passwordEncoder();

    // Empreintes de mots de passe fictifs, produites avec Spring Security 6.2.4.
    private static final String ANCIEN_HASH =
            "$2a$10$.J5tnOlvK5hz1YmzGTfCveVTFmwylAyCesO4/wU4eI9S0zgbIyYm2";
    private static final String HASH_LIMITE_ASCII =
            "$2a$10$2nIZNaJYnCgS8.dHd5/OPeUcqBvBKVtI5q90FbS54pDBq2VfDnkV.";
    private static final String HASH_LIMITE_UTF8 =
            "$2a$10$D.yFX5LSx/mOvjFb9vISYuZ22TNG7ecH7z5/NGBfmwAs.aUow9NWW";

    @Test
    void lesEmpreintesExistantesRestentUtilisables() {
        assertThat(encoder.matches("MotDePasseTest42", ANCIEN_HASH)).isTrue();
        assertThat(encoder.matches("MotDePasseIncorrect", ANCIEN_HASH)).isFalse();
    }

    @Test
    void unSuffixeApresLaLimiteBcryptNestPlusAccepte() {
        String motDePasse = "a".repeat(72);
        assertThat(encoder.matches(motDePasse, HASH_LIMITE_ASCII)).isTrue();
        assertThat(encoder.matches(motDePasse + "suffixe", HASH_LIMITE_ASCII)).isFalse();
    }

    @Test
    void laLimiteEstMesureeEnOctetsUtf8() {
        String motDePasse = "é".repeat(36);
        assertThat(encoder.matches(motDePasse, HASH_LIMITE_UTF8)).isTrue();
        assertThat(encoder.matches(motDePasse + "é", HASH_LIMITE_UTF8)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> encoder.encode(motDePasse + "é"));
    }

    @Test
    void unNouveauMotDePasseTropLongNestPasTronque() {
        assertThatIllegalArgumentException().isThrownBy(() -> encoder.encode("a".repeat(73)));
    }
}
