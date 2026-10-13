package org.example.stock.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LimiteurInscriptionBoutiqueTests {
    @Test
    void leMemeEmailNeDepassePasTroisDemandesParHeureEnChangeantDIp() {
        Horloge horloge = new Horloge();
        var limiteur = new LimiteurInscriptionBoutique(horloge);
        for (int i = 0; i < 3; i++) assertThat(limiteur.autoriserDemande("compte@example.test", "adresse-" + i)).isTrue();
        assertThat(limiteur.autoriserDemande("compte@example.test", "autre-adresse")).isFalse();
        horloge.avancer(Duration.ofHours(1));
        assertThat(limiteur.autoriserDemande("compte@example.test", "autre-adresse")).isTrue();
    }

    @Test
    void uneIpNeDepassePasDixDemandesParHeureEnChangeantDEmail() {
        var limiteur = new LimiteurInscriptionBoutique(new Horloge());
        for (int i = 0; i < 10; i++) assertThat(limiteur.autoriserDemande("compte" + i + "@example.test", "adresse")).isTrue();
        assertThat(limiteur.autoriserDemande("dernier@example.test", "adresse")).isFalse();
    }

    @Test
    void lesConfirmationsSontLimiteesParJetonEtParIpIndependammentDesDemandes() {
        var limiteur = new LimiteurInscriptionBoutique(new Horloge());
        for (int i = 0; i < 3; i++) limiteur.autoriserDemande("compte@example.test", "adresse");
        for (int i = 0; i < 5; i++) assertThat(limiteur.autoriserConfirmation("jeton-factice", "adresse-" + i)).isTrue();
        assertThat(limiteur.autoriserConfirmation("jeton-factice", "autre-adresse")).isFalse();
        assertThat(limiteur.autoriserConfirmation("autre-jeton", "adresse")).isTrue();
    }

    @Test
    void leVolumeDeClesResteBorneSansSupprimerUnQuotaVivant() {
        Horloge horloge = new Horloge();
        var limiteur = new LimiteurInscriptionBoutique(horloge);
        for (int i = 0; i < 6000; i++) {
            if (i % 60 == 0) horloge.avancer(Duration.ofSeconds(61));
            limiteur.autoriserDemande("compte" + i + "@example.test", "adresse-" + i);
            assertThat(limiteur.nombreCompteurs()).isLessThanOrEqualTo(LimiteurInscriptionBoutique.CAPACITE);
        }
    }

    @Test
    void lesDemandesConcurrentesRespectentLeQuotaParEmail() throws Exception {
        var limiteur = new LimiteurInscriptionBoutique(new Horloge());
        CountDownLatch depart = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(12)) {
            var resultats = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 12; i++) {
                final String ip = "adresse-" + i;
                resultats.add(pool.submit(() -> { depart.await(); return limiteur.autoriserDemande("compte@example.test", ip); }));
            }
            depart.countDown();
            int autorisees = 0;
            for (var resultat : resultats) if (resultat.get(5, TimeUnit.SECONDS)) autorisees++;
            assertThat(autorisees).isEqualTo(3);
        }
    }

    private static final class Horloge extends Clock {
        private Instant instant = Instant.parse("2026-10-13T10:00:00Z");
        void avancer(Duration duree) { instant = instant.plus(duree); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
