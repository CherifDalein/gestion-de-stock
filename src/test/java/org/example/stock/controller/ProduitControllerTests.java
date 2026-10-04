package org.example.stock.controller;

import org.example.stock.model.Fournisseur;
import org.example.stock.service.CategorieService;
import org.example.stock.service.FournisseurService;
import org.example.stock.service.ProduitService;
import org.example.stock.service.UtilisateurService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class ProduitControllerTests {
    @Mock ProduitService produitService;
    @Mock CategorieService categorieService;
    @Mock FournisseurService fournisseurService;
    @Mock UtilisateurService utilisateurService;
    @InjectMocks ProduitController controller;
    MockMvc mvc;
    List<Fournisseur> fournisseurs;

    @BeforeEach
    void preparer() {
        mvc = standaloneSetup(controller).setControllerAdvice(new FormBindingAdvice()).build();
        fournisseurs = List.of(new Fournisseur());
        when(fournisseurService.listerTous()).thenReturn(fournisseurs);
    }

    @Test
    void creationInvalideConserveLesFournisseursSansSauvegarder() throws Exception {
        mvc.perform(produitInvalide("/produits/ajouter"))
                .andExpect(view().name("dashboard"))
                .andExpect(model().attributeHasFieldErrors("produit", "prixVente"))
                .andExpect(model().attribute("fournisseurs", fournisseurs));
        verify(produitService, never()).ajouterProduit(any());
    }

    @Test
    void modificationInvalideConserveIdDeRouteEtFournisseurs() throws Exception {
        var resultat = mvc.perform(produitInvalide("/produits/modifier/7"))
                .andExpect(view().name("dashboard"))
                .andExpect(model().attributeHasFieldErrors("produit", "prixVente"))
                .andExpect(model().attribute("fournisseurs", fournisseurs))
                .andReturn();
        var produit = (org.example.stock.model.Produit) resultat.getModelAndView().getModel().get("produit");
        assertThat(produit.getId()).isEqualTo(7L);
        verify(produitService, never()).ajouterProduit(any());
    }

    private MockHttpServletRequestBuilder produitInvalide(String route) {
        var requete = post(route).param("nom", "Produit").param("reference", "REF-TEST")
                .param("prixAchat", "10").param("prixVente", "-1")
                .param("quantite", "0").param("categorie.id", "1");
        if (route.contains("/modifier/")) requete.param("version", "0");
        return requete;
    }
}
