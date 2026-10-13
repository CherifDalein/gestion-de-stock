package org.example.stock.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class SignupPropertiesTests {
    @Test
    void laDureeParDefautEst24HeuresEtLesBornesSontAcceptees() {
        SignupProperties properties = new SignupProperties();
        assertThat(properties.getTtl()).isEqualTo(Duration.ofHours(24));
        properties.valider();
        properties.setTtl(Duration.ofHours(1)); properties.valider();
        properties.setTtl(Duration.ofHours(48)); properties.valider();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT59M", "PT49H", "PT-1H"})
    void uneDureeHorsBornesEstRefusee(String duree) {
        SignupProperties properties = new SignupProperties(); properties.setTtl(Duration.parse(duree));
        assertThatThrownBy(properties::valider).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void uneDureeNulleEstRefusee() {
        SignupProperties properties = new SignupProperties(); properties.setTtl(null);
        assertThatThrownBy(properties::valider).isInstanceOf(IllegalStateException.class);
    }
}
