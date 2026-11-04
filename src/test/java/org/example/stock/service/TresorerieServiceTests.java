package org.example.stock.service;

import org.example.stock.enums.CompteTresorerie;
import org.example.stock.enums.Role;
import org.example.stock.form.DepenseTresorerieForm;
import org.example.stock.form.OuvertureCaisseForm;
import org.example.stock.form.TransfertTresorerieForm;
import org.example.stock.model.Boutique;
import org.example.stock.model.MouvementCaisse;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.example.stock.enums.CompteTresorerie.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:tresorerie-services;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
class TresorerieServiceTests {
    @Autowired CaisseService caisse;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired MouvementCaisseRepository mouvements;
    @Autowired CompteTresorerieBoutiqueRepository comptes;
    @Autowired OperationTresorerieRepository operations;
    @Autowired PlatformTransactionManager transactions;
    Boutique boutique;
    Utilisateur gerant;

    @BeforeEach void preparer() {
        boutique = nouvelleBoutique();
        gerant = compte(boutique, Role.ADMIN);
        connecter(gerant);
    }

    @AfterEach void deconnecter() { SecurityContextHolder.clearContext(); }

    @Test void troisComptesSontProvisionnesAvecChaqueNouvelleBoutique() {
        assertThat(comptes.findAll().stream().filter(c -> c.getBoutique().getId().equals(boutique.getId())))
                .extracting(c -> c.getCompte()).containsExactlyInAnyOrder(CAISSE, ORANGE_MONEY, BANQUE);
        assertThat(caisse.getSoldesParCompte()).containsOnlyKeys(CAISSE, ORANGE_MONEY, BANQUE);
        assertThat(caisse.getSoldesParCompte().values()).allSatisfy(v -> assertThat(v).isZero());
    }

