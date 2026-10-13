package org.example.stock.service;

import org.example.stock.config.RecoveryProperties;
import org.example.stock.config.SignupProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InscriptionBoutiqueServiceTests {
    final OperationsInscriptionBoutique operations = mock(OperationsInscriptionBoutique.class);
    final InscriptionBoutiqueMailer mailer = mock(InscriptionBoutiqueMailer.class);
    final LimiteurInscriptionBoutique limiteur = mock(LimiteurInscriptionBoutique.class);
    InscriptionBoutiqueService service;

    @AfterEach void fermer() { if (service != null) service.fermer(); }

    @Test
    void uneMessagerieDesactiveeOuUneDemandeLimiteeNeConsultePasLesComptes() {
        service = new InscriptionBoutiqueService(operations, mailer, new RecoveryProperties(), new SignupProperties(), limiteur);
        service.demander("compte@example.test", "127.0.0.1");
        verifyNoInteractions(operations, mailer, limiteur);
        service.fermer();
        service = new InscriptionBoutiqueService(operations, mailer, properties(), new SignupProperties(), limiteur);
        service.demander("compte@example.test", "127.0.0.1");
        verify(limiteur).autoriserDemande("compte@example.test", "127.0.0.1");
        verifyNoInteractions(operations, mailer);
    }

    @Test
    void leLienUtiliseLOrigineConfigureeEtLaDemandeEstTraiteeHorsDuThreadHttp() throws Exception {
        RecoveryProperties properties = properties(); properties.setBaseUrl("https://stock.example.test");
        when(limiteur.autoriserDemande(anyString(), anyString())).thenReturn(true);
        when(operations.emettre("compte@example.test")).thenReturn(Optional.of(new OperationsInscriptionBoutique.LienEmis(
                "compte@example.test", "A".repeat(43))));
        CountDownLatch envoi = new CountDownLatch(1);
        AtomicReference<String> url = new AtomicReference<>();
        AtomicReference<Thread> executant = new AtomicReference<>();
        doAnswer(invocation -> { url.set(invocation.getArgument(1)); executant.set(Thread.currentThread()); envoi.countDown(); return null; })
                .when(mailer).envoyerLien(anyString(), anyString(), anyLong());
        service = new InscriptionBoutiqueService(operations, mailer, properties, new SignupProperties(), limiteur);
        service.demander(" COMPTE@EXAMPLE.TEST ", "127.0.0.1");
        assertThat(envoi.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(url.get().startsWith("https://stock.example.test/inscription/confirmer?jeton=")).isTrue();
        assertThat(executant.get()).isNotSameAs(Thread.currentThread());
        verify(mailer).envoyerLien(eq("compte@example.test"), anyString(), eq(24L));
    }

    @Test
    void emailPrisEtEchecSmtpRestentIndependantsDeLaReponsePublique() throws Exception {
        when(limiteur.autoriserDemande(anyString(), anyString())).thenReturn(true);
        when(operations.emettre("deja-utilise@example.test")).thenReturn(Optional.empty());
        when(operations.emettre("nouveau@example.test")).thenReturn(Optional.of(new OperationsInscriptionBoutique.LienEmis(
                "nouveau@example.test", "A".repeat(43))));
        CountDownLatch envoi = new CountDownLatch(1);
        doAnswer(invocation -> { envoi.countDown(); throw new IllegalStateException("Erreur SMTP de test"); })
                .when(mailer).envoyerLien(anyString(), anyString(), anyLong());
        service = new InscriptionBoutiqueService(operations, mailer, properties(), new SignupProperties(), limiteur);
        service.demander("deja-utilise@example.test", "127.0.0.1");
        service.demander("nouveau@example.test", "127.0.0.1");
        assertThat(envoi.await(5, TimeUnit.SECONDS)).isTrue();
        verify(mailer, never()).envoyerLien(eq("deja-utilise@example.test"), anyString(), anyLong());
    }

    @Test
    void laFileAccepte64AttentesEtRejetteLeSurplusSansBloquerLeClient() throws Exception {
        when(limiteur.autoriserDemande(anyString(), anyString())).thenReturn(true);
        CountDownLatch premiere = new CountDownLatch(1);
        CountDownLatch liberer = new CountDownLatch(1);
        CountDownLatch terminees = new CountDownLatch(65);
        AtomicInteger recherches = new AtomicInteger();
        when(operations.emettre(anyString())).thenAnswer(invocation -> {
            if (recherches.incrementAndGet() == 1) { premiere.countDown(); liberer.await(5, TimeUnit.SECONDS); }
            terminees.countDown(); return Optional.empty();
        });
        service = new InscriptionBoutiqueService(operations, mailer, properties(), new SignupProperties(), limiteur);
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
    void leQuotaDeConfirmationEstVerifieAvantSqlEtBcrypt() {
        service = new InscriptionBoutiqueService(operations, mailer, properties(), new SignupProperties(), limiteur);
        var formulaire = OperationsInscriptionBoutiqueTests.formulaire("A".repeat(43), "Une phrase de passe complète");
        assertThatThrownBy(() -> service.confirmer(formulaire, "127.0.0.1")).isInstanceOf(InscriptionBoutiqueException.class);
        verifyNoInteractions(operations, mailer);
    }

    @Test
    void unEmailPrisPendantLaCreationRetourneUneErreurGeneriqueApresRollback() {
        when(limiteur.autoriserConfirmation(anyString(), anyString())).thenReturn(true);
        var formulaire = OperationsInscriptionBoutiqueTests.formulaire("A".repeat(43), "Une phrase de passe complète");
        doThrow(new org.hibernate.exception.ConstraintViolationException("Conflit de test", new java.sql.SQLException(), "uk_utilisateur_email"))
                .when(operations).confirmer(formulaire);
        service = new InscriptionBoutiqueService(operations, mailer, properties(), new SignupProperties(), limiteur);
        assertThatThrownBy(() -> service.confirmer(formulaire, "127.0.0.1"))
                .isInstanceOf(InscriptionBoutiqueException.class).hasMessage(OperationsInscriptionBoutique.LIEN_INVALIDE);
        verifyNoInteractions(mailer);
    }

    private static RecoveryProperties properties() {
        RecoveryProperties properties = new RecoveryProperties(); properties.setEnabled(true);
        properties.setFrom("tests@example.test"); properties.valider(); return properties;
    }
}
