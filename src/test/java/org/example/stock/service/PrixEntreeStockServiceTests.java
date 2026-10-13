package org.example.stock.service;

import org.example.stock.enums.Role;
import org.example.stock.form.AjustementStockForm;
import org.example.stock.form.StockInitialForm;
import org.example.stock.model.*;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:stock-prix-entree-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
class PrixEntreeStockServiceTests {
    @Autowired ProduitService catalogue;
    @Autowired AchatService achats;
    @Autowired VenteService ventes;
    @Autowired InventaireService inventaire;
    @Autowired CaisseService caisse;
    @Autowired ProduitRepository produits;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired CategorieRepository categories;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired AchatRepository documentsAchat;
    @Autowired VenteRepository documentsVente;
    @Autowired MouvementStockRepository mouvements;
    @Autowired PlatformTransactionManager transactions;
    Boutique boutique;
    Categorie categorie;
    Fournisseur fournisseur;
    Produit produit;

    @BeforeEach void preparer() {
        boutique = new Boutique();
        boutique.setNom("Boutique tarifs");
        boutique = boutiques.saveAndFlush(boutique);
        var compte = new Utilisateur();
        compte.setBoutique(boutique);
        compte.setEmail("tarifs-" + UUID.randomUUID() + "@example.test");
        compte.setNom("Gérante tarifs");
        compte.setRole(Role.ADMIN);
        utilisateurs.saveAndFlush(compte);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                compte.getEmail(), "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        categorie = new Categorie();
        categorie.setNom("Catalogue");
        categorie.setBoutique(boutique);
        categorie = categories.saveAndFlush(categorie);
        fournisseur = new Fournisseur();
        fournisseur.setNom("Fournisseur tarifs");
        fournisseur.setBoutique(boutique);
        fournisseur = fournisseurs.saveAndFlush(fournisseur);
        produit = catalogue.ajouterProduit(fiche());
    }

    @AfterEach void deconnecter() { SecurityContextHolder.clearContext(); }

    private Produit fiche() {
        var p = new Produit();
        p.setNom("Article à tarifer");
        p.setReference("TARIF-" + UUID.randomUUID());
        p.setCategorie(categorie);
        return p;
    }

    private Produit actuel() { return produits.findById(produit.getId()).orElseThrow(); }

    private DetailAchat ligne(int quantite, String cout, String prixVente) {
        var ligne = new DetailAchat();
        ligne.setProduit(produit);
        ligne.setQuantite(quantite);
        ligne.setPrixAchatUnitaire(cout == null ? null : new BigDecimal(cout));
        ligne.setPrixVenteUnitaire(prixVente == null ? null : new BigDecimal(prixVente));
        return ligne;
    }

    private Achat achat(DetailAchat... lignes) {
        var achat = new Achat();
        achat.setFournisseur(fournisseur);
        achat.setMontantVerse(Montants.ZERO);
        achat.getLignes().addAll(List.of(lignes));
        return achat;
    }

    private Vente vente(int quantite) {
        var vente = new Vente();
        vente.setMontantVerse(Montants.ZERO);
        var ligne = new DetailVente();
        ligne.setProduit(produit);
        ligne.setQuantite(quantite);
        vente.getLignes().add(ligne);
        return vente;
    }

    private StockInitialForm initial(String prixVente) {
        var formulaire = new StockInitialForm();
        formulaire.setVersion(actuel().getVersion());
        formulaire.setQuantite(10L);
        formulaire.setCoutUnitaire(new BigDecimal("12.50"));
        formulaire.setPrixVente(prixVente == null ? null : new BigDecimal(prixVente));
        formulaire.setMotif("Marchandises présentes à l’ouverture");
        return formulaire;
    }

    private void sansEntree() {
        assertThat(actuel().getQuantite()).isZero();
        assertThat(actuel().getPrixAchat()).isZero();
        assertThat(actuel().getPrixVente()).isZero();
        assertThat(actuel().isPrixDefinis()).isFalse();
        assertThat(documentsAchat.findByBoutiqueIdOrderByDateAchatDesc(boutique.getId())).isEmpty();
        assertThat(documentsVente.findByBoutiqueIdOrderByDateVenteDesc(boutique.getId())).isEmpty();
        assertThat(mouvements.existsByProduitId(produit.getId())).isFalse();
        assertThat(caisse.getSoldeActuel()).isZero();
    }

