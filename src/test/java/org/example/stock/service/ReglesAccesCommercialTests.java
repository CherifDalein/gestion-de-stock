package org.example.stock.service;

import org.example.stock.model.Boutique;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ReglesAccesCommercialTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 10, 0);

    @Test
    void lesAccesAnterieursSansDatesRestentOuverts() {
        Boutique boutique = new Boutique();
        assertThat(ReglesAccesCommercial.accesExistant(boutique)).isTrue();
        assertThat(ReglesAccesCommercial.estExpire(boutique, NOW)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"-30,0,true", "-30,1,false", "0,30,false", "1,30,true", "1,0,true", "0,0,true"})
    void lesPeriodesRespectentLeurDebutEtLeurFinExclusive(int debut, int fin, boolean expire) {
        Boutique boutique = new Boutique();
        boutique.setAbonnementDebut(NOW.plusDays(debut));
        boutique.setAbonnementFin(NOW.plusDays(fin));
        assertThat(ReglesAccesCommercial.estExpire(boutique, NOW)).isEqualTo(expire);
    }

    @Test
    void lesCouplesIncompletsNeRedonnentPasUnAccesExistant() {
        Boutique boutique = new Boutique();
        boutique.setAbonnementFin(NOW.plusMonths(1));
        assertThat(ReglesAccesCommercial.estExpire(boutique, NOW)).isTrue();
        boutique.setAbonnementFin(null);
        boutique.setAbonnementDebut(NOW.minusMonths(1));
        assertThat(ReglesAccesCommercial.estExpire(boutique, NOW)).isTrue();
        boutique.setAbonnementDebut(null);
        boutique.setEssaiFin(NOW.plusDays(1));
        assertThat(ReglesAccesCommercial.estExpire(boutique, NOW)).isTrue();
    }

    @Test
    void unAbonnementActifPrendLeRelaisDeLessaiExpire() {
        Boutique boutique = new Boutique();
        boutique.setEssaiDebut(NOW.minusDays(30));
        boutique.setEssaiFin(NOW);
        boutique.setAbonnementDebut(NOW);
        boutique.setAbonnementFin(NOW.plusMonths(1));
        assertThat(ReglesAccesCommercial.estExpire(boutique, NOW)).isFalse();
        assertThat(ReglesAccesCommercial.abonnementActif(boutique, NOW)).isTrue();
        assertThat(ReglesAccesCommercial.estExpire(boutique, boutique.getAbonnementFin())).isTrue();
    }

    @Test
    void unEssaiEncoreValideAutoriseLaccesMalgreUnAbonnementExpire() {
        Boutique boutique = new Boutique();
        boutique.setEssaiDebut(NOW.minusDays(30));
        boutique.setEssaiFin(NOW.plusDays(5));
        boutique.setAbonnementDebut(NOW.minusMonths(1));
        boutique.setAbonnementFin(NOW);
        assertThat(ReglesAccesCommercial.estExpire(boutique, NOW)).isFalse();
    }

    @Test
    void leDernierNanosecondeAutoriseLaccesPuisLaFinLeFerme() {
        Boutique boutique = new Boutique();
        boutique.setAbonnementDebut(NOW.minusMonths(1));
        boutique.setAbonnementFin(NOW.plusNanos(1));
        assertThat(ReglesAccesCommercial.estExpire(boutique, NOW)).isFalse();
        assertThat(ReglesAccesCommercial.estExpire(boutique, NOW.plusNanos(1))).isTrue();
    }

    @Test
    void laccesOffertNeDependPasDesDatesEtLaSuspensionResteIndependante() {
        Boutique boutique = new Boutique();
        boutique.setEssaiDebut(NOW);
        boutique.setActif(false);
        boutique.setAccesOffert(true);
        assertThat(ReglesAccesCommercial.estExpire(boutique, NOW)).isFalse();
        assertThat(boutique.isActif()).isFalse();
        boutique.setAccesOffert(false);
        assertThat(ReglesAccesCommercial.estExpire(boutique, NOW)).isTrue();
    }
}
