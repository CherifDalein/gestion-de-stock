package org.example.stock.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.example.stock.enums.Role;
import org.example.stock.enums.TypeMouvementStock;
import org.example.stock.form.OuvertureCaisseForm;
import org.example.stock.form.StockInitialForm;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:stock-demarrage-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
class InitialisationBoutiqueServiceTests {
    @Autowired InitialisationBoutiqueService demarrage;
    @Autowired InventaireService inventaire;
    @Autowired ProduitService catalogue;
    @Autowired AchatService achats;
    @Autowired VenteService ventes;
    @Autowired CaisseService caisse;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ProduitRepository produits;
    @Autowired CategorieRepository categories;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired AchatRepository documentsAchat;
    @Autowired VenteRepository documentsVente;
    @Autowired MouvementCaisseRepository mouvementsCaisse;
    @Autowired MouvementStockRepository mouvementsStock;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager em;
    Boutique boutique;
    Categorie categorie;
    Fournisseur fournisseur;
    Utilisateur gerant;
    Utilisateur secondGerant;
    Produit produit;

    @BeforeEach void preparer() {
        boutique = new Boutique(); boutique.setNom("Démarrage test"); boutique = boutiques.saveAndFlush(boutique);
        gerant = compte(Role.ADMIN); secondGerant = compte(Role.ADMIN); connecter(gerant, "ADMIN");
        categorie = new Categorie(); categorie.setBoutique(boutique); categorie.setNom("Articles"); categorie = categories.saveAndFlush(categorie);
        fournisseur = new Fournisseur(); fournisseur.setBoutique(boutique); fournisseur.setNom("Fournisseur"); fournisseur = fournisseurs.saveAndFlush(fournisseur);
        produit = creerProduit();
    }

    @AfterEach void deconnecter() { SecurityContextHolder.clearContext(); }

    Utilisateur compte(Role role) {
        var compte = new Utilisateur(); compte.setBoutique(boutique); compte.setNom("Gérant test"); compte.setRole(role);
        compte.setEmail("demarrage-" + UUID.randomUUID() + "@example.test");
        return utilisateurs.saveAndFlush(compte);
    }

    void connecter(Utilisateur compte, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                compte.getEmail(), "unused", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    Produit creerProduit() {
        var p = new Produit(); p.setCategorie(categorie); p.setNom("Article"); p.setReference(UUID.randomUUID().toString());
        return catalogue.ajouterProduit(p);
    }

    StockInitialForm initial(Produit p, long quantite) {
        var f = new StockInitialForm(); f.setVersion(produits.findById(p.getId()).orElseThrow().getVersion());
        f.setQuantite(quantite); f.setCoutUnitaire(BigDecimal.TEN); f.setPrixVente(new BigDecimal("20")); f.setMotif("À l'ouverture"); return f;
    }

    OuvertureCaisseForm ouverture() {
        var f = new OuvertureCaisseForm(); f.setMontant(new BigDecimal("30000")); f.setMotif("Caisse présente à l'ouverture"); return f;
    }

    Achat achat(int quantite) {
        var a = new Achat(); a.setFournisseur(fournisseur); a.setMontantVerse(BigDecimal.ZERO);
        var d = new DetailAchat(); d.setProduit(produit); d.setQuantite(quantite); d.setPrixAchatUnitaire(BigDecimal.TEN); d.setPrixVenteUnitaire(new BigDecimal("20"));
        a.getLignes().add(d); return a;
    }

    Vente vente(int quantite) {
        var v = new Vente(); v.setMontantVerse(BigDecimal.ZERO);
        var d = new DetailVente(); d.setProduit(produit); d.setQuantite(quantite); v.getLignes().add(d); return v;
    }

    void initialisationsBloquees() {
        var nouveau = creerProduit();
        assertThat(nouveau.getQuantite()).isZero(); assertThat(nouveau.isPrixDefinis()).isFalse();
        assertThat(inventaire.consulter(nouveau.getId(), 0).initialAutorise()).isFalse();
        assertThat(caisse.peutInitialiser()).isFalse();
        assertThatThrownBy(() -> inventaire.initialiser(nouveau.getId(), initial(nouveau, 3))).hasMessageContaining("démarrage");
        assertThatThrownBy(() -> caisse.initialiser(ouverture())).hasMessageContaining("démarrage");
        assertThat(produits.findById(nouveau.getId()).orElseThrow().getQuantite()).isZero();
    }

    @Test void catalogueEtPlusieursStocksInitiauxEtSoldeInitialLaissentLaPhaseOuverte() {
        assertThat(demarrage.estOuverte()).isTrue();
        caisse.initialiser(ouverture()); inventaire.initialiser(produit.getId(), initial(produit, 10));
        var autre = creerProduit(); inventaire.initialiser(autre.getId(), initial(autre, 5));
        assertThat(demarrage.estOuverte()).isTrue();
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().isInitialisationTerminee()).isFalse();
        assertThat(mouvementsCaisse.findByBoutiqueIdOrderByDateMouvementDesc(boutique.getId())).hasSize(1);
        assertThat(documentsAchat.findByBoutiqueIdOrderByDateAchatDesc(boutique.getId())).isEmpty();
    }

