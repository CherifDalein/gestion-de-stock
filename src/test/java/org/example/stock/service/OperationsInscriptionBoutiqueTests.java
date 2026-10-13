package org.example.stock.service;

import org.example.stock.config.RecoveryProperties;
import org.example.stock.config.SignupProperties;
import org.example.stock.enums.Role;
import org.example.stock.form.ConfirmationInscriptionForm;
import org.example.stock.model.Boutique;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.BoutiqueRepository;
import org.example.stock.repository.InscriptionBoutiqueRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:inscription-operations;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "stock.recovery.enabled=true", "stock.recovery.from=tests@example.test"})
@ActiveProfiles("test")
class OperationsInscriptionBoutiqueTests {
    static final Instant MAINTENANT = Instant.parse("2026-10-13T10:00:00Z");
    static final String MOT_DE_PASSE = "Une phrase de passe complète";
    @Autowired OperationsInscriptionBoutique operations;
    @Autowired InscriptionBoutiqueService service;
    @Autowired InscriptionBoutiqueRepository inscriptions;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired BoutiqueRepository boutiques;
    @MockitoSpyBean PasswordEncoder encoder;
    @Autowired SignupProperties properties;
    @Autowired RecoveryProperties messagerie;
    @MockitoBean(name = "recoveryClock") Clock clock;
    @MockitoBean RecuperationMotDePasseMailer recoveryMailer;
    @MockitoBean InscriptionBoutiqueMailer signupMailer;

    @BeforeEach
    void preparer() {
        inscriptions.deleteAll(); utilisateurs.deleteAll(); boutiques.deleteAll();
        properties.setTtl(Duration.ofHours(24)); messagerie.setEnabled(true);
        when(clock.instant()).thenReturn(MAINTENANT);
    }

    @Test
    void laDemandeConserveEmailEtEmpreinteSansCompteNiBoutiqueNiMotDePasse() {
        var lien = operations.emettre("  GERANT@EXAMPLE.TEST  ").orElseThrow();
        var ligne = inscriptions.findById("gerant@example.test").orElseThrow();
        assertThat(lien.jeton().matches("[A-Za-z0-9_-]{43}")).isTrue();
        assertThat(ligne.getEmpreinteJeton().matches("[a-f0-9]{64}")).isTrue();
        assertThat(ligne.getEmpreinteJeton().equals(lien.jeton())).isFalse();
        assertThat(ligne.getCreeLe()).isEqualTo(LocalDateTime.ofInstant(MAINTENANT, ZoneOffset.UTC));
        assertThat(ligne.getExpireLe()).isEqualTo(ligne.getCreeLe().plusHours(24));
        assertThat(lien.toString()).isEqualTo("LienEmis[confidentiel]");
        assertThat(utilisateurs.count()).isZero(); assertThat(boutiques.count()).isZero();
        assertThat(operations.emailPourLien(lien.jeton())).contains("gerant@example.test");
        assertThat(operations.emailPourLien(lien.jeton())).contains("gerant@example.test");
        assertThat(inscriptions.count()).isEqualTo(1);
    }

    @Test
    void renvoyerLeLienInvalideLePrecedentSansMultiplierLesDemandes() {
        var premier = operations.emettre("gerant@example.test").orElseThrow();
        var second = operations.emettre("GERANT@example.test").orElseThrow();
        assertThat(inscriptions.count()).isEqualTo(1);
        assertThat(operations.emailPourLien(premier.jeton())).isEmpty();
        assertThat(operations.emailPourLien(second.jeton())).contains("gerant@example.test");
    }

    @Test
    void laConfirmationCreeUneBoutiqueIsoleeEtSonGerantAvecUnEssaiPuisConsommeLeLien() {
        var existant = creerExistant("ancien@example.test");
        String ancienHash = existant.getMotDePasse();
        var lien = operations.emettre("gerant@example.test").orElseThrow();
        when(clock.instant()).thenReturn(MAINTENANT.plusSeconds(3600));
        operations.confirmer(formulaire(lien.jeton(), MOT_DE_PASSE));
        var gerant = utilisateurs.findByEmail("gerant@example.test").orElseThrow();
        assertThat(gerant.getNom()).isEqualTo("Le gérant");
        assertThat(gerant.getRole()).isEqualTo(Role.ADMIN);
        assertThat(gerant.isActif()).isTrue(); assertThat(gerant.isAdministrateurPlateforme()).isFalse();
        assertThat(encoder.matches(MOT_DE_PASSE, gerant.getMotDePasse())).isTrue();
        var boutique = boutiques.findById(gerant.getBoutique().getId()).orElseThrow();
        assertThat(boutique.getNom()).isEqualTo("Ma nouvelle boutique");
        assertThat(boutique.isActif()).isTrue(); assertThat(boutique.isAccesOffert()).isFalse();
        assertThat(boutique.getId()).isNotEqualTo(existant.getBoutique().getId());
        assertThat(boutique.getEssaiDebut()).isEqualTo(LocalDateTime.ofInstant(MAINTENANT.plusSeconds(3600), ZoneOffset.UTC));
        assertThat(boutique.getEssaiFin()).isEqualTo(boutique.getEssaiDebut().plusDays(30));
        assertThat(inscriptions.count()).isZero();
        assertThat(utilisateurs.findByEmail(existant.getEmail()).orElseThrow().getMotDePasse().equals(ancienHash)).isTrue();
        assertThat(operations.emailPourLien(lien.jeton())).isEmpty();
        assertThatThrownBy(() -> operations.confirmer(formulaire(lien.jeton(), MOT_DE_PASSE))).isInstanceOf(InscriptionBoutiqueException.class);
        assertThat(boutiques.count()).isEqualTo(2); assertThat(utilisateurs.count()).isEqualTo(2);
    }