    @Test void uneFicheEstCreeeSansStockNiPrixMemeSiLeServiceRecoitDesValeurs() {
        var saisie = fiche();
        saisie.setQuantite(400L);
        saisie.setPrixAchat(new BigDecimal("900"));
        saisie.setPrixVente(new BigDecimal("1500"));
        saisie.setPrixDefinis(true);
        produit = catalogue.ajouterProduit(saisie);
        sansEntree();
    }

    @Test void lesAnciensProduitsConserventLeursTarifsSansEntreeReinventee() {
        var ancien = fiche();
        ancien.setBoutique(boutique);
        ancien.setPrixAchat(new BigDecimal("10"));
        ancien.setPrixVente(new BigDecimal("30"));
        ancien.setQuantite(20L);
        ancien = produits.saveAndFlush(ancien);
        assertThat(ancien.isPrixDefinis()).isTrue();
        assertThat(ancien.getPrixVente()).isEqualByComparingTo("30");
        assertThat(ancien.getQuantite()).isEqualTo(20);
    }

    @Test void laFicheSansTarifsPeutEtreRenommeeSansDefinirUnPrixGratuit() {
        var saisie = actuel();
        saisie.setNom("Article renommé");
        saisie.setPrixDefinis(true);
        saisie.setPrixVente(null);
        catalogue.modifierProduit(produit.getId(), saisie);
        assertThat(actuel().getNom()).isEqualTo("Article renommé");
        sansEntree();
    }

    @Test void laFicheSansTarifsRefuseDeDefinirUnPrixEnDehorsDuneEntree() {
        var saisie = actuel();
        saisie.setPrixVente(BigDecimal.TEN);
        saisie.setNom("Nom à refuser");
        assertThatThrownBy(() -> catalogue.modifierProduit(produit.getId(), saisie))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("achat");
        assertThat(actuel().getNom()).isEqualTo("Article à tarifer");
        sansEntree();
    }

    @Test void laFicheDejaTarifeePermetDeChangerLeTarifSansModifierLeCoutNiLeStock() {
        achats.enregistrerAchat(achat(ligne(10, "12.50", "20")));
        var saisie = actuel();
        saisie.setPrixAchat(new BigDecimal("999"));
        saisie.setQuantite(999L);
        saisie.setPrixVente(new BigDecimal("25"));
        saisie.setPrixDefinis(false);
        catalogue.modifierProduit(produit.getId(), saisie);
        assertThat(actuel().getPrixAchat()).isEqualByComparingTo("12.50");
        assertThat(actuel().getPrixVente()).isEqualByComparingTo("25");
        assertThat(actuel().getQuantite()).isEqualTo(10);
        assertThat(actuel().isPrixDefinis()).isTrue();
    }

    @Test void uneFicheTarifeeRefuseUnPrixOmisMemeSiUneReceptionVientDeLeDefinir() {
        // Le contrôleur peut avoir lu le formulaire avant que cet achat fixe les tarifs.
        var saisie = actuel();
        saisie.setPrixVente(null);
        achats.enregistrerAchat(achat(ligne(10, "12.50", "20")));
        saisie.setVersion(actuel().getVersion());
        assertThatThrownBy(() -> catalogue.modifierProduit(produit.getId(), saisie))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("obligatoire");
        assertThat(actuel().getPrixVente()).isEqualByComparingTo("20");
    }

    @Test void laPremiereReceptionDefinitLesDeuxPrixEtLeTotalResteCalculeSurLeCout() {
        var document = achats.enregistrerAchat(achat(ligne(4, "12.50", "20")));
        assertThat(actuel().getPrixAchat()).isEqualByComparingTo("12.50");
        assertThat(actuel().getPrixVente()).isEqualByComparingTo("20");
        assertThat(actuel().getQuantite()).isEqualTo(4);
        assertThat(actuel().isPrixDefinis()).isTrue();
        assertThat(document.getMontantTotal()).isEqualByComparingTo("50");
        assertThat(document.getLignes().getFirst().getPrixVenteUnitaire()).isEqualByComparingTo("20");
        assertThat(caisse.getSoldeActuel()).isZero();
    }

