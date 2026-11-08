package org.example.stock.controller;

import org.example.stock.enums.Role;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.example.stock.service.TableauDeBordService.LigneVendeur;
import org.example.stock.service.TableauDeBordService.VentesParVendeur;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * État « Ventes par vendeur » sur un jeu de données fixe (mars 2026) à deux boutiques : chaque chiffre est
 * comparé au calcul fait à la main. Les ventes sont insérées directement, avec leur coût figé.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ventes-par-vendeur;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class VentesParVendeurTests {
    private static final String GERANT_A = "etat-gerant-a@example.test";
    private static final String CAISSIER_A = "etat-caissier-a@example.test";
    private static final String GERANT_B = "etat-gerant-b@example.test";
    private static final String URL = "/etats/ventes-par-vendeur";

    @Autowired MockMvc mvc;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired CategorieRepository categories;
    @Autowired ProduitRepository produits;
    @Autowired VenteRepository ventes;
    @Autowired MouvementCaisseRepository caisse;
    @Autowired OperationCreationRepository operations;

    Boutique boutiqueA, boutiqueB;
    Utilisateur awa, bakary, cellou, dora;
    Produit riz, huile, livraison, rizB;

    @BeforeEach
    void preparer() {
        operations.deleteAll(); caisse.deleteAll(); ventes.deleteAll(); produits.deleteAll(); categories.deleteAll();
        utilisateurs.deleteAll(); boutiques.deleteAll();
        boutiqueA = boutique("Boutique état A"); boutiqueB = boutique("Boutique état B");
        awa = compte(GERANT_A, "Awa Gérante", Role.ADMIN, boutiqueA, true);
        bakary = compte(CAISSIER_A, "Bakary Caissier", Role.CAISSIER, boutiqueA, true);
        cellou = compte("etat-ancien@example.test", "Cellou Désactivé", Role.CAISSIER, boutiqueA, false);
        dora = compte("etat-dora@example.test", "Dora Sans Vente", Role.CAISSIER, boutiqueA, true);
        Utilisateur gerantB = compte(GERANT_B, "Zoé Autre Boutique", Role.ADMIN, boutiqueB, true);
        Categorie rayonA = categorie(boutiqueA), rayonB = categorie(boutiqueB);
        riz = produit(boutiqueA, rayonA, "Riz"); huile = produit(boutiqueA, rayonA, "Huile");
        livraison = produit(boutiqueA, rayonA, "Livraison"); rizB = produit(boutiqueB, rayonB, "Riz B");

        // Awa : 2 ventes. V1 le 1er mars à 0 h : 2 × (300 − 200) + 1 × (400 − 100) = 500, total 1 000.
        vente(boutiqueA, awa, LocalDateTime.of(2026, 3, 1, 0, 0), "1000", ligne(riz, 2, "300", "200"), ligne(livraison, 1, "400", "100"));
        // V2 le 31 mars à 23 h 59 min 59 : une ligne sans coût compte pour 0, marge 500.
        vente(boutiqueA, awa, LocalDateTime.of(2026, 3, 31, 23, 59, 59), "500", ligne(huile, 1, "500", null));
        // Bakary : V3 4 × (500 − 200,50) = 1 198 ; V4 333,33 − 1 = 332,33.
        vente(boutiqueA, bakary, LocalDateTime.of(2026, 3, 15, 12, 0), "2000", ligne(riz, 4, "500", "200.50"));
        vente(boutiqueA, bakary, LocalDateTime.of(2026, 3, 16, 9, 30), "333.33", ligne(huile, 1, "333.33", "1"));
        // Cellou (compte désactivé depuis) : 100 − 60 = 40.
        vente(boutiqueA, cellou, LocalDateTime.of(2026, 3, 10, 8, 0), "100", ligne(riz, 1, "100", "60"));
        // Sans vendeur : 700 − 300 = 400.
        vente(boutiqueA, null, LocalDateTime.of(2026, 3, 20, 14, 0), "700", ligne(huile, 1, "700", "300"));
        // Hors période : la veille à 23 h 59 min 59 et le 1er avril à 0 h.
        vente(boutiqueA, awa, LocalDateTime.of(2026, 2, 28, 23, 59, 59), "9999", ligne(riz, 1, "9999", "1"));
        vente(boutiqueA, bakary, LocalDateTime.of(2026, 4, 1, 0, 0), "8888", ligne(riz, 1, "8888", "1"));
        // Autre boutique, même période.
        vente(boutiqueB, gerantB, LocalDateTime.of(2026, 3, 15, 10, 0), "77777", ligne(rizB, 1, "77777", "7"));
        vente(boutiqueB, null, LocalDateTime.of(2026, 3, 15, 11, 0), "66666", ligne(rizB, 1, "66666", "6"));
    }

    @Test
    void chaqueChiffreCorrespondAuCalculALaMain() throws Exception {
        MvcResult resultat = mvc.perform(gerantA(get(URL).param("du", "2026-03-01").param("au", "2026-03-31")))
                .andExpect(status().isOk()).andReturn();
        VentesParVendeur etat = attribut(resultat);

        // Tri par chiffre d'affaires décroissant ; compte actif sans vente à zéro ; « Non renseigné » ensuite.
        assertThat(etat.lignes()).extracting(LigneVendeur::nom)
                .containsExactly("Bakary Caissier", "Awa Gérante", "Cellou Désactivé", "Dora Sans Vente", "Non renseigné");
        ligne(etat, "Bakary Caissier", 2, "2333.33", "1530.33", 0, "1166.67");   // 1 198 + 332,33 ; (2 333,33) / 2 arrondi
        ligne(etat, "Awa Gérante", 2, "1500", "1000", 1, "750");                 // 500 + 500 ; 1 ligne sans coût
        ligne(etat, "Cellou Désactivé", 1, "100", "40", 0, "100");
        ligne(etat, "Dora Sans Vente", 0, "0", "0", 0, "0");
        ligne(etat, "Non renseigné", 1, "700", "400", 0, "700");
        assertThat(etat.lignes().get(2).desactive()).isTrue();
        assertThat(etat.lignes().get(1).desactive()).isFalse();
        // Total : 6 ventes ; CA 4 633,33 ; brut 2 970,33 ; panier 4 633,33 / 6 = 772,22.
        LigneVendeur total = etat.total();
        assertThat(total.nombreVentes()).isEqualTo(6);
        assertThat(total.chiffreAffaires()).isEqualByComparingTo("4633.33");
        assertThat(total.beneficeBrut()).isEqualByComparingTo("2970.33");
        assertThat(total.panierMoyen()).isEqualByComparingTo("772.22");
        assertThat(etat.lignesSansCout()).isEqualTo(1);

        String page = resultat.getResponse().getContentAsString();
        assertThat(page).contains("Ventes par vendeur", "Imprimer", "1 ligne sans coût connu, comptée à 0", "2 333.33 GNF", "4 633.33 GNF",
                        "1 166.67 GNF", "(compte désactivé)", "Période du 01/03/2026 au 31/03/2026 inclus", "Non renseigné")
                .doesNotContain("Zoé Autre Boutique", "77 777", "66 666", "9 999", "8 888");
    }

    @Test
    void laPeriodeEstSemiOuverteEtInclutLeDernierJour() throws Exception {
        // Jusqu'au 15 mars inclus : V1, V3 (12 h) et la vente de Cellou ; V4 (16 mars) et la vente sans vendeur sont exclues.
        VentesParVendeur etat = attribut(mvc.perform(gerantA(get(URL).param("du", "2026-03-01").param("au", "2026-03-15"))).andReturn());
        ligne(etat, "Bakary Caissier", 1, "2000", "1198", 0, "2000");
        ligne(etat, "Awa Gérante", 1, "1000", "500", 0, "1000");
        ligne(etat, "Cellou Désactivé", 1, "100", "40", 0, "100");
        assertThat(etat.lignes()).extracting(LigneVendeur::nom).doesNotContain("Non renseigné");
        assertThat(etat.total().chiffreAffaires()).isEqualByComparingTo("3100");
        assertThat(etat.total().nombreVentes()).isEqualTo(3);

        // Un seul jour : le 31 mars à 23 h 59 min 59 appartient à ce jour, le 1er avril à minuit non.
        etat = attribut(mvc.perform(gerantA(get(URL).param("du", "2026-03-31").param("au", "2026-03-31"))).andReturn());
        ligne(etat, "Awa Gérante", 1, "500", "500", 1, "500");
        assertThat(etat.total().chiffreAffaires()).isEqualByComparingTo("500");
        etat = attribut(mvc.perform(gerantA(get(URL).param("du", "2026-04-01").param("au", "2026-04-01"))).andReturn());
        ligne(etat, "Bakary Caissier", 1, "8888", "8887", 0, "8888");
        // Une période sans vente : lignes à zéro des comptes actifs, message dédié.
        MvcResult vide = mvc.perform(gerantA(get(URL).param("du", "2026-05-01").param("au", "2026-05-31"))).andReturn();
        assertThat(this.<VentesParVendeur>attribut(vide).total().nombreVentes()).isZero();
        assertThat(vide.getResponse().getContentAsString()).contains("Aucune vente sur cette période.");
    }

    @Test
    void sansParametreLeMoisEnCoursEstAfficheEtUnePeriodeInvalideEstCorrigee() throws Exception {
        LocalDate aujourdHui = LocalDate.now();
        MvcResult defaut = mvc.perform(gerantA(get(URL))).andExpect(status().isOk()).andReturn();
        assertThat(defaut.getModelAndView().getModel().get("du")).isEqualTo(aujourdHui.withDayOfMonth(1));
        assertThat(defaut.getModelAndView().getModel().get("au")).isEqualTo(aujourdHui.withDayOfMonth(1).plusMonths(1).minusDays(1));
        assertThat(defaut.getModelAndView().getModel()).doesNotContainKey("avertissementPeriode");

        for (String[] invalide : new String[][]{{"abc", "2026-03-31"}, {"2026-03-31", "2026-03-01"}, {"2026-02-30", "2026-03-31"},
                {"1999-12-31", "2026-03-31"}, {"2026-03-01", ""}, {"", ""}, {"+999999999-01-01", "2026-03-31"}}) {
            MvcResult resultat = mvc.perform(gerantA(get(URL).param("du", invalide[0]).param("au", invalide[1])))
                    .andExpect(status().isOk()).andReturn();
            assertThat(resultat.getModelAndView().getModel().get("du")).as(invalide[0]).isEqualTo(aujourdHui.withDayOfMonth(1));
            assertThat(resultat.getModelAndView().getModel().get("avertissementPeriode")).as(invalide[0]).isNotNull();
        }
        // Valeurs multiples.
        MvcResult multiple = mvc.perform(gerantA(get(URL).param("du", "2026-03-01", "2026-03-02").param("au", "2026-03-31"))).andReturn();
        assertThat(multiple.getModelAndView().getModel().get("avertissementPeriode")).isNotNull();
    }

    @Test
    void uneAutreBoutiqueNeVoitQueSesVentesEtNeFaitJamaisApparaitreLesComptesDeLAutre() throws Exception {
        MvcResult resultat = mvc.perform(avec(get(URL).param("du", "2026-03-01").param("au", "2026-03-31"), GERANT_B, "ADMIN"))
                .andExpect(status().isOk()).andReturn();
        VentesParVendeur etat = attribut(resultat);
        assertThat(etat.lignes()).extracting(LigneVendeur::nom).containsExactly("Zoé Autre Boutique", "Non renseigné");
        ligne(etat, "Zoé Autre Boutique", 1, "77777", "77770", 0, "77777");
        ligne(etat, "Non renseigné", 1, "66666", "66660", 0, "66666");
        assertThat(etat.total().chiffreAffaires()).isEqualByComparingTo("144443");
        assertThat(resultat.getResponse().getContentAsString()).doesNotContain("Awa Gérante", "Bakary Caissier", "Cellou", "1 500.00", "4 633");
    }

    @Test
    void leCaissierReçoitUn403EtLaPageNeFigurePasDansSonMenu() throws Exception {
        mvc.perform(avec(get(URL).param("du", "2026-03-01").param("au", "2026-03-31"), CAISSIER_A, "CAISSIER")).andExpect(status().isForbidden());
        mvc.perform(avec(get(URL), CAISSIER_A, "CAISSIER")).andExpect(status().isForbidden());
        mvc.perform(avec(get(URL + "/autre"), CAISSIER_A, "CAISSIER")).andExpect(status().isForbidden());
        String menuCaissier = mvc.perform(avec(get("/ventes"), CAISSIER_A, "CAISSIER")).andReturn().getResponse().getContentAsString();
        assertThat(menuCaissier).doesNotContain("/etats/ventes-par-vendeur");
        String menuGerant = mvc.perform(gerantA(get("/ventes"))).andReturn().getResponse().getContentAsString();
        assertThat(menuGerant).contains("href=\"/etats/ventes-par-vendeur\"");
        // Un visiteur non connecté est renvoyé vers la connexion.
        mvc.perform(get(URL)).andExpect(status().is3xxRedirection());
    }

    @Test
    void lEtatEstEnLectureSeule() throws Exception {
        mvc.perform(post(URL).with(user(GERANT_A).roles("ADMIN")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is4xxClientError());
        assertThat(ventes.count()).isEqualTo(10);
    }

    // ----- Outils -----

    private static void ligne(VentesParVendeur etat, String nom, long ventes, String chiffre, String brut, long sansCout, String panier) {
        LigneVendeur ligne = etat.lignes().stream().filter(l -> l.nom().equals(nom)).findFirst().orElseThrow();
        assertThat(ligne.nombreVentes()).as(nom + " : ventes").isEqualTo(ventes);
        assertThat(ligne.chiffreAffaires()).as(nom + " : chiffre d'affaires").isEqualByComparingTo(chiffre);
        assertThat(ligne.beneficeBrut()).as(nom + " : bénéfice brut").isEqualByComparingTo(brut);
        assertThat(ligne.lignesSansCout()).as(nom + " : lignes sans coût").isEqualTo(sansCout);
        assertThat(ligne.panierMoyen()).as(nom + " : panier moyen").isEqualByComparingTo(panier);
    }

    @SuppressWarnings("unchecked")
    private <T> T attribut(MvcResult resultat) {
        Object valeur = resultat.getModelAndView().getModel().get("etat");
        assertThat(valeur).isNotNull();
        return (T) valeur;
    }

    private MockHttpServletRequestBuilder gerantA(MockHttpServletRequestBuilder requete) { return avec(requete, GERANT_A, "ADMIN"); }

    private MockHttpServletRequestBuilder avec(MockHttpServletRequestBuilder requete, String email, String role) {
        return requete.with(user(email).roles(role));
    }

    private Boutique boutique(String nom) { Boutique b = new Boutique(); b.setNom(nom); return boutiques.saveAndFlush(b); }

    private Utilisateur compte(String email, String nom, Role role, Boutique boutique, boolean actif) {
        Utilisateur u = new Utilisateur(); u.setNom(nom); u.setEmail(email); u.setRole(role); u.setBoutique(boutique); u.setActif(actif);
        return utilisateurs.saveAndFlush(u);
    }

    private Categorie categorie(Boutique boutique) {
        Categorie c = new Categorie(); c.setNom("Rayon"); c.setBoutique(boutique); return categories.saveAndFlush(c);
    }

    private Produit produit(Boutique boutique, Categorie categorie, String nom) {
        Produit p = new Produit(); p.setBoutique(boutique); p.setCategorie(categorie); p.setNom(nom);
        p.setReference("ETAT-" + nom.toUpperCase().replace(' ', '-')); p.setQuantite(100L); p.setPrixDefinis(true);
        p.setPrixAchat(new BigDecimal("100")); p.setPrixVente(new BigDecimal("1000")); p.setCoutMoyen(new BigDecimal("100"));
        return produits.saveAndFlush(p);
    }

    private static DetailVente ligne(Produit produit, int quantite, String prix, String cout) {
        DetailVente ligne = new DetailVente(); ligne.setProduit(produit); ligne.setQuantite(quantite);
        ligne.setPrixUnitaire(new BigDecimal(prix)); ligne.setCoutUnitaire(cout == null ? null : new BigDecimal(cout));
        return ligne;
    }

    private void vente(Boutique boutique, Utilisateur vendeur, LocalDateTime date, String total, DetailVente... lignes) {
        Vente vente = new Vente(); vente.setBoutique(boutique); vente.setVendeur(vendeur); vente.setDateVente(date);
        vente.setMontantTotal(new BigDecimal(total)); vente.setMontantVerse(new BigDecimal(total));
        for (DetailVente ligne : lignes) { ligne.setVente(vente); vente.getLignes().add(ligne); }
        ventes.saveAndFlush(vente);
    }
}
