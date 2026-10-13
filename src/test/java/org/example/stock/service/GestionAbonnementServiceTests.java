package org.example.stock.service;

import org.example.stock.enums.ActionAbonnement;
import org.example.stock.enums.Role;
import org.example.stock.enums.StatutAbonnement;
import org.example.stock.form.AccesBoutiqueForm;
import org.example.stock.form.ActivationAbonnementForm;
import org.example.stock.form.ProlongationEssaiForm;
import org.example.stock.model.Boutique;
import org.example.stock.model.HistoriqueAbonnement;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.BoutiqueRepository;
import org.example.stock.repository.HistoriqueAbonnementRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:gestion-abonnement-service-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
class GestionAbonnementServiceTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 31, 10, 15);
    @Autowired GestionAbonnementService service;
    @Autowired PlateformeService plateforme;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @MockitoSpyBean HistoriqueAbonnementRepository historique;
    @MockitoBean(name = "recoveryClock") Clock clock;
    Boutique boutiquePlateforme, boutiqueA, boutiqueB;
    Utilisateur operateur, secondOperateur, gerantA, gerantB, caissier;

    @BeforeEach
    void preparer() {
        SecurityContextHolder.clearContext();
        when(clock.instant()).thenReturn(NOW.toInstant(ZoneOffset.UTC));
        historique.deleteAll(); utilisateurs.deleteAll(); boutiques.deleteAll();
        boutiquePlateforme = boutique("Plateforme");
        boutiqueA = boutique("Boutique A"); boutiqueB = boutique("Boutique B confidentielle");
        operateur = compte("operateur@abonnement.test", boutiquePlateforme, Role.ADMIN, true);
        secondOperateur = compte("autre-operateur@abonnement.test", boutiquePlateforme, Role.ADMIN, true);
        gerantA = compte("gerant-a@abonnement.test", boutiqueA, Role.ADMIN, false);
        gerantB = compte("gerant-b@abonnement.test", boutiqueB, Role.ADMIN, false);
        caissier = compte("caissier@abonnement.test", boutiqueA, Role.CAISSIER, false);
        connecter(operateur, "PLATEFORME_ADMIN");
    }

    @AfterEach
    void nettoyerContexte() { SecurityContextHolder.clearContext(); }

    @Test
    void unMoisCivilActiveUnAccesEtEnregistreUniquementLactionEffective() {
        ActivationAbonnementForm formulaire = activation(); formulaire.setMotif("  Activation manuelle  ");
        var situation = service.activerMois(boutiqueA.getId(), formulaire);
        assertThat(situation.statut()).isEqualTo(StatutAbonnement.ABONNEMENT);
        assertThat(situation.abonnementDebut()).isEqualTo(NOW);
        assertThat(situation.abonnementFin()).isEqualTo(LocalDateTime.of(2026, 2, 28, 10, 15));
        assertThat(situation.joursRestants()).isEqualTo(28);
        assertThat(situation.accesAutorise()).isTrue();
        HistoriqueAbonnement evenement = historique.findAll().getFirst();
        assertThat(evenement.getAction()).isEqualTo(ActionAbonnement.ACTIVATION_ABONNEMENT);
        assertThat(evenement.getActeurNom()).isEqualTo("Opérateur conservé");
        assertThat(evenement.getCreeLe()).isEqualTo(NOW);
        assertThat(evenement.getPeriodeDebut()).isEqualTo(NOW);
        assertThat(evenement.getPeriodeFin()).isEqualTo(situation.abonnementFin());
        assertThat(evenement.getAncienneFin()).isNull();
        assertThat(evenement.getQuantite()).isEqualTo(1);
        assertThat(evenement.getMotif()).isEqualTo("Activation manuelle");
        assertThat(evenement.getJetonAction()).isEqualTo(formulaire.getJetonAction());
        Utilisateur conserve = utilisateurs.findById(gerantA.getId()).orElseThrow();
        assertThat(conserve.getMotDePasse()).isEqualTo("hash conservé");
        assertThat(conserve.getRole()).isEqualTo(Role.ADMIN);
        assertThat(conserve.isAdministrateurPlateforme()).isFalse();
        assertThat(boutiques.findById(boutiqueB.getId()).orElseThrow().getAbonnementFin()).isNull();
    }

    @Test
    void lessaiValideEstConserveEtLeMoisCommenceALaFinDeLessai() {
        essai(boutiqueA, NOW.minusDays(20), NOW.plusDays(10));
        var situation = service.activerMois(boutiqueA.getId(), activation());
        assertThat(situation.statut()).isEqualTo(StatutAbonnement.ESSAI);
        assertThat(situation.joursRestants()).isEqualTo(10);
        assertThat(situation.essaiDebut()).isEqualTo(NOW.minusDays(20));
        assertThat(situation.essaiFin()).isEqualTo(NOW.plusDays(10));
        assertThat(situation.abonnementDebut()).isEqualTo(NOW.plusDays(10));
        assertThat(situation.abonnementFin()).isEqualTo(NOW.plusDays(10).plusMonths(1));
        var second = service.activerMois(boutiqueA.getId(), activation());
        assertThat(second.abonnementDebut()).isEqualTo(situation.abonnementDebut());
        assertThat(second.abonnementFin()).isEqualTo(situation.abonnementFin().plusMonths(1));
        assertThat(historique.count()).isEqualTo(2);
        when(clock.instant()).thenReturn(situation.essaiFin().toInstant(ZoneOffset.UTC));
        assertThat(service.situation(boutiques.findById(boutiqueA.getId()).orElseThrow()).statut())
                .isEqualTo(StatutAbonnement.ABONNEMENT);
    }

    @Test
    void unRenouvellementConserveLeDebutEtLesJoursDejaAccordes() {
        abonnement(boutiqueA, NOW.minusDays(10), NOW.plusDays(20));
        var situation = service.activerMois(boutiqueA.getId(), activation());
        assertThat(situation.abonnementDebut()).isEqualTo(NOW.minusDays(10));
        assertThat(situation.abonnementFin()).isEqualTo(NOW.plusDays(20).plusMonths(1));
        HistoriqueAbonnement evenement = historique.findAll().getFirst();
        assertThat(evenement.getAncienneFin()).isEqualTo(NOW.plusDays(20));
        assertThat(evenement.getPeriodeDebut()).isEqualTo(NOW.plusDays(20));
    }

    @Test
    void apresUneInterruptionLeNouveauMoisCommenceMaintenant() {
        abonnement(boutiqueA, NOW.minusMonths(2), NOW.minusDays(3));
        var situation = service.activerMois(boutiqueA.getId(), activation());
        assertThat(situation.abonnementDebut()).isEqualTo(NOW);
        assertThat(situation.abonnementFin()).isEqualTo(NOW.plusMonths(1));
        assertThat(historique.findAll().getFirst().getAncienneFin()).isEqualTo(NOW.minusDays(3));
    }

    @Test
    void leMoisCivilRespecteLeJourSupplementaireDesAnneesBissextiles() {
        Instant maintenant = Instant.parse("2024-01-31T10:15:00Z");
        when(clock.instant()).thenReturn(maintenant);
        assertThat(service.activerMois(boutiqueA.getId(), activation()).abonnementFin())
                .isEqualTo(LocalDateTime.of(2024, 2, 29, 10, 15));
    }

    @Test
    void laProlongationAjouteDesJoursALaFinDeLessaiSansChangerSonDebut() {
        essai(boutiqueA, NOW.minusDays(10), NOW.plusDays(20));
        ProlongationEssaiForm formulaire = prolongation(7);
        var situation = service.prolongerEssai(boutiqueA.getId(), formulaire);
        assertThat(situation.essaiDebut()).isEqualTo(NOW.minusDays(10));
        assertThat(situation.essaiFin()).isEqualTo(NOW.plusDays(27));
        assertThat(situation.abonnementFin()).isNull();
        var evenement = historique.findAll().getFirst();
        assertThat(evenement.getAction()).isEqualTo(ActionAbonnement.PROLONGATION_ESSAI);
        assertThat(evenement.getAncienneFin()).isEqualTo(NOW.plusDays(20));
        assertThat(evenement.getPeriodeDebut()).isEqualTo(NOW.plusDays(20));
        assertThat(evenement.getPeriodeFin()).isEqualTo(NOW.plusDays(27));
        assertThat(evenement.getQuantite()).isEqualTo(7);
    }

    @Test
    void prolongerUnEssaiExpireAccordeTousLesJoursAPartirDeMaintenant() {
        essai(boutiqueA, NOW.minusDays(60), NOW.minusDays(30));
        var situation = service.prolongerEssai(boutiqueA.getId(), prolongation(7));
        assertThat(situation.essaiDebut()).isEqualTo(NOW.minusDays(60));
        assertThat(situation.essaiFin()).isEqualTo(NOW.plusDays(7));
        assertThat(situation.accesAutorise()).isTrue();
        assertThat(historique.findAll().getFirst().getPeriodeDebut()).isEqualTo(NOW);
    }

    @Test
    void uneProlongationNeTransformePasUnAccesExistantEnAccesLimite() {
        assertThatThrownBy(() -> service.prolongerEssai(boutiqueA.getId(), prolongation(7)))
                .isInstanceOf(AbonnementException.class);
        Boutique conservee = boutiques.findById(boutiqueA.getId()).orElseThrow();
        assertThat(service.situation(conservee).statut()).isEqualTo(StatutAbonnement.ACCES_EXISTANT);
        assertThat(conservee.getEssaiFin()).isNull();
        assertThat(historique.count()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void lesPeriodesNeChangentPasLaSuspensionOuLaccesOffert(boolean actif, boolean offert) {
        essai(boutiqueA, NOW.minusDays(30), NOW);
        boutiqueA.setActif(actif); boutiqueA.setAccesOffert(offert); boutiques.saveAndFlush(boutiqueA);
        service.prolongerEssai(boutiqueA.getId(), prolongation(3));
        service.activerMois(boutiqueA.getId(), activation());
        Boutique conservee = boutiques.findById(boutiqueA.getId()).orElseThrow();
        assertThat(conservee.isActif()).isEqualTo(actif);
        assertThat(conservee.isAccesOffert()).isEqualTo(offert);
        assertThat(service.situation(conservee).accesAutorise()).isEqualTo(actif);
        if (!actif) assertThat(service.situation(conservee).statut()).isEqualTo(StatutAbonnement.SUSPENDU);
        else if (offert) assertThat(service.situation(conservee).statut()).isEqualTo(StatutAbonnement.OFFERT);
        assertThat(historique.findAll()).extracting(HistoriqueAbonnement::getAction)
                .containsExactlyInAnyOrder(ActionAbonnement.ACTIVATION_ABONNEMENT, ActionAbonnement.PROLONGATION_ESSAI);
    }

    @Test
    void leMemeFormulaireNeCompteQuuneSeuleFois() {
        ActivationAbonnementForm formulaire = activation();
        var premiere = service.activerMois(boutiqueA.getId(), formulaire);
        var repetition = service.activerMois(boutiqueA.getId(), formulaire);
        assertThat(repetition).isEqualTo(premiere);
        assertThat(historique.count()).isEqualTo(1);
        essai(boutiqueB, NOW.minusDays(5), NOW.plusDays(25));
        ProlongationEssaiForm jours = prolongation(7);
        var debut = service.prolongerEssai(boutiqueB.getId(), jours);
        assertThat(service.prolongerEssai(boutiqueB.getId(), jours)).isEqualTo(debut);
        assertThat(historique.count()).isEqualTo(2);
    }

    @Test
    void leJetonNePeutPasServirPourUneAutreBoutiqueActeurOuAction() {
        ActivationAbonnementForm formulaire = activation();
        service.activerMois(boutiqueA.getId(), formulaire);
        assertConflit(() -> service.activerMois(boutiqueB.getId(), formulaire));
        connecter(secondOperateur, "PLATEFORME_ADMIN");
        assertConflit(() -> service.activerMois(boutiqueA.getId(), formulaire));
        connecter(operateur, "PLATEFORME_ADMIN");
        formulaire.setMotif("Modification du contenu");
        assertConflit(() -> service.activerMois(boutiqueA.getId(), formulaire));
        ProlongationEssaiForm prolongation = prolongation(5);
        prolongation.setJetonAction(formulaire.getJetonAction());
        assertConflit(() -> service.prolongerEssai(boutiqueA.getId(), prolongation));
        assertThat(historique.count()).isEqualTo(1);
        assertThat(boutiques.findById(boutiqueB.getId()).orElseThrow().getAbonnementFin()).isNull();
    }

    @Test
    void unChangementDuNombreDeJoursAvecLeMemeJetonEstRefuse() {
        essai(boutiqueA, NOW.minusDays(10), NOW.plusDays(20));
        ProlongationEssaiForm formulaire = prolongation(7);
        service.prolongerEssai(boutiqueA.getId(), formulaire);
        formulaire.setJours(8);
        assertConflit(() -> service.prolongerEssai(boutiqueA.getId(), formulaire));
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getEssaiFin()).isEqualTo(NOW.plusDays(27));
        assertThat(historique.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"10,40", "-10,20"})
    void laProlongationNeChevauchePasUnAbonnementPrevuOuActif(int debutAbonnement, int finAbonnement) {
        essai(boutiqueA, NOW.minusDays(20), NOW.plusDays(10));
        abonnement(boutiqueA, NOW.plusDays(debutAbonnement), NOW.plusDays(finAbonnement));
        assertThat(service.situation(boutiqueA).essaiProlongeable()).isFalse();
        assertThatThrownBy(() -> service.prolongerEssai(boutiqueA.getId(), prolongation(7)))
                .isInstanceOf(AbonnementException.class)
                .hasMessage("Un abonnement est déjà prévu ou actif. Ajoutez un mois à cet abonnement pour prolonger l’accès.");
        Boutique conservee = boutiques.findById(boutiqueA.getId()).orElseThrow();
        assertThat(conservee.getEssaiDebut()).isEqualTo(NOW.minusDays(20));
        assertThat(conservee.getEssaiFin()).isEqualTo(NOW.plusDays(10));
        assertThat(conservee.getAbonnementDebut()).isEqualTo(NOW.plusDays(debutAbonnement));
        assertThat(conservee.getAbonnementFin()).isEqualTo(NOW.plusDays(finAbonnement));
        assertThat(historique.count()).isZero();
    }

    @Test
    void lessaiPeutEtreProlongeApresExpirationDeLabonnement() {
        essai(boutiqueA, NOW.minusDays(60), NOW.minusDays(30));
        abonnement(boutiqueA, NOW.minusDays(30), NOW);
        assertThat(service.situation(boutiqueA).essaiProlongeable()).isTrue();
        var situation = service.prolongerEssai(boutiqueA.getId(), prolongation(7));
        assertThat(situation.essaiFin()).isEqualTo(NOW.plusDays(7));
        assertThat(situation.abonnementDebut()).isEqualTo(NOW.minusDays(30));
        assertThat(situation.abonnementFin()).isEqualTo(NOW);
        assertThat(historique.count()).isEqualTo(1);
    }

    @Test
    void unAncienFormulaireRejoueApresActivationNeModifiePasLesPeriodes() {
        essai(boutiqueA, NOW.minusDays(10), NOW.plusDays(20));
        ProlongationEssaiForm prolongation = prolongation(7);
        service.prolongerEssai(boutiqueA.getId(), prolongation);
        var apresActivation = service.activerMois(boutiqueA.getId(), activation());
        assertThat(service.prolongerEssai(boutiqueA.getId(), prolongation)).isEqualTo(apresActivation);
        assertThat(historique.count()).isEqualTo(2);
    }

    @Test
    void lesChangementsDaccesSontHistorisesSansEvenementsPourLesRepetitions() {
        AccesBoutiqueForm formulaire = acces(false, true);
        plateforme.modifierAcces(boutiqueA.getId(), formulaire);
        plateforme.modifierAcces(boutiqueA.getId(), formulaire);
        assertThat(historique.count()).isEqualTo(2);
        plateforme.modifierAcces(boutiqueA.getId(), acces(true, false));
        assertThat(historique.findAll()).extracting(HistoriqueAbonnement::getAction)
                .containsExactlyInAnyOrder(ActionAbonnement.SUSPENSION_BOUTIQUE, ActionAbonnement.ACCES_OFFERT,
                        ActionAbonnement.REACTIVATION_BOUTIQUE, ActionAbonnement.RETRAIT_ACCES_OFFERT);
        assertThat(historique.findAll()).allSatisfy(e -> {
            assertThat(e.getQuantite()).isNull(); assertThat(e.getPeriodeDebut()).isNull();
            assertThat(e.getPeriodeFin()).isNull(); assertThat(e.getAncienneFin()).isNull();
        });
    }

    @Test
    void uneErreurDenregistrementAnnuleLaPeriodeEtSonHistorique() {
        doThrow(new IllegalStateException("Échec simulé d’enregistrement"))
                .when(historique).saveAndFlush(any(HistoriqueAbonnement.class));
        assertThatThrownBy(() -> service.activerMois(boutiqueA.getId(), activation()))
                .isInstanceOf(IllegalStateException.class);
        Boutique conservee = boutiques.findById(boutiqueA.getId()).orElseThrow();
        assertThat(conservee.getAbonnementDebut()).isNull(); assertThat(conservee.getAbonnementFin()).isNull();
        assertThat(historique.count()).isZero();
    }

    @Test
    void unePeriodeExistanteIncoherenteNestPasRepareeImplicitement() {
        boutiqueA.setAbonnementDebut(NOW); boutiques.saveAndFlush(boutiqueA);
        assertThatThrownBy(() -> service.activerMois(boutiqueA.getId(), activation()))
                .isInstanceOf(AbonnementException.class);
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getAbonnementFin()).isNull();
        assertThat(historique.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 3651})
    void lesDureesInvalidesSontRefuseesAussiParLeService(int jours) {
        assertThatThrownBy(() -> service.prolongerEssai(boutiqueA.getId(), prolongation(jours)))
                .isInstanceOf(AbonnementException.class);
        assertThat(historique.count()).isZero();
    }

    @Test
    void lesChampsAbsentsEtTropLongsNeModifientPasLaBoutique() {
        ProlongationEssaiForm prolongation = prolongation(1); prolongation.setJours(null);
        assertThatThrownBy(() -> service.prolongerEssai(boutiqueA.getId(), prolongation))
                .isInstanceOf(AbonnementException.class);
        ActivationAbonnementForm activation = activation(); activation.setJetonAction("invalid");
        assertThatThrownBy(() -> service.activerMois(boutiqueA.getId(), activation)).isInstanceOf(AbonnementException.class);
        activation.setJetonAction(UUID.randomUUID().toString()); activation.setMotif("a".repeat(501));
        assertThatThrownBy(() -> service.activerMois(boutiqueA.getId(), activation)).isInstanceOf(AbonnementException.class);
        assertThatThrownBy(() -> service.activerMois(boutiqueA.getId(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(historique.count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(AutorisationInvalide.class)
    void lesDroitsDePlateformeSontRelusPourChaquePointDentree(AutorisationInvalide cas) {
        switch (cas) {
            case ABSENTE -> SecurityContextHolder.clearContext();
            case ANONYME -> setAuth(new AnonymousAuthenticationToken("test", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
            case NON_AUTHENTIFIEE -> setAuth(UsernamePasswordAuthenticationToken.unauthenticated(operateur.getEmail(), ""));
            case AUTORITE_ABSENTE -> connecter(operateur, "ADMIN");
            case COMPTE_ABSENT -> setAuth(auth("inexistant@abonnement.test", "PLATEFORME_ADMIN"));
            case FLAG_ABSENT -> connecter(gerantA, "PLATEFORME_ADMIN");
            case FLAG_RETIRE -> { operateur.setAdministrateurPlateforme(false); utilisateurs.saveAndFlush(operateur); }
            case ROLE_RETIRE -> { operateur.setRole(Role.CAISSIER); utilisateurs.saveAndFlush(operateur); }
            case COMPTE_DESACTIVE -> { operateur.setActif(false); utilisateurs.saveAndFlush(operateur); }
        }
        List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> appels = List.of(
                () -> service.consulterPourPlateforme(boutiqueA.getId(), 0),
                () -> service.activerMois(boutiqueA.getId(), activation()),
                () -> service.prolongerEssai(boutiqueA.getId(), prolongation(1)),
                () -> service.modifierAcces(boutiqueA.getId(), acces(false, true)));
        appels.forEach(appel -> assertThatThrownBy(appel).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN)));
        assertThat(historique.count()).isZero();
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().isActif()).isTrue();
    }

    @Test
    void leGerantLitSonHistoriqueMemeApresSuspensionOuExpiration() {
        service.activerMois(boutiqueA.getId(), activation());
        service.activerMois(boutiqueB.getId(), activation());
        plateforme.modifierAcces(boutiqueA.getId(), acces(false, false));
        connecter(gerantA, "ADMIN");
        VueAbonnement vue = service.consulterPourCompteCourant(0);
        assertThat(vue.situation().boutiqueId()).isEqualTo(boutiqueA.getId());
        assertThat(vue.situation().statut()).isEqualTo(StatutAbonnement.SUSPENDU);
        assertThat(vue.historique()).hasSize(2);
        assertThat(vue.historique()).extracting(EvenementAbonnement::action)
                .containsExactly(ActionAbonnement.SUSPENSION_BOUTIQUE, ActionAbonnement.ACTIVATION_ABONNEMENT);
        assertThat(vue.toString()).doesNotContain("hash conservé", operateur.getEmail(), "jetonAction", boutiqueB.getNom());
        assertThatThrownBy(() -> service.consulterPourPlateforme(boutiqueB.getId(), 0))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void unCaissierOuUnRoleRetireNeLitPasLabonnement() {
        connecter(caissier, "ADMIN");
        assertThatThrownBy(() -> service.consulterPourCompteCourant(0)).isInstanceOf(ResponseStatusException.class);
        connecter(gerantA, "CAISSIER");
        assertThatThrownBy(() -> service.consulterPourCompteCourant(0)).isInstanceOf(ResponseStatusException.class);
        connecter(gerantA, "ADMIN"); gerantA.setActif(false); utilisateurs.saveAndFlush(gerantA);
        assertThatThrownBy(() -> service.consulterPourCompteCourant(0)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void lhistoriqueEstPagineDansLordreDesDernieresActions() {
        for (int i = 0; i < 22; i++) service.activerMois(boutiqueA.getId(), activation());
        var premiere = service.consulterPourPlateforme(boutiqueA.getId(), 0);
        var suivante = service.consulterPourPlateforme(boutiqueA.getId(), 1);
        assertThat(premiere.historique()).hasSize(20);
        assertThat(premiere.pagePrecedente()).isFalse(); assertThat(premiere.pageSuivante()).isTrue();
        assertThat(suivante.historique()).hasSize(2);
        assertThat(suivante.pagePrecedente()).isTrue(); assertThat(suivante.pageSuivante()).isFalse();
        assertThat(premiere.historique().getLast().id()).isGreaterThan(suivante.historique().getFirst().id());
        assertThat(premiere.historique()).extracting(EvenementAbonnement::id).doesNotHaveDuplicates();
        assertThat(service.consulterPourPlateforme(boutiqueA.getId(), 100_000).historique()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 100_001, Integer.MAX_VALUE})
    void laPaginationHorsBornesEstRefusee(int page) {
        assertThatThrownBy(() -> service.consulterPourPlateforme(boutiqueA.getId(), page))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void leDecompteArronditToutJourCommenceYComprisUneFractionDeSeconde() {
        Boutique boutique = new Boutique(); boutique.setNom("Décompte");
        boutique.setEssaiDebut(NOW.minusDays(1)); boutique.setEssaiFin(NOW.plusDays(1).plusNanos(1));
        assertThat(service.situation(boutique).joursRestants()).isEqualTo(2);
        boutique.setEssaiFin(NOW.plusNanos(1)); assertThat(service.situation(boutique).joursRestants()).isEqualTo(1);
        boutique.setEssaiFin(NOW); assertThat(service.situation(boutique).joursRestants()).isZero();
    }

    @Test
    void unChangementDeNomNeReecritPasLeNomConserveDansLhistorique() {
        service.activerMois(boutiqueA.getId(), activation());
        operateur.setNom("Nouveau nom"); utilisateurs.saveAndFlush(operateur);
        assertThat(service.consulterPourPlateforme(boutiqueA.getId(), 0).historique().getFirst().acteurNom())
                .isEqualTo("Opérateur conservé");
    }

    @Test
    void lesNomsEtMotifsUnicodeRestentNormalisesSansCouperDeCaractere() {
        operateur.setNom("\u2003" + "😀".repeat(100) + "\u2003"); utilisateurs.saveAndFlush(operateur);
        ActivationAbonnementForm formulaire = activation(); formulaire.setMotif("\u2003Accès mensuel\u2003");
        service.activerMois(boutiqueA.getId(), formulaire);
        HistoriqueAbonnement evenement = historique.findAll().getFirst();
        assertThat(evenement.getActeurNom()).isEqualTo("😀".repeat(100));
        assertThat(evenement.getMotif()).isEqualTo("Accès mensuel");
    }

    @Test
    void deuxAdministrateursConcurrentsAjoutentDeuxMoisSansPerdreUneActivation() throws Exception {
        executerConcurrent(() -> { connecter(operateur, "PLATEFORME_ADMIN"); service.activerMois(boutiqueA.getId(), activation()); },
                () -> { connecter(secondOperateur, "PLATEFORME_ADMIN"); service.activerMois(boutiqueA.getId(), activation()); });
        Boutique boutique = boutiques.findById(boutiqueA.getId()).orElseThrow();
        assertThat(boutique.getAbonnementDebut()).isEqualTo(NOW);
        assertThat(boutique.getAbonnementFin()).isEqualTo(NOW.plusMonths(1).plusMonths(1));
        assertThat(historique.count()).isEqualTo(2);
        assertThat(historique.findAll()).extracting(HistoriqueAbonnement::getPeriodeDebut)
                .containsExactlyInAnyOrder(NOW, NOW.plusMonths(1));
    }

    @Test
    void deuxRequetesConcurrentesDuMemeFormulaireNaccordentQuunMois() throws Exception {
        ActivationAbonnementForm formulaire = activation();
        executerConcurrent(() -> { connecter(operateur, "PLATEFORME_ADMIN"); service.activerMois(boutiqueA.getId(), formulaire); },
                () -> { connecter(operateur, "PLATEFORME_ADMIN"); service.activerMois(boutiqueA.getId(), formulaire); });
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getAbonnementFin()).isEqualTo(NOW.plusMonths(1));
        assertThat(historique.count()).isEqualTo(1);
    }

    private void executerConcurrent(Runnable premier, Runnable second) throws Exception {
        CountDownLatch prets = new CountDownLatch(2), depart = new CountDownLatch(1);
        try (var executeur = Executors.newFixedThreadPool(2)) {
            var taches = List.of(premier, second).stream().map(action -> executeur.submit(() -> {
                prets.countDown();
                try { assertThat(depart.await(5, TimeUnit.SECONDS)).isTrue(); action.run(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                finally { SecurityContextHolder.clearContext(); }
            })).toList();
            assertThat(prets.await(5, TimeUnit.SECONDS)).isTrue(); depart.countDown();
            for (var tache : taches) tache.get(15, TimeUnit.SECONDS);
        }
    }

    private Boutique boutique(String nom) {
        Boutique b = new Boutique(); b.setNom(nom); return boutiques.saveAndFlush(b);
    }

    private Utilisateur compte(String email, Boutique b, Role role, boolean plateforme) {
        Utilisateur u = new Utilisateur(); u.setNom("Opérateur conservé"); u.setEmail(email); u.setBoutique(b);
        u.setRole(role); u.setAdministrateurPlateforme(plateforme); u.setMotDePasse("hash conservé");
        u.setDateInscription(LocalDate.of(2026, 1, 1)); return utilisateurs.saveAndFlush(u);
    }

    private void essai(Boutique b, LocalDateTime debut, LocalDateTime fin) {
        b.setEssaiDebut(debut); b.setEssaiFin(fin); boutiques.saveAndFlush(b);
    }

    private void abonnement(Boutique b, LocalDateTime debut, LocalDateTime fin) {
        b.setAbonnementDebut(debut); b.setAbonnementFin(fin); boutiques.saveAndFlush(b);
    }

    private static ActivationAbonnementForm activation() {
        ActivationAbonnementForm f = new ActivationAbonnementForm(); f.setJetonAction(UUID.randomUUID().toString()); return f;
    }

    private static ProlongationEssaiForm prolongation(int jours) {
        ProlongationEssaiForm f = new ProlongationEssaiForm(); f.setJours(jours); f.setJetonAction(UUID.randomUUID().toString()); return f;
    }

    private static AccesBoutiqueForm acces(boolean actif, boolean offert) {
        AccesBoutiqueForm f = new AccesBoutiqueForm(); f.setActif(actif); f.setAccesOffert(offert); return f;
    }

    private static Authentication auth(String email, String role) {
        return UsernamePasswordAuthenticationToken.authenticated(email, "", List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static void connecter(Utilisateur u, String role) { setAuth(auth(u.getEmail(), role)); }
    private static void setAuth(Authentication auth) {
        var contexte = SecurityContextHolder.createEmptyContext(); contexte.setAuthentication(auth); SecurityContextHolder.setContext(contexte);
    }

    private static void assertConflit(org.assertj.core.api.ThrowableAssert.ThrowingCallable appel) {
        assertThatThrownBy(appel).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    enum AutorisationInvalide {
        ABSENTE, ANONYME, NON_AUTHENTIFIEE, AUTORITE_ABSENTE, COMPTE_ABSENT, FLAG_ABSENT,
        FLAG_RETIRE, ROLE_RETIRE, COMPTE_DESACTIVE
    }
}
