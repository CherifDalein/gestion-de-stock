package org.example.stock.service;

import org.example.stock.enums.Role;
import org.example.stock.form.ReinitialisationMotDePasseForm;
import org.example.stock.model.Boutique;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.BoutiqueRepository;
import org.example.stock.repository.ReinitialisationMotDePasseRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:recuperation-operations;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "stock.recovery.enabled=true", "stock.recovery.from=tests@example.test"})
@ActiveProfiles("test")
class OperationsReinitialisationMotDePasseTests {
    static final String ANCIEN = "AncienMotDePasse42";
    static final String NOUVEAU = "NouveauMotDePasse42";
    static final Instant MAINTENANT = Instant.parse("2026-10-13T10:00:00Z");
    @Autowired OperationsReinitialisationMotDePasse operations;
    @Autowired ReinitialisationMotDePasseRepository liens;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired BoutiqueRepository boutiques;
    @Autowired PasswordEncoder encoder;
    @MockitoBean(name = "recoveryClock") Clock clock;
    @MockitoBean RecuperationMotDePasseMailer mailer;
    Utilisateur compte;

    @BeforeEach
    void preparer() {
        liens.deleteAll(); utilisateurs.deleteAll(); boutiques.deleteAll();
        when(clock.instant()).thenReturn(MAINTENANT);
        compte = creer("caissier@example.test", "Première boutique", Role.CAISSIER);
    }

    @Test
    void laBaseConserveUniquementDesEmpreintesEtUnLienParCompte() {
        var premier = operations.emettre(compte.getEmail()).orElseThrow();
        var ligne = liens.findById(compte.getId()).orElseThrow();
        assertThat(premier.jeton().matches("[A-Za-z0-9_-]{43}")).isTrue();
        assertThat(ligne.getEmpreinteJeton().matches("[a-f0-9]{64}")).isTrue();
        assertThat(ligne.getEmpreinteJeton().equals(premier.jeton())).isFalse();
        assertThat(ligne.getEmpreinteMotDePasse().matches("[a-f0-9]{64}")).isTrue();
        assertThat(ligne.getCreeLe()).isEqualTo(LocalDateTime.ofInstant(MAINTENANT, ZoneOffset.UTC));
        assertThat(ligne.getExpireLe()).isEqualTo(ligne.getCreeLe().plusMinutes(30));
        assertThat(premier.toString()).isEqualTo("LienEmis[confidentiel]");
        var second = operations.emettre(compte.getEmail()).orElseThrow();
        assertThat(liens.count()).isEqualTo(1);
        assertThat(operations.lienValide(premier.jeton())).isFalse();
        assertThat(operations.lienValide(second.jeton())).isTrue();
    }

    @Test
    void leLienChangeSeulementLeCompteCibleEtNestUtilisableQuUneFois() {
        var autre = creer("autre@example.test", "Deuxième boutique", Role.ADMIN);
        String empreinteAutre = autre.getMotDePasse();
        var lien = operations.emettre(compte.getEmail()).orElseThrow();
        assertThat(operations.reinitialiser(formulaire(lien.jeton(), NOUVEAU))).isEqualTo(compte.getEmail());
        var apres = utilisateurs.findByEmail(compte.getEmail()).orElseThrow();
        assertThat(encoder.matches(NOUVEAU, apres.getMotDePasse())).isTrue();
        assertThat(encoder.matches(ANCIEN, apres.getMotDePasse())).isFalse();
        assertThat(apres.getId()).isEqualTo(compte.getId());
        assertThat(apres.getRole()).isEqualTo(Role.CAISSIER);
        assertThat(apres.getBoutique().getId()).isEqualTo(compte.getBoutique().getId());
        assertThat(apres.isActif()).isTrue(); assertThat(apres.isAdministrateurPlateforme()).isFalse();
        assertThat(utilisateurs.findByEmail(autre.getEmail()).orElseThrow().getMotDePasse().equals(empreinteAutre)).isTrue();
        assertThat(liens.count()).isZero();
        assertThat(operations.lienValide(lien.jeton())).isFalse();
        verifierRefus(() -> operations.reinitialiser(formulaire(lien.jeton(), "EncoreUnMotDePasse42")));
    }

    @Test
    void unGetNeConsommePasLeLienEtLaLimiteDExpirationEstExacte() {
        var lien = operations.emettre(compte.getEmail()).orElseThrow();
        assertThat(operations.lienValide(lien.jeton())).isTrue();
        assertThat(operations.lienValide(lien.jeton())).isTrue();
        assertThat(liens.count()).isEqualTo(1);
        when(clock.instant()).thenReturn(MAINTENANT.plusSeconds(1800));
        assertThat(operations.lienValide(lien.jeton())).isFalse();
        verifierRefus(() -> operations.reinitialiser(formulaire(lien.jeton(), NOUVEAU)));
        assertThat(encoder.matches(ANCIEN, utilisateurs.findByEmail(compte.getEmail()).orElseThrow().getMotDePasse())).isTrue();
    }

    @Test
    void unChangementParMonCompteInvalideLeLienSansColonneSupplementaire() {
        var lien = operations.emettre(compte.getEmail()).orElseThrow();
        compte.setMotDePasse(encoder.encode("ChangementPersonnel42")); utilisateurs.saveAndFlush(compte);
        assertThat(operations.lienValide(lien.jeton())).isFalse();
        verifierRefus(() -> operations.reinitialiser(formulaire(lien.jeton(), NOUVEAU)));
    }

