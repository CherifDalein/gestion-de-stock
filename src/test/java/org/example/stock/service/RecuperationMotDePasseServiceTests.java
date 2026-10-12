package org.example.stock.service;

import org.example.stock.config.RecoveryProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecuperationMotDePasseServiceTests {
    final OperationsReinitialisationMotDePasse operations = mock(OperationsReinitialisationMotDePasse.class);
    final RecuperationMotDePasseMailer mailer = mock(RecuperationMotDePasseMailer.class);
    final LimiteurRecuperationMotDePasse limiteur = mock(LimiteurRecuperationMotDePasse.class);
    RecuperationMotDePasseService service;

    @AfterEach void fermer() { if (service != null) service.fermer(); }

    @Test
    void aucuneRechercheOuMessagerieLorsqueLaRecuperationEstDesactivee() {
        RecoveryProperties properties = new RecoveryProperties();
        service = new RecuperationMotDePasseService(operations, mailer, properties, limiteur);
        service.demander("compte@example.test", "127.0.0.1");
        verifyNoInteractions(operations, mailer, limiteur);
    }

    @Test
    void leLienUtiliseSeulementLOrigineConfigureeEtLEnvoiEstAsynchrone() throws Exception {
        RecoveryProperties properties = properties(); properties.setBaseUrl("https://stock.example.test");
        when(limiteur.autoriserDemande(anyString(), anyString())).thenReturn(true);
        when(operations.emettre("compte@example.test")).thenReturn(Optional.of(new OperationsReinitialisationMotDePasse.LienEmis(
                "compte@example.test", "A".repeat(43))));
        CountDownLatch envoi = new CountDownLatch(1);
        AtomicReference<String> url = new AtomicReference<>();
        doAnswer(invocation -> { url.set(invocation.getArgument(1)); envoi.countDown(); return null; })
                .when(mailer).envoyerLien(anyString(), anyString(), anyLong());
        service = new RecuperationMotDePasseService(operations, mailer, properties, limiteur);
        service.demander("  COMPTE@EXAMPLE.TEST  ", "127.0.0.1");
        assertThat(envoi.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(url.get().startsWith("https://stock.example.test/reinitialiser-mot-de-passe?jeton=")).isTrue();
        verify(mailer).envoyerLien(eq("compte@example.test"), anyString(), eq(30L));
    }

    @Test
    void compteAbsentEtEchecSmtpNeRemontentPasALEmetteurHttp() throws Exception {
        when(limiteur.autoriserDemande(anyString(), anyString())).thenReturn(true);
        when(operations.emettre("absent@example.test")).thenReturn(Optional.empty());
        when(operations.emettre("present@example.test")).thenReturn(Optional.of(new OperationsReinitialisationMotDePasse.LienEmis(
                "present@example.test", "A".repeat(43))));
        CountDownLatch envoi = new CountDownLatch(1);
        doAnswer(invocation -> { envoi.countDown(); throw new IllegalStateException("Erreur SMTP de test"); })
                .when(mailer).envoyerLien(anyString(), anyString(), anyLong());
        service = new RecuperationMotDePasseService(operations, mailer, properties(), limiteur);
        service.demander("absent@example.test", "127.0.0.1");
        service.demander("present@example.test", "127.0.0.1");
        assertThat(envoi.await(5, TimeUnit.SECONDS)).isTrue();
        verify(mailer, never()).envoyerLien(eq("absent@example.test"), anyString(), anyLong());
    }

    @Test
    void laFileNeDepassePas64TachesEnAttenteEtNeBloquePasLesDemandes() throws Exception {
        when(limiteur.autoriserDemande(anyString(), anyString())).thenReturn(true);
        CountDownLatch premiere = new CountDownLatch(1);
        CountDownLatch liberer = new CountDownLatch(1);
        CountDownLatch terminees = new CountDownLatch(65);
        AtomicInteger recherches = new AtomicInteger();
        when(operations.emettre(anyString())).thenAnswer(invocation -> {
            if (recherches.incrementAndGet() == 1) { premiere.countDown(); liberer.await(5, TimeUnit.SECONDS); }
            terminees.countDown(); return Optional.empty();
        });
        service = new RecuperationMotDePasseService(operations, mailer, properties(), limiteur);
        service.demander("premier@example.test", "127.0.0.1");
        assertThat(premiere.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 80; i++) service.demander("compte" + i + "@example.test", "127.0.0.1");
        liberer.countDown();
        assertThat(terminees.await(5, TimeUnit.SECONDS)).isTrue();
        service.fermer();
        assertThat(recherches.get()).isEqualTo(65);
        verifyNoInteractions(mailer);
    }

    @Test
    void laLimiteEstAppliqueeAvantToutAccesSqlOuCalculDeMotDePasse() {
        when(limiteur.autoriserReinitialisation(anyString(), anyString())).thenReturn(false);
        service = new RecuperationMotDePasseService(operations, mailer, properties(), limiteur);
        var formulaire = OperationsReinitialisationMotDePasseTests.formulaire("A".repeat(43), "NouveauMotDePasse42");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.reinitialiser(formulaire, "127.0.0.1"))
                .isInstanceOf(RecuperationMotDePasseException.class);
        verifyNoInteractions(operations, mailer);
    }

    @Test
    void laNotificationSuitUnChangementValideEtNeContientAucunLienNiSecret() throws Exception {
        when(limiteur.autoriserReinitialisation(anyString(), anyString())).thenReturn(true);
        var formulaire = OperationsReinitialisationMotDePasseTests.formulaire("A".repeat(43), "NouveauMotDePasse42");
        when(operations.reinitialiser(formulaire)).thenReturn("compte@example.test");
        CountDownLatch notification = new CountDownLatch(1);
        doAnswer(invocation -> { notification.countDown(); return null; }).when(mailer).notifierChangement(anyString());
        service = new RecuperationMotDePasseService(operations, mailer, properties(), limiteur);
        service.reinitialiser(formulaire, "127.0.0.1");
        assertThat(notification.await(5, TimeUnit.SECONDS)).isTrue();
        verify(mailer).notifierChangement("compte@example.test");
        verify(mailer, never()).envoyerLien(anyString(), anyString(), anyLong());
    }

    private static RecoveryProperties properties() {
        RecoveryProperties properties = new RecoveryProperties(); properties.setEnabled(true);
        properties.setFrom("tests@example.test"); properties.valider(); return properties;
    }
}
