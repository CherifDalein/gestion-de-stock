package org.example.stock.service;

import org.example.stock.config.RecoveryProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class RecoveryPropertiesTests {
    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8080", "http://127.0.0.1:8080/", "http://[::1]:8080", "https://stock.example.test"})
    void uneOrigineDeConfianceExpliciteEstAcceptee(String baseUrl) {
        RecoveryProperties properties = new RecoveryProperties(); properties.setBaseUrl(baseUrl);
        assertThatCode(properties::valider).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://stock.example.test", "//stock.example.test", "https://user@stock.example.test",
            "https://stock.example.test?destination=ailleurs", "https://stock.example.test#ailleurs",
            "https://stock.example.test/ailleurs", "javascript:alert(1)", "https://stock.example.test:0"})
    void lesOriginesAmbiguesEtLesUrlsAvecParametresSontRefusees(String baseUrl) {
        RecoveryProperties properties = new RecoveryProperties(); properties.setBaseUrl(baseUrl);
        assertThatThrownBy(properties::valider).isInstanceOf(IllegalStateException.class);
    }
}
