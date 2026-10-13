package org.example.stock.service;

import org.example.stock.enums.Role;
import org.example.stock.form.AccesBoutiqueForm;
import org.example.stock.form.CreationBoutiqueForm;
import org.example.stock.model.Boutique;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.BoutiqueRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:plateforme-service-test;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PlateformeServiceTests {
    private static final String SECRET = "Mot-de-passe-boutique-2026!";
    private static final String EMAIL_PLATEFORME = "plateforme@services.test";
    private static final String EMAIL_GERANT = "gerant@services.test";

    @Autowired PlateformeService plateforme;
    @Autowired BoutiqueRepository boutiques;
    @Autowired org.example.stock.repository.HistoriqueAbonnementRepository historiqueAbonnement;
    @MockitoSpyBean UtilisateurRepository utilisateurs;
    @Autowired PasswordEncoder passwordEncoder;

    Boutique boutiquePlateforme;
    Boutique boutiqueGerant;
    Utilisateur administrateur;
    Utilisateur gerant;

    @BeforeEach
    void preparer() {
        SecurityContextHolder.clearContext();
        historiqueAbonnement.deleteAll();
        utilisateurs.deleteAll();
        boutiques.deleteAll();
        boutiquePlateforme = boutique("Boutique plateforme");
        boutiqueGerant = boutique("Boutique du gérant");
        administrateur = utilisateur(EMAIL_PLATEFORME, boutiquePlateforme, true);
        gerant = utilisateur(EMAIL_GERANT, boutiqueGerant, false);
        connecter(autorise(EMAIL_PLATEFORME));
    }

    @AfterEach
    void deconnecter() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void laCreationEnregistreUneBoutiqueEtSonGerantSansPrivilegeDePlateforme() {
        CreationBoutiqueForm formulaire = formulaire();
        formulaire.setNomBoutique("  Nouvelle boutique  ");
        formulaire.setNom("  Nouveau gérant  ");
        formulaire.setEmail("  NOUVEAU@SERVICES.TEST  ");
        formulaire.setAccesOffert(true);
        Boutique nouvelle = plateforme.creerBoutique(formulaire);

        assertThat(boutiques.count()).isEqualTo(3);
        assertThat(utilisateurs.count()).isEqualTo(3);
        assertThat(nouvelle.getNom()).isEqualTo("Nouvelle boutique");
        assertThat(nouvelle.getDateCreation()).isNotNull();
        assertThat(nouvelle.isActif()).isTrue();
        assertThat(nouvelle.isAccesOffert()).isTrue();
        Utilisateur compte = utilisateurs.findByEmail("nouveau@services.test").orElseThrow();
        assertThat(compte.getNom()).isEqualTo("Nouveau gérant");
        assertThat(compte.getRole()).isEqualTo(Role.ADMIN);
        assertThat(compte.isAdministrateurPlateforme()).isFalse();
        assertThat(compte.getBoutique().getId()).isEqualTo(nouvelle.getId());
        assertThat(compte.getDateInscription()).isNotNull();
        assertThat(compte.getMotDePasse()).isNotEqualTo(SECRET);
        assertThat(passwordEncoder.matches(SECRET, compte.getMotDePasse())).isTrue();
    }

    @Test
    void unEmailDejaUtiliseNeTransferePasLeCompteEtNeCreePasDeBoutique() {
        CreationBoutiqueForm formulaire = formulaire();
        formulaire.setEmail("  GERANT@SERVICES.TEST  ");
        assertThatThrownBy(() -> plateforme.creerBoutique(formulaire)).isInstanceOf(EmailDejaUtiliseException.class);
        assertThat(boutiques.count()).isEqualTo(2);
        assertThat(utilisateurs.count()).isEqualTo(2);
        Utilisateur conserve = utilisateurs.findByEmail(EMAIL_GERANT).orElseThrow();
        assertThat(conserve.getId()).isEqualTo(gerant.getId());
        assertThat(conserve.getBoutique().getId()).isEqualTo(boutiqueGerant.getId());
        assertThat(conserve.isAdministrateurPlateforme()).isFalse();
    }

    @Test
    void uneCollisionSqlApresCreationDeBoutiqueAnnuleTouteLaTransaction() {
        // Simule deux pré-vérifications réussies : la contrainte SQL détectera le compte déjà enregistré.
        doReturn(false).when(utilisateurs).existsByEmail(EMAIL_GERANT);
        CreationBoutiqueForm formulaire = formulaire();
        formulaire.setEmail(EMAIL_GERANT);
        assertThatThrownBy(() -> plateforme.creerBoutique(formulaire)).isInstanceOf(EmailDejaUtiliseException.class);
        assertThat(boutiques.count()).isEqualTo(2);
        assertThat(utilisateurs.count()).isEqualTo(2);
        assertThat(boutiques.findAll()).extracting(Boutique::getNom).doesNotContain("Nouvelle boutique");
        assertThat(utilisateurs.findByEmail(EMAIL_GERANT).orElseThrow().getBoutique().getId())
                .isEqualTo(boutiqueGerant.getId());
    }

    @ParameterizedTest
    @CsvSource(value = {"nomBoutique|", "nom|", "email|", "email|invalide", "password|court"},
            delimiter = '|', emptyValue = "")
    void lesCreationsInvalidesSontRefuseesAussiParLeService(String champ, String valeur) {
        CreationBoutiqueForm formulaire = formulaire();
        switch (champ) {
            case "nomBoutique" -> formulaire.setNomBoutique(valeur);
            case "nom" -> formulaire.setNom(valeur);
            case "email" -> formulaire.setEmail(valeur);
            case "password" -> formulaire.setPassword(valeur);
            default -> throw new IllegalArgumentException(champ);
        }
        assertThatThrownBy(() -> plateforme.creerBoutique(formulaire)).isInstanceOf(IllegalArgumentException.class);
        assertThat(boutiques.count()).isEqualTo(2);
        assertThat(utilisateurs.count()).isEqualTo(2);
    }

    @Test
    void unNomDeBoutiqueTropLongEstRefuseAvantTouteCreation() {
        CreationBoutiqueForm formulaire = formulaire();
        formulaire.setNomBoutique("a".repeat(256));
        assertThatThrownBy(() -> plateforme.creerBoutique(formulaire)).isInstanceOf(IllegalArgumentException.class);
        assertThat(boutiques.count()).isEqualTo(2);
        assertThat(utilisateurs.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(AutorisationInvalide.class)
    void tousLesPointsDentreeRefusentLesAutorisationsAbsentesOuPerimees(AutorisationInvalide cas) {
        switch (cas) {
            case ABSENTE -> SecurityContextHolder.clearContext();
            case ANONYME -> connecter(new AnonymousAuthenticationToken("test", "anonymousUser",
                    List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
            case NON_AUTHENTIFIEE -> connecter(UsernamePasswordAuthenticationToken.unauthenticated(EMAIL_PLATEFORME, ""));
            case AUTORITE_ABSENTE -> connecter(UsernamePasswordAuthenticationToken.authenticated(EMAIL_PLATEFORME, "",
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            case COMPTE_ABSENT -> connecter(autorise("inexistant@services.test"));
            case FLAG_ABSENT -> connecter(autorise(EMAIL_GERANT));
            case FLAG_RETIRE -> {
                administrateur.setAdministrateurPlateforme(false);
                utilisateurs.saveAndFlush(administrateur);
            }
            case ROLE_RETIRE -> {
                administrateur.setRole(Role.CAISSIER);
                utilisateurs.saveAndFlush(administrateur);
            }
        }
        for (PointDentree point : PointDentree.values()) {
            assertThatThrownBy(() -> appeler(point)).as(point.name())
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(erreur -> assertThat(((ResponseStatusException) erreur).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }
        assertThat(boutiques.count()).isEqualTo(2);
        assertThat(utilisateurs.count()).isEqualTo(2);
        assertThat(boutiques.findById(boutiqueGerant.getId()).orElseThrow().isActif()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void leStatutEtLAccesOffertSontIndependantsDesRolesEtDesDonnees(boolean actif, boolean offert) {
        AccesBoutiqueForm formulaire = new AccesBoutiqueForm();
        formulaire.setActif(actif);
        formulaire.setAccesOffert(offert);
        Boutique modifiee = plateforme.modifierAcces(boutiqueGerant.getId(), formulaire);
        assertThat(modifiee.isActif()).isEqualTo(actif);
        assertThat(modifiee.isAccesOffert()).isEqualTo(offert);
        assertThat(modifiee.getNom()).isEqualTo("Boutique du gérant");
        assertThat(utilisateurs.findByEmail(EMAIL_GERANT).orElseThrow().getRole()).isEqualTo(Role.ADMIN);
        assertThat(utilisateurs.findByEmail(EMAIL_GERANT).orElseThrow().isAdministrateurPlateforme()).isFalse();
        assertThat(utilisateurs.count()).isEqualTo(2);
        assertThat(boutiques.findById(boutiquePlateforme.getId()).orElseThrow().isActif()).isTrue();
    }

    @Test
    void uneBoutiqueSuspendueNeBloquePasSaReactivationParLAdministrateurDePlateforme() {
        boutiquePlateforme.setActif(false);
        boutiques.saveAndFlush(boutiquePlateforme);
        assertThat(plateforme.listerBoutiques()).hasSize(2);
        AccesBoutiqueForm acces = new AccesBoutiqueForm();
        acces.setActif(true);
        acces.setAccesOffert(false);
        plateforme.modifierAcces(boutiquePlateforme.getId(), acces);
        assertThat(boutiques.findById(boutiquePlateforme.getId()).orElseThrow().isActif()).isTrue();
    }

    @Test
    void laListeEtLesComptagesIncluentLesBoutiquesVides() {
        Boutique vide = boutique("Boutique vide");
        assertThat(plateforme.listerBoutiques()).extracting(Boutique::getId)
                .containsExactlyInAnyOrder(boutiquePlateforme.getId(), boutiqueGerant.getId(), vide.getId());
        assertThat(plateforme.compterUtilisateursParBoutique())
                .containsEntry(boutiquePlateforme.getId(), 1L).containsEntry(boutiqueGerant.getId(), 1L)
                .containsEntry(vide.getId(), 0L);
    }

    @Test
    void laListeDesComptesExposeSeulementDesCopiesSansHash() {
        var comptes = plateforme.listerUtilisateurs(boutiqueGerant.getId());
        assertThat(comptes).hasSize(1);
        Utilisateur copie = comptes.getFirst();
        assertThat(copie.getId()).isEqualTo(gerant.getId());
        assertThat(copie.getEmail()).isEqualTo(EMAIL_GERANT);
        assertThat(copie.getRole()).isEqualTo(Role.ADMIN);
        assertThat(copie.getBoutique().getId()).isEqualTo(boutiqueGerant.getId());
        assertThat(copie.getMotDePasse()).isNull();
        assertThat(utilisateurs.findByEmail(EMAIL_GERANT).orElseThrow().getMotDePasse()).isEqualTo("hash-conservé");
        copie.setNom("Modification de copie");
        assertThat(utilisateurs.findByEmail(EMAIL_GERANT).orElseThrow().getNom()).isEqualTo(EMAIL_GERANT);
    }

    @Test
    void lesIdentifiantsDeBoutiqueInexistantsNeCreentRien() {
        assertThatThrownBy(() -> plateforme.trouverBoutique(999999L)).isInstanceOf(ResponseStatusException.class)
                .satisfies(erreur -> assertThat(((ResponseStatusException) erreur).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> plateforme.listerUtilisateurs(999999L)).isInstanceOf(ResponseStatusException.class);
        AccesBoutiqueForm acces = new AccesBoutiqueForm();
        acces.setActif(true); acces.setAccesOffert(false);
        assertThatThrownBy(() -> plateforme.modifierAcces(999999L, acces))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(boutiques.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL|NULL", "true|NULL", "NULL|false"}, delimiter = '|', nullValues = "NULL")
    void unFormulaireDAccesVideOuPartielNeSuspendPasLaBoutique(Boolean actif, Boolean offert) {
        AccesBoutiqueForm acces = new AccesBoutiqueForm();
        acces.setActif(actif); acces.setAccesOffert(offert);
        assertThatThrownBy(() -> plateforme.modifierAcces(boutiqueGerant.getId(), acces))
                .isInstanceOf(IllegalArgumentException.class);
        Boutique intacte = boutiques.findById(boutiqueGerant.getId()).orElseThrow();
        assertThat(intacte.isActif()).isTrue();
        assertThat(intacte.isAccesOffert()).isFalse();
    }

    private void appeler(PointDentree point) {
        switch (point) {
            case VERIFICATION -> plateforme.verifierAdministrationPlateforme();
            case LISTE -> plateforme.listerBoutiques();
            case COMPTAGES -> plateforme.compterUtilisateursParBoutique();
            case DETAIL -> plateforme.trouverBoutique(boutiqueGerant.getId());
            case UTILISATEURS -> plateforme.listerUtilisateurs(boutiqueGerant.getId());
            case CREATION -> plateforme.creerBoutique(formulaire());
            case ACCES -> plateforme.modifierAcces(boutiqueGerant.getId(), new AccesBoutiqueForm());
        }
    }

    private Boutique boutique(String nom) {
        Boutique boutique = new Boutique(); boutique.setNom(nom); return boutiques.saveAndFlush(boutique);
    }

    private Utilisateur utilisateur(String email, Boutique boutique, boolean plateforme) {
        Utilisateur compte = new Utilisateur(); compte.setEmail(email); compte.setNom(email); compte.setBoutique(boutique);
        compte.setRole(Role.ADMIN); compte.setAdministrateurPlateforme(plateforme); compte.setMotDePasse("hash-conservé");
        return utilisateurs.saveAndFlush(compte);
    }

    private static CreationBoutiqueForm formulaire() {
        CreationBoutiqueForm formulaire = new CreationBoutiqueForm(); formulaire.setNomBoutique("Nouvelle boutique");
        formulaire.setNom("Nouveau gérant"); formulaire.setEmail("nouveau@services.test"); formulaire.setPassword(SECRET);
        return formulaire;
    }

    private static Authentication autorise(String email) {
        return UsernamePasswordAuthenticationToken.authenticated(email, "",
                List.of(new SimpleGrantedAuthority("ROLE_PLATEFORME_ADMIN")));
    }

    private static void connecter(Authentication authentication) {
        var contexte = SecurityContextHolder.createEmptyContext(); contexte.setAuthentication(authentication);
        SecurityContextHolder.setContext(contexte);
    }

    enum AutorisationInvalide {
        ABSENTE, ANONYME, NON_AUTHENTIFIEE, AUTORITE_ABSENTE, COMPTE_ABSENT, FLAG_ABSENT, FLAG_RETIRE, ROLE_RETIRE
    }

    enum PointDentree {
        VERIFICATION, LISTE, COMPTAGES, DETAIL, UTILISATEURS, CREATION, ACCES
    }
}
