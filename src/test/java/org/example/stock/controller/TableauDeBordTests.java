package org.example.stock.controller;

import org.example.stock.support.ComptesDeTest;
import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.enums.NatureProduit;
import org.example.stock.enums.Role;
import org.example.stock.enums.TypeOperationTresorerie;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.example.stock.service.TableauDeBordService.EvolutionAnnuelle;
import org.example.stock.service.TableauDeBordService.Indicateurs;
import org.example.stock.service.TableauDeBordService.MoisActivite;
import org.example.stock.service.TableauDeBordService.ProduitASurveiller;
import org.example.stock.service.TableauDeBordService.ProduitsASurveiller;
import org.example.stock.service.TableauDeBordService.ValeurStock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tableau de bord sur un jeu de données fixe, avec deux boutiques : chaque chiffre est comparé au
 * calcul fait à la main. Les dates sont relatives au jour courant du serveur, comme la caisse.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:tableau-de-bord;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class TableauDeBordTests {
    private static final String GERANT_A = "tdb-gerant-a@example.test";
    private static final String CAISSIER_A = "tdb-caissier-a@example.test";
    private static final String GERANT_B = "tdb-gerant-b@example.test";
    private static final String SECRET_B = "PRODUIT_CONFIDENTIEL_B";
    private static final int ANNEE_HISTORIQUE = 2024;
    private static final int ANNEE_VIDE = 2020;

    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired CategorieRepository categories;
    @Autowired ProduitRepository produits;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired AchatRepository achats;
    @Autowired VenteRepository ventes;
    @Autowired MouvementCaisseRepository mouvements;
    @Autowired OperationTresorerieRepository operationsTresorerie;
    @Autowired OperationCreationRepository operationsCreation;

    LocalDate aujourdHui;
    Boutique boutiqueA, boutiqueB;
    Utilisateur gerantA, gerantB;
    Categorie rayonA, rayonB;
    Fournisseur fournisseurA;
    Produit riz, huile, savon, sucre, sel, livraison;

    @BeforeEach
    void preparer() {
        operationsCreation.deleteAll(); mouvements.deleteAll(); operationsTresorerie.deleteAll();
        ventes.deleteAll(); achats.deleteAll(); produits.deleteAll(); fournisseurs.deleteAll();
        categories.deleteAll(); utilisateurs.deleteAll(); boutiques.deleteAll();
        aujourdHui = LocalDate.now();

        boutiqueA = boutique("Boutique tableau A"); boutiqueB = boutique("Boutique tableau B");
        gerantA = utilisateur(GERANT_A, Role.ADMIN, boutiqueA);
        utilisateur(CAISSIER_A, Role.CAISSIER, boutiqueA);
        gerantB = utilisateur(GERANT_B, Role.ADMIN, boutiqueB);
        rayonA = categorie(boutiqueA); rayonB = categorie(boutiqueB);
        fournisseurA = new Fournisseur(); fournisseurA.setNom("Fournisseur A"); fournisseurA.setBoutique(boutiqueA);
        fournisseurA = fournisseurs.saveAndFlush(fournisseurA);

        // Valeur du stock A : 10 × 200,50 + 3 × 1 000 + 0 × 50 + 7 × 10,25 + 5 × 4 = 5 096,75 GNF, 5 articles.
        riz = produit(boutiqueA, rayonA, "Riz", NatureProduit.STOCK, 10, 5, "200.50");      // disponible
        huile = produit(boutiqueA, rayonA, "Huile", NatureProduit.STOCK, 3, 5, "1000");     // faible
        savon = produit(boutiqueA, rayonA, "Savon", NatureProduit.STOCK, 0, 5, "50");       // rupture
        sucre = produit(boutiqueA, rayonA, "Sucre", NatureProduit.STOCK, 7, 0, "10.25");    // disponible (seuil 0)
        sel = produit(boutiqueA, rayonA, "Sel", NatureProduit.STOCK, 5, 5, "4");            // faible (au seuil)
        // Un service n'a pas de stock : sa quantité et son coût fixe ne comptent jamais.
        livraison = produit(boutiqueA, rayonA, "Livraison", NatureProduit.SERVICE, 5, 5, "100");
        produit(boutiqueB, rayonB, SECRET_B, NatureProduit.STOCK, 0, 5, "999999");

        LocalDateTime minuit = aujourdHui.atStartOfDay();
        // Jour : CA 1 500 ; brut (300 − 200) × 2 + (400 − 100) × 1 + (500 − 0) × 1 = 1 000 ; 1 ligne sans coût.
        vente(boutiqueA, minuit, "1000", "400",
                ligne(riz, 2, "300", "200"), ligne(livraison, 1, "400", "100"));
        vente(boutiqueA, minuit.withHour(12), "500", "0", ligne(huile, 1, "500", null));
        depense(boutiqueA, gerantA, minuit.withHour(10), "150");                       // dépenses du jour : 150
        transfert(boutiqueA, gerantA, minuit.withHour(11), "1000");                    // transfert : jamais une dépense
        achat(boutiqueA, minuit.withHour(9), "5000", "2000");                          // achat : jamais une dépense

        // Mois (hors jour) : CA 2 000 ; brut (500 − 200,50) × 4 = 1 198 ; dépense 300,25.
        LocalDate autreJour = aujourdHui.getDayOfMonth() > 1 ? aujourdHui.minusDays(1) : aujourdHui.plusDays(1);
        vente(boutiqueA, autreJour.atTime(9, 0), "2000", "2000", ligne(riz, 4, "500", "200.50"));
        depense(boutiqueA, gerantA, autreJour.atTime(15, 30), "300.25");

        // Juste avant le mois courant : exclu du jour et du mois.
        LocalDateTime finMoisPrecedent = aujourdHui.withDayOfMonth(1).atStartOfDay().minusNanos(1_000);
        vente(boutiqueA, finMoisPrecedent, "7777", "0", ligne(riz, 1, "7777", "0"));
        depense(boutiqueA, gerantA, finMoisPrecedent, "999");

        // Autre boutique, aujourd'hui : jamais visible depuis A.
        vente(boutiqueB, minuit.withHour(13), "88888", "88888", ligne(produits.findAll().stream()
                .filter(p -> SECRET_B.equals(p.getNom())).findFirst().orElseThrow(), 1, "88888", "1"));
        depense(boutiqueB, gerantB, minuit.withHour(13), "4444");

        // Année historique pour l'évolution mensuelle.
        vente(boutiqueA, LocalDateTime.of(ANNEE_HISTORIQUE, 1, 31, 23, 59, 59), "100", "100", ligne(riz, 1, "100", "60"));
        vente(boutiqueA, LocalDateTime.of(ANNEE_HISTORIQUE, 2, 1, 0, 0), "200", "200", ligne(riz, 2, "100", "30"));
        depense(boutiqueA, gerantA, LocalDateTime.of(ANNEE_HISTORIQUE, 3, 15, 8, 0), "500");
        vente(boutiqueA, LocalDateTime.of(ANNEE_HISTORIQUE, 12, 31, 23, 59, 59), "300", "0", ligne(huile, 3, "100", null));
        vente(boutiqueA, LocalDateTime.of(ANNEE_HISTORIQUE + 1, 1, 1, 0, 0), "999", "0", ligne(riz, 1, "999", "1"));
        vente(boutiqueA, LocalDateTime.of(ANNEE_HISTORIQUE - 1, 12, 31, 23, 59, 59), "999", "0", ligne(riz, 1, "999", "1"));
        achat(boutiqueA, LocalDateTime.of(ANNEE_HISTORIQUE, 4, 10, 10, 0), "10000", "10000");
        transfert(boutiqueA, gerantA, LocalDateTime.of(ANNEE_HISTORIQUE, 5, 2, 10, 0), "50");
        vente(boutiqueB, LocalDateTime.of(ANNEE_HISTORIQUE, 6, 10, 10, 0), "5555", "0", ligne(produits.findAll().stream()
                .filter(p -> SECRET_B.equals(p.getNom())).findFirst().orElseThrow(), 1, "5555", "5"));
        depense(boutiqueB, gerantB, LocalDateTime.of(ANNEE_HISTORIQUE, 6, 11, 10, 0), "66");
    }

    @Test
    void lesIndicateursDuJourEtDuMoisCorrespondentAuCalculALaMain() throws Exception {
        MvcResult resultat = mvc.perform(gerantA(get("/"))).andExpect(status().isOk()).andReturn();
        montant(resultat, "chiffreAffairesJour", "1500");
        montant(resultat, "ventesDuJour", "400");

        Indicateurs jour = attribut(resultat, "indicateursJour");
        assertThat(jour.chiffreAffaires()).isEqualByComparingTo("1500");
        assertThat(jour.beneficeBrut()).isEqualByComparingTo("1000");
        assertThat(jour.depenses()).isEqualByComparingTo("150");
        assertThat(jour.beneficeNet()).isEqualByComparingTo("850");
        assertThat(jour.lignesSansCout()).isEqualTo(1);

        Indicateurs mois = attribut(resultat, "indicateursMois");
        assertThat(mois.chiffreAffaires()).isEqualByComparingTo("3500");
        assertThat(mois.beneficeBrut()).isEqualByComparingTo("2198.00");
        assertThat(mois.depenses()).isEqualByComparingTo("450.25");
        assertThat(mois.beneficeNet()).isEqualByComparingTo("1747.75");
        assertThat(mois.lignesSansCout()).isEqualTo(1);

        String page = resultat.getResponse().getContentAsString();
        assertThat(page).contains("Activité du jour", "Activité du mois", "Bénéfice brut du jour", "Bénéfice net du mois",
                        "1 ligne de vente sans coût connu, comptée à 0", "data-lignes-sans-cout=\"1\"",
                        "1 500.00", "1 747.75", "450.25")
                .doesNotContain(SECRET_B, "88 888.00", "4 444.00");
    }

    @Test
    void laValeurDuStockExclutLesServices() throws Exception {
        MvcResult resultat = mvc.perform(gerantA(get("/"))).andExpect(status().isOk()).andReturn();
        ValeurStock valeur = attribut(resultat, "valeurStock");
        assertThat(valeur.valeur()).isEqualByComparingTo("5096.75");
        assertThat(valeur.nombreProduits()).isEqualTo(5);
        assertThat(resultat.getResponse().getContentAsString()).contains("Valeur du stock", "5 096.75", "5 articles en stock");
    }

    @Test
    void lesProduitsASurveillerSuiventLeSeuilRupturesDAbord() throws Exception {
        MvcResult resultat = mvc.perform(gerantA(get("/"))).andExpect(status().isOk()).andReturn();
        ProduitsASurveiller surveillance = attribut(resultat, "produitsASurveiller");
        assertThat(surveillance.produits()).extracting(ProduitASurveiller::nom).containsExactly("Savon", "Huile", "Sel");
        assertThat(surveillance.produits()).extracting(ProduitASurveiller::etat).containsExactly("rupture", "faible", "faible");
        assertThat(surveillance.total()).isEqualTo(3);
        assertThat(resultat.getResponse().getContentAsString()).contains("href=\"/produits?stock=a-surveiller\"");

        // Le seuil change l'état : l'huile n'est plus à surveiller avec un seuil de 2.
        huile.setSeuilAlerte(2L); produits.saveAndFlush(huile);
        surveillance = attribut(mvc.perform(gerantA(get("/"))).andReturn(), "produitsASurveiller");
        assertThat(surveillance.produits()).extracting(ProduitASurveiller::nom).containsExactly("Savon", "Sel");
    }

    @Test
    void laListeDesProduitsASurveillerEstLimiteeADix() throws Exception {
        for (int i = 0; i < 12; i++) produit(boutiqueA, rayonA, "Rupture " + (char) ('a' + i), NatureProduit.STOCK, 0, 5, "1");
        MvcResult resultat = mvc.perform(gerantA(get("/"))).andExpect(status().isOk()).andReturn();
        ProduitsASurveiller surveillance = attribut(resultat, "produitsASurveiller");
        assertThat(surveillance.produits()).hasSize(10).allSatisfy(p -> assertThat(p.etat()).isEqualTo("rupture"));
        assertThat(surveillance.total()).isEqualTo(15);
        assertThat(surveillance.autres()).isEqualTo(5);
        assertThat(resultat.getResponse().getContentAsString()).contains("Voir les 15 produits à surveiller");
    }

    @Test
    void lEvolutionMensuelleDUneAnneeChoisie() throws Exception {
        MvcResult resultat = mvc.perform(gerantA(get("/").param("annee", String.valueOf(ANNEE_HISTORIQUE))))
                .andExpect(status().isOk()).andExpect(model().attribute("anneeCorrigee", false)).andReturn();
        EvolutionAnnuelle evolution = attribut(resultat, "evolution");
        assertThat(evolution.annee()).isEqualTo(ANNEE_HISTORIQUE);
        assertThat(evolution.vide()).isFalse();
        assertThat(evolution.mois()).hasSize(12);
        mois(evolution, 1, "100", "40", "0", "40");
        mois(evolution, 2, "200", "140", "0", "140");
        mois(evolution, 3, "0", "0", "500", "-500");
        for (int m = 4; m <= 11; m++) mois(evolution, m, "0", "0", "0", "0");
        mois(evolution, 12, "300", "300", "0", "300");
        assertThat(evolution.totalVentes()).isEqualByComparingTo("600");
        assertThat(evolution.totalBeneficeBrut()).isEqualByComparingTo("480");
        assertThat(evolution.totalDepenses()).isEqualByComparingTo("500");
        assertThat(evolution.totalResultat()).isEqualByComparingTo("-20");
        assertThat(evolution.anneePrecedente()).isEqualTo(ANNEE_HISTORIQUE - 1);
        assertThat(evolution.anneeSuivante()).isEqualTo(ANNEE_HISTORIQUE + 1);
        // Graphique : ventes et résultats positifs en haut (max 500 avec la perte), perte de mars en bas.
        assertThat(evolution.partPositive()).isEqualTo(38);
        assertThat(evolution.partNegative()).isEqualTo(62);
        assertThat(evolution.mois().get(11).hauteurVentes()).isEqualTo(100);
        assertThat(evolution.mois().get(2).hauteurPerte()).isEqualTo(100);
        assertThat(evolution.mois().get(0).hauteurResultat()).isEqualTo(14);

        String page = resultat.getResponse().getContentAsString();
        assertThat(page).contains("Évolution mensuelle " + ANNEE_HISTORIQUE, "class=\"tdb-chart\"",
                "href=\"/?annee=" + (ANNEE_HISTORIQUE - 1) + "#evolution\"", "href=\"/?annee=" + (ANNEE_HISTORIQUE + 1) + "#evolution\"",
                "height: 100%", "-500.00", "-20.00").doesNotContain(SECRET_B, "5 555.00");
    }

    @Test
    void uneAnneeSansDonneesAfficheDouzeMoisAZero() throws Exception {
        MvcResult resultat = mvc.perform(gerantA(get("/").param("annee", String.valueOf(ANNEE_VIDE))))
                .andExpect(status().isOk()).andReturn();
        EvolutionAnnuelle evolution = attribut(resultat, "evolution");
        assertThat(evolution.annee()).isEqualTo(ANNEE_VIDE);
        assertThat(evolution.vide()).isTrue();
        assertThat(evolution.mois()).hasSize(12).allSatisfy(m -> {
            assertThat(m.ventes()).isZero(); assertThat(m.resultat()).isZero();
        });
        assertThat(resultat.getResponse().getContentAsString())
                .contains("Aucune vente ni dépense enregistrée en " + ANNEE_VIDE).doesNotContain("class=\"tdb-chart\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1999", "3000", "2024.5", "-2024", "0", "99999999999999999999", "2024,2025", " "})
    void uneAnneeInvalideEstRameneeALAnneeCourante(String annee) throws Exception {
        MvcResult resultat = mvc.perform(gerantA(get("/").param("annee", annee))).andExpect(status().isOk()).andReturn();
        EvolutionAnnuelle evolution = attribut(resultat, "evolution");
        assertThat(evolution.annee()).isEqualTo(aujourdHui.getYear());
        assertThat(evolution.anneeSuivante()).isNull();
        assertThat((Boolean) resultat.getModelAndView().getModel().get("anneeCorrigee")).isEqualTo(!annee.isBlank());
    }

    @Test
    void plusieursAnneesEnvoyeesSontRefusees() throws Exception {
        MvcResult resultat = mvc.perform(gerantA(get("/").param("annee", "2024", "2025"))).andExpect(status().isOk())
                .andExpect(model().attribute("anneeCorrigee", true)).andReturn();
        assertThat(((EvolutionAnnuelle) attribut(resultat, "evolution")).annee()).isEqualTo(aujourdHui.getYear());
    }

    @Test
    void sansParametreLAnneeCouranteEstAffichee() throws Exception {
        MvcResult resultat = mvc.perform(gerantA(get("/"))).andExpect(status().isOk())
                .andExpect(model().attribute("anneeCorrigee", false)).andReturn();
        EvolutionAnnuelle evolution = attribut(resultat, "evolution");
        assertThat(evolution.annee()).isEqualTo(aujourdHui.getYear());
        // Le mois courant de l'évolution reprend exactement les chiffres du mois.
        MoisActivite courant = evolution.mois().get(aujourdHui.getMonthValue() - 1);
        Indicateurs mois = attribut(resultat, "indicateursMois");
        assertThat(courant.ventes()).isEqualByComparingTo(mois.chiffreAffaires());
        assertThat(courant.beneficeBrut()).isEqualByComparingTo(mois.beneficeBrut());
        assertThat(courant.depenses()).isEqualByComparingTo(mois.depenses());
        assertThat(courant.resultat()).isEqualByComparingTo(mois.beneficeNet());
    }

    @Test
    void leCaissierNeRecoitQueLesChiffresDuJourSansMarge() throws Exception {
        MvcResult resultat = mvc.perform(get("/").param("annee", String.valueOf(ANNEE_HISTORIQUE))
                        .with(user(CAISSIER_A).roles("CAISSIER")))
                .andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("indicateursJour", "indicateursMois", "valeurStock", "evolution",
                        "anneeCorrigee", "libelleMois", "soldeCaisse", "soldeOuverture", "entreesJour", "sortiesJour",
                        "bilanJour", "caisseDuJour", "soldeCloture"))
                .andReturn();
        montant(resultat, "chiffreAffairesJour", "1500");
        montant(resultat, "ventesDuJour", "400");
        ProduitsASurveiller surveillance = attribut(resultat, "produitsASurveiller");
        assertThat(surveillance.produits()).extracting(ProduitASurveiller::nom).containsExactly("Savon", "Huile", "Sel");
        // Aucune valeur du modèle n'expose un bénéfice, une dépense ou un coût.
        for (Map.Entry<String, Object> entree : resultat.getModelAndView().getModel().entrySet()) {
            assertThat(entree.getValue()).as(entree.getKey())
                    .isNotInstanceOfAny(Indicateurs.class, ValeurStock.class, EvolutionAnnuelle.class);
        }

        String page = resultat.getResponse().getContentAsString();
        assertThat(page).contains("Activité du jour", "Chiffre d’affaires du jour", "1 500.00",
                        "Encaissé sur les ventes du jour", "Produits à surveiller", "Savon")
                .doesNotContain("Bénéfice", "Dépenses", "Valeur du stock", "Évolution mensuelle", "Activité du mois",
                        "sans coût connu", "1 000.00", "850.00", "5 096.75", "tdb-chart\"", "annee=");
    }

    @Test
    void chaqueBoutiqueNeVoitQueSesPropresChiffres() throws Exception {
        MvcResult resultat = mvc.perform(get("/").param("annee", String.valueOf(ANNEE_HISTORIQUE))
                .with(user(GERANT_B).roles("ADMIN"))).andExpect(status().isOk()).andReturn();
        montant(resultat, "chiffreAffairesJour", "88888");
        Indicateurs jour = attribut(resultat, "indicateursJour");
        assertThat(jour.beneficeBrut()).isEqualByComparingTo("88887");
        assertThat(jour.depenses()).isEqualByComparingTo("4444");
        assertThat(jour.beneficeNet()).isEqualByComparingTo("84443");
        assertThat(jour.lignesSansCout()).isZero();
        ValeurStock valeur = attribut(resultat, "valeurStock");
        assertThat(valeur.valeur()).isEqualByComparingTo("0");
        assertThat(valeur.nombreProduits()).isEqualTo(1);
        ProduitsASurveiller surveillance = attribut(resultat, "produitsASurveiller");
        assertThat(surveillance.produits()).extracting(ProduitASurveiller::nom).containsExactly(SECRET_B);
        EvolutionAnnuelle evolution = attribut(resultat, "evolution");
        mois(evolution, 6, "5555", "5550", "66", "5484");
        assertThat(evolution.totalVentes()).isEqualByComparingTo("5555");
        assertThat(resultat.getResponse().getContentAsString()).doesNotContain("Savon", "Huile", "1 747.75");

        // Et inversement, A ne voit rien de B.
        MvcResult a = mvc.perform(gerantA(get("/"))).andReturn();
        assertThat(((ProduitsASurveiller) attribut(a, "produitsASurveiller")).produits())
                .extracting(ProduitASurveiller::nom).doesNotContain(SECRET_B);
        assertThat(a.getResponse().getContentAsString()).doesNotContain(SECRET_B);
    }

    @Test
    void unVisiteurNeVoitPasLeTableauDeBord() throws Exception {
        mvc.perform(get("/").param("annee", "2024")).andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("chiffreAffairesJour", "produitsASurveiller", "evolution"));
    }

    // --- Outils

    private MockHttpServletRequestBuilder gerantA(MockHttpServletRequestBuilder requete) {
        return requete.with(user(GERANT_A).roles("ADMIN"));
    }

    @SuppressWarnings("unchecked")
    private static <T> T attribut(MvcResult resultat, String nom) {
        Object valeur = resultat.getModelAndView().getModel().get(nom);
        assertThat(valeur).as(nom).isNotNull();
        return (T) valeur;
    }

    private static void montant(MvcResult resultat, String nom, String attendu) {
        assertThat((BigDecimal) attribut(resultat, nom)).as(nom).isEqualByComparingTo(attendu);
    }

    private static void mois(EvolutionAnnuelle evolution, int numero, String ventes, String brut, String depenses, String resultat) {
        MoisActivite mois = evolution.mois().get(numero - 1);
        assertThat(mois.numero()).isEqualTo(numero);
        assertThat(mois.ventes()).as("ventes " + numero).isEqualByComparingTo(ventes);
        assertThat(mois.beneficeBrut()).as("brut " + numero).isEqualByComparingTo(brut);
        assertThat(mois.depenses()).as("dépenses " + numero).isEqualByComparingTo(depenses);
        assertThat(mois.resultat()).as("résultat " + numero).isEqualByComparingTo(resultat);
    }

    private Boutique boutique(String nom) {
        Boutique boutique = new Boutique(); boutique.setNom(nom); return boutiques.saveAndFlush(boutique);
    }

    private Utilisateur utilisateur(String email, Role role, Boutique boutique) {
        Utilisateur utilisateur = new Utilisateur(); utilisateur.setNom("Compte " + email); utilisateur.setEmail(email);
        utilisateur.setRole(role); utilisateur.setBoutique(boutique);
        utilisateur.setMotDePasse(passwordEncoder.encode("Mot de passe de test 2026 !"));
        return utilisateurs.saveAndFlush(utilisateur);
    }

    private Categorie categorie(Boutique boutique) {
        Categorie categorie = new Categorie(); categorie.setNom("Rayon"); categorie.setBoutique(boutique);
        return categories.saveAndFlush(categorie);
    }

    private Produit produit(Boutique boutique, Categorie categorie, String nom, NatureProduit nature, long quantite,
                            long seuil, String coutMoyen) {
        Produit produit = new Produit(); produit.setNom(nom); produit.setReference("REF-" + UUID.randomUUID());
        produit.setBoutique(boutique); produit.setCategorie(categorie); produit.setNature(nature);
        produit.setQuantite(quantite); produit.setSeuilAlerte(seuil); produit.setCoutMoyen(new BigDecimal(coutMoyen));
        produit.setPrixAchat(new BigDecimal(coutMoyen)); produit.setPrixVente(new BigDecimal("1000"));
        return produits.saveAndFlush(produit);
    }

    private static DetailVente ligne(Produit produit, int quantite, String prix, String cout) {
        DetailVente ligne = new DetailVente(); ligne.setProduit(produit); ligne.setQuantite(quantite);
        ligne.setPrixUnitaire(new BigDecimal(prix)); ligne.setCoutUnitaire(cout == null ? null : new BigDecimal(cout));
        return ligne;
    }

    private void vente(Boutique boutique, LocalDateTime date, String total, String verse, DetailVente... lignes) {
        Vente vente = new Vente(); vente.setBoutique(boutique); vente.setDateVente(date);
        vente.setMontantTotal(new BigDecimal(total)); vente.setMontantVerse(new BigDecimal(verse));
        for (DetailVente ligne : lignes) { ligne.setVente(vente); vente.getLignes().add(ligne); }
        ventes.saveAndFlush(vente);
    }

    private void achat(Boutique boutique, LocalDateTime date, String total, String verse) {
        Achat achat = new Achat(); achat.setBoutique(boutique); achat.setFournisseur(fournisseurA); achat.setDateAchat(date);
        achat.setMontantTotal(new BigDecimal(total)); achat.setMontantVerse(new BigDecimal(verse));
        DetailAchat ligne = new DetailAchat(); ligne.setAchat(achat); ligne.setProduit(riz); ligne.setQuantite(1);
        ligne.setPrixAchatUnitaire(new BigDecimal(total)); achat.getLignes().add(ligne);
        achat = achats.saveAndFlush(achat);
        MouvementCaisse sortie = mouvement(boutique, gerantA, date, "-" + verse, "ACHAT", "SORTIE", null);
        sortie.setAchat(achat); mouvements.saveAndFlush(sortie);
    }

    private void depense(Boutique boutique, Utilisateur acteur, LocalDateTime date, String montant) {
        OperationTresorerie operation = operation(boutique, acteur, date, montant, TypeOperationTresorerie.DEPENSE, null);
        mouvements.saveAndFlush(mouvement(boutique, acteur, date, "-" + montant, "DEPENSE", "SORTIE", operation));
    }

    private void transfert(Boutique boutique, Utilisateur acteur, LocalDateTime date, String montant) {
        OperationTresorerie operation = operation(boutique, acteur, date, montant, TypeOperationTresorerie.TRANSFERT,
                TypeCompteTresorerie.BANQUE);
        mouvements.saveAndFlush(mouvement(boutique, acteur, date, "-" + montant, "TRANSFERT", "SORTIE", operation));
        MouvementCaisse entree = mouvement(boutique, acteur, date, montant, "TRANSFERT", "ENTREE", operation);
        entree.setCompte(ComptesDeTest.de(boutique, TypeCompteTresorerie.BANQUE)); mouvements.saveAndFlush(entree);
    }

    private OperationTresorerie operation(Boutique boutique, Utilisateur acteur, LocalDateTime date, String montant,
                                          TypeOperationTresorerie type, TypeCompteTresorerie destination) {
        OperationTresorerie operation = new OperationTresorerie(); operation.setBoutique(boutique); operation.setUtilisateur(acteur);
        operation.setJeton(UUID.randomUUID().toString()); operation.setType(type); operation.setCompteSource(ComptesDeTest.de(boutique, TypeCompteTresorerie.ESPECES));
        operation.setCompteDestination(destination == null ? null : ComptesDeTest.de(boutique, destination)); operation.setMontant(new BigDecimal(montant));
        operation.setMotif(type == TypeOperationTresorerie.DEPENSE ? "Loyer" : "Versement banque"); operation.setDateOperation(date);
        return operationsTresorerie.saveAndFlush(operation);
    }

    private static MouvementCaisse mouvement(Boutique boutique, Utilisateur acteur, LocalDateTime date, String montant,
                                             String source, String type, OperationTresorerie operation) {
        MouvementCaisse mouvement = new MouvementCaisse(); mouvement.setCompte(org.example.stock.support.ComptesDeTest.de(boutique, org.example.stock.enums.TypeCompteTresorerie.ESPECES)); mouvement.setBoutique(boutique); mouvement.setUtilisateur(acteur);
        mouvement.setDateMouvement(date); mouvement.setMontant(new BigDecimal(montant)); mouvement.setType(type);
        mouvement.setSource(source); mouvement.setMotif(source); mouvement.setOperationTresorerie(operation);
        return mouvement;
    }
}
