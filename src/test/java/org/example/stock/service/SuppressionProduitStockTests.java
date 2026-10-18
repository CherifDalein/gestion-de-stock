package org.example.stock.service;

import org.example.stock.enums.Role;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:stock-suppression-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
class SuppressionProduitStockTests {
    @Autowired ProduitService catalogue;
    @Autowired AchatService achats;
    @Autowired ProduitRepository produits;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired CategorieRepository categories;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired AchatRepository documentsAchat;
    @Autowired VenteRepository documentsVente;
    @Autowired PlatformTransactionManager transactions;
    Boutique boutique;
    Produit produit;
    Categorie categorie;
    Fournisseur fournisseur;
    String email;

    @BeforeEach void preparer() {
        boutique = new Boutique(); boutique.setNom("Suppression produit"); boutique = boutiques.saveAndFlush(boutique);
        var compte = new Utilisateur(); compte.setBoutique(boutique); compte.setNom("Gérant"); compte.setRole(Role.ADMIN);
        email = "suppression-" + UUID.randomUUID() + "@example.test"; compte.setEmail(email); utilisateurs.saveAndFlush(compte); connecter();
        categorie = new Categorie(); categorie.setBoutique(boutique); categorie.setNom("Catalogue"); categorie = categories.saveAndFlush(categorie);
        fournisseur = new Fournisseur(); fournisseur.setBoutique(boutique); fournisseur.setNom("Fournisseur"); fournisseur = fournisseurs.saveAndFlush(fournisseur);
        produit = new Produit(); produit.setNom("Article"); produit.setReference(UUID.randomUUID().toString()); produit.setCategorie(categorie);
        produit = catalogue.ajouterProduit(produit);
    }

    @AfterEach void deconnecter() { SecurityContextHolder.clearContext(); }

    void connecter() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                email, "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    Achat achat() {
        var achat = new Achat(); achat.setFournisseur(fournisseur); achat.setMontantVerse(BigDecimal.ZERO);
        var ligne = new DetailAchat(); ligne.setProduit(produit); ligne.setQuantite(5); ligne.setPrixAchatUnitaire(BigDecimal.TEN); ligne.setPrixVenteUnitaire(new BigDecimal("20"));
        achat.getLignes().add(ligne); return achat;
    }

    @Test void stockPositifHeriteSansHistoriqueNePeutPasDisparaitreParSuppression() {
        var ancien = produits.findById(produit.getId()).orElseThrow(); ancien.setQuantite(7L); ancien.setPrixDefinis(true); produits.saveAndFlush(ancien);
        assertThatThrownBy(() -> catalogue.supprimerProduit(produit.getId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stock");
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(7);
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().isInitialisationTerminee()).isFalse();
    }

    @Test void uneFicheVideSansHistoriqueResteSupprimableSansTerminerLeDemarrage() {
        catalogue.supprimerProduit(produit.getId());
        assertThat(produits.findById(produit.getId())).isEmpty();
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().isInitialisationTerminee()).isFalse();
    }

    @Test void uneFicheVideResteSupprimableApresLeDemarrage() {
        boutique.setInitialisationTerminee(true); boutiques.saveAndFlush(boutique);
        catalogue.supprimerProduit(produit.getId());
        assertThat(produits.findById(produit.getId())).isEmpty();
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().isInitialisationTerminee()).isTrue();
    }

    @Test void unProduitAZeroAvecAncienAchatEstConserveAvecUnMessageMetier() {
        var ancien = achat(); ancien.setBoutique(boutique); ancien.setMontantTotal(new BigDecimal("50")); ancien.getLignes().forEach(ligne -> ligne.setAchat(ancien));
        documentsAchat.saveAndFlush(ancien);
        assertThatThrownBy(() -> catalogue.supprimerProduit(produit.getId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("historique");
        assertThat(produits.findById(produit.getId())).isPresent();
        assertThat(documentsAchat.findById(ancien.getId())).isPresent();
    }

    @Test void unProduitAZeroAvecAncienneVenteEstConserveAvecUnMessageMetier() {
        var ancienne = new Vente(); ancienne.setBoutique(boutique); ancienne.setMontantTotal(new BigDecimal("20")); ancienne.setMontantVerse(BigDecimal.ZERO);
        var ligne = new DetailVente(); ligne.setVente(ancienne); ligne.setProduit(produit); ligne.setQuantite(1); ligne.setPrixUnitaire(new BigDecimal("20")); ancienne.getLignes().add(ligne);
        documentsVente.saveAndFlush(ancienne);
        assertThatThrownBy(() -> catalogue.supprimerProduit(produit.getId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("historique");
        assertThat(produits.findById(produit.getId())).isPresent();
        assertThat(documentsVente.findById(ancienne.getId())).isPresent();
    }

    @Test void uneAutreBoutiqueNePeutPasSupprimerLeProduit() {
        var autre = new Boutique(); autre.setNom("Autre boutique"); autre = boutiques.saveAndFlush(autre);
        var compte = utilisateurs.findByEmail(email).orElseThrow(); compte.setBoutique(autre); utilisateurs.saveAndFlush(compte);
        assertThatThrownBy(() -> catalogue.supprimerProduit(produit.getId()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThat(produits.findById(produit.getId())).isPresent();
    }

    @Test void laSuppressionAttendLaReceptionEnCoursPuisRefuseDeSupprimerLeStock() throws Exception {
        var commence = new CountDownLatch(1);
        var resultat = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Throwable>>();
        try (var pool = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactions).executeWithoutResult(statut -> {
                achats.enregistrerAchat(achat());
                var futur = pool.submit(() -> {
                    connecter(); commence.countDown();
                    try { catalogue.supprimerProduit(produit.getId()); return null; }
                    catch (Throwable e) { return e; }
                    finally { SecurityContextHolder.clearContext(); }
                });
                resultat.set(futur);
                try { assertThat(commence.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                assertThatThrownBy(() -> futur.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            });
            assertThat(resultat.get().get(10, TimeUnit.SECONDS)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stock");
        }
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(5);
        assertThat(documentsAchat.findByBoutiqueIdOrderByDateAchatDesc(boutique.getId())).hasSize(1);
    }
}