    @Test
    void compteAbsentDesactiveOuBoutiqueSuspendueNeRecoitAucunLien() {
        assertThat(operations.emettre("absent@example.test")).isEmpty();
        compte.setActif(false); utilisateurs.saveAndFlush(compte);
        assertThat(operations.emettre(compte.getEmail())).isEmpty();
        compte.setActif(true); utilisateurs.saveAndFlush(compte);
        Boutique boutique = boutiques.findById(compte.getBoutique().getId()).orElseThrow();
        boutique.setActif(false); boutiques.saveAndFlush(boutique);
        assertThat(operations.emettre(compte.getEmail())).isEmpty();
        assertThat(liens.count()).isZero();
    }

    @Test
    void laDesactivationApresEmissionInterditAussiLaConsommation() {
        var lien = operations.emettre(compte.getEmail()).orElseThrow();
        compte.setActif(false); utilisateurs.saveAndFlush(compte);
        verifierRefus(() -> operations.reinitialiser(formulaire(lien.jeton(), NOUVEAU)));
        assertThat(utilisateurs.findByEmail(compte.getEmail()).orElseThrow().isActif()).isFalse();
    }

    @Test
    void laSuspensionApresEmissionInterditAussiLaConsommation() {
        var lien = operations.emettre(compte.getEmail()).orElseThrow();
        Boutique boutique = boutiques.findById(compte.getBoutique().getId()).orElseThrow();
        boutique.setActif(false); boutiques.saveAndFlush(boutique);
        verifierRefus(() -> operations.reinitialiser(formulaire(lien.jeton(), NOUVEAU)));
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().isActif()).isFalse();
    }

    @Test
    void seulUnVeritableAdminDePlateformePeutRecupererSurUneBoutiqueSuspendue() {
        Boutique boutique = boutiques.findById(compte.getBoutique().getId()).orElseThrow();
        boutique.setActif(false); boutiques.saveAndFlush(boutique);
        compte.setAdministrateurPlateforme(true); utilisateurs.saveAndFlush(compte);
        assertThat(operations.emettre(compte.getEmail())).isEmpty();
        compte.setRole(Role.ADMIN); utilisateurs.saveAndFlush(compte);
        var lien = operations.emettre(compte.getEmail()).orElseThrow();
        operations.reinitialiser(formulaire(lien.jeton(), NOUVEAU));
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().isActif()).isFalse();
        assertThat(utilisateurs.findByEmail(compte.getEmail()).orElseThrow().isAdministrateurPlateforme()).isTrue();
    }

    @Test
    void lesErreursDeConfirmationDeLongueurEtUnMotDePasseIdentiqueNeConsommentPasLeLien() {
        var lien = operations.emettre(compte.getEmail()).orElseThrow();
        ReinitialisationMotDePasseForm different = formulaire(lien.jeton(), NOUVEAU);
        different.setConfirmationMotDePasse("ConfirmationDifferente42");
        verifierRefus(() -> operations.reinitialiser(different));
        verifierRefus(() -> operations.reinitialiser(formulaire(lien.jeton(), "court")));
        verifierRefus(() -> operations.reinitialiser(formulaire(lien.jeton(), "é".repeat(37))));
        verifierRefus(() -> operations.reinitialiser(formulaire(lien.jeton(), ANCIEN)));
        assertThat(operations.lienValide(lien.jeton())).isTrue();
    }

    @Test
    void lesEspacesDuNouveauMotDePasseSontConserves() {
        var lien = operations.emettre(compte.getEmail()).orElseThrow();
        operations.reinitialiser(formulaire(lien.jeton(), "  NouveauMotDePasse42  "));
        String hash = utilisateurs.findByEmail(compte.getEmail()).orElseThrow().getMotDePasse();
        assertThat(encoder.matches("  NouveauMotDePasse42  ", hash)).isTrue();
        assertThat(encoder.matches("NouveauMotDePasse42", hash)).isFalse();
    }

    @Test
    void deuxConsommationsConcurrentesNeReussissentPasEnsemble() throws Exception {
        var lien = operations.emettre(compte.getEmail()).orElseThrow();
        CountDownLatch depart = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> consommer(depart, lien.jeton(), "NouveauConcurrentA42"));
            var b = pool.submit(() -> consommer(depart, lien.jeton(), "NouveauConcurrentB42"));
            depart.countDown();
            assertThat(a.get(15, TimeUnit.SECONDS) + b.get(15, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(liens.count()).isZero();
    }

    private int consommer(CountDownLatch depart, String jeton, String motDePasse) throws InterruptedException {
        depart.await();
        try { operations.reinitialiser(formulaire(jeton, motDePasse)); return 1; }
        catch (RecuperationMotDePasseException exception) { return 0; }
    }

    private Utilisateur creer(String email, String nomBoutique, Role role) {
        Boutique boutique = new Boutique(); boutique.setNom(nomBoutique); boutique = boutiques.saveAndFlush(boutique);
        Utilisateur utilisateur = new Utilisateur(); utilisateur.setBoutique(boutique); utilisateur.setEmail(email);
        utilisateur.setNom("Compte de test"); utilisateur.setRole(role); utilisateur.setMotDePasse(encoder.encode(ANCIEN));
        return utilisateurs.saveAndFlush(utilisateur);
    }

    static ReinitialisationMotDePasseForm formulaire(String jeton, String motDePasse) {
        ReinitialisationMotDePasseForm formulaire = new ReinitialisationMotDePasseForm();
        formulaire.setJeton(jeton); formulaire.setNouveauMotDePasse(motDePasse); formulaire.setConfirmationMotDePasse(motDePasse);
        return formulaire;
    }

    private static void verifierRefus(Runnable action) {
        // Ne pas exposer le contenu du formulaire ou un jeton en cas d'échec du test.
        assertThatThrownBy(action::run).isInstanceOf(RecuperationMotDePasseException.class);
    }
}