    @Test void clotureManuelleEstIrreversibleEtNeBloquePasLaCreationDeFichesVides() {
        demarrage.terminer(); demarrage.terminer();
        assertThat(demarrage.estOuverte()).isFalse();
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().isInitialisationTerminee()).isTrue();
        initialisationsBloquees();
        achats.enregistrerAchat(achat(5));
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(5);
    }

    @Test void premierAchatACreditClotAussiLaCaisseMemeSansMouvementFinancier() {
        achats.enregistrerAchat(achat(5));
        assertThat(caisse.listerTous()).isEmpty(); assertThat(demarrage.estOuverte()).isFalse();
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().isInitialisationTerminee()).isTrue();
        initialisationsBloquees();
    }

    @Test void achatGratuitClotEgalementLaPhaseInitiale() {
        var a = achat(1); a.getLignes().getFirst().setPrixAchatUnitaire(BigDecimal.ZERO); a.getLignes().getFirst().setPrixVenteUnitaire(BigDecimal.ZERO);
        achats.enregistrerAchat(a);
        assertThat(demarrage.estOuverte()).isFalse(); assertThat(caisse.listerTous()).isEmpty();
        initialisationsBloquees();
    }

    @Test void achatPayeConserveLaCloturePendantLenregistrementImbriqueEnCaisse() {
        var a = achat(5); a.setMontantVerse(new BigDecimal("50"));
        achats.enregistrerAchat(a);
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().isInitialisationTerminee()).isTrue();
        assertThat(caisse.getSoldeActuel()).isEqualByComparingTo("-50");
        assertThat(caisse.listerTous()).hasSize(1);
        initialisationsBloquees();
    }

    @Test void premiereVenteACreditClotLeDemarrageApresStocksInitiaux() {
        inventaire.initialiser(produit.getId(), initial(produit, 10));
        ventes.effectuerVente(vente(2));
        assertThat(demarrage.estOuverte()).isFalse(); assertThat(caisse.listerTous()).isEmpty();
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(8);
        initialisationsBloquees();
    }

    @Test void venteRefuseeAnnuleLaClotureEtTousLesChangementsDeStock() {
        inventaire.initialiser(produit.getId(), initial(produit, 10));
        assertThatThrownBy(() -> ventes.effectuerVente(vente(11))).hasMessageContaining("Stock insuffisant");
        assertThat(demarrage.estOuverte()).isTrue();
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().isInitialisationTerminee()).isFalse();
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(10);
        assertThat(documentsVente.findByBoutiqueIdOrderByDateVenteDesc(boutique.getId())).isEmpty();
        caisse.initialiser(ouverture());
    }

    @Test void achatRefuseApresPriseDuVerrouNeClotPasLeDemarrage() {
        var a = achat(2); a.setMontantVerse(new BigDecimal("100"));
        assertThatThrownBy(() -> achats.enregistrerAchat(a)).hasMessageContaining("depasser");
        assertThat(demarrage.estOuverte()).isTrue();
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().isInitialisationTerminee()).isFalse();
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isZero();
        assertThat(documentsAchat.findByBoutiqueIdOrderByDateAchatDesc(boutique.getId())).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"ENTREE", "SORTIE"})
    void unMouvementDeCaisseHorsFactureClotLaPhaseInitiale(String sens) {
        if (sens.equals("ENTREE")) caisse.enregistrerEntree(BigDecimal.TEN, "Apport", "APPORT", gerant);
        else caisse.enregistrerSortie(BigDecimal.TEN, "Dépense", "DEPENSE", gerant);
        assertThat(demarrage.estOuverte()).isFalse();
        initialisationsBloquees();
    }

    @ParameterizedTest @ValueSource(strings = {"SOLDE_INITIAL", "solde_initial", " SOLDE_INITIAL "})
    void uneSourceReserveeNeContournePasLeFormulaireInitial(String source) {
        assertThatThrownBy(() -> caisse.enregistrerEntree(BigDecimal.TEN, "Faux initial", source, gerant)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> caisse.enregistrerSortie(BigDecimal.TEN, "Faux initial", source, gerant)).isInstanceOf(IllegalArgumentException.class);
        assertThat(demarrage.estOuverte()).isTrue(); assertThat(caisse.listerTous()).isEmpty();
    }

    @Test void unAppelDeCaisseSansMouvementNeClotPasLaPhaseInitiale() {
        caisse.enregistrerEntree(BigDecimal.ZERO, "Néant", "APPORT", gerant);
        caisse.enregistrerSortie(null, "Néant", "DEPENSE", gerant);
        assertThat(demarrage.estOuverte()).isTrue(); assertThat(caisse.listerTous()).isEmpty();
    }

    @Test void lesAnciensAchatsSansFlagFermentAussiLeDemarrage() {
        var ancien = new Achat(); ancien.setBoutique(boutique); ancien.setMontantTotal(BigDecimal.ZERO); ancien.setMontantVerse(BigDecimal.ZERO);
        documentsAchat.saveAndFlush(ancien);
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().isInitialisationTerminee()).isFalse();
        assertThat(demarrage.estOuverte()).isFalse(); initialisationsBloquees();
    }

    @Test void lesAnciennesVentesSansFlagFermentAussiLeDemarrage() {
        var ancienne = new Vente(); ancienne.setBoutique(boutique); ancienne.setMontantTotal(BigDecimal.ZERO); ancienne.setMontantVerse(BigDecimal.ZERO);
        documentsVente.saveAndFlush(ancienne);
        assertThat(demarrage.estOuverte()).isFalse(); initialisationsBloquees();
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"VENTE", "ACHAT", "SOLDE_INITIAL"})
    void uneAncienneCaisseOperationnelleNeRouvrePasLeDemarrage(String source) {
        var mouvement = new MouvementCaisse(); mouvement.setBoutique(boutique); mouvement.setSource(source); mouvement.setType("ENTREE"); mouvement.setMontant(BigDecimal.ZERO);
        mouvementsCaisse.saveAndFlush(mouvement);
        assertThat(demarrage.estOuverte()).isFalse(); initialisationsBloquees();
    }

    @Test void unStockPositifHeriteSansInitialNePermetPasDinitialiserUnNouveauProduit() {
        var ancien = produits.findById(produit.getId()).orElseThrow(); ancien.setQuantite(2L); ancien.setPrixDefinis(true); produits.saveAndFlush(ancien);
        assertThat(demarrage.estOuverte()).isFalse(); initialisationsBloquees();
    }

    @Test void unAncienAjustementResteVisibleMaisNePermetPasDeNouveauStockInitial() {
        var ancien = new MouvementStock(); ancien.setBoutique(boutique); ancien.setProduit(produit); ancien.setActeurId(gerant.getId()); ancien.setActeurNom("Ancien gérant");
        ancien.setType(TypeMouvementStock.AJUSTEMENT); ancien.setCreeLe(LocalDateTime.now().minusDays(1)); ancien.setQuantiteAvant(1L); ancien.setQuantiteApres(0L); ancien.setMotif("Ancien comptage");
        mouvementsStock.saveAndFlush(ancien);
        assertThat(demarrage.estOuverte()).isFalse();
        assertThat(inventaire.consulter(produit.getId(), 0).historique()).extracting(MouvementStock::getType).containsExactly(TypeMouvementStock.AJUSTEMENT);
        initialisationsBloquees();
    }

    @Test void roleForgeEtCompteDesactiveNePeuventPasTerminerLeDemarrage() {
        var caissier = compte(Role.CAISSIER); connecter(caissier, "ADMIN");
        assertThatThrownBy(demarrage::terminer).isInstanceOf(ResponseStatusException.class);
        connecter(gerant, "CAISSIER"); assertThatThrownBy(demarrage::terminer).isInstanceOf(ResponseStatusException.class);
        gerant.setActif(false); utilisateurs.saveAndFlush(gerant); connecter(gerant, "ADMIN");
        assertThatThrownBy(demarrage::terminer).isInstanceOf(ResponseStatusException.class);
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().isInitialisationTerminee()).isFalse();
    }

    @Test void cloturerUneBoutiqueNeFermePasLeDemarrageDesAutres() {
        var autre = new Boutique(); autre.setNom("Autre boutique"); autre = boutiques.saveAndFlush(autre);
        demarrage.terminer();
        assertThat(boutiques.findById(autre.getId()).orElseThrow().isInitialisationTerminee()).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"STOCK", "CAISSE"})
    void achatAttendLaSaisieInitialeDunAutreGerantEtFermeEnsuiteLaPhase(String saisie) throws Exception {
        var commence = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var reference = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
            new TransactionTemplate(transactions).executeWithoutResult(statut -> {
                em.find(Utilisateur.class, gerant.getId(), LockModeType.PESSIMISTIC_WRITE);
                demarrage.verrouillerEtExigerOuverte();
                var futur = pool.submit(() -> {
                    connecter(secondGerant, "ADMIN"); commence.countDown();
                    try { achats.enregistrerAchat(achat(5)); } finally { SecurityContextHolder.clearContext(); }
                });
                reference.set(futur);
                try { assertThat(commence.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                assertThatThrownBy(() -> futur.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                if (saisie.equals("STOCK")) inventaire.initialiser(produit.getId(), initial(produit, 10));
                else caisse.initialiser(ouverture());
            });
            reference.get().get(10, TimeUnit.SECONDS);
        }
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(saisie.equals("STOCK") ? 15 : 5);
        assertThat(caisse.listerTous()).hasSize(saisie.equals("CAISSE") ? 1 : 0);
        assertThat(demarrage.estOuverte()).isFalse(); initialisationsBloquees();
    }
}
