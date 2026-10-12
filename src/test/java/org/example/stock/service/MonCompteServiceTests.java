package org.example.stock.service;

import jakarta.persistence.EntityManager;
import org.example.stock.enums.Role;
import org.example.stock.form.ChangementMotDePasseForm;
import org.example.stock.model.Boutique;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.BoutiqueRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:mon-compte-service-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
class MonCompteServiceTests {
    private static final String ANCIEN = "Ancien42";
    private static final String NOUVEAU = "Une nouvelle phrase secrète";
    private static String hashInitial;
    @Autowired MonCompteService service;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired PasswordEncoder encoder;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;
    Boutique boutiqueA, boutiqueB;
    Utilisateur gerant, caissier, autre;

    @BeforeEach
    void preparer() {
        SecurityContextHolder.clearContext();
        utilisateurs.deleteAll(); boutiques.deleteAll();
        if (hashInitial == null) hashInitial = encoder.encode(ANCIEN);
        boutiqueA = boutique("Commerce Alpha"); boutiqueB = boutique("Commerce Beta confidentiel");
        gerant = compte("gerant@mon-compte.test", boutiqueA, Role.ADMIN);
        caissier = compte("caissier@mon-compte.test", boutiqueA, Role.CAISSIER);
        autre = compte("autre@mon-compte.test", boutiqueB, Role.ADMIN);
        connecter(gerant, "ADMIN");
    }

    @AfterEach
    void deconnecter() { SecurityContextHolder.clearContext(); }