    @Test void achatSansPrixDeVenteNeCreeNiReceptionNiDocument() {
        assertThatThrownBy(() -> achats.enregistrerAchat(achat(ligne(4, "12.50", null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("prix de vente unitaire");
        sansEntree();
    }

    @Test void achatSansCoutNeCreeNiReceptionNiDocument() {
        assertThatThrownBy(() -> achats.enregistrerAchat(achat(ligne(4, null, "20"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("prix d'achat unitaire");
        sansEntree();
    }

    @ParameterizedTest
    @CsvSource({"ACHAT,-1", "ACHAT,0.001", "ACHAT,1000000000000000", "VENTE,-1", "VENTE,0.001", "VENTE,1000000000000000"})
    void lesPrixInvalidesDuneReceptionSontRefusesAvantToutChangement(String champ, String montant) {
        var ligne = ligne(4, champ.equals("ACHAT") ? montant : "12.50", champ.equals("VENTE") ? montant : "20");
        assertThatThrownBy(() -> achats.enregistrerAchat(achat(ligne))).isInstanceOf(IllegalArgumentException.class);
        sansEntree();
    }

    @Test void unMemeProduitNePeutPasRecevoirDeuxTarifsContradictoiresDansLeMemeAchat() {
        assertThatThrownBy(() -> achats.enregistrerAchat(achat(ligne(4, "12", "20"), ligne(2, "13", "21"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("même prix de vente");
        sansEntree();
    }

    @Test void lesLignesDupliqueesAvecUnTarifEquivalentRestentValides() {
        var document = achats.enregistrerAchat(achat(ligne(4, "12", "20.0"), ligne(2, "13", "20.00")));
        assertThat(actuel().getQuantite()).isEqualTo(6);
        assertThat(actuel().getPrixVente()).isEqualByComparingTo("20");
        assertThat(document.getMontantTotal()).isEqualByComparingTo("74");
    }

    @Test void unPrixZeroExplicitementDefiniResteDistinctDunPrixAbsent() {
        achats.enregistrerAchat(achat(ligne(2, "0", "0")));
        var vente = ventes.effectuerVente(vente(1));
        assertThat(actuel().isPrixDefinis()).isTrue();
        assertThat(actuel().getQuantite()).isEqualTo(1);
        assertThat(vente.getMontantTotal()).isZero();
        assertThat(vente.getLignes().getFirst().getPrixUnitaire()).isZero();
    }

    @Test void uneVenteSansTarifsEstRefuseeMemeSiUnStockIncoherentExisteDeja() {
        var incoherent = actuel();
        incoherent.setQuantite(5L);
        produits.saveAndFlush(incoherent);
        assertThatThrownBy(() -> ventes.effectuerVente(vente(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("doivent être définis");
        assertThat(actuel().getQuantite()).isEqualTo(5);
        assertThat(documentsVente.findByBoutiqueIdOrderByDateVenteDesc(boutique.getId())).isEmpty();
        assertThat(caisse.getSoldeActuel()).isZero();
    }

    @Test void leStockInitialDefinitEtArchiveLesDeuxPrixSansCreerUnAchatOuUneSortieDeCaisse() {
        inventaire.initialiser(produit.getId(), initial("20"));
        assertThat(actuel().getPrixAchat()).isEqualByComparingTo("12.50");
        assertThat(actuel().getPrixVente()).isEqualByComparingTo("20");
        assertThat(actuel().isPrixDefinis()).isTrue();
        var trace = inventaire.consulter(produit.getId(), 0).historique().getFirst();
        assertThat(trace.getCoutUnitaire()).isEqualByComparingTo("12.50");
        assertThat(trace.getPrixVente()).isEqualByComparingTo("20");
        assertThat(documentsAchat.findByBoutiqueIdOrderByDateAchatDesc(boutique.getId())).isEmpty();
        assertThat(caisse.getSoldeActuel()).isZero();
        var vente = ventes.effectuerVente(vente(1));
        assertThat(vente.getLignes().getFirst().getPrixUnitaire()).isEqualByComparingTo("20");
    }

    @Test void leStockInitialSansPrixDeVenteEstRefuse() {
        assertThatThrownBy(() -> inventaire.initialiser(produit.getId(), initial(null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("prix de vente");
        sansEntree();
    }

    @ParameterizedTest @ValueSource(strings = {"-1", "0.001", "1000000000000000"})
    void leStockInitialAvecTarifInvalideNeChangeAucunChamp(String montant) {
        assertThatThrownBy(() -> inventaire.initialiser(produit.getId(), initial(montant)))
                .isInstanceOf(IllegalArgumentException.class);
        sansEntree();
    }

    @Test void uneCorrectionDeQuantiteNePeutPasContournerLaDefinitionDesPrix() {
        var formulaire = new AjustementStockForm();
        formulaire.setVersion(actuel().getVersion());
        formulaire.setQuantite(5L);
        formulaire.setMotif("Comptage");
        assertThatThrownBy(() -> inventaire.ajuster(produit.getId(), formulaire))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("avec les prix");
        sansEntree();
    }

    @Test void lesNouveauxTarifsNeReecriventPasLesPrixDesAnciensDocuments() {
        var premier = achats.enregistrerAchat(achat(ligne(10, "12", "20")));
        var vente = ventes.effectuerVente(vente(2));
        achats.enregistrerAchat(achat(ligne(5, "14", "25")));
        var saisie = actuel();
        saisie.setPrixVente(new BigDecimal("30"));
        catalogue.modifierProduit(produit.getId(), saisie);
        new TransactionTemplate(transactions).executeWithoutResult(statut -> {
            var ancienAchat = documentsAchat.findById(premier.getId()).orElseThrow();
            var ancienneVente = documentsVente.findById(vente.getId()).orElseThrow();
            assertThat(ancienAchat.getLignes().getFirst().getPrixAchatUnitaire()).isEqualByComparingTo("12");
            assertThat(ancienAchat.getLignes().getFirst().getPrixVenteUnitaire()).isEqualByComparingTo("20");
            assertThat(ancienAchat.getMontantTotal()).isEqualByComparingTo("120");
            assertThat(ancienneVente.getLignes().getFirst().getPrixUnitaire()).isEqualByComparingTo("20");
            assertThat(ancienneVente.getMontantTotal()).isEqualByComparingTo("40");
        });
        assertThat(actuel().getPrixVente()).isEqualByComparingTo("30");
    }

    @Test void modifierUnAchatActualiseLesPrixSansChangerLesVentesPassees() {
        var premier = achats.enregistrerAchat(achat(ligne(10, "12", "20")));
        var vente = ventes.effectuerVente(vente(2));
        achats.enregistrerAchat(achat(ligne(10, "14", "25")));
        var modifications = achat(ligne(10, "13", "28"));
        modifications.setMontantVerse(null);
        achats.modifierAchat(premier.getId(), modifications);
        assertThat(actuel().getPrixAchat()).isEqualByComparingTo("13");
        assertThat(actuel().getPrixVente()).isEqualByComparingTo("28");
        assertThat(actuel().getQuantite()).isEqualTo(18);
        new TransactionTemplate(transactions).executeWithoutResult(statut -> {
            assertThat(documentsVente.findById(vente.getId()).orElseThrow().getLignes().getFirst().getPrixUnitaire())
                    .isEqualByComparingTo("20");
            assertThat(documentsAchat.findById(premier.getId()).orElseThrow().getLignes().getFirst().getPrixVenteUnitaire())
                    .isEqualByComparingTo("28");
        });
    }

    @Test void modifierUnAchatSansTarifDeVenteEstRefuseEtConserveLaReception() {
        var original = achats.enregistrerAchat(achat(ligne(10, "12", "20")));
        var modifications = achat(ligne(5, "14", null));
        modifications.setMontantVerse(null);
        assertThatThrownBy(() -> achats.modifierAchat(original.getId(), modifications))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("prix de vente unitaire");
        assertThat(actuel().getPrixVente()).isEqualByComparingTo("20");
        assertThat(actuel().getQuantite()).isEqualTo(10);
    }

    @Test void lesAchatsEtStocksInitiauxAnterieursPeuventConserverUnTarifNonArchive() {
        new TransactionTemplate(transactions).executeWithoutResult(statut -> {
            var ancien = achat(ligne(3, "12", null));
            ancien.setBoutique(boutique);
            ancien.setDateAchat(LocalDateTime.now().minusMonths(1));
            ancien.setMontantTotal(new BigDecimal("36"));
            ancien.getLignes().forEach(ligne -> ligne.setAchat(ancien));
            documentsAchat.saveAndFlush(ancien);
            assertThat(ancien.getLignes().getFirst().getPrixVenteUnitaire()).isNull();
        });
    }
}
