package org.example.stock.service;

import jakarta.persistence.EntityManager;
import org.example.stock.enums.Role;
import org.example.stock.form.AccesBoutiqueForm;
import org.example.stock.form.ParametresBoutiqueForm;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:parametres-boutique-service-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
class ParametresBoutiqueServiceTests {
    @Autowired ParametresBoutiqueService service;
    @Autowired PlateformeService plateforme;
    @Autowired BoutiqueRepository boutiques;
    @Autowired org.example.stock.repository.HistoriqueAbonnementRepository historiqueAbonnement;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;

    Boutique boutiqueA, boutiqueB;
    Utilisateur gerantA, gerantB, caissierA, operateur;

    @BeforeEach
    void preparer() {
        SecurityContextHolder.clearContext();
        historiqueAbonnement.deleteAll();
        utilisateurs.deleteAll(); boutiques.deleteAll();
        boutiqueA = boutique("Boutique A"); boutiqueB = boutique("Boutique B confidentielle");
        gerantA = compte("gerant-a@parametres.test", boutiqueA, Role.ADMIN, false);
        gerantB = compte("gerant-b@parametres.test", boutiqueB, Role.ADMIN, false);
        caissierA = compte("caissier@parametres.test", boutiqueA, Role.CAISSIER, false);
        operateur = compte("plateforme@parametres.test", boutiqueA, Role.ADMIN, true);
        connecter(gerantA, "ADMIN");
    }

    @AfterEach
    void deconnecter() { SecurityContextHolder.clearContext(); }

    @Test
    void leChargementEtLaModificationRestentDansLaBoutiqueDuCompte() {
        boutiqueA.setAccesOffert(true); boutiques.saveAndFlush(boutiqueA);
        String hash = utilisateurs.findById(gerantA.getId()).orElseThrow().getMotDePasse();
        var avant = boutiques.findById(boutiqueA.getId()).orElseThrow();
        var creation = avant.getDateCreation();
        assertThat(service.chargerPourBoutiqueCourante().getNom()).isEqualTo("Boutique A");
        service.modifierPourBoutiqueCourante(formulaire("  Commerce Alpha  ", "  Conakry\nRue 1  ", "  +224 620 00 00 00  ", "  contact@example.test  "));
        Boutique apres = boutiques.findById(boutiqueA.getId()).orElseThrow();
        assertThat(apres.getNom()).isEqualTo("Commerce Alpha");
        assertThat(apres.getAdresse()).isEqualTo("Conakry\nRue 1");
        assertThat(apres.getTelephone()).isEqualTo("+224 620 00 00 00");
        assertThat(apres.getEmail()).isEqualTo("contact@example.test");
        assertThat(apres.getDateCreation()).isEqualTo(creation);
        assertThat(apres.isActif()).isTrue(); assertThat(apres.isAccesOffert()).isTrue();
        Boutique autre = boutiques.findById(boutiqueB.getId()).orElseThrow();
        assertThat(autre.getNom()).isEqualTo("Boutique B confidentielle");
        assertThat(autre.getAdresse()).isNull(); assertThat(autre.getTelephone()).isNull(); assertThat(autre.getEmail()).isNull();
        assertThat(autre.isActif()).isTrue(); assertThat(autre.isAccesOffert()).isFalse();
        assertThat(boutiques.count()).isEqualTo(2); assertThat(utilisateurs.count()).isEqualTo(4);
        var compte = utilisateurs.findById(gerantA.getId()).orElseThrow();
        assertThat(compte.getMotDePasse()).isEqualTo(hash); assertThat(compte.getRole()).isEqualTo(Role.ADMIN);
        assertThat(compte.getEmail()).isEqualTo(gerantA.getEmail()); assertThat(compte.getBoutique().getId()).isEqualTo(boutiqueA.getId());
        connecter(gerantB, "ADMIN");
        assertThat(service.chargerPourBoutiqueCourante().getNom()).isEqualTo("Boutique B confidentielle");
    }

