package org.example.stock.controller;

import org.apache.poi.ss.usermodel.*;
import org.example.stock.enums.NatureProduit;
import org.example.stock.enums.Role;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.stock.export.ExportTestOutils.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lot 4, tâche B : exports Excel et PDF des listes du catalogue et des répertoires : produits (gérant avec coûts, caissier sans),
 * catégories (gérant), clients (gérant et caissier) et fournisseurs (gérant). Deux boutiques, un gérant et un caissier dans la boutique A.
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:export-listes-catalogue;DB_CLOSE_DELAY=-1", "stock.export.limite-lignes=6"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class ListesCatalogueExportTests {
    private static final String GERANT_A = "catalogue-gerant-a@example.test";
    private static final String CAISSIER_A = "catalogue-caissier-a@example.test";
    private static final String GERANT_B = "catalogue-gerant-b@example.test";
    private static final String FORMULE = "=HYPERLINK(\"http://evil.example\",\"clic\")";

    @Autowired MockMvc mvc;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired CategorieRepository categories;
    @Autowired ProduitRepository produits;
    @Autowired ClientRepository clients;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired VenteRepository ventes;

    Boutique boutiqueA, boutiqueB;
    Utilisateur awa, bakary;
    Categorie alimentation, boissons, services, vide, rayonB;
    Fournisseur sory;

    @BeforeEach
    void preparer() {
        ventes.deleteAll(); clients.deleteAll(); produits.deleteAll(); fournisseurs.deleteAll(); categories.deleteAll(); utilisateurs.deleteAll(); boutiques.deleteAll();
        boutiqueA = boutique("Boutique Étoile A", "12 rue de l'Œuvre, Conakry"); boutiqueB = boutique("Boutique Autre B", null);
        awa = compte(GERANT_A, "Awa Gérante", Role.ADMIN, boutiqueA); bakary = compte(CAISSIER_A, "Bakary Caissier", Role.CAISSIER, boutiqueA);
        compte(GERANT_B, "Zoé Autre Boutique", Role.ADMIN, boutiqueB);
        alimentation = categorie(boutiqueA, "Alimentation"); boissons = categorie(boutiqueA, "Boissons"); services = categorie(boutiqueA, "Services");
        vide = categorie(boutiqueA, "Vide"); rayonB = categorie(boutiqueB, "Rayon B");
        sory = fournisseur(boutiqueA, "Sory Import", null, null, null, "-200000");
        fournisseur(boutiqueA, "Agro Plus", "+224 622 00 00 01", "agro@example.test", "Kaloum", "60000");
        fournisseur(boutiqueB, "Fournisseur Secret B", null, null, null, "0");

        //                       réf        nom            catégorie     nature   unité  qté seuil vente     coût      dernier achat  fournisseur
        produit(boutiqueA, "REF-002", "Riz 50kg", alimentation, NatureProduit.STOCK, "sac", 40, 10, "450000", "380000", "400000", sory, true);
        produit(boutiqueA, "REF-010", "Sucre", alimentation, NatureProduit.STOCK, "kg", 3, 5, "12000", "9000", "9500", null, true);
        produit(boutiqueA, "REF-001", "Huile", boissons, NatureProduit.STOCK, "litre", 0, 5, "30000", "20000", "21000", sory, true);
        produit(boutiqueA, "SRV-001", "Livraison", services, NatureProduit.SERVICE, null, 0, 5, "15000", "5000", "5000", null, true);
        produit(boutiqueA, "REF-020", "Café neuf", alimentation, NatureProduit.STOCK, "boîte", 0, 5, "0", "0", "0", null, false);
        produit(boutiqueB, "REF-777", "Riz B", rayonB, NatureProduit.STOCK, "sac", 1, 1, "9999999", "8888888", "7777777", null, true);

        // Clients : Awa doit 30 000 de solde de départ + 20 000 de facture ; Boubacar a une avance de 40 000 ; Élève sans téléphone.
        Client awaDiallo = client(boutiqueA, "Awa Diallo", "+224 620 11 22 33", "awa@example.test", "Matoto", "30000");
        client(boutiqueA, "Boubacar Sow", null, null, null, "-40000");
        client(boutiqueA, "Élève Ça’ñ", null, "eleve@example.test", "Dixinn", "0");
        client(boutiqueA, FORMULE, "620 99 88 77", null, null, "0");
        client(boutiqueB, "Client Secret B", null, null, null, "0");
        Vente v = new Vente(); v.setBoutique(boutiqueA); v.setVendeur(awa); v.setClient(awaDiallo); v.setNumero("FV-000001"); v.setDateVente(LocalDateTime.of(2026, 10, 5, 10, 0));
        v.setMontantTotal(new BigDecimal("120000")); v.setMontantVerse(new BigDecimal("100000"));
        ventes.saveAndFlush(v);
    }

    // ===== Produits =====

    @Test
    void lExcelDesProduitsDuGerantPorteCoutsMargesEtValeurDuStock() throws Exception {
        MockHttpServletResponse reponse = mvc.perform(gerant(get("/produits/export/excel"))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(reponse.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"produits_" + LocalDate.now() + ".xlsx\"");
        try (Workbook classeur = classeur(reponse)) {
            Sheet f = classeur.getSheet("Produits");
            assertThat(f.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Boutique Étoile A");
            assertThat(f.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Tous les produits");
            assertThat(entetes(f, "Référence")).containsExactly("Référence", "Produit", "Catégorie", "Nature", "Unité", "Quantité", "Seuil d’alerte", "État du stock",
                    "Prix de vente", "Fournisseur", "Coût moyen", "Dernier prix d’achat", "Marge unitaire", "Taux de marge", "Valeur du stock");
            List<Row> lignes = lignes(f, "Référence");
            // Par référence, les nombres comparés comme des nombres : REF-001, REF-002, REF-010, REF-020, SRV-001.
            assertThat(premieresCellules(lignes)).containsExactly("REF-001", "REF-002", "REF-010", "REF-020", "SRV-001");
            Row huile = lignes.get(0), riz = lignes.get(1), sucre = lignes.get(2), cafe = lignes.get(3), livraison = lignes.get(4);
            assertThat(chaine(riz, 1)).isEqualTo("Riz 50kg");
            assertThat(chaine(riz, 2)).isEqualTo("Alimentation");
            assertThat(chaine(riz, 3)).isEqualTo("Article");
            assertThat(chaine(riz, 4)).isEqualTo("sac");
            assertThat(List.of(nombre(riz, 5), nombre(riz, 6))).containsExactly(40d, 10d);
            assertThat(chaine(riz, 7)).isEqualTo("Disponible");
            assertThat(nombre(riz, 8)).isEqualTo(450_000d);
            assertThat(chaine(riz, 9)).isEqualTo("Sory Import");
            // Marge 450 000 − 380 000 = 70 000 ; taux 15,6 % ; valeur 40 × 380 000 = 15 200 000.
            assertThat(List.of(nombre(riz, 10), nombre(riz, 11), nombre(riz, 12), nombre(riz, 13), nombre(riz, 14))).containsExactly(380_000d, 400_000d, 70_000d, 0.156, 15_200_000d);
            // Un pourcentage Excel est une fraction affichée en % : 0,156 s'affiche « 15,6% ».
            assertThat(riz.getCell(13).getCellStyle().getDataFormatString()).isEqualTo("0.0%");
            assertThat(chaine(sucre, 7)).isEqualTo("Stock faible");
            assertThat(List.of(nombre(sucre, 12), nombre(sucre, 13), nombre(sucre, 14))).containsExactly(3_000d, 0.25, 27_000d);
            assertThat(chaine(huile, 7)).isEqualTo("En rupture");
            assertThat(List.of(nombre(huile, 12), nombre(huile, 13), nombre(huile, 14))).containsExactly(10_000d, 0.333, 0d);
            // Un service : sans quantité ni valeur de stock, avec son coût fixe.
            assertThat(chaine(livraison, 3)).isEqualTo("Service");
            assertThat(chaine(livraison, 7)).isEqualTo("Service");
            assertThat(livraison.getCell(5)).satisfiesAnyOf(c -> assertThat(c).isNull(), c -> assertThat(c.getCellType()).isEqualTo(CellType.BLANK));
            assertThat(nombre(livraison, 10)).isEqualTo(5_000d);
            assertThat(nombre(livraison, 12)).isEqualTo(10_000d);
            assertThat(nombre(livraison, 14)).isNull();
            // Une fiche dont les prix ne sont pas définis n'a ni prix, ni coût, ni marge.
            assertThat(nombre(cafe, 8)).isNull();
            assertThat(nombre(cafe, 10)).isNull();
            assertThat(nombre(cafe, 12)).isNull();
            // Totaux : 5 produits et valeur du stock 15 200 000 + 27 000 + 0.
            Row totaux = totaux(f, "Référence");
            assertThat(chaine(totaux, 0)).isEqualTo("5 produits");
            assertThat(nombre(totaux, 14)).isEqualTo(15_227_000d);
            assertThat(texteDeLaFeuille(f)).doesNotContain("REF-777", "Riz B", "9999999", "Boutique Autre B");
        }
    }

    @Test
    void lePdfDesProduitsDuGerantEstEnPaysageAvecLaBoutiqueEtLesCouts() throws Exception {
        MockHttpServletResponse reponse = mvc.perform(gerant(get("/produits/export/pdf"))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(reponse.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"produits_" + LocalDate.now() + ".pdf\"");
        String texte = pdf(reponse);
        assertThat(texte).contains("Boutique Étoile A", "12 rue de l'Œuvre, Conakry", "Liste des produits", "REF-001", "Riz 50kg", "Alimentation", "Coût",
                "moyen", "Marge", "Valeur du stock", "380 000", "15 200 000", "15 227 000", "15,6 %", "Stock faible", "En rupture", "5 produits", "par Awa Gérante");
        assertThat(texte).doesNotContain("RFC-COMPTA", "REF-777", "Riz B", "9 999 999");
        assertThat(estPaysage(reponse.getContentAsByteArray())).isTrue();
    }

    @Test
    void leCaissierNeRecoitAucunCoutAucuneMargeNiValeurDeStock() throws Exception {
        try (Workbook classeur = classeur(mvc.perform(caissier(get("/produits/export/excel"))).andExpect(status().isOk()).andReturn().getResponse())) {
            Sheet f = classeur.getSheetAt(0);
            assertThat(entetes(f, "Référence")).containsExactly("Référence", "Produit", "Catégorie", "Nature", "Unité", "Quantité", "Seuil d’alerte", "État du stock", "Prix de vente");
            List<Row> lignes = lignes(f, "Référence");
            assertThat(lignes).hasSize(5);
            assertThat(lignes).allSatisfy(r -> assertThat(r.getLastCellNum()).isLessThanOrEqualTo((short) 9));
            assertThat(nombre(lignes.get(1), 8)).isEqualTo(450_000d);
            String tout = texteDeLaFeuille(f);
            assertThat(tout).doesNotContain("Coût", "coût", "Marge", "marge", "Valeur du stock", "Dernier prix", "Fournisseur", "Sory", "Taux");
            for (Row ligne : f) for (Cell c : ligne) {
                if (c.getCellType() == CellType.NUMERIC && !DateUtil.isCellDateFormatted(c)) {
                    // Ni coût moyen, ni dernier prix d'achat, ni marge, ni valeur du stock d'aucun produit.
                    assertThat(c.getNumericCellValue()).isNotIn(380_000d, 400_000d, 70_000d, 15_200_000d, 9_000d, 9_500d, 27_000d, 20_000d, 21_000d, 10_000d, 5_000d, 15_227_000d);
                }
            }
            Row totaux = totaux(f, "Référence");
            assertThat(chaine(totaux, 0)).isEqualTo("5 produits");
            assertThat(totaux.getLastCellNum()).isLessThanOrEqualTo((short) 9);
        }
        MockHttpServletResponse reponse = mvc.perform(caissier(get("/produits/export/pdf"))).andExpect(status().isOk()).andReturn().getResponse();
        String pdf = pdf(reponse);
        assertThat(pdf).contains("Boutique Étoile A", "Riz 50kg", "450 000", "par Bakary Caissier", "5 produits")
                .doesNotContain("Coût", "Marge", "Valeur du stock", "Dernier prix", "Fournisseur", "Sory", "380 000", "400 000", "15 200 000", "70 000", "REF-777");
        assertThat(estPaysage(reponse.getContentAsByteArray())).isTrue();
    }

    @Test
    void leCaissierNePeutPasChercherParFournisseurNiElargirSesDroitsParUnParametre() throws Exception {
        // Le gérant retrouve les produits de « Sory » (fournisseur affiché pour lui) ; le caissier n'a pas cette information.
        try (Workbook classeur = classeur(mvc.perform(gerant(get("/produits/export/excel").param("q", "sory"))).andReturn().getResponse())) {
            assertThat(premieresCellules(lignes(classeur.getSheetAt(0), "Référence"))).containsExactly("REF-001", "REF-002");
        }
        try (Workbook classeur = classeur(mvc.perform(caissier(get("/produits/export/excel").param("q", "sory"))).andReturn().getResponse())) {
            assertThat(lignes(classeur.getSheetAt(0), "Référence")).isEmpty();
        }
        // Aucun paramètre ne donne les colonnes de coût à un caissier.
        try (Workbook classeur = classeur(mvc.perform(caissier(get("/produits/export/excel").param("couts", "oui").param("role", "ADMIN").param("gerant", "true"))).andReturn().getResponse())) {
            assertThat(entetes(classeur.getSheetAt(0), "Référence")).hasSize(9);
        }
    }

    @Test
    void lesFiltresDeStockDeCategorieEtDeRechercheDonnentLesLignesAttendues() throws Exception {
        record Cas(String[] parametres, List<String> attendu) { }
        List<Cas> cas = List.of(
                new Cas(new String[]{"stock", "en-stock"}, List.of("REF-002", "REF-010")),
                new Cas(new String[]{"stock", "disponible"}, List.of("REF-002")),
                new Cas(new String[]{"stock", "faible"}, List.of("REF-010")),
                new Cas(new String[]{"stock", "rupture"}, List.of("REF-001", "REF-020")),
                new Cas(new String[]{"stock", "a-surveiller"}, List.of("REF-001", "REF-010", "REF-020")),
                new Cas(new String[]{"stock", "service"}, List.of("SRV-001")),
                new Cas(new String[]{"categorie", "" + alimentation.getId()}, List.of("REF-002", "REF-010", "REF-020")),
                new Cas(new String[]{"categorie", "" + alimentation.getId(), "stock", "rupture"}, List.of("REF-020")),
                new Cas(new String[]{"q", "SUCRE"}, List.of("REF-010")),
                new Cas(new String[]{"q", "cafe"}, List.of("REF-020")),
                new Cas(new String[]{"q", "alimentation riz"}, List.of("REF-002")),
                new Cas(new String[]{"q", "ref-00"}, List.of("REF-001", "REF-002")),
                new Cas(new String[]{"q", "introuvable"}, List.of()));
        for (Cas c : cas) {
            for (String role : new String[]{"gerant", "caissier"}) {
                MockHttpServletRequestBuilder excel = avecParametres(get("/produits/export/excel"), c.parametres());
                try (Workbook classeur = classeur(mvc.perform(role.equals("gerant") ? gerant(excel) : caissier(excel)).andReturn().getResponse())) {
                    assertThat(premieresCellules(lignes(classeur.getSheetAt(0), "Référence"))).as(role + " " + String.join("=", c.parametres())).containsExactlyElementsOf(c.attendu());
                }
            }
            String pdf = pdf(mvc.perform(gerant(avecParametres(get("/produits/export/pdf"), c.parametres()))).andReturn().getResponse());
            for (String reference : List.of("REF-001", "REF-002", "REF-010", "REF-020", "SRV-001")) {
                if (c.attendu().contains(reference)) assertThat(pdf).contains(reference); else assertThat(pdf).doesNotContain(reference);
            }
        }
        try (Workbook classeur = classeur(mvc.perform(gerant(get("/produits/export/excel").param("stock", "rupture").param("q", "caf"))).andReturn().getResponse())) {
            assertThat(classeur.getSheetAt(0).getRow(2).getCell(0).getStringCellValue()).isEqualTo("Stock : en rupture · Recherche : « caf »");
        }
    }

    @Test
    void lesParametresInvalidesDesProduitsSontRefuses() throws Exception {
        mvc.perform(gerant(get("/produits/export/excel").param("stock", "plein"))).andExpect(status().isBadRequest());
        mvc.perform(gerant(get("/produits/export/excel").param("categorie", "abc"))).andExpect(status().isBadRequest());
        mvc.perform(gerant(get("/produits/export/excel").param("categorie", "" + rayonB.getId()))).andExpect(status().isBadRequest());
        mvc.perform(gerant(get("/produits/export/excel").param("categorie", "999999"))).andExpect(status().isBadRequest());
        mvc.perform(gerant(get("/produits/export/pdf").param("q", "x".repeat(101)))).andExpect(status().isBadRequest());
        mvc.perform(gerant(get("/produits/export/pdf").param("stock", "faible", "rupture"))).andExpect(status().isBadRequest());
    }

    @Test
    void uneAutreBoutiqueNeVoitQueSesProduitsEtLAccesExigeUneConnexion() throws Exception {
        try (Workbook classeur = classeur(mvc.perform(avec(get("/produits/export/excel"), GERANT_B, "ADMIN")).andReturn().getResponse())) {
            assertThat(premieresCellules(lignes(classeur.getSheetAt(0), "Référence"))).containsExactly("REF-777");
            assertThat(texteDeLaFeuille(classeur.getSheetAt(0))).doesNotContain("Étoile", "Riz 50kg", "REF-002");
        }
        for (String url : List.of("/produits/export/excel", "/produits/export/pdf")) {
            assertThat(mvc.perform(get(url)).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl()).endsWith("/login");
        }
    }

    @Test
    void auDelaDeLaLimiteLesProduitsRevientALaListeAvecLeFiltreDeStock() throws Exception {
        produit(boutiqueA, "REF-100", "Surplus", alimentation, NatureProduit.STOCK, "kg", 5, 1, "1000", "500", "500", null, true);
        produit(boutiqueA, "REF-101", "Surplus 2", alimentation, NatureProduit.STOCK, "kg", 5, 1, "1000", "500", "500", null, true);
        MvcResult resultat = mvc.perform(gerant(get("/produits/export/excel").param("stock", "rupture"))).andExpect(status().is3xxRedirection()).andReturn();
        assertThat(resultat.getResponse().getRedirectedUrl()).isEqualTo("/produits?stock=rupture");
        assertThat(resultat.getFlashMap().get("error").toString()).contains("dépasse 6 lignes");
        mvc.perform(caissier(get("/produits/export/pdf"))).andExpect(status().is3xxRedirection());
    }

    @Test
    void laListeDesProduitsPorteLesBoutonsPourLesDeuxRolesEtLeurChampsDeFiltre() throws Exception {
        for (MockHttpServletRequestBuilder requete : List.of(gerant(get("/produits")), caissier(get("/produits")))) {
            String page = mvc.perform(requete).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(page).contains("data-export-liste=\"excel\"", "data-export-liste=\"pdf\"", "href=\"/produits/export/excel\"", "href=\"/produits/export/pdf\"",
                    "data-export-champs=\"q=#produitsSearch;categorie=#produitsCategory;stock=#produitsStock\"", "js/export-liste.js");
        }
    }

    // ===== Catégories =====

    @Test
    void lExcelDesCategoriesCompteLesProduitsDeChacune() throws Exception {
        MockHttpServletResponse reponse = mvc.perform(gerant(get("/categories/export/excel"))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(reponse.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"categories_" + LocalDate.now() + ".xlsx\"");
        try (Workbook classeur = classeur(reponse)) {
            Sheet f = classeur.getSheetAt(0);
            assertThat(entetes(f, "Nom")).containsExactly("Nom", "Nombre de produits");
            List<Row> lignes = lignes(f, "Nom");
            assertThat(premieresCellules(lignes)).containsExactly("Alimentation", "Boissons", "Services", "Vide");
            assertThat(lignes.stream().map(r -> nombre(r, 1)).toList()).containsExactly(3d, 1d, 1d, 0d);
            Row totaux = totaux(f, "Nom");
            assertThat(chaine(totaux, 0)).isEqualTo("4 catégories");
            assertThat(nombre(totaux, 1)).isEqualTo(5d);
            assertThat(texteDeLaFeuille(f)).doesNotContain("Rayon B");
        }
        MockHttpServletResponse pdf = mvc.perform(gerant(get("/categories/export/pdf"))).andReturn().getResponse();
        assertThat(pdf(pdf)).contains("Boutique Étoile A", "Catégories", "Alimentation", "Services", "4 catégories").doesNotContain("Rayon B");
        assertThat(estPaysage(pdf.getContentAsByteArray())).isFalse();
        try (Workbook classeur = classeur(mvc.perform(gerant(get("/categories/export/excel").param("q", "BOIS"))).andReturn().getResponse())) {
            assertThat(premieresCellules(lignes(classeur.getSheetAt(0), "Nom"))).containsExactly("Boissons");
        }
    }

    @Test
    void lesCategoriesSontReserveesAuGerantEtLAutreBoutiqueEstExclue() throws Exception {
        for (String url : List.of("/categories/export/excel", "/categories/export/pdf")) {
            mvc.perform(caissier(get(url))).andExpect(status().isForbidden());
            assertThat(mvc.perform(get(url)).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl()).endsWith("/login");
        }
        try (Workbook classeur = classeur(mvc.perform(avec(get("/categories/export/excel"), GERANT_B, "ADMIN")).andReturn().getResponse())) {
            assertThat(premieresCellules(lignes(classeur.getSheetAt(0), "Nom"))).containsExactly("Rayon B");
        }
        String page = mvc.perform(gerant(get("/categories"))).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("href=\"/categories/export/excel\"", "data-export-champs=\"q=#categoriesSearch\"");
    }

    // ===== Clients =====

    @Test
    void lExcelDesClientsPorteCoordonneesSoldesEtAvances() throws Exception {
        MockHttpServletResponse reponse = mvc.perform(gerant(get("/clients/export/excel"))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(reponse.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"clients_" + LocalDate.now() + ".xlsx\"");
        try (Workbook classeur = classeur(reponse)) {
            Sheet f = classeur.getSheet("Clients");
            assertThat(f.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Tous les clients");
            assertThat(entetes(f, "Nom")).containsExactly("Nom", "Téléphone", "Email", "Adresse", "Me doit", "Je dois", "Avance disponible", "Dont solde de départ");
            List<Row> lignes = lignes(f, "Nom");
            // Ordre alphabétique, la ponctuation d'abord : le nom qui commence par « = », puis Awa Diallo, Boubacar Sow, Élève Ça’ñ.
            assertThat(premieresCellules(lignes)).containsExactly(FORMULE, "Awa Diallo", "Boubacar Sow", "Élève Ça’ñ");
            Row awaDiallo = lignes.get(1), boubacar = lignes.get(2), eleve = lignes.get(3);
            assertThat(chaine(awaDiallo, 1)).isEqualTo("+224 620 11 22 33");
            assertThat(chaine(awaDiallo, 2)).isEqualTo("awa@example.test");
            assertThat(chaine(awaDiallo, 3)).isEqualTo("Matoto");
            // 30 000 de solde de départ + 20 000 de facture restent dus.
            assertThat(List.of(nombre(awaDiallo, 4), nombre(awaDiallo, 7))).containsExactly(50_000d, 30_000d);
            assertThat(nombre(awaDiallo, 5)).isNull();
            // Une avance de 40 000 : la boutique lui doit 40 000.
            assertThat(List.of(nombre(boubacar, 5), nombre(boubacar, 6))).containsExactly(40_000d, 40_000d);
            assertThat(nombre(boubacar, 4)).isNull();
            assertThat(chaine(boubacar, 1)).isNull();
            assertThat(nombre(eleve, 4)).isNull();
            Row totaux = totaux(f, "Nom");
            assertThat(chaine(totaux, 0)).isEqualTo("4 fiches");
            assertThat(List.of(nombre(totaux, 4), nombre(totaux, 5), nombre(totaux, 6), nombre(totaux, 7))).containsExactly(50_000d, 40_000d, 40_000d, 30_000d);
            // Un nom qui ressemble à une formule reste du texte.
            Cell piege = lignes.get(0).getCell(0);
            assertThat(piege.getStringCellValue()).isEqualTo(FORMULE);
            assertThat(piege.getCellStyle().getQuotePrefixed()).isTrue();
            aucuneFormule(f);
            assertThat(texteDeLaFeuille(f)).doesNotContain("Client Secret B");
        }
        MockHttpServletResponse pdf = mvc.perform(gerant(get("/clients/export/pdf"))).andReturn().getResponse();
        assertThat(pdf(pdf)).contains("Boutique Étoile A", "Clients", "Awa Diallo", "+224 620 11 22 33", "50 000", "40 000", "4 fiches", "Me doit").doesNotContain("Client Secret B");
        assertThat(estPaysage(pdf.getContentAsByteArray())).isTrue();
    }

    @Test
    void leCaissierExporteLesClientsAvecLesMemesInformationsQueLEcran() throws Exception {
        // L'écran des clients montre aussi le solde et l'avance au caissier : l'export reste identique à cet écran.
        try (Workbook classeur = classeur(mvc.perform(caissier(get("/clients/export/excel"))).andExpect(status().isOk()).andReturn().getResponse())) {
            List<Row> lignes = lignes(classeur.getSheetAt(0), "Nom");
            assertThat(premieresCellules(lignes)).containsExactly(FORMULE, "Awa Diallo", "Boubacar Sow", "Élève Ça’ñ");
            assertThat(nombre(lignes.get(1), 4)).isEqualTo(50_000d);
        }
        String ecran = mvc.perform(caissier(get("/clients"))).andReturn().getResponse().getContentAsString();
        assertThat(ecran).contains("Il me doit", "Je lui dois", "href=\"/clients/export/excel\"", "data-export-champs=\"q=#partenairesSearch;contact=#partenairesContact\"");
        assertThat(pdf(mvc.perform(caissier(get("/clients/export/pdf"))).andReturn().getResponse())).contains("par Bakary Caissier", "Awa Diallo").doesNotContain("Client Secret B");
    }

    @Test
    void lesFiltresDesClientsEtDesFournisseursDonnentLesLignesAttendues() throws Exception {
        record Cas(String liste, String[] parametres, List<String> attendu) { }
        List<Cas> cas = List.of(
                new Cas("clients", new String[]{"contact", "telephone"}, List.of(FORMULE, "Awa Diallo")),
                new Cas("clients", new String[]{"contact", "email"}, List.of("Awa Diallo", "Élève Ça’ñ")),
                new Cas("clients", new String[]{"contact", "sans-contact"}, List.of("Boubacar Sow")),
                new Cas("clients", new String[]{"q", "ELEVE ca"}, List.of("Élève Ça’ñ")),
                new Cas("clients", new String[]{"q", "matoto"}, List.of("Awa Diallo")),
                new Cas("clients", new String[]{"q", "620112233"}, List.of("Awa Diallo")),
                new Cas("clients", new String[]{"q", "+224 620", "contact", "telephone"}, List.of("Awa Diallo")),
                new Cas("clients", new String[]{"q", "introuvable"}, List.of()),
                new Cas("fournisseurs", new String[]{"contact", "telephone"}, List.of("Agro Plus")),
                new Cas("fournisseurs", new String[]{"contact", "sans-contact"}, List.of("Sory Import")),
                new Cas("fournisseurs", new String[]{"q", "KALOUM"}, List.of("Agro Plus")));
        for (Cas c : cas) {
            MockHttpServletRequestBuilder excel = avecParametres(get("/" + c.liste() + "/export/excel"), c.parametres());
            try (Workbook classeur = classeur(mvc.perform(gerant(excel)).andReturn().getResponse())) {
                assertThat(premieresCellules(lignes(classeur.getSheetAt(0), "Nom"))).as(c.liste() + " " + String.join("=", c.parametres())).containsExactlyElementsOf(c.attendu());
            }
            String pdf = pdf(mvc.perform(gerant(avecParametres(get("/" + c.liste() + "/export/pdf"), c.parametres()))).andReturn().getResponse());
            for (String nom : List.of("Awa Diallo", "Boubacar Sow", "Élève Ça’ñ", "Agro Plus", "Sory Import")) {
                if (c.attendu().contains(nom)) assertThat(pdf).contains(nom); else assertThat(pdf).doesNotContain(nom);
            }
        }
        mvc.perform(gerant(get("/clients/export/excel").param("contact", "fax"))).andExpect(status().isBadRequest());
        mvc.perform(gerant(get("/clients/export/excel").param("contact", "email", "telephone"))).andExpect(status().isBadRequest());
    }

    @Test
    void uneAutreBoutiqueNeVoitQueSesClientsEtSesFournisseurs() throws Exception {
        try (Workbook classeur = classeur(mvc.perform(avec(get("/clients/export/excel"), GERANT_B, "ADMIN")).andReturn().getResponse())) {
            assertThat(premieresCellules(lignes(classeur.getSheetAt(0), "Nom"))).containsExactly("Client Secret B");
            assertThat(texteDeLaFeuille(classeur.getSheetAt(0))).doesNotContain("Awa Diallo", "Étoile");
        }
        try (Workbook classeur = classeur(mvc.perform(avec(get("/fournisseurs/export/excel"), GERANT_B, "ADMIN")).andReturn().getResponse())) {
            assertThat(premieresCellules(lignes(classeur.getSheetAt(0), "Nom"))).containsExactly("Fournisseur Secret B");
            assertThat(texteDeLaFeuille(classeur.getSheetAt(0))).doesNotContain("Sory", "Agro");
        }
    }

    @Test
    void auDelaDeLaLimiteLesClientsRevientALaListeAvecSonFiltre() throws Exception {
        client(boutiqueA, "Un", null, null, null, "0"); client(boutiqueA, "Deux", null, null, null, "0"); client(boutiqueA, "Trois", null, null, null, "0");
        MvcResult resultat = mvc.perform(caissier(get("/clients/export/excel").param("contact", "email"))).andExpect(status().is3xxRedirection()).andReturn();
        assertThat(resultat.getResponse().getRedirectedUrl()).isEqualTo("/clients?contact=email");
        assertThat(resultat.getFlashMap().get("error").toString()).contains("dépasse 6 lignes");
    }

    // ===== Fournisseurs =====

    @Test
    void lExcelDesFournisseursPorteLesMemesColonnesQueLEcran() throws Exception {
        MockHttpServletResponse reponse = mvc.perform(gerant(get("/fournisseurs/export/excel"))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(reponse.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"fournisseurs_" + LocalDate.now() + ".xlsx\"");
        try (Workbook classeur = classeur(reponse)) {
            Sheet f = classeur.getSheet("Fournisseurs");
            List<Row> lignes = lignes(f, "Nom");
            assertThat(premieresCellules(lignes)).containsExactly("Agro Plus", "Sory Import");
            Row agro = lignes.get(0), sory = lignes.get(1);
            // Agro Plus a versé 60 000 d'avance : il nous doit 60 000. Nous devons 200 000 à Sory.
            assertThat(chaine(agro, 1)).isEqualTo("+224 622 00 00 01");
            assertThat(chaine(agro, 3)).isEqualTo("Kaloum");
            assertThat(List.of(nombre(agro, 4), nombre(agro, 6))).containsExactly(60_000d, 60_000d);
            assertThat(nombre(agro, 5)).isNull();
            assertThat(List.of(nombre(sory, 5), nombre(sory, 7))).containsExactly(200_000d, 200_000d);
            Row totaux = totaux(f, "Nom");
            assertThat(chaine(totaux, 0)).isEqualTo("2 fiches");
            assertThat(List.of(nombre(totaux, 4), nombre(totaux, 5), nombre(totaux, 6), nombre(totaux, 7))).containsExactly(60_000d, 200_000d, 60_000d, 200_000d);
            assertThat(texteDeLaFeuille(f)).doesNotContain("Fournisseur Secret B");
        }
        MockHttpServletResponse pdf = mvc.perform(gerant(get("/fournisseurs/export/pdf"))).andReturn().getResponse();
        assertThat(pdf(pdf)).contains("Boutique Étoile A", "Fournisseurs", "Sory Import", "200 000", "2 fiches").doesNotContain("Fournisseur Secret B");
        assertThat(estPaysage(pdf.getContentAsByteArray())).isTrue();
        String page = mvc.perform(gerant(get("/fournisseurs"))).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("href=\"/fournisseurs/export/pdf\"", "data-export-champs=\"q=#partenairesSearch;contact=#partenairesContact\"");
    }

    @Test
    void lesFournisseursSontReservesAuGerant() throws Exception {
        for (String url : List.of("/fournisseurs/export/excel", "/fournisseurs/export/pdf")) {
            mvc.perform(caissier(get(url))).andExpect(status().isForbidden());
            assertThat(mvc.perform(get(url)).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl()).endsWith("/login");
        }
        for (String url : List.of("/clients/export/excel", "/clients/export/pdf")) {
            assertThat(mvc.perform(get(url)).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl()).endsWith("/login");
        }
    }

    /** Génère les PDF et Excel de démonstration si EXPORT_DEMO_DIR est défini (inspection visuelle, voir docs/lot-4.md). */
    @Test
    void genererLaDemonstration() throws Exception {
        String dossier = System.getenv("EXPORT_DEMO_DIR");
        org.junit.jupiter.api.Assumptions.assumeTrue(dossier != null && !dossier.isBlank());
        java.nio.file.Path cible = java.nio.file.Path.of(dossier);
        java.nio.file.Files.createDirectories(cible);
        for (String[] cas : new String[][]{{"gerant", GERANT_A, "ADMIN"}, {"caissier", CAISSIER_A, "CAISSIER"}}) {
            for (String liste : List.of("produits", "clients")) {
                for (String format : new String[]{"pdf", "excel"}) {
                    byte[] octets = mvc.perform(avec(get("/" + liste + "/export/" + format), cas[1], cas[2])).andReturn().getResponse().getContentAsByteArray();
                    java.nio.file.Files.write(cible.resolve("demo-" + liste + "-" + cas[0] + (format.equals("pdf") ? ".pdf" : ".xlsx")), octets);
                }
            }
        }
        for (String liste : List.of("categories", "fournisseurs")) {
            byte[] octets = mvc.perform(gerant(get("/" + liste + "/export/pdf"))).andReturn().getResponse().getContentAsByteArray();
            java.nio.file.Files.write(cible.resolve("demo-" + liste + ".pdf"), octets);
        }
    }

    // ----- Outils -----

    private MockHttpServletRequestBuilder gerant(MockHttpServletRequestBuilder requete) { return avec(requete, GERANT_A, "ADMIN"); }
    private MockHttpServletRequestBuilder caissier(MockHttpServletRequestBuilder requete) { return avec(requete, CAISSIER_A, "CAISSIER"); }

    private Boutique boutique(String nom, String adresse) {
        Boutique b = new Boutique(); b.setNom(nom); b.setAdresse(adresse); return boutiques.saveAndFlush(b);
    }

    private Utilisateur compte(String email, String nom, Role role, Boutique boutique) {
        Utilisateur u = new Utilisateur(); u.setNom(nom); u.setEmail(email); u.setRole(role); u.setBoutique(boutique); u.setActif(true);
        return utilisateurs.saveAndFlush(u);
    }

    private Categorie categorie(Boutique boutique, String nom) {
        Categorie c = new Categorie(); c.setNom(nom); c.setBoutique(boutique); return categories.saveAndFlush(c);
    }

    private Client client(Boutique boutique, String nom, String telephone, String email, String adresse, String soldeInitial) {
        Client c = new Client(); c.setBoutique(boutique); c.setNom(nom); c.setTelephone(telephone); c.setEmail(email); c.setAdresse(adresse);
        c.setSoldeInitial(new BigDecimal(soldeInitial));
        return clients.saveAndFlush(c);
    }

    private Fournisseur fournisseur(Boutique boutique, String nom, String telephone, String email, String adresse, String soldeInitial) {
        Fournisseur f = new Fournisseur(); f.setBoutique(boutique); f.setNom(nom); f.setTelephone(telephone); f.setEmail(email); f.setAdresse(adresse);
        f.setSoldeInitial(new BigDecimal(soldeInitial));
        return fournisseurs.saveAndFlush(f);
    }

    private Produit produit(Boutique boutique, String reference, String nom, Categorie categorie, NatureProduit nature, String unite, long quantite, long seuil,
                            String prixVente, String cout, String dernierAchat, Fournisseur fournisseur, boolean prixDefinis) {
        Produit p = new Produit(); p.setBoutique(boutique); p.setReference(reference); p.setNom(nom); p.setCategorie(categorie); p.setNature(nature); p.setUnite(unite);
        p.setQuantite(quantite); p.setSeuilAlerte(seuil); p.setPrixVente(new BigDecimal(prixVente)); p.setCoutMoyen(new BigDecimal(cout));
        p.setPrixAchat(new BigDecimal(dernierAchat)); p.setFournisseur(fournisseur); p.setPrixDefinis(prixDefinis);
        return produits.saveAndFlush(p);
    }
}
