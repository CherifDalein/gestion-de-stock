package org.example.stock.service;

import org.example.stock.enums.NatureProduit;
import org.example.stock.enums.Role;
import org.example.stock.enums.TypeMouvementStock;
import org.example.stock.form.AjustementStockForm;
import org.example.stock.form.StockInitialForm;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/** Coût moyen pondéré avec les vrais services, transactions et verrous H2. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:stock-cout-moyen-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
class CoutMoyenServiceTests {
    @Autowired ProduitService catalogue;
    @Autowired AchatService achats;
    @Autowired VenteService ventes;
    @Autowired InventaireService inventaire;
    @Autowired CoutMoyenService coutMoyen;
    @Autowired ProduitRepository produits;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired CategorieRepository categories;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired DetailVenteRepository lignesVente;
    @Autowired MouvementStockRepository mouvements;
    @Autowired PlatformTransactionManager transactions;

    Espace espace;

    record Espace(Boutique boutique, Utilisateur gerant, Categorie categorie, Fournisseur fournisseur, Produit produit) {}

    @BeforeEach void preparer() {
        espace = creerEspace("Boutique CMP");
        connecter(espace.gerant());
    }

    @AfterEach void deconnecter() { SecurityContextHolder.clearContext(); }

    private Espace creerEspace(String nom) {
        var boutique = new Boutique();
        boutique.setNom(nom);
        boutique = boutiques.saveAndFlush(boutique);
        var gerant = new Utilisateur();
        gerant.setBoutique(boutique);
        gerant.setEmail("cmp-" + UUID.randomUUID() + "@example.test");
        gerant.setNom("Gérant CMP");
        gerant.setRole(Role.ADMIN);
        gerant = utilisateurs.saveAndFlush(gerant);
        var categorie = new Categorie();
        categorie.setNom("Catalogue");
        categorie.setBoutique(boutique);
        categorie = categories.saveAndFlush(categorie);
        var fournisseur = new Fournisseur();
        fournisseur.setNom("Fournisseur CMP");
        fournisseur.setBoutique(boutique);
        fournisseur = fournisseurs.saveAndFlush(fournisseur);
        var precedent = SecurityContextHolder.getContext();
        connecter(gerant);
        try {
            var fiche = new Produit();
            fiche.setNom("Sac de riz");
            fiche.setReference("CMP-" + UUID.randomUUID());
            fiche.setCategorie(categorie);
            return new Espace(boutique, gerant, categorie, fournisseur, catalogue.ajouterProduit(fiche));
        } finally { SecurityContextHolder.setContext(precedent); }
    }

    private static void connecter(Utilisateur compte) {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(compte.getEmail(), "",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(contexte);
    }

    private Produit actuel() { return actuel(espace); }
    private Produit actuel(Espace e) { return produits.findById(e.produit().getId()).orElseThrow(); }

    private Achat achat(int quantite, String prix) { return achat(espace, quantite, prix); }

    private static Achat achat(Espace e, int quantite, String prix) {
        var ligne = new DetailAchat();
        ligne.setProduit(reference(e));
        ligne.setQuantite(quantite);
        ligne.setPrixAchatUnitaire(new BigDecimal(prix));
        ligne.setPrixVenteUnitaire(new BigDecimal("5000"));
        var achat = new Achat();
        achat.setFournisseur(e.fournisseur());
        achat.setMontantVerse(Montants.ZERO);
        achat.getLignes().add(ligne);
        return achat;
    }

    private Achat modification(int quantite, String prix) {
        var achat = achat(quantite, prix);
        achat.setMontantVerse(null);
        return achat;
    }

    private Vente vente(int quantite) { return vente(espace, quantite); }

    private static Vente vente(Espace e, int quantite) {
        var ligne = new DetailVente();
        ligne.setProduit(reference(e));
        ligne.setQuantite(quantite);
        var vente = new Vente();
        vente.setMontantVerse(Montants.ZERO);
        vente.getLignes().add(ligne);
        return vente;
    }

    /** Une référence détachée comme celle liée par les formulaires, jamais l'entité gérée. */
    private static Produit reference(Espace e) {
        var reference = new Produit();
        reference.setId(e.produit().getId());
        return reference;
    }

    private BigDecimal coutFige(Vente vente) {
        return lignesVente.findById(vente.getLignes().getFirst().getId()).orElseThrow().getCoutUnitaire();
    }

    private StockInitialForm initial(long quantite, String cout) {
        var formulaire = new StockInitialForm();
        formulaire.setVersion(actuel().getVersion());
        formulaire.setQuantite(quantite);
        formulaire.setCoutUnitaire(new BigDecimal(cout));
        formulaire.setPrixVente(new BigDecimal("5000"));
        formulaire.setMotif("Stock présent à l’ouverture");
        return formulaire;
    }

    @Test void uneVenteDeServiceFigeSonCoutFixeSansToucherAuStock() {
        var fiche = new Produit();
        fiche.setNom("Livraison");
        fiche.setReference("SRV-" + UUID.randomUUID());
        fiche.setCategorie(espace.categorie());
        fiche.setNature(NatureProduit.SERVICE);
        fiche.setPrixVente(new BigDecimal("5000"));
        fiche.setCoutMoyen(new BigDecimal("1500"));
        Produit service = catalogue.ajouterProduit(fiche);
        achats.enregistrerAchat(achat(10, "1000"));

        var ligneService = new DetailVente();
        var referenceService = new Produit();
        referenceService.setId(service.getId());
        ligneService.setProduit(referenceService);
        ligneService.setQuantite(2);
        Vente vente = vente(3);
        vente.setMontantVerse(null);
        vente.getLignes().add(ligneService);
        vente = ventes.effectuerVente(vente);

        var lignes = vente.getLignes().stream()
                .map(l -> lignesVente.findById(l.getId()).orElseThrow()).toList();
        assertThat(lignes).filteredOn(l -> l.getProduit().getId().equals(service.getId()))
                .singleElement().satisfies(l -> assertThat(l.getCoutUnitaire()).isEqualByComparingTo("1500"));
        assertThat(lignes).filteredOn(l -> l.getProduit().getId().equals(espace.produit().getId()))
                .singleElement().satisfies(l -> assertThat(l.getCoutUnitaire()).isEqualByComparingTo("1000"));
        Produit serviceRelu = produits.findById(service.getId()).orElseThrow();
        assertThat(serviceRelu.getQuantite()).isZero();
        assertThat(serviceRelu.getCoutMoyen()).isEqualByComparingTo("1500");
        assertThat(actuel().getQuantite()).isEqualTo(7L);
    }

    @Test void deuxAchatsDonnentLaMoyennePondereeEtLePrixAchatResteLeDernier() {
        achats.enregistrerAchat(achat(10, "1000"));
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1000.00");
        achats.enregistrerAchat(achat(10, "2000"));
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1500.00");
        assertThat(actuel().getPrixAchat()).isEqualByComparingTo("2000");
        assertThat(actuel().getQuantite()).isEqualTo(20);
        assertThat(ventes.effectuerVente(vente(1)).getLignes().getFirst().getCoutUnitaire()).isEqualByComparingTo("1500.00");
    }

    @Test void uneVenteEntreDeuxAchatsGardeLeCoutDuMomentDeLaVente() {
        achats.enregistrerAchat(achat(10, "1000"));
        var vente = ventes.effectuerVente(vente(3));
        achats.enregistrerAchat(achat(10, "2000"));
        assertThat(coutFige(vente)).isEqualByComparingTo("1000.00");
        // (7 × 1 000 + 10 × 2 000) / 17 = 1 588,235…
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1588.24");
        assertThat(actuel().getPrixAchat()).isEqualByComparingTo("2000");
    }

    @Test void lesPrixADecimalesSontArrondisUneSeuleFois() {
        achats.enregistrerAchat(achat(3, "1000.01"));
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1000.01");
        achats.enregistrerAchat(achat(3, "1000.00"));
        // 6 000,03 / 6 = 1 000,005 → 1 000,01
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1000.01");
        achats.enregistrerAchat(achat(6, "1000.00"));
        // (6 × 1 000,01 + 6 × 1 000,00) / 12 = 1 000,005 → 1 000,01
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1000.01").hasScaleOf(2);
    }

    @Test void lesLignesDUnMemeAchatSontPondereesDansLOrdre() {
        var achat = achat(1, "1000");
        var seconde = new DetailAchat();
        seconde.setProduit(reference(espace));
        seconde.setQuantite(2);
        seconde.setPrixAchatUnitaire(new BigDecimal("2000"));
        seconde.setPrixVenteUnitaire(new BigDecimal("5000"));
        achat.getLignes().add(seconde);
        achats.enregistrerAchat(achat);
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1666.67");
        assertThat(actuel().getQuantite()).isEqualTo(3);
    }

    @Test void leStockInitialFixeLeCoutPuisLesAchatsLePonderent() {
        inventaire.initialiser(espace.produit().getId(), initial(10, "1000"));
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1000.00");
        achats.enregistrerAchat(achat(10, "2000"));
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1500.00");
    }

    @Test void unStockEpuiseRepartAuPrixDuNouvelAchat() {
        inventaire.initialiser(espace.produit().getId(), initial(10, "1000"));
        achats.enregistrerAchat(achat(10, "2000"));
        var vente = ventes.effectuerVente(vente(20));
        assertThat(actuel().getQuantite()).isZero();
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1500.00");
        achats.enregistrerAchat(achat(5, "3000"));
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("3000.00");
        assertThat(coutFige(vente)).isEqualByComparingTo("1500.00");
    }

    @Test void modifierUnAncienAchatRecalculeLeCoutSansToucherAuxVentesPassees() {
        var premier = achats.enregistrerAchat(achat(10, "1000"));
        var vente = ventes.effectuerVente(vente(4));
        achats.enregistrerAchat(achat(10, "2000"));
        // (6 × 1 000 + 10 × 2 000) / 16
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1625.00");

        achats.modifierAchat(premier.getId(), modification(10, "1200"));
        // Rejeu : 10 × 1 200, vente de 4, puis (6 × 1 200 + 10 × 2 000) / 16.
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1700.00");
        assertThat(actuel().getPrixAchat()).isEqualByComparingTo("1200");
        assertThat(actuel().getQuantite()).isEqualTo(16);
        assertThat(coutFige(vente)).isEqualByComparingTo("1000.00");

        achats.modifierAchat(premier.getId(), modification(12, "1000"));
        // (8 × 1 000 + 10 × 2 000) / 18 = 1 555,55…
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1555.56");
        assertThat(actuel().getQuantite()).isEqualTo(18);
        assertThat(coutFige(vente)).isEqualByComparingTo("1000.00");
    }

    @Test void uneModificationIdentiqueRetrouveExactementLeCoutIncremental() {
        inventaire.initialiser(espace.produit().getId(), initial(3, "1000.01"));
        var premier = achats.enregistrerAchat(achat(7, "999.99"));
        ventes.effectuerVente(vente(5));
        achats.enregistrerAchat(achat(4, "1234.57"));
        var incremental = actuel().getCoutMoyen();
        achats.modifierAchat(premier.getId(), modification(7, "999.99"));
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo(incremental);
    }

    @Test void retirerUnProduitDUnAchatRecalculeAussiSonCout() {
        var autre = new Produit();
        autre.setNom("Huile");
        autre.setReference("CMP-" + UUID.randomUUID());
        autre.setCategorie(espace.categorie());
        var huile = catalogue.ajouterProduit(autre);
        achats.enregistrerAchat(achat(5, "800"));
        var premier = achat(10, "1000");
        var ligneHuile = new DetailAchat();
        var refHuile = new Produit(); refHuile.setId(huile.getId());
        ligneHuile.setProduit(refHuile); ligneHuile.setQuantite(2);
        ligneHuile.setPrixAchatUnitaire(new BigDecimal("300")); ligneHuile.setPrixVenteUnitaire(new BigDecimal("400"));
        premier.getLignes().add(ligneHuile);
        var document = achats.enregistrerAchat(premier);
        // (5 × 800 + 10 × 1 000) / 15
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("933.33");
        achats.modifierAchat(document.getId(), modification(5, "2000"));
        // (5 × 800 + 5 × 2 000) / 10
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1400.00");
        var huileApres = produits.findById(huile.getId()).orElseThrow();
        assertThat(huileApres.getQuantite()).isZero();
        assertThat(huileApres.getCoutMoyen()).isEqualByComparingTo("300.00");
    }

    @Test void leStockAnterieurAuJournalEstValoriseAuPremierCoutConnuLorsDUnRecalcul() {
        // Produit historique : du stock sans aucune opération enregistrée, coût repris de la migration.
        var historique = actuel();
        historique.setQuantite(10L);
        historique.setPrixDefinis(true);
        historique.setCoutMoyen(new BigDecimal("500.00"));
        produits.saveAndFlush(historique);
        var achat = achats.enregistrerAchat(achat(10, "1000"));
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("750.00");
        achats.modifierAchat(achat.getId(), modification(10, "1200"));
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1200.00");
        assertThat(actuel().getQuantite()).isEqualTo(20);
    }

    @Test void unAjustementDInventaireNeChangePasLeCout() {
        achats.enregistrerAchat(achat(10, "1000"));
        var formulaire = new AjustementStockForm();
        formulaire.setVersion(actuel().getVersion());
        formulaire.setQuantite(4L);
        formulaire.setMotif("Comptage");
        assertThatThrownBy(() -> inventaire.ajuster(espace.produit().getId(), formulaire))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1000.00");
        assertThat(actuel().getQuantite()).isEqualTo(10);
    }

    @Test void unAjustementHistoriqueNeChangeQueLePoidsDuStockLorsDUnRecalcul() {
        inventaire.initialiser(espace.produit().getId(), initial(10, "500"));
        var premier = achats.enregistrerAchat(achat(10, "1000"));
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("750.00");
        // Ajustement enregistré par une ancienne version, daté en UTC comme tout le journal : 20 → 5.
        // Sur un serveur hors UTC, le comparer sans conversion le placerait avant l'achat.
        var produit = actuel();
        var mouvement = new MouvementStock();
        mouvement.setBoutique(espace.boutique()); mouvement.setProduit(produit);
        mouvement.setActeurId(espace.gerant().getId()); mouvement.setActeurNom("Gérant CMP");
        mouvement.setType(TypeMouvementStock.AJUSTEMENT); mouvement.setCreeLe(LocalDateTime.now(ZoneOffset.UTC));
        mouvement.setQuantiteAvant(20L); mouvement.setQuantiteApres(5L); mouvement.setMotif("Casse");
        mouvements.saveAndFlush(mouvement);
        produit.setQuantite(5L);
        produits.saveAndFlush(produit);
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("750.00");
        achats.enregistrerAchat(achat(10, "2000"));
        // (5 × 750 + 10 × 2 000) / 15
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1583.33");
        achats.modifierAchat(premier.getId(), modification(10, "1000"));
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1583.33");
        assertThat(actuel().getQuantite()).isEqualTo(15);
    }

    @Test void leCoutDUnServiceNEstPasPonderePourUneEntree() {
        var service = actuel();
        service.setNature(NatureProduit.SERVICE);
        service.setCoutMoyen(new BigDecimal("250.00"));
        produits.saveAndFlush(service);
        new TransactionTemplate(transactions).executeWithoutResult(statut -> {
            var produit = produits.findById(espace.produit().getId()).orElseThrow();
            coutMoyen.enregistrerEntree(produit, 10, new BigDecimal("1000"));
            assertThat(produit.getCoutMoyen()).isEqualByComparingTo("250.00");
            assertThat(coutMoyen.coutUnitaireVente(produit)).isEqualByComparingTo("250.00");
            statut.setRollbackOnly();
        });
    }

    @Test void lesCoutsDesBoutiquesRestentIsoles() {
        var autre = creerEspace("Autre boutique CMP");
        var premierA = achats.enregistrerAchat(achat(10, "1000"));
        connecter(autre.gerant());
        achats.enregistrerAchat(achat(autre, 10, "3000"));
        var venteB = ventes.effectuerVente(vente(autre, 2));
        achats.enregistrerAchat(achat(autre, 2, "5000"));
        connecter(espace.gerant());
        achats.enregistrerAchat(achat(10, "2000"));
        achats.modifierAchat(premierA.getId(), modification(10, "1600"));
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1800.00");
        // (8 × 3 000 + 2 × 5 000) / 10 : ni les achats ni la correction de la première boutique ne comptent.
        assertThat(actuel(autre).getCoutMoyen()).isEqualByComparingTo("3400.00");
        assertThat(coutFige(venteB)).isEqualByComparingTo("3000.00");
        // La seconde boutique ne peut pas corriger un achat de la première.
        connecter(autre.gerant());
        assertThatThrownBy(() -> achats.modifierAchat(premierA.getId(), modification(10, "1")))
                .hasMessageContaining("introuvable");
        connecter(espace.gerant());
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1800.00");
    }

    @Test void uneVenteQuiAttendUnAchatFigeLeNouveauCout() throws Exception {
        achats.enregistrerAchat(achat(10, "1000"));
        var vente = concurrents(() -> achats.enregistrerAchat(achat(10, "2000")), () -> ventes.effectuerVente(vente(5)));
        assertThat(coutFige((Vente) vente)).isEqualByComparingTo("1500.00");
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1500.00");
        assertThat(actuel().getQuantite()).isEqualTo(15);
    }

    @Test void unAchatQuiAttendUneVentePondereLeStockRestant() throws Exception {
        achats.enregistrerAchat(achat(10, "1000"));
        var vente = new Vente[1];
        concurrents(() -> vente[0] = ventes.effectuerVente(vente(5)), () -> achats.enregistrerAchat(achat(10, "2000")));
        assertThat(coutFige(vente[0])).isEqualByComparingTo("1000.00");
        // (5 × 1 000 + 10 × 2 000) / 15
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1666.67");
        assertThat(actuel().getQuantite()).isEqualTo(15);
    }

    @Test void uneCorrectionDAchatQuiAttendUneVenteLaPrendEnCompte() throws Exception {
        var premier = achats.enregistrerAchat(achat(10, "1000"));
        achats.enregistrerAchat(achat(10, "2000"));
        var vente = new Vente[1];
        concurrents(() -> vente[0] = ventes.effectuerVente(vente(5)),
                () -> { achats.modifierAchat(premier.getId(), modification(10, "1200")); return null; });
        assertThat(coutFige(vente[0])).isEqualByComparingTo("1500.00");
        // Rejeu : (10 × 1 200 + 10 × 2 000) / 20, puis la vente concurrente de 5.
        assertThat(actuel().getCoutMoyen()).isEqualByComparingTo("1600.00");
        assertThat(actuel().getQuantite()).isEqualTo(15);
    }

    /**
     * La première opération garde ses verrous jusqu'à autorisation ; la seconde doit attendre le
     * produit, puis relire son stock et son coût après le commit. Renvoie le résultat de la seconde.
     */
    private Object concurrents(Callable<?> premiere, Callable<?> seconde) throws Exception {
        var verrouPris = new CountDownLatch(1);
        var liberer = new CountDownLatch(1);
        var gerant = espace.gerant();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> a = pool.submit(() -> comme(gerant, () -> new TransactionTemplate(transactions).execute(statut -> {
                try { premiere.call(); } catch (Exception e) { throw new IllegalStateException(e); }
                verrouPris.countDown();
                attendre(liberer);
                return null;
            })));
            try {
                assertThat(verrouPris.await(10, TimeUnit.SECONDS)).isTrue();
                Future<?> b = pool.submit(() -> comme(gerant, seconde));
                assertThatThrownBy(() -> b.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                liberer.countDown();
                a.get(10, TimeUnit.SECONDS);
                return b.get(10, TimeUnit.SECONDS);
            } finally { liberer.countDown(); }
        }
    }

    private static <T> T comme(Utilisateur compte, Callable<T> action) {
        connecter(compte);
        try { return action.call(); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException(e); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private static void attendre(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Synchronisation expirée"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