    @Test
    void leFormulaireNeModifiePasLEntiteAvantEnregistrementEtPermetDEffacerLesCoordonnees() {
        service.modifierPourBoutiqueCourante(formulaire("Commerce Alpha", "Conakry", "620000000", "contact@example.test"));
        var formulaire = service.chargerPourBoutiqueCourante();
        formulaire.setNom("Nom non enregistré");
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getNom()).isEqualTo("Commerce Alpha");
        service.modifierPourBoutiqueCourante(formulaire("Commerce Alpha", " \n\t ", " \t ", "  "));
        Boutique boutique = boutiques.findById(boutiqueA.getId()).orElseThrow();
        assertThat(boutique.getAdresse()).isNull(); assertThat(boutique.getTelephone()).isNull(); assertThat(boutique.getEmail()).isNull();
    }

    @Test
    void lesLongueursMaximalesSontAccepteesEtLAdresseEmailDeContactPeutEtrePartagee() {
        boutiqueB.setEmail("contact@example.test"); boutiques.saveAndFlush(boutiqueB);
        service.modifierPourBoutiqueCourante(formulaire("n".repeat(255), "a".repeat(500), "t".repeat(50), "contact@example.test"));
        Boutique boutique = boutiques.findById(boutiqueA.getId()).orElseThrow();
        assertThat(boutique.getNom()).hasSize(255); assertThat(boutique.getAdresse()).hasSize(500);
        assertThat(boutique.getTelephone()).hasSize(50); assertThat(boutique.getEmail()).isEqualTo("contact@example.test");
    }

    @ParameterizedTest
    @ValueSource(strings = {"nom-vide", "nom-long", "adresse-longue", "telephone-long", "email-invalide", "email-long"})
    void lesValeursInvalidesNeChangentAucunChamp(String cas) {
        var formulaire = formulaire("Nouveau commerce", "Nouvelle adresse", "620000000", "contact@example.test");
        switch (cas) {
            case "nom-vide" -> formulaire.setNom(" \n\t ");
            case "nom-long" -> formulaire.setNom("n".repeat(256));
            case "adresse-longue" -> formulaire.setAdresse("a".repeat(501));
            case "telephone-long" -> formulaire.setTelephone("t".repeat(51));
            case "email-invalide" -> formulaire.setEmail("ceci n'est pas un email");
            case "email-long" -> formulaire.setEmail("a".repeat(245) + "@example.test");
            default -> throw new IllegalArgumentException(cas);
        }
        exigerStatut(() -> service.modifierPourBoutiqueCourante(formulaire), HttpStatus.BAD_REQUEST);
        Boutique boutique = boutiques.findById(boutiqueA.getId()).orElseThrow();
        assertThat(boutique.getNom()).isEqualTo("Boutique A"); assertThat(boutique.getAdresse()).isNull();
        assertThat(boutique.getTelephone()).isNull(); assertThat(boutique.getEmail()).isNull();
    }

    @Test
    void unFormulaireAbsentEstRefuse() {
        exigerStatut(() -> service.modifierPourBoutiqueCourante(null), HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @EnumSource(DroitsInvalides.class)
    void laLectureEtLEcritureExigentLesDroitsDuCompteEnBase(DroitsInvalides cas) {
        switch (cas) {
            case ABSENTS -> SecurityContextHolder.clearContext();
            case ANONYME -> contexte(new AnonymousAuthenticationToken("test", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
            case AUTORITE_ABSENTE -> connecter(gerantA, "CAISSIER");
            case COMPTE_ABSENT -> contexte(UsernamePasswordAuthenticationToken.authenticated("inconnu@parametres.test", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            case ROLE_CAISSIER -> connecter(caissierA, "ADMIN");
            case COMPTE_INACTIF -> { gerantA.setActif(false); utilisateurs.saveAndFlush(gerantA); }
            case BOUTIQUE_INACTIVE -> { boutiqueA.setActif(false); boutiques.saveAndFlush(boutiqueA); }
        }
        exigerStatut(service::chargerPourBoutiqueCourante, HttpStatus.FORBIDDEN);
        exigerStatut(service::verifierGestionBoutiqueCourante, HttpStatus.FORBIDDEN);
        exigerStatut(() -> service.modifierPourBoutiqueCourante(formulaire("Nouveau", null, null, null)), HttpStatus.FORBIDDEN);
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getNom()).isEqualTo("Boutique A");
    }

    @Test
    void lesFlagsModifiesDansUneAutreTransactionSontPreservesApresRafraichissement() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Boutique ancienneCopie = entityManager.find(Boutique.class, boutiqueA.getId());
            autreTransaction().executeWithoutResult(autre -> {
                Boutique modifiee = boutiques.findById(boutiqueA.getId()).orElseThrow();
                modifiee.setAccesOffert(true); boutiques.saveAndFlush(modifiee);
            });
            assertThat(ancienneCopie.isAccesOffert()).isFalse();
            service.modifierPourBoutiqueCourante(formulaire("Commerce actualisé", "Conakry", "620000000", "contact@example.test"));
            assertThat(ancienneCopie.isAccesOffert()).isTrue();
        });
        var boutique = boutiques.findById(boutiqueA.getId()).orElseThrow();
        assertThat(boutique.getNom()).isEqualTo("Commerce actualisé"); assertThat(boutique.isAccesOffert()).isTrue();
    }

    @Test
    void laMiseAJourDeStatutPlateformeNeReecritPasDesCoordonneesDejaEnregistrees() {
        connecter(operateur, "ADMIN", "PLATEFORME_ADMIN");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Boutique ancienneCopie = plateforme.trouverBoutique(boutiqueA.getId());
            autreTransaction().executeWithoutResult(autre -> {
                connecter(gerantA, "ADMIN");
                service.modifierPourBoutiqueCourante(formulaire("Nouveau nom commercial", "Conakry", "620000000", "contact@example.test"));
            });
            connecter(operateur, "ADMIN", "PLATEFORME_ADMIN");
            assertThat(ancienneCopie.getNom()).isEqualTo("Boutique A");
            AccesBoutiqueForm acces = new AccesBoutiqueForm(); acces.setActif(false); acces.setAccesOffert(true);
            plateforme.modifierAcces(boutiqueA.getId(), acces);
        });
        var boutique = boutiques.findById(boutiqueA.getId()).orElseThrow();
        assertThat(boutique.getNom()).isEqualTo("Nouveau nom commercial"); assertThat(boutique.getAdresse()).isEqualTo("Conakry");
        assertThat(boutique.getTelephone()).isEqualTo("620000000"); assertThat(boutique.getEmail()).isEqualTo("contact@example.test");
        assertThat(boutique.isActif()).isFalse(); assertThat(boutique.isAccesOffert()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"role-retire", "compte-desactive", "boutique-transferee"})
    void lesDroitsDeLActeurDejaChargeSontRelusSousVerrou(String cas) {
        exigerStatut(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Utilisateur ancienneCopie = entityManager.find(Utilisateur.class, gerantA.getId());
            autreTransaction().executeWithoutResult(autre -> {
                Utilisateur modifie = utilisateurs.findById(gerantA.getId()).orElseThrow();
                switch (cas) {
                    case "role-retire" -> modifie.setRole(Role.CAISSIER);
                    case "compte-desactive" -> modifie.setActif(false);
                    case "boutique-transferee" -> modifie.setBoutique(boutiques.findById(boutiqueB.getId()).orElseThrow());
                    default -> throw new IllegalArgumentException(cas);
                }
                utilisateurs.saveAndFlush(modifie);
            });
            assertThat(ancienneCopie.getRole()).isEqualTo(Role.ADMIN); assertThat(ancienneCopie.isActif()).isTrue();
            assertThat(ancienneCopie.getBoutique().getId()).isEqualTo(boutiqueA.getId());
            service.modifierPourBoutiqueCourante(formulaire("Interdit", null, null, null));
        }), HttpStatus.FORBIDDEN);
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getNom()).isEqualTo("Boutique A");
        assertThat(boutiques.findById(boutiqueB.getId()).orElseThrow().getNom()).isEqualTo("Boutique B confidentielle");
    }

    @Test
    void uneBoutiqueDejaChargeePuisSuspendueNePeutPlusEtreModifiee() {
        exigerStatut(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Boutique ancienneCopie = entityManager.find(Boutique.class, boutiqueA.getId());
            autreTransaction().executeWithoutResult(autre -> {
                Boutique suspendue = boutiques.findById(boutiqueA.getId()).orElseThrow();
                suspendue.setActif(false); boutiques.saveAndFlush(suspendue);
            });
            assertThat(ancienneCopie.isActif()).isTrue();
            service.modifierPourBoutiqueCourante(formulaire("Interdit", null, null, null));
        }), HttpStatus.FORBIDDEN);
        Boutique boutique = boutiques.findById(boutiqueA.getId()).orElseThrow();
        assertThat(boutique.getNom()).isEqualTo("Boutique A"); assertThat(boutique.isActif()).isFalse();
    }

    private TransactionTemplate autreTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); return transaction;
    }

    private Boutique boutique(String nom) {
        Boutique boutique = new Boutique(); boutique.setNom(nom); return boutiques.saveAndFlush(boutique);
    }

    private Utilisateur compte(String email, Boutique boutique, Role role, boolean operateur) {
        Utilisateur utilisateur = new Utilisateur(); utilisateur.setNom(email); utilisateur.setEmail(email);
        utilisateur.setMotDePasse("hash conservé"); utilisateur.setBoutique(boutique); utilisateur.setRole(role);
        utilisateur.setAdministrateurPlateforme(operateur); utilisateur.setDateInscription(LocalDate.of(2026, 10, 12));
        return utilisateurs.saveAndFlush(utilisateur);
    }

    private static ParametresBoutiqueForm formulaire(String nom, String adresse, String telephone, String email) {
        ParametresBoutiqueForm formulaire = new ParametresBoutiqueForm(); formulaire.setNom(nom);
        formulaire.setAdresse(adresse); formulaire.setTelephone(telephone); formulaire.setEmail(email); return formulaire;
    }

    private static void connecter(Utilisateur utilisateur, String... roles) {
        contexte(UsernamePasswordAuthenticationToken.authenticated(utilisateur.getEmail(), "",
                Arrays.stream(roles).map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()));
    }

    private static void contexte(Authentication authentication) {
        var contexte = SecurityContextHolder.createEmptyContext(); contexte.setAuthentication(authentication); SecurityContextHolder.setContext(contexte);
    }

    private static void exigerStatut(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, HttpStatus statut) {
        assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class)
                .satisfies(erreur -> assertThat(((ResponseStatusException) erreur).getStatusCode()).isEqualTo(statut));
    }

    enum DroitsInvalides { ABSENTS, ANONYME, AUTORITE_ABSENTE, COMPTE_ABSENT, ROLE_CAISSIER, COMPTE_INACTIF, BOUTIQUE_INACTIVE }
}
