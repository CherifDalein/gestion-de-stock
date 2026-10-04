package org.example.stock.security;

import org.example.stock.enums.Role;
import org.example.stock.model.Categorie;
import org.example.stock.model.Client;
import org.example.stock.model.Fournisseur;
import org.example.stock.model.Produit;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.UtilisateurRepository;
import org.example.stock.service.CategorieService;
import org.example.stock.service.ClientService;
import org.example.stock.service.FournisseurService;
import org.example.stock.service.ProduitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "admin-audit@example.test", roles = "ADMIN")
class SecurityFlowTests {
    @Autowired MockMvc mvc;
    @Autowired UtilisateurRepository utilisateurRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @MockBean ProduitService produitService;
    @MockBean ClientService clientService;
    @MockBean CategorieService categorieService;
    @MockBean FournisseurService fournisseurService;

    @BeforeEach
    void preparer() {
        Utilisateur admin = new Utilisateur();
        admin.setNom("Administrateur test");
        admin.setEmail("admin-audit@example.test");
        admin.setMotDePasse(passwordEncoder.encode("MotDePasseTest42"));
        admin.setRole(Role.ADMIN);
        utilisateurRepository.saveAndFlush(admin);

        Categorie categorie = new Categorie();
        categorie.setId(1L);
        categorie.setNom("Catégorie test");
        Fournisseur fournisseur = new Fournisseur();
        fournisseur.setId(1L);
        fournisseur.setNom("Fournisseur test");
        Client client = new Client();
        client.setId(1L);
        client.setNom("Client test");
        Produit produit = new Produit();
        produit.setId(1L);
        produit.setNom("Produit test");
        produit.setReference("REF-TEST");
        produit.setPrixAchat(10.0);
        produit.setPrixVente(20.0);
        produit.setQuantite(5L);
        produit.setCategorie(categorie);
        produit.setFournisseur(fournisseur);
        when(produitService.listerTous()).thenReturn(List.of(produit));
        when(clientService.listerTous()).thenReturn(List.of(client));
        when(categorieService.listerToutes()).thenReturn(List.of(categorie));
        when(fournisseurService.listerTous()).thenReturn(List.of(fournisseur));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login", "/register", "/produits/nouveau", "/clients/nouveau",
            "/categories/nouveau", "/fournisseurs/nouveau", "/ventes/nouveau", "/achats/nouveau",
            "/produits", "/clients", "/categories", "/fournisseurs"})
    void lesFormulairesThymeleafContiennentLeJeton(String route) throws Exception {
        mvc.perform(get(route)).andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"produits", "clients", "categories", "fournisseurs"})
    void supprimerExigePostEtUnJetonValide(String ressource) throws Exception {
        String route = "/" + ressource + "/supprimer/1";
        mvc.perform(get(route)).andExpect(status().isMethodNotAllowed());
        mvc.perform(post(route)).andExpect(status().isForbidden());
        mvc.perform(post(route).with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        verifierSuppression(ressource, false);
        mvc.perform(post(route).with(csrf())).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/" + ressource));
        verifierSuppression(ressource, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"produits", "clients", "categories", "fournisseurs"})
    @WithMockUser(username = "admin-audit@example.test", roles = "CAISSIER")
    void unCaissierNePeutPasSupprimerMemeAvecUnJeton(String ressource) throws Exception {
        mvc.perform(post("/" + ressource + "/supprimer/1").with(csrf()))
                .andExpect(status().isForbidden());
        verifierSuppression(ressource, false);
    }

    @Test
    @WithAnonymousUser
    void laCreationDeCompteNestPlusPublique() throws Exception {
        mvc.perform(get("/register")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/register").with(csrf()).param("nom", "Intrus")
                        .param("email", "intrus@example.test").param("password", "MotDePasseTest42"))
                .andExpect(status().is3xxRedirection());
        assertThat(utilisateurRepository.findByEmail("intrus@example.test")).isEmpty();
        mvc.perform(get("/login")).andExpect(content().string(
                org.hamcrest.Matchers.not(containsString("href=\"/register\""))));
    }

    @Test
    @WithMockUser(username = "admin-audit@example.test", roles = "CAISSIER")
    void unCaissierNePeutPasCreerUnCompte() throws Exception {
        mvc.perform(get("/register")).andExpect(status().isForbidden());
        mvc.perform(post("/register").with(csrf()).param("nom", "Intrus")
                        .param("email", "intrus@example.test").param("password", "MotDePasseTest42"))
                .andExpect(status().isForbidden());
        assertThat(utilisateurRepository.findByEmail("intrus@example.test")).isEmpty();
    }

    @Test
    void unAdministrateurCreeUnCaissierSansPouvoirImposerUnRoleParLeFormulaire() throws Exception {
        mvc.perform(post("/register").param("nom", "Caissier")
                        .param("email", "caissier@example.test").param("password", "MotDePasseTest42"))
                .andExpect(status().isForbidden());
        assertThat(utilisateurRepository.findByEmail("caissier@example.test")).isEmpty();
        mvc.perform(post("/register").with(csrf()).param("nom", "Caissier")
                        .param("email", "caissier@example.test").param("password", "MotDePasseTest42")
                        .param("role", "ADMIN"))
                .andExpect(redirectedUrl("/register?created"));
        Utilisateur compte = utilisateurRepository.findByEmail("caissier@example.test").orElseThrow();
        assertThat(compte.getRole()).isEqualTo(Role.CAISSIER);
        assertThat(passwordEncoder.matches("MotDePasseTest42", compte.getMotDePasse())).isTrue();
        assertThat(utilisateurRepository.findByEmail("admin-audit@example.test").orElseThrow().getRole())
                .isEqualTo(Role.ADMIN);
    }

    @Test
    void logoutExigeUnJeton() throws Exception {
        mvc.perform(post("/logout")).andExpect(status().isForbidden());
        mvc.perform(post("/logout").with(csrf())).andExpect(redirectedUrl("/login?logout"));
    }

    @Test
    @WithAnonymousUser
    void connexionSuppressionEtDeconnexionAvecLesJetonsDesPages() throws Exception {
        var pageLogin = mvc.perform(get("/login")).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) pageLogin.getRequest().getSession(false);
        mvc.perform(post("/login").session(session).param("username", "admin-audit@example.test")
                        .param("password", "MotDePasseTest42")
                        .param("_csrf", lireJeton(pageLogin.getResponse().getContentAsString())))
                .andExpect(redirectedUrl("/"));
        // Retirer l'identité anonyme imposée par le test pour utiliser la session du login réel.
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        var pageProduits = mvc.perform(get("/produits").session(session))
                .andExpect(status().isOk()).andReturn();
        String html = pageProduits.getResponse().getContentAsString();
        var formulaire = Pattern.compile("<form[^>]*action=\"/produits/supprimer/1\"[^>]*>([\\s\\S]*?)</form>")
                .matcher(html);
        assertThat(formulaire.find()).isTrue();
        mvc.perform(post("/produits/supprimer/1").session(session)
                        .param("_csrf", lireJeton(formulaire.group(1))))
                .andExpect(redirectedUrl("/produits"));
        verify(produitService).supprimerProduit(1L);
        mvc.perform(post("/logout").session(session).param("_csrf", lireJeton(html)))
                .andExpect(redirectedUrl("/login?logout"));
        assertThat(session.isInvalid()).isTrue();
    }

    private String lireJeton(String html) {
        var matcher = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private void verifierSuppression(String ressource, boolean executee) {
        var mode = executee ? org.mockito.Mockito.times(1) : never();
        switch (ressource) {
            case "produits" -> verify(produitService, mode).supprimerProduit(1L);
            case "clients" -> verify(clientService, mode).supprimerClient(1L);
            case "categories" -> verify(categorieService, mode).supprimer(1L);
            case "fournisseurs" -> verify(fournisseurService, mode).supprimerFournisseur(1L);
        }
    }
}