    @Test
    void lesInformationsDuCompteNeContiennentAucunSecretEtRestentCellesDuCompteAuthentifie() {
        ComptePersonnel compte = service.chargerCompteCourant();
        assertThat(compte.nom()).isEqualTo(gerant.getNom());
        assertThat(compte.email()).isEqualTo(gerant.getEmail());
        assertThat(compte.role()).isEqualTo(Role.ADMIN);
        assertThat(compte.dateInscription()).isEqualTo(gerant.getDateInscription());
        assertThat(compte.nomBoutique()).isEqualTo("Commerce Alpha");
        assertThat(compte.toString()).doesNotContain(hashInitial, ANCIEN, autre.getEmail(), "Beta");
        assertThat(Arrays.stream(ComptePersonnel.class.getRecordComponents()).map(composant -> composant.getName()))
                .containsExactly("nom", "email", "role", "dateInscription", "nomBoutique");
        connecter(autre, "ADMIN");
        assertThat(service.chargerCompteCourant().email()).isEqualTo(autre.getEmail());
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void chaqueRoleChangeSeulementLeSecretDeSonPropreCompte(Role role) {
        Utilisateur actuel = role == Role.ADMIN ? gerant : caissier;
        connecter(actuel, role.name());
        Boutique avantBoutique = boutiques.findById(boutiqueA.getId()).orElseThrow();
        service.changerMotDePasse(formulaire(ANCIEN, NOUVEAU, NOUVEAU));
        Utilisateur apres = utilisateurs.findById(actuel.getId()).orElseThrow();
        assertThat(apres.getMotDePasse()).isNotEqualTo(hashInitial).isNotEqualTo(NOUVEAU);
        assertThat(encoder.matches(NOUVEAU, apres.getMotDePasse())).isTrue();
        assertThat(encoder.matches(ANCIEN, apres.getMotDePasse())).isFalse();
        assertThat(apres.getNom()).isEqualTo(actuel.getNom()); assertThat(apres.getEmail()).isEqualTo(actuel.getEmail());
        assertThat(apres.getRole()).isEqualTo(actuel.getRole()); assertThat(apres.getDateInscription()).isEqualTo(actuel.getDateInscription());
        assertThat(apres.getBoutique().getId()).isEqualTo(actuel.getBoutique().getId());
        assertThat(apres.isActif()).isTrue(); assertThat(apres.isAdministrateurPlateforme()).isFalse();
        assertThat(utilisateurs.findById(autre.getId()).orElseThrow().getMotDePasse()).isEqualTo(hashInitial);
        Utilisateur autreLocal = role == Role.ADMIN ? caissier : gerant;
        assertThat(utilisateurs.findById(autreLocal.getId()).orElseThrow().getMotDePasse()).isEqualTo(hashInitial);
        Boutique apresBoutique = boutiques.findById(boutiqueA.getId()).orElseThrow();
        assertThat(apresBoutique.getNom()).isEqualTo(avantBoutique.getNom());
        assertThat(apresBoutique.getDateCreation()).isEqualTo(avantBoutique.getDateCreation());
        assertThat(boutiques.count()).isEqualTo(2); assertThat(utilisateurs.count()).isEqualTo(3);
    }

    @Test
    void lesEspacesDuNouveauMotDePasseSontConserves() {
        String secret = "  Une nouvelle phrase secrète  ";
        service.changerMotDePasse(formulaire(ANCIEN, secret, secret));
        String hash = utilisateurs.findById(gerant.getId()).orElseThrow().getMotDePasse();
        assertThat(encoder.matches(secret, hash)).isTrue(); assertThat(encoder.matches(secret.strip(), hash)).isFalse();
    }

    @Test
    void leMotDePasseActuelPeutAvoirMoinsDeQuinzeCaracteres() {
        assertThat(ANCIEN).hasSizeLessThan(15);
        service.changerMotDePasse(formulaire(ANCIEN, NOUVEAU, NOUVEAU));
        assertThat(encoder.matches(NOUVEAU, utilisateurs.findById(gerant.getId()).orElseThrow().getMotDePasse())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ancien-invalide", "ancien-vide", "nouveau-court", "nouveau-trop-long", "nouveau-vide", "confirmation-vide", "confirmation-differente", "confirmation-espaces", "absent"})
    void lesSaisiesInvalidesNeModifientAucunCompte(String cas) {
        var formulaire = formulaire(ANCIEN, NOUVEAU, NOUVEAU);
        switch (cas) {
            case "ancien-invalide" -> formulaire.setAncienMotDePasse("Incorrect42");
            case "ancien-vide" -> formulaire.setAncienMotDePasse(" \t ");
            case "nouveau-court" -> { formulaire.setNouveauMotDePasse("trop court"); formulaire.setConfirmationMotDePasse("trop court"); }
            case "nouveau-trop-long" -> { formulaire.setNouveauMotDePasse("é".repeat(37)); formulaire.setConfirmationMotDePasse("é".repeat(37)); }
            case "nouveau-vide" -> { formulaire.setNouveauMotDePasse(" \t "); formulaire.setConfirmationMotDePasse(" \t "); }
            case "confirmation-vide" -> formulaire.setConfirmationMotDePasse("");
            case "confirmation-differente" -> formulaire.setConfirmationMotDePasse("Une autre phrase secrète");
            case "confirmation-espaces" -> formulaire.setConfirmationMotDePasse(" " + NOUVEAU);
            case "absent" -> formulaire = null;
            default -> throw new IllegalArgumentException(cas);
        }
        ChangementMotDePasseForm saisie = formulaire;
        assertThatThrownBy(() -> service.changerMotDePasse(saisie)).isInstanceOf(ChangementMotDePasseException.class)
                .satisfies(erreur -> assertThat(erreur.getMessage()).doesNotContain(ANCIEN, NOUVEAU, "Incorrect42", hashInitial));
        assertThat(utilisateurs.findAll()).allSatisfy(compte -> assertThat(compte.getMotDePasse()).isEqualTo(hashInitial));
    }

    @Test
    void reutiliserLeMemeSecretEstRefuse() {
        gerant.setMotDePasse(encoder.encode(NOUVEAU)); utilisateurs.saveAndFlush(gerant);
        String avant = gerant.getMotDePasse();
        assertThatThrownBy(() -> service.changerMotDePasse(formulaire(NOUVEAU, NOUVEAU, NOUVEAU)))
                .isInstanceOf(ChangementMotDePasseException.class)
                .satisfies(erreur -> assertThat(((ChangementMotDePasseException) erreur).getChamp()).isEqualTo("nouveauMotDePasse"));
        assertThat(utilisateurs.findById(gerant.getId()).orElseThrow().getMotDePasse()).isEqualTo(avant);
    }

    @ParameterizedTest
    @EnumSource(DroitsInvalides.class)
    void lesDroitsPersistesSontExigesPourLireEtModifier(DroitsInvalides cas) {
        switch (cas) {
            case ABSENTS -> SecurityContextHolder.clearContext();
            case ANONYME -> SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("test", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
            case AUTORITE_ABSENTE -> connecter(gerant, "CAISSIER");
            case COMPTE_ABSENT -> SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("inconnu@mon-compte.test", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            case COMPTE_INACTIF -> { gerant.setActif(false); utilisateurs.saveAndFlush(gerant); }
            case BOUTIQUE_INACTIVE -> { boutiqueA.setActif(false); boutiques.saveAndFlush(boutiqueA); }
            case FAUX_PLATEFORME -> { boutiqueA.setActif(false); boutiques.saveAndFlush(boutiqueA); connecter(gerant, "ADMIN", "PLATEFORME_ADMIN"); }
        }
        exigerInterdit(service::chargerCompteCourant); exigerInterdit(service::verifierCompteCourant);
        exigerInterdit(() -> service.changerMotDePasse(formulaire(ANCIEN, NOUVEAU, NOUVEAU)));
        assertThat(utilisateurs.findAll()).allSatisfy(compte -> assertThat(compte.getMotDePasse()).isEqualTo(hashInitial));
    }

    @Test
    void unOperateurPlateformeConserveSonCompteQuandSaBoutiqueEstSuspendue() {
        gerant.setAdministrateurPlateforme(true); utilisateurs.saveAndFlush(gerant);
        boutiqueA.setActif(false); boutiques.saveAndFlush(boutiqueA);
        connecter(gerant, "ADMIN", "PLATEFORME_ADMIN");
        assertThat(service.chargerCompteCourant().email()).isEqualTo(gerant.getEmail());
        service.changerMotDePasse(formulaire(ANCIEN, NOUVEAU, NOUVEAU));
        assertThat(encoder.matches(NOUVEAU, utilisateurs.findById(gerant.getId()).orElseThrow().getMotDePasse())).isTrue();
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().isActif()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"secret-change", "compte-desactive", "role-change", "boutique-transferee", "email-change"})
    void uneCopieChargeeAvantUneAutreTransactionEstRafraichieSousVerrou(String cas) {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Utilisateur ancienneCopie = entityManager.find(Utilisateur.class, gerant.getId());
            autreTransaction().executeWithoutResult(autre -> {
                Utilisateur modifie = utilisateurs.findById(gerant.getId()).orElseThrow();
                switch (cas) {
                    case "secret-change" -> modifie.setMotDePasse(encoder.encode("Un secret changé ailleurs"));
                    case "compte-desactive" -> modifie.setActif(false);
                    case "role-change" -> modifie.setRole(Role.CAISSIER);
                    case "boutique-transferee" -> modifie.setBoutique(boutiques.findById(boutiqueB.getId()).orElseThrow());
                    case "email-change" -> modifie.setEmail("compte-renomme@mon-compte.test");
                    default -> throw new IllegalArgumentException(cas);
                }
                utilisateurs.saveAndFlush(modifie);
            });
            assertThat(ancienneCopie.getMotDePasse()).isEqualTo(hashInitial); assertThat(ancienneCopie.isActif()).isTrue();
            service.changerMotDePasse(formulaire(ANCIEN, NOUVEAU, NOUVEAU));
        })).isInstanceOf(cas.equals("secret-change") ? ChangementMotDePasseException.class : ResponseStatusException.class);
        String hash = utilisateurs.findById(gerant.getId()).orElseThrow().getMotDePasse();
        assertThat(encoder.matches(NOUVEAU, hash)).isFalse();
        assertThat(utilisateurs.findById(autre.getId()).orElseThrow().getMotDePasse()).isEqualTo(hashInitial);
    }

    @Test
    void uneBoutiqueDejaChargeePuisSuspendueEstRelueAvantLeChangement() {
        exigerInterdit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Boutique ancienneCopie = entityManager.find(Boutique.class, boutiqueA.getId());
            autreTransaction().executeWithoutResult(autre -> {
                Boutique suspendue = boutiques.findById(boutiqueA.getId()).orElseThrow();
                suspendue.setActif(false); boutiques.saveAndFlush(suspendue);
            });
            assertThat(ancienneCopie.isActif()).isTrue();
            service.changerMotDePasse(formulaire(ANCIEN, NOUVEAU, NOUVEAU));
        }));
        assertThat(utilisateurs.findById(gerant.getId()).orElseThrow().getMotDePasse()).isEqualTo(hashInitial);
    }

    private TransactionTemplate autreTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); return transaction;
    }
    private Boutique boutique(String nom) { Boutique boutique = new Boutique(); boutique.setNom(nom); return boutiques.saveAndFlush(boutique); }
    private Utilisateur compte(String email, Boutique boutique, Role role) {
        Utilisateur compte = new Utilisateur(); compte.setNom("Compte " + role); compte.setEmail(email);
        compte.setMotDePasse(hashInitial); compte.setBoutique(boutique); compte.setRole(role);
        compte.setDateInscription(LocalDate.of(2026, 10, 13)); return utilisateurs.saveAndFlush(compte);
    }
    private static ChangementMotDePasseForm formulaire(String ancien, String nouveau, String confirmation) {
        ChangementMotDePasseForm formulaire = new ChangementMotDePasseForm();
        formulaire.setAncienMotDePasse(ancien); formulaire.setNouveauMotDePasse(nouveau);
        formulaire.setConfirmationMotDePasse(confirmation); return formulaire;
    }
    private static void connecter(Utilisateur compte, String... roles) {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(compte.getEmail(), "",
                Arrays.stream(roles).map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()));
        SecurityContextHolder.setContext(contexte);
    }
    private static void exigerInterdit(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class)
                .satisfies(erreur -> assertThat(((ResponseStatusException) erreur).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }
    enum DroitsInvalides { ABSENTS, ANONYME, AUTORITE_ABSENTE, COMPTE_ABSENT, COMPTE_INACTIF, BOUTIQUE_INACTIVE, FAUX_PLATEFORME }
}