    @Test
    void unEmailDejaPrisNeRecoitPasDeLienEtUnEmailDevenuPrisNeCreeAucuneBoutiqueOrpheline() {
        creerExistant("ancien@example.test");
        assertThat(operations.emettre("ANCIEN@example.test")).isEmpty();
        var lien = operations.emettre("gerant@example.test").orElseThrow();
        creerExistant("gerant@example.test");
        assertThat(operations.emailPourLien(lien.jeton())).isEmpty();
        assertThatThrownBy(() -> operations.confirmer(formulaire(lien.jeton(), MOT_DE_PASSE))).isInstanceOf(InscriptionBoutiqueException.class);
        assertThat(boutiques.count()).isEqualTo(2); assertThat(utilisateurs.count()).isEqualTo(2);
    }

    @Test
    void unConflitDUniciteApresLaVerificationAnnuleVraimentLaBoutiqueEtConserveLeJeton() {
        var lien = operations.emettre("course@example.test").orElseThrow();
        String hashExistant = encoder.encode("Autre phrase de passe complète");
        doAnswer(invocation -> {
            // Une autre transaction prend l'email entre le contrôle et l'INSERT final.
            CompletableFuture.runAsync(() -> {
                Boutique boutique = new Boutique(); boutique.setNom("Boutique créée en parallèle");
                boutique = boutiques.saveAndFlush(boutique);
                Utilisateur compte = new Utilisateur(); compte.setEmail("course@example.test");
                compte.setNom("Compte créé en parallèle"); compte.setRole(Role.CAISSIER);
                compte.setBoutique(boutique); compte.setMotDePasse(hashExistant); utilisateurs.saveAndFlush(compte);
            }).get(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(encoder).encode(MOT_DE_PASSE);
        assertThatThrownBy(() -> service.confirmer(formulaire(lien.jeton(), MOT_DE_PASSE), "127.0.0.1"))
                .isInstanceOf(InscriptionBoutiqueException.class);
        assertThat(boutiques.count()).isEqualTo(1); assertThat(utilisateurs.count()).isEqualTo(1);
        assertThat(boutiques.findAll().getFirst().getNom()).isEqualTo("Boutique créée en parallèle");
        assertThat(inscriptions.count()).isEqualTo(1);
        assertThat(utilisateurs.findByEmail("course@example.test").orElseThrow().getRole()).isEqualTo(Role.CAISSIER);
    }

    @Test
    void laLimiteDExpirationEstExacteEtUneNouvelleDemandeNettoieLesAnciennes() {
        var lien = operations.emettre("gerant@example.test").orElseThrow();
        when(clock.instant()).thenReturn(MAINTENANT.plusSeconds(24 * 3600));
        assertThat(operations.emailPourLien(lien.jeton())).isEmpty();
        assertThatThrownBy(() -> operations.confirmer(formulaire(lien.jeton(), MOT_DE_PASSE))).isInstanceOf(InscriptionBoutiqueException.class);
        operations.emettre("autre@example.test").orElseThrow();
        assertThat(inscriptions.findById("gerant@example.test")).isEmpty();
        assertThat(inscriptions.count()).isEqualTo(1);
        assertThat(boutiques.count()).isZero(); assertThat(utilisateurs.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "court", "ééééééééééééééééééééééééééééééééééééé"})
    void unMotDePasseInvalideNeConsommePasLeLien(String motDePasse) {
        var lien = operations.emettre("gerant@example.test").orElseThrow();
        assertThatThrownBy(() -> operations.confirmer(formulaire(lien.jeton(), motDePasse))).isInstanceOf(InscriptionBoutiqueException.class);
        assertThat(operations.emailPourLien(lien.jeton())).isPresent();
        assertThat(boutiques.count()).isZero(); assertThat(utilisateurs.count()).isZero();
    }

    @Test
    void uneConfirmationDifferenteEtDesNomsInvalidesNeConsommentPasLeLien() {
        var lien = operations.emettre("gerant@example.test").orElseThrow();
        var different = formulaire(lien.jeton(), MOT_DE_PASSE);
        different.setConfirmationMotDePasse("Autre phrase de passe complète");
        assertThatThrownBy(() -> operations.confirmer(different)).isInstanceOf(InscriptionBoutiqueException.class);
        var nomAbsent = formulaire(lien.jeton(), MOT_DE_PASSE); nomAbsent.setNom("  ");
        assertThatThrownBy(() -> operations.confirmer(nomAbsent)).isInstanceOf(InscriptionBoutiqueException.class);
        var boutiqueLongue = formulaire(lien.jeton(), MOT_DE_PASSE); boutiqueLongue.setNomBoutique("a".repeat(256));
        assertThatThrownBy(() -> operations.confirmer(boutiqueLongue)).isInstanceOf(InscriptionBoutiqueException.class);
        assertThat(operations.emailPourLien(lien.jeton())).isPresent();
        assertThat(boutiques.count()).isZero();
    }

    @Test
    void lesEspacesDuMotDePasseSontConservesEtLesNomsSontNettoyes() {
        var lien = operations.emettre("gerant@example.test").orElseThrow();
        operations.confirmer(formulaire(lien.jeton(), "  Phrase de passe complète  "));
        var gerant = utilisateurs.findByEmail("gerant@example.test").orElseThrow();
        assertThat(encoder.matches("  Phrase de passe complète  ", gerant.getMotDePasse())).isTrue();
        assertThat(encoder.matches("Phrase de passe complète", gerant.getMotDePasse())).isFalse();
    }

    @Test
    void deuxConfirmationsConcurrentesCreentExactementUnCompteEtUneBoutique() throws Exception {
        var lien = operations.emettre("gerant@example.test").orElseThrow();
        CountDownLatch depart = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var premier = pool.submit(() -> confirmer(depart, lien.jeton()));
            var second = pool.submit(() -> confirmer(depart, lien.jeton()));
            depart.countDown();
            assertThat(premier.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(utilisateurs.count()).isEqualTo(1); assertThat(boutiques.count()).isEqualTo(1);
        assertThat(inscriptions.count()).isZero();
    }

    @Test
    void deuxRenvoisConcurrentsConserventUnSeulLienValide() throws Exception {
        var initial = operations.emettre("gerant@example.test").orElseThrow();
        CountDownLatch depart = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var premier = pool.submit(() -> { depart.await(); return operations.emettre("gerant@example.test").orElseThrow(); });
            var second = pool.submit(() -> { depart.await(); return operations.emettre("gerant@example.test").orElseThrow(); });
            depart.countDown();
            var a = premier.get(15, TimeUnit.SECONDS);
            var b = second.get(15, TimeUnit.SECONDS);
            int valides = (operations.emailPourLien(a.jeton()).isPresent() ? 1 : 0)
                    + (operations.emailPourLien(b.jeton()).isPresent() ? 1 : 0);
            assertThat(valides).isEqualTo(1);
        }
        assertThat(operations.emailPourLien(initial.jeton())).isEmpty();
        assertThat(inscriptions.count()).isEqualTo(1);
        assertThat(utilisateurs.count()).isZero(); assertThat(boutiques.count()).isZero();
    }

    @Test
    void laDesactivationDeLaMessagerieInterditEmissionEtConfirmation() {
        var lien = operations.emettre("gerant@example.test").orElseThrow();
        messagerie.setEnabled(false);
        assertThat(operations.emettre("autre@example.test")).isEmpty();
        assertThat(operations.emailPourLien(lien.jeton())).isEmpty();
        assertThatThrownBy(() -> operations.confirmer(formulaire(lien.jeton(), MOT_DE_PASSE))).isInstanceOf(InscriptionBoutiqueException.class);
        assertThat(utilisateurs.count()).isZero(); assertThat(boutiques.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pas-un-email", "email\n@example.test", "", "   "})
    void uneAdresseInvalideNeCreePasDeDemande(String email) {
        assertThat(operations.emettre(email)).isEmpty();
        assertThat(inscriptions.count()).isZero();
    }

    private int confirmer(CountDownLatch depart, String jeton) throws InterruptedException {
        depart.await();
        try { operations.confirmer(formulaire(jeton, MOT_DE_PASSE)); return 1; }
        catch (InscriptionBoutiqueException exception) { return 0; }
    }

    private Utilisateur creerExistant(String email) {
        Boutique boutique = new Boutique(); boutique.setNom("Boutique existante"); boutique = boutiques.saveAndFlush(boutique);
        Utilisateur compte = new Utilisateur(); compte.setEmail(email); compte.setNom("Compte existant");
        compte.setRole(Role.CAISSIER); compte.setBoutique(boutique); compte.setMotDePasse(encoder.encode(MOT_DE_PASSE));
        return utilisateurs.saveAndFlush(compte);
    }

    static ConfirmationInscriptionForm formulaire(String jeton, String motDePasse) {
        ConfirmationInscriptionForm formulaire = new ConfirmationInscriptionForm();
        formulaire.setJeton(jeton); formulaire.setNom("  Le gérant  "); formulaire.setNomBoutique("  Ma nouvelle boutique  ");
        formulaire.setNouveauMotDePasse(motDePasse); formulaire.setConfirmationMotDePasse(motDePasse);
        return formulaire;
    }
}
