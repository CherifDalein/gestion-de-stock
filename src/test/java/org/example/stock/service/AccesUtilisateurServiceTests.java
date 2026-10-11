package org.example.stock.service;

import jakarta.persistence.EntityManager;
import org.example.stock.enums.Role;
import org.example.stock.form.AccesUtilisateurForm;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:acces-utilisateur-service-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
class AccesUtilisateurServiceTests {
    @Autowired AccesUtilisateurService service;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ProduitRepository produits;
    @Autowired CategorieRepository categories;
    @Autowired VenteRepository ventes;
    @Autowired MouvementCaisseRepository caisse;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;

    Boutique boutiqueA;
    Boutique boutiqueB;
    Utilisateur gerantA;
    Utilisateur gerantB;
    Utilisateur caissierA;
    Utilisateur plateformeA;
    Utilisateur plateformeB;
    Produit produit;
    Vente vente;

    @BeforeEach
    void preparer() {
        SecurityContextHolder.clearContext();
        caisse.deleteAll(); ventes.deleteAll(); produits.deleteAll(); categories.deleteAll();
        utilisateurs.deleteAll(); boutiques.deleteAll();
        boutiqueA = boutique("Boutique A"); boutiqueB = boutique("Boutique B");
        gerantA = utilisateur("gerant-a@acces.test", boutiqueA, Role.ADMIN, false);
        gerantB = utilisateur("gerant-b@acces.test", boutiqueB, Role.ADMIN, false);
        caissierA = utilisateur("caissier-a@acces.test", boutiqueA, Role.CAISSIER, false);
        plateformeA = utilisateur("plateforme-a@acces.test", boutiqueA, Role.ADMIN, true);
        plateformeB = utilisateur("plateforme-b@acces.test", boutiqueB, Role.ADMIN, true);
        Categorie categorie = new Categorie(); categorie.setNom("Stock conservé"); categorie.setBoutique(boutiqueA);
        categorie = categories.saveAndFlush(categorie);
        produit = new Produit(); produit.setNom("Produit conservé"); produit.setReference("REF-CONSERVEE");
        produit.setBoutique(boutiqueA); produit.setCategorie(categorie); produit.setQuantite(6L);
        produit.setPrixAchat(new BigDecimal("1.10")); produit.setPrixVente(new BigDecimal("2.20"));
        produit = produits.saveAndFlush(produit);
        vente = new Vente(); vente.setBoutique(boutiqueA); vente.setDateVente(LocalDateTime.now());
        vente.setMontantTotal(new BigDecimal("2.20")); vente.setMontantVerse(new BigDecimal("2.20"));
        DetailVente ligne = new DetailVente(); ligne.setVente(vente); ligne.setProduit(produit);
        ligne.setQuantite(1); ligne.setPrixUnitaire(new BigDecimal("2.20")); vente.getLignes().add(ligne);
        vente = ventes.saveAndFlush(vente);
        MouvementCaisse mouvement = new MouvementCaisse(); mouvement.setBoutique(boutiqueA); mouvement.setVente(vente);
        mouvement.setUtilisateur(caissierA); mouvement.setDateMouvement(LocalDateTime.now());
        mouvement.setMontant(new BigDecimal("2.20")); mouvement.setType("ENTREE"); mouvement.setSource("VENTE");
        caisse.saveAndFlush(mouvement);
        connecter(gerantA, "ADMIN");
    }

    @AfterEach
    void deconnecter() { SecurityContextHolder.clearContext(); }

    @Test
    void laListeNeContientQueLaBoutiqueCouranteEtJamaisDeHash() {
        var comptes = service.listerPourBoutiqueCourante();
        assertThat(comptes).extracting(Utilisateur::getId)
                .containsExactlyInAnyOrder(gerantA.getId(), caissierA.getId(), plateformeA.getId());
        assertThat(comptes).allSatisfy(compte -> {
            assertThat(compte.getMotDePasse()).isNull();
            assertThat(compte.isActif()).isTrue();
            assertThat(compte.getBoutique().getId()).isEqualTo(boutiqueA.getId());
        });
        assertThat(recharger(caissierA).getMotDePasse()).isEqualTo("hash initial");
    }

