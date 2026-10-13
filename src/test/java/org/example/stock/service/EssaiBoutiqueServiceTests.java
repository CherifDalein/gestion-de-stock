package org.example.stock.service;

import org.example.stock.config.TrialProperties;
import org.example.stock.model.Boutique;
import org.example.stock.model.ParametresPlateforme;
import org.example.stock.repository.ParametresPlateformeRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EssaiBoutiqueServiceTests {
    private final ParametresPlateformeRepository repository = mock(ParametresPlateformeRepository.class);
    private final TrialProperties properties = new TrialProperties();
    private final Instant now = Instant.parse("2026-10-13T10:00:00Z");
    private final EssaiBoutiqueService service = new EssaiBoutiqueService(repository, properties, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void lexpirationEstEffectiveALinstantExactEtLesDatesSontUTC() {
        Boutique b = new Boutique(); service.demarrerEssai(b);
        assertThat(b.getEssaiDebut()).isEqualTo(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        assertThat(b.getEssaiFin()).isEqualTo(b.getEssaiDebut().plusDays(30));
        b.setEssaiDebut(b.getEssaiDebut().minusDays(30)); b.setEssaiFin(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        assertThat(service.estExpire(b)).isTrue();
        b.setEssaiFin(b.getEssaiFin().plusNanos(1)); assertThat(service.estExpire(b)).isFalse();
    }

    @Test
    void lesDatesIncompletesOuInverseesNeDonnentPasUnAccesIllimite() {
        Boutique b = new Boutique(); b.setEssaiDebut(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        assertThat(service.estExpire(b)).isTrue();
        b.setEssaiFin(b.getEssaiDebut().minusDays(1)); assertThat(service.estExpire(b)).isTrue();
        b.setAccesOffert(true); assertThat(service.estExpire(b)).isFalse();
        assertThatThrownBy(() -> service.demarrerEssai(b)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void laConfigurationEtLeReglagePersistantRestentBornes() {
        properties.setDays(45); properties.verifier(); assertThat(service.dureeEssaiJours()).isEqualTo(45);
        ParametresPlateforme p = new ParametresPlateforme(); p.setId(1); p.setDureeEssaiJours(10);
        when(repository.findById(1)).thenReturn(Optional.of(p)); assertThat(service.dureeEssaiJours()).isEqualTo(10);
        p.setDureeEssaiJours(0); assertThatThrownBy(service::dureeEssaiJours).isInstanceOf(IllegalStateException.class);
        properties.setDays(3651); assertThatThrownBy(properties::verifier).isInstanceOf(IllegalArgumentException.class);
    }
}
