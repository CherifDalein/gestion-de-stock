package org.example.stock.security;

import org.example.stock.enums.Role;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:demarrage-http-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = DemarrageBoutiqueSecurityTests.ADMIN, roles = "ADMIN")
class DemarrageBoutiqueSecurityTests {
    static final String ADMIN = "gerant-demarrage@example.test";
    private static final String ROUTE = "/boutique/demarrage/terminer";
    @Autowired MockMvc mvc;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired CategorieRepository categories;
    @Autowired ProduitRepository produits;
    @Autowired MouvementStockRepository stock;
    @Autowired MouvementCaisseRepository caisse;
    @Autowired AchatRepository achats;
    @Autowired VenteRepository ventes;
    Boutique boutique, autre;
    Categorie categorie;
    Produit produit;

    @BeforeEach void preparer() {
        boutique = new Boutique(); boutique.setNom("Boutique au démarrage"); boutique = boutiques.saveAndFlush(boutique);
        autre = new Boutique(); autre.setNom("Boutique indépendante"); autre = boutiques.saveAndFlush(autre);
        compte(ADMIN, Role.ADMIN, boutique);
        compte("caissier-demarrage@example.test", Role.CAISSIER, boutique);
        compte("autre-demarrage@example.test", Role.ADMIN, autre);
        categorie = new Categorie(); categorie.setNom("Catalogue"); categorie.setBoutique(boutique); categorie = categories.saveAndFlush(categorie);
        produit = new Produit(); produit.setNom("Produit vide"); produit.setReference("DEMARRAGE");
        produit.setBoutique(boutique); produit.setCategorie(categorie); produit.setPrixDefinis(false); produit = produits.saveAndFlush(produit);
    }

    private void compte(String email, Role role, Boutique espace) {
        var compte = new Utilisateur(); compte.setEmail(email); compte.setNom("Utilisateur de test");
        compte.setRole(role); compte.setBoutique(espace); utilisateurs.saveAndFlush(compte);
    }

    @Test void leGerantVoitLeDemarrageEtLeTermineSansModifierLesDonneesMetier() throws Exception {
        mvc.perform(get("/boutique/parametres")).andExpect(status().isOk())
                .andExpect(model().attribute("initialisationOuverte", true))
                .andExpect(content().string(containsString("action=\"" + ROUTE + "\"")));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(ROUTE).with(csrf())).andExpect(redirectedUrl("/boutique/parametres"));
        }
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().isInitialisationTerminee()).isTrue();
        assertThat(boutiques.findById(autre.getId()).orElseThrow().isInitialisationTerminee()).isFalse();
        mvc.perform(get("/boutique/parametres")).andExpect(model().attribute("initialisationOuverte", false))
                .andExpect(content().string(not(containsString("action=\"" + ROUTE + "\""))));
        verifierAucunMouvement();
    }

    @Test void lesStocksEtLaCaisseInitiauxSontBloquesAussiEnPostApresCloture() throws Exception {
        mvc.perform(post(ROUTE).with(csrf())).andExpect(status().isFound());
        mvc.perform(get("/produits/" + produit.getId() + "/stock")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("stockInitialQuantite"))))
                .andExpect(content().string(not(containsString("ajustementQuantite"))));
        mvc.perform(post("/produits/" + produit.getId() + "/stock/initial").with(csrf())
                        .param("version", produit.getVersion().toString()).param("quantite", "5")
                        .param("coutUnitaire", "10").param("prixVente", "20").param("motif", "Anciennes marchandises"))
                .andExpect(status().isOk()).andExpect(model().attributeExists("error"));
        mvc.perform(get("/caisse/ouverture")).andExpect(status().isOk())
                .andExpect(model().attribute("initialAutorise", false));
        mvc.perform(post("/caisse/ouverture").with(csrf()).param("montant", "30000").param("motif", "Fonds initiaux"))
                .andExpect(status().isOk()).andExpect(model().attributeExists("error"));
        verifierAucunMouvement();
    }

    @Test void uneNouvelleFicheProduitRestePossibleApresLeDemarrage() throws Exception {
        mvc.perform(post(ROUTE).with(csrf())).andExpect(status().isFound());
        mvc.perform(post("/produits/ajouter").with(csrf()).param("nom", "Nouvelle référence")
                        .param("reference", "APRES-DEMARRAGE").param("categorie", categorie.getId().toString()))
                .andExpect(redirectedUrl("/produits"));
        var nouveau = produits.findAllByBoutiqueId(boutique.getId()).stream()
                .filter(p -> p.getReference().equals("APRES-DEMARRAGE")).findFirst().orElseThrow();
        assertThat(nouveau.getQuantite()).isZero(); assertThat(nouveau.isPrixDefinis()).isFalse();
        assertThat(nouveau.getPrixAchat()).isZero(); assertThat(nouveau.getPrixVente()).isZero();
        verifierAucunMouvement();
    }

    @Test void lesAnciensFormulairesDAjustementNePeuventPlusChangerLeStock() throws Exception {
        mvc.perform(post("/produits/" + produit.getId() + "/stock/ajuster").with(csrf())
                        .param("version", produit.getVersion().toString()).param("quantite", "50").param("motif", "Ancien formulaire"))
                .andExpect(status().isGone());
        verifierAucunMouvement();
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "boutique", "boutique.id", "initialisationTerminee", "initialisationOuverte", "role"})
    void laClotureNAccueilleAucunChampMetierOuIdentifiant(String champ) throws Exception {
        mvc.perform(post(ROUTE).with(csrf()).param(champ, autre.getId().toString())).andExpect(status().isBadRequest());
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().isInitialisationTerminee()).isFalse();
        assertThat(boutiques.findById(autre.getId()).orElseThrow().isInitialisationTerminee()).isFalse();
    }

    @Test void laClotureExigeUnPostAvecCsrfEtLesDroitsReelsDuGerant() throws Exception {
        mvc.perform(get(ROUTE)).andExpect(status().isMethodNotAllowed());
        mvc.perform(post(ROUTE)).andExpect(status().isForbidden());
        mvc.perform(post(ROUTE).with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        mvc.perform(post(ROUTE).with(csrf()).with(user("caissier-demarrage@example.test").roles("CAISSIER")))
                .andExpect(status().isForbidden());
        mvc.perform(post(ROUTE).with(csrf()).with(user("caissier-demarrage@example.test").roles("ADMIN")))
                .andExpect(status().isForbidden());
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().isInitialisationTerminee()).isFalse();
    }

    private void verifierAucunMouvement() {
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isZero();
        assertThat(stock.count()).isZero(); assertThat(caisse.count()).isZero();
        assertThat(achats.count()).isZero(); assertThat(ventes.count()).isZero();
    }
}