    @Test
    void laDesactivationEtLaReactivationSontIdempotentesEtNeChangentQueLeStatut() {
        service.modifierPourBoutiqueCourante(caissierA.getId(), acces(false));
        service.modifierPourBoutiqueCourante(caissierA.getId(), acces(false));
        verifierCompteEtMetierPreserves(false);
        service.modifierPourBoutiqueCourante(caissierA.getId(), acces(true));
        service.modifierPourBoutiqueCourante(caissierA.getId(), acces(true));
        verifierCompteEtMetierPreserves(true);
    }

    @Test
    void unGerantPeutGererUnAutreGerantDeSaBoutique() {
        Utilisateur autreGerant = utilisateur("autre-gerant@acces.test", boutiqueA, Role.ADMIN, false);
        service.modifierPourBoutiqueCourante(autreGerant.getId(), acces(false));
        assertThat(recharger(autreGerant).isActif()).isFalse();
        assertThat(recharger(autreGerant).getRole()).isEqualTo(Role.ADMIN);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unGerantNePeutPasGererUnAdministrateurDePlateforme(boolean actif) {
        exigerStatut(() -> service.modifierPourBoutiqueCourante(plateformeA.getId(), acces(actif)), HttpStatus.FORBIDDEN);
        assertThat(recharger(plateformeA).isActif()).isTrue();
    }

    @Test
    void lesIdentifiantsEtrangersEtInexistantsRestentIntrouvables() {
        exigerStatut(() -> service.modifierPourBoutiqueCourante(gerantB.getId(), acces(false)), HttpStatus.NOT_FOUND);
        exigerStatut(() -> service.modifierPourBoutiqueCourante(999999L, acces(false)), HttpStatus.NOT_FOUND);
        assertThat(recharger(gerantB).isActif()).isTrue();
        connecter(plateformeA, "ADMIN", "PLATEFORME_ADMIN");
        exigerStatut(() -> service.modifierPourPlateforme(boutiqueA.getId(), gerantB.getId(), acces(false)), HttpStatus.NOT_FOUND);
        exigerStatut(() -> service.modifierPourPlateforme(999999L, gerantB.getId(), acces(false)), HttpStatus.NOT_FOUND);
    }

    @Test
    void aucunParcoursNePermetDeDesactiverSonPropreCompte() {
        exigerStatut(() -> service.modifierPourBoutiqueCourante(gerantA.getId(), acces(false)), HttpStatus.BAD_REQUEST);
        connecter(plateformeA, "ADMIN", "PLATEFORME_ADMIN");
        exigerStatut(() -> service.modifierPourPlateforme(boutiqueA.getId(), plateformeA.getId(), acces(false)), HttpStatus.BAD_REQUEST);
        exigerStatut(() -> service.modifierPourBoutiqueCourante(plateformeA.getId(), acces(false)), HttpStatus.BAD_REQUEST);
        assertThat(recharger(gerantA).isActif()).isTrue();
        assertThat(recharger(plateformeA).isActif()).isTrue();
    }

    @Test
    void laPlateformePeutGererLesAutresComptesYComprisUnAdministrateurDePlateforme() {
        connecter(plateformeA, "ADMIN", "PLATEFORME_ADMIN");
        service.modifierPourPlateforme(boutiqueB.getId(), plateformeB.getId(), acces(false));
        assertThat(recharger(plateformeB).isActif()).isFalse();
        assertThat(recharger(plateformeB).isAdministrateurPlateforme()).isTrue();
        assertThat(recharger(plateformeB).getRole()).isEqualTo(Role.ADMIN);
        service.modifierPourPlateforme(boutiqueB.getId(), plateformeB.getId(), acces(true));
        assertThat(recharger(plateformeB).isActif()).isTrue();
        assertThat(recharger(plateformeB).getMotDePasse()).isEqualTo("hash initial");
    }

    @Test
    void laPlateformePeutReactiverUnCompteDansUneBoutiqueSuspendue() {
        boutiqueB.setActif(false); boutiques.saveAndFlush(boutiqueB);
        gerantB.setActif(false); utilisateurs.saveAndFlush(gerantB);
        connecter(plateformeA, "ADMIN", "PLATEFORME_ADMIN");
        service.modifierPourPlateforme(boutiqueB.getId(), gerantB.getId(), acces(true));
        assertThat(recharger(gerantB).isActif()).isTrue();
        assertThat(boutiques.findById(boutiqueB.getId()).orElseThrow().isActif()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(AutorisationGerantInvalide.class)
    void lesAutorisationsDeGerantAbsentesOuPerimeesSontRefusees(AutorisationGerantInvalide cas) {
        switch (cas) {
            case ABSENTE -> SecurityContextHolder.clearContext();
            case ANONYME -> contexte(new AnonymousAuthenticationToken("test", "anonymousUser",
                    List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
            case AUTORITE_ABSENTE -> connecter(gerantA, "CAISSIER");
            case COMPTE_ABSENT -> contexte(UsernamePasswordAuthenticationToken.authenticated("absent@acces.test", "",
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            case ROLE_RETIRE -> { gerantA.setRole(Role.CAISSIER); utilisateurs.saveAndFlush(gerantA); }
            case COMPTE_INACTIF -> { gerantA.setActif(false); utilisateurs.saveAndFlush(gerantA); }
            case BOUTIQUE_INACTIVE -> { boutiqueA.setActif(false); boutiques.saveAndFlush(boutiqueA); }
        }
        exigerStatut(service::verifierGestionBoutiqueCourante, HttpStatus.FORBIDDEN);
        exigerStatut(service::listerPourBoutiqueCourante, HttpStatus.FORBIDDEN);
        exigerStatut(() -> service.modifierPourBoutiqueCourante(caissierA.getId(), acces(false)), HttpStatus.FORBIDDEN);
        assertThat(recharger(caissierA).isActif()).isTrue();
    }

    @Test
    void leGerantNePeutPasUtiliserLApiDePlateformeMemeAvecUneAutoriteForgee() {
        connecter(gerantA, "ADMIN", "PLATEFORME_ADMIN");
        exigerStatut(() -> service.modifierPourPlateforme(boutiqueB.getId(), gerantB.getId(), acces(false)), HttpStatus.FORBIDDEN);
        assertThat(recharger(gerantB).isActif()).isTrue();
    }

    @Test
    void unAdministrateurDePlateformeDesactiveNePeutPasGererLesComptes() {
        plateformeA.setActif(false); utilisateurs.saveAndFlush(plateformeA);
        connecter(plateformeA, "ADMIN", "PLATEFORME_ADMIN");
        exigerStatut(() -> service.modifierPourPlateforme(boutiqueB.getId(), gerantB.getId(), acces(false)), HttpStatus.FORBIDDEN);
        assertThat(recharger(gerantB).isActif()).isTrue();
    }

    @Test
    void unStatutAbsentEstRefuseAvantTouteMutationDansLesDeuxParcours() {
        exigerStatut(() -> service.modifierPourBoutiqueCourante(caissierA.getId(), new AccesUtilisateurForm()), HttpStatus.BAD_REQUEST);
        connecter(plateformeA, "ADMIN", "PLATEFORME_ADMIN");
        exigerStatut(() -> service.modifierPourPlateforme(boutiqueA.getId(), caissierA.getId(), null), HttpStatus.BAD_REQUEST);
        assertThat(recharger(caissierA).isActif()).isTrue();
    }

    @Test
    void unCompteDejaChargeEstRafraichiAvantLaMiseAJourDuStatut() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Utilisateur ancienneCopie = entityManager.find(Utilisateur.class, caissierA.getId());
            autreTransaction().executeWithoutResult(autre -> {
                Utilisateur miseAJour = utilisateurs.findById(caissierA.getId()).orElseThrow();
                miseAJour.setMotDePasse("hash réinitialisé"); miseAJour.setRole(Role.ADMIN);
                utilisateurs.saveAndFlush(miseAJour);
            });
            assertThat(ancienneCopie.getMotDePasse()).isEqualTo("hash initial");
            service.modifierPourBoutiqueCourante(caissierA.getId(), acces(false));
            assertThat(ancienneCopie.getMotDePasse()).isEqualTo("hash réinitialisé");
            assertThat(ancienneCopie.getRole()).isEqualTo(Role.ADMIN);
        });
        Utilisateur relu = recharger(caissierA);
        assertThat(relu.isActif()).isFalse();
        assertThat(relu.getMotDePasse()).isEqualTo("hash réinitialisé");
        assertThat(relu.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void leFlagDePlateformeEstRecontroleApresRafraichissementDeLaCible() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            entityManager.find(Utilisateur.class, caissierA.getId());
            autreTransaction().executeWithoutResult(autre -> {
                Utilisateur promu = utilisateurs.findById(caissierA.getId()).orElseThrow();
                promu.setRole(Role.ADMIN); promu.setAdministrateurPlateforme(true); utilisateurs.saveAndFlush(promu);
            });
            service.modifierPourBoutiqueCourante(caissierA.getId(), acces(false));
        })).isInstanceOf(ResponseStatusException.class);
        assertThat(recharger(caissierA).isAdministrateurPlateforme()).isTrue();
        assertThat(recharger(caissierA).isActif()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void lesDroitsDeLActeurDejaChargeSontRecontrolesSousVerrou(boolean parcoursPlateforme, boolean retirerRole) {
        Utilisateur acteur = parcoursPlateforme ? plateformeA : gerantA;
        connecter(acteur, parcoursPlateforme ? new String[]{"ADMIN", "PLATEFORME_ADMIN"} : new String[]{"ADMIN"});
        exigerStatut(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Utilisateur ancienneCopie = entityManager.find(Utilisateur.class, acteur.getId());
            autreTransaction().executeWithoutResult(autre -> {
                Utilisateur modifie = utilisateurs.findById(acteur.getId()).orElseThrow();
                if (retirerRole) modifie.setRole(Role.CAISSIER); else modifie.setActif(false);
                utilisateurs.saveAndFlush(modifie);
            });
            assertThat(ancienneCopie.isActif()).isTrue();
            assertThat(ancienneCopie.getRole()).isEqualTo(Role.ADMIN);
            if (parcoursPlateforme) service.modifierPourPlateforme(boutiqueA.getId(), caissierA.getId(), acces(false));
            else service.modifierPourBoutiqueCourante(caissierA.getId(), acces(false));
        }), HttpStatus.FORBIDDEN);
        assertThat(recharger(caissierA).isActif()).isTrue();
        assertThat(recharger(acteur).isActif()).isEqualTo(retirerRole);
        assertThat(recharger(acteur).getRole()).isEqualTo(retirerRole ? Role.CAISSIER : Role.ADMIN);
    }

    @Test
    void deuxAdministrateursDePlateformeNePeuventPasSeDesactiverMutuellementEnMemeTemps() throws Exception {
        CyclicBarrier depart = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        Future<Throwable> premiere = null;
        Future<Throwable> seconde = null;
        try {
            premiere = pool.submit(() -> desactiverAutrePlateforme(plateformeA, plateformeB, depart));
            seconde = pool.submit(() -> desactiverAutrePlateforme(plateformeB, plateformeA, depart));
            Throwable erreurA = premiere.get(15, TimeUnit.SECONDS);
            Throwable erreurB = seconde.get(15, TimeUnit.SECONDS);
            var resultats = java.util.Arrays.asList(erreurA, erreurB);
            assertThat(resultats.stream().filter(java.util.Objects::isNull).count()).isEqualTo(1);
            assertThat(resultats.stream().filter(java.util.Objects::nonNull).toList()).singleElement()
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(erreur -> assertThat(((ResponseStatusException) erreur).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
            long actifs = List.of(recharger(plateformeA), recharger(plateformeB)).stream().filter(Utilisateur::isActif).count();
            assertThat(actifs).isEqualTo(1);
        } finally {
            if (premiere != null) premiere.cancel(true);
            if (seconde != null) seconde.cancel(true);
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Throwable desactiverAutrePlateforme(Utilisateur acteur, Utilisateur cible, CyclicBarrier depart) {
        connecter(acteur, "ADMIN", "PLATEFORME_ADMIN");
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                // Les deux sessions ont déjà lu leur propre compte actif avant de tenter les modifications opposées.
                assertThat(entityManager.find(Utilisateur.class, acteur.getId()).isActif()).isTrue();
                try { depart.await(5, TimeUnit.SECONDS); }
                catch (Exception erreur) { throw new IllegalStateException("Départ simultané impossible", erreur); }
                service.modifierPourPlateforme(cible.getBoutique().getId(), cible.getId(), acces(false));
            });
            return null;
        } catch (Throwable erreur) {
            return erreur;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void verifierCompteEtMetierPreserves(boolean actif) {
        Utilisateur relu = recharger(caissierA);
        assertThat(relu.isActif()).isEqualTo(actif); assertThat(relu.getRole()).isEqualTo(Role.CAISSIER);
        assertThat(relu.isAdministrateurPlateforme()).isFalse(); assertThat(relu.getEmail()).isEqualTo(caissierA.getEmail());
        assertThat(relu.getNom()).isEqualTo(caissierA.getNom()); assertThat(relu.getMotDePasse()).isEqualTo("hash initial");
        assertThat(relu.getDateInscription()).isEqualTo(caissierA.getDateInscription());
        assertThat(relu.getBoutique().getId()).isEqualTo(boutiqueA.getId());
        assertThat(utilisateurs.count()).isEqualTo(5); assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(6L);
        assertThat(ventes.count()).isEqualTo(1); assertThat(ventes.findById(vente.getId()).orElseThrow().getMontantTotal()).isEqualByComparingTo("2.20");
        assertThat(caisse.count()).isEqualTo(1); assertThat(caisse.findAll().getFirst().getUtilisateur().getId()).isEqualTo(caissierA.getId());
        assertThat(caisse.calculerSoldeTotal(boutiqueA.getId())).isEqualByComparingTo("2.20");
    }

    private TransactionTemplate autreTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); return transaction;
    }

    private Boutique boutique(String nom) { Boutique boutique = new Boutique(); boutique.setNom(nom); return boutiques.saveAndFlush(boutique); }

    private Utilisateur utilisateur(String email, Boutique boutique, Role role, boolean plateforme) {
        Utilisateur compte = new Utilisateur(); compte.setNom(email); compte.setEmail(email); compte.setMotDePasse("hash initial");
        compte.setBoutique(boutique); compte.setRole(role); compte.setAdministrateurPlateforme(plateforme);
        compte.setDateInscription(LocalDate.of(2026, 10, 11)); return utilisateurs.saveAndFlush(compte);
    }

    private Utilisateur recharger(Utilisateur compte) { return utilisateurs.findByEmail(compte.getEmail()).orElseThrow(); }

    private static AccesUtilisateurForm acces(boolean actif) { AccesUtilisateurForm formulaire = new AccesUtilisateurForm(); formulaire.setActif(actif); return formulaire; }

    private static void connecter(Utilisateur compte, String... roles) {
        contexte(UsernamePasswordAuthenticationToken.authenticated(compte.getEmail(), "",
                java.util.Arrays.stream(roles).map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()));
    }

    private static void contexte(Authentication authentication) {
        var contexte = SecurityContextHolder.createEmptyContext(); contexte.setAuthentication(authentication); SecurityContextHolder.setContext(contexte);
    }

    private static void exigerStatut(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, HttpStatus statut) {
        assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class)
                .satisfies(erreur -> assertThat(((ResponseStatusException) erreur).getStatusCode()).isEqualTo(statut));
    }

    enum AutorisationGerantInvalide {
        ABSENTE, ANONYME, AUTORITE_ABSENTE, COMPTE_ABSENT, ROLE_RETIRE, COMPTE_INACTIF, BOUTIQUE_INACTIVE
    }
}