    @Test void chaqueSoldeInitialSeSaisitUneFoisPendantLeDemarrage() {
        initialiser(CAISSE, "0");
        assertThat(caisse.peutInitialiser()).isFalse();
        assertThat(caisse.getComptesInitialisables()).containsExactly(ORANGE_MONEY, BANQUE);
        assertThatThrownBy(() -> initialiser(CAISSE, "100")).hasMessageContaining("une seule fois");
        initialiser(ORANGE_MONEY, "200"); initialiser(BANQUE, "300");
        assertThat(caisse.getSoldeActuel()).isEqualByComparingTo("500");
        assertThat(caisse.getComptesInitialisables()).isEmpty();
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().isInitialisationTerminee()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"CAISSE,ORANGE_MONEY", "CAISSE,BANQUE", "ORANGE_MONEY,CAISSE", "ORANGE_MONEY,BANQUE", "BANQUE,CAISSE", "BANQUE,ORANGE_MONEY"})
    void unTransfertDansChaqueSensConserveLeTotalEtNeGonflePasLesFlux(CompteTresorerie source, CompteTresorerie destination) {
        provisionner();
        var formulaire = transfert(source, destination, "100");
        assertThat(caisse.transferer(formulaire)).isFalse();
        assertThat(caisse.getSoldeCompte(source)).isEqualByComparingTo("900");
        assertThat(caisse.getSoldeCompte(destination)).isEqualByComparingTo("1100");
        assertThat(caisse.getSoldeActuel()).isEqualByComparingTo("3000");
        assertThat(caisse.getEntreesDuJour(LocalDate.now())).isZero();
        assertThat(caisse.getSortiesDuJour(LocalDate.now())).isZero();
        assertThat(caisse.getNetDuJour(LocalDate.now())).isZero();
        var ecritures = caisse.listerTous().stream().filter(m -> "TRANSFERT".equals(m.getSource())).toList();
        assertThat(ecritures).hasSize(2);
        assertThat(ecritures.stream().map(MouvementCaisse::getMontant).reduce(BigDecimal.ZERO, BigDecimal::add)).isZero();
        assertThat(ecritures).allSatisfy(m -> {
            assertThat(m.getOperationTresorerie()).isNotNull();
            assertThat(m.getOperationTresorerie().getId()).isEqualTo(ecritures.getFirst().getOperationTresorerie().getId());
            assertThat(m.getUtilisateur().getId()).isEqualTo(gerant.getId());
            assertThat(m.getMotif()).isEqualTo(formulaire.getMotif());
        });
        assertThat(caisse.getComptesInitialisables()).isEmpty();
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().isInitialisationTerminee()).isTrue();
    }

    @Test void uneDepenseNeDebiteQueLeCompteChoisiEtLeTotal() {
        provisionner();
        assertThat(caisse.depenser(depense(BANQUE, "150"))).isFalse();
        assertThat(caisse.getSoldeCompte(BANQUE)).isEqualByComparingTo("850");
        assertThat(caisse.getSoldeCompte(CAISSE)).isEqualByComparingTo("1000");
        assertThat(caisse.getSoldeCompte(ORANGE_MONEY)).isEqualByComparingTo("1000");
        assertThat(caisse.getSoldeActuel()).isEqualByComparingTo("2850");
        assertThat(caisse.getSortiesDuJour(LocalDate.now())).isEqualByComparingTo("150");
        assertThat(caisse.getNetDuJour(LocalDate.now())).isEqualByComparingTo("-150");
        assertThat(caisse.getEntreesDuJour(LocalDate.now())).isZero();
    }

    @Test void repeterUnTransfertOuUneDepenseNeDoubleAucunMouvement() {
        provisionner();
        var transfert = transfert(CAISSE, ORANGE_MONEY, "100");
        var depense = depense(BANQUE, "20");
        assertThat(caisse.transferer(transfert)).isFalse(); assertThat(caisse.transferer(transfert)).isTrue();
        assertThat(caisse.depenser(depense)).isFalse(); assertThat(caisse.depenser(depense)).isTrue();
        assertThat(caisse.listerTous()).hasSize(6); // Trois soldes initiaux, deux jambes, une dépense.
        assertThat(caisse.getSoldeActuel()).isEqualByComparingTo("2980");
    }

    @Test void unJetonRejoueAvecAutreContenuOuAutreActeurEstRefuse() {
        provisionner();
        var formulaire = transfert(CAISSE, ORANGE_MONEY, "100"); caisse.transferer(formulaire);
        formulaire.setMontant(new BigDecimal("101"));
        assertThatThrownBy(() -> caisse.transferer(formulaire)).isInstanceOf(OperationTresorerieConflitException.class);
        formulaire.setMontant(new BigDecimal("100")); formulaire.setCompteDestination(BANQUE);
        assertThatThrownBy(() -> caisse.transferer(formulaire)).isInstanceOf(OperationTresorerieConflitException.class);
        formulaire.setCompteDestination(ORANGE_MONEY); formulaire.setMotif("Autre motif");
        assertThatThrownBy(() -> caisse.transferer(formulaire)).isInstanceOf(OperationTresorerieConflitException.class);
        formulaire.setMotif("Transfert de test"); connecter(compte(boutique, Role.ADMIN));
        assertThatThrownBy(() -> caisse.transferer(formulaire)).isInstanceOf(OperationTresorerieConflitException.class);
        assertThat(caisse.listerTous()).hasSize(5);
        assertThat(caisse.getSoldeActuel()).isEqualByComparingTo("3000");
    }

    @Test void lesBoutiquesNePeuventNiConsommerLesSoldesNiRejouerLesOperationsDesAutres() {
        provisionner();
        var formulaire = transfert(CAISSE, ORANGE_MONEY, "100"); caisse.transferer(formulaire);
        var autre = nouvelleBoutique(); connecter(compte(autre, Role.ADMIN));
        assertThat(caisse.getSoldeActuel()).isZero(); assertThat(caisse.listerTous()).isEmpty();
        assertThatThrownBy(() -> caisse.transferer(formulaire)).isInstanceOf(OperationTresorerieConflitException.class);
        assertThatThrownBy(() -> caisse.transferer(transfert(CAISSE, BANQUE, "1"))).hasMessageContaining("insuffisant");
        assertThat(caisse.listerTous()).isEmpty();
        connecter(gerant); assertThat(caisse.getSoldeActuel()).isEqualByComparingTo("3000");
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "1.001", "1000000000000000"})
    void lesMontantsInvalidesNeLaissentAucuneOperation(String montant) {
        provisionner();
        var formulaire = transfert(CAISSE, ORANGE_MONEY, montant);
        assertThatThrownBy(() -> caisse.transferer(formulaire)).isInstanceOf(IllegalArgumentException.class);
        assertThat(operations.findByJeton(formulaire.getJetonOperation())).isEmpty();
        assertThat(caisse.listerTous()).hasSize(3);
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().isInitialisationTerminee()).isFalse();
    }

    @Test void leCompteSourceDoitEtreDifferentEtSuffisammentApprovisionne() {
        provisionner();
        assertThatThrownBy(() -> caisse.transferer(transfert(CAISSE, CAISSE, "1"))).hasMessageContaining("différents");
        assertThatThrownBy(() -> caisse.transferer(transfert(CAISSE, BANQUE, "1001"))).hasMessageContaining("insuffisant");
        assertThatThrownBy(() -> caisse.depenser(depense(ORANGE_MONEY, "1001"))).hasMessageContaining("insuffisant");
        var incomplet = transfert(CAISSE, BANQUE, "10"); incomplet.setCompteDestination(null);
        assertThatThrownBy(() -> caisse.transferer(incomplet)).isInstanceOf(IllegalArgumentException.class);
        assertThat(caisse.listerTous()).hasSize(3);
        assertThat(caisse.getSoldeActuel()).isEqualByComparingTo("3000");
    }

    @Test void lesCaissiersEtLesComptesDesactivesNePeuventPasTransfererOuDepenser() {
        provisionner();
        var caissier = compte(boutique, Role.CAISSIER); connecter(caissier);
        assertThatThrownBy(() -> caisse.transferer(transfert(CAISSE, BANQUE, "1"))).hasMessageContaining("403");
        assertThatThrownBy(() -> caisse.depenser(depense(CAISSE, "1"))).hasMessageContaining("403");
        // Un rôle fourni par une session ne remplace jamais le rôle réel du compte.
        connecter(caissier, "ADMIN");
        assertThatThrownBy(() -> caisse.transferer(transfert(CAISSE, BANQUE, "1"))).hasMessageContaining("403");
        gerant.setActif(false); utilisateurs.saveAndFlush(gerant); connecter(gerant);
        assertThatThrownBy(() -> caisse.transferer(transfert(CAISSE, BANQUE, "1"))).hasMessageContaining("403");
    }

    @Test void uneExceptionAnnuleEnsembleLesDeuxEcrituresEtLaClotureDuDemarrage() {
        provisionner();
        var formulaire = transfert(CAISSE, BANQUE, "100");
        var tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThatThrownBy(() -> tx.execute(status -> {
            caisse.transferer(formulaire);
            throw new IllegalStateException("Annulation de l'opération complète");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(caisse.listerTous()).hasSize(3);
        assertThat(operations.findByJeton(formulaire.getJetonOperation())).isEmpty();
        assertThat(caisse.getSoldeCompte(CAISSE)).isEqualByComparingTo("1000");
        assertThat(caisse.getSoldeCompte(BANQUE)).isEqualByComparingTo("1000");
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().isInitialisationTerminee()).isFalse();
    }

    @Test void deuxGerantsNePeuventPasDepenserDeuxFoisLeMemeSolde() throws Exception {
        initialiser(BANQUE, "100");
        var second = compte(boutique, Role.ADMIN);
        var pret = new CountDownLatch(2); var depart = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> concurrent(gerant, pret, depart, () -> caisse.transferer(transfert(BANQUE, CAISSE, "80"))));
            var b = executor.submit(() -> concurrent(second, pret, depart, () -> caisse.depenser(depense(BANQUE, "80"))));
            assertThat(pret.await(5, TimeUnit.SECONDS)).isTrue(); depart.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ok", "insuffisant");
        }
        connecter(gerant);
        assertThat(caisse.getSoldeCompte(BANQUE)).isEqualByComparingTo("20");
        assertThat(caisse.getSoldesParCompte().values()).allSatisfy(v -> assertThat(v.signum()).isNotNegative());
        var ecritures = caisse.listerTous().stream().filter(m -> !"SOLDE_INITIAL".equals(m.getSource())).toList();
        assertThat(ecritures).hasSizeBetween(1, 2);
        assertThat(ecritures.stream().map(m -> m.getOperationTresorerie().getId()).distinct()).hasSize(1);
    }

    private String concurrent(Utilisateur acteur, CountDownLatch pret, CountDownLatch depart, Runnable action) throws InterruptedException {
        connecter(acteur); pret.countDown();
        try {
            if (!depart.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Départ non reçu");
            action.run(); return "ok";
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage().contains("insuffisant")) return "insuffisant";
            throw exception;
        } finally { SecurityContextHolder.clearContext(); }
    }

    private void provisionner() { for (var compte : CompteTresorerie.values()) initialiser(compte, "1000"); }

    private void initialiser(CompteTresorerie compte, String montant) {
        var f = new OuvertureCaisseForm(); f.setComptePaiement(compte); f.setMontant(new BigDecimal(montant));
        caisse.initialiser(f);
    }

    private TransfertTresorerieForm transfert(CompteTresorerie source, CompteTresorerie destination, String montant) {
        var f = new TransfertTresorerieForm(); f.setCompteSource(source); f.setCompteDestination(destination);
        f.setMontant(new BigDecimal(montant)); f.setMotif("Transfert de test"); f.setJetonOperation(UUID.randomUUID().toString()); return f;
    }

    private DepenseTresorerieForm depense(CompteTresorerie source, String montant) {
        var f = new DepenseTresorerieForm(); f.setCompteSource(source); f.setMontant(new BigDecimal(montant));
        f.setMotif("Frais de fonctionnement"); f.setJetonOperation(UUID.randomUUID().toString()); return f;
    }

    private Boutique nouvelleBoutique() {
        var b = new Boutique(); b.setNom("Boutique " + UUID.randomUUID()); return boutiques.saveAndFlush(b);
    }

    private Utilisateur compte(Boutique b, Role role) {
        var u = new Utilisateur(); u.setBoutique(b); u.setRole(role); u.setNom("Compte trésorerie");
        u.setEmail(UUID.randomUUID() + "@example.test"); return utilisateurs.saveAndFlush(u);
    }

    private void connecter(Utilisateur u) { connecter(u, u.getRole().name()); }
    private void connecter(Utilisateur u, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u.getEmail(), "unused",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
