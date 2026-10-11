package org.example.stock.security;

import org.example.stock.enums.Role;
import org.example.stock.form.CreationBoutiqueForm;
import org.example.stock.model.Boutique;
import org.example.stock.model.Categorie;
import org.example.stock.model.Produit;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.BoutiqueRepository;
import org.example.stock.repository.CategorieRepository;
import org.example.stock.repository.ProduitRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.example.stock.service.BoutiqueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PlateformeSecurityTests {
    private static final String OPERATEUR = "plateforme-http@example.test";
    private static final String GERANT = "gerant-plateforme-http@example.test";
    private static final String SECRET = "Secret-plateforme-test-2026!";
    private static final String EMAIL_NOUVEAU = "gerant-nouvelle-http@example.test";
    @Autowired MockMvc mvc;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired CategorieRepository categories;
    @Autowired ProduitRepository produits;
    @Autowired PasswordEncoder passwords;
    @Autowired BoutiqueService boutiqueService;
    Boutique principale, externe;
    Utilisateur operateur, gerant;

    @BeforeEach
    void preparer() {
        principale = boutique("Boutique de l'opérateur");
        externe = boutique("Boutique externe");
        operateur = compte(OPERATEUR, principale, true);
        gerant = compte(GERANT, externe, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CAISSIER"})
    void lesRolesDeBoutiqueNeDonnentPasAccesALaPlateforme(String role) throws Exception {
        gerant.setRole(Role.valueOf(role)); utilisateurs.saveAndFlush(gerant);
        long nombre = boutiques.count();
        for (String route : List.of("/plateforme/boutiques", "/plateforme/boutiques/nouvelle",
                "/plateforme/boutiques/" + externe.getId())) {
            mvc.perform(get(route).with(user(GERANT).roles(role))).andExpect(status().isForbidden());
        }
        mvc.perform(creation().with(user(GERANT).roles(role)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(acces()).with(user(GERANT).roles(role)).with(csrf())
                .param("actif", "false").param("accesOffert", "true")).andExpect(status().isForbidden());
        assertThat(boutiques.count()).isEqualTo(nombre);
        assertThat(externe.isActif()).isTrue();
        assertThat(externe.isAccesOffert()).isFalse();
    }

    @Test
    void lesRoutesPlateformeExigentUneConnexion() throws Exception {
        mvc.perform(get("/plateforme/boutiques")).andExpect(redirectedUrl("http://localhost/login"));
        mvc.perform(creation().with(csrf())).andExpect(redirectedUrl("http://localhost/login"));
        assertThat(utilisateurs.existsByEmail(EMAIL_NOUVEAU)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {GERANT, "compte-plateforme-supprime@example.test"})
    void uneAutoriteFalsifieeNeRemplacePasLeDroitPersistant(String email) throws Exception {
        mvc.perform(get("/plateforme/boutiques").with(user(email).roles("ADMIN", "PLATEFORME_ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(creation().with(user(email).roles("ADMIN", "PLATEFORME_ADMIN")).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(utilisateurs.existsByEmail(EMAIL_NOUVEAU)).isFalse();
    }

    @Test
    void uneAutoriteDeSessionRevoqueeEstRefuseeImmediatement() throws Exception {
        operateur.setAdministrateurPlateforme(false); utilisateurs.saveAndFlush(operateur);
        mvc.perform(admin(get("/plateforme/boutiques"))).andExpect(status().isForbidden());
        mvc.perform(admin(post(acces())).with(csrf()).param("actif", "false").param("accesOffert", "true"))
                .andExpect(status().isForbidden());
        assertThat(externe.isActif()).isTrue();
    }

    @Test
    void lesEcransPlateformeRendentLesFormulairesSansAfficherDEmpreintes() throws Exception {
        mvc.perform(admin(get("/plateforme"))).andExpect(redirectedUrl("/plateforme/boutiques"));
        for (String route : List.of("/plateforme/boutiques", "/plateforme/boutiques/nouvelle",
                "/plateforme/boutiques/" + externe.getId())) {
            mvc.perform(admin(get(route))).andExpect(status().isOk())
                    .andExpect(content().string(containsString("name=\"_csrf\"")))
                    .andExpect(content().string(not(containsString(gerant.getMotDePasse()))))
                    .andExpect(content().string(not(containsString(SECRET))));
        }
        var resultat = mvc.perform(admin(get("/plateforme/boutiques/" + externe.getId()))).andReturn();
        @SuppressWarnings("unchecked")
        List<Utilisateur> comptes = (List<Utilisateur>) resultat.getModelAndView().getModel().get("utilisateurs");
        assertThat(comptes).extracting(Utilisateur::getEmail).containsExactly(GERANT);
        assertThat(comptes).allMatch(compte -> compte.getMotDePasse() == null);
        assertThat(utilisateurs.findByEmail(GERANT).orElseThrow().getMotDePasse()).isNotNull();
    }

    @Test
    void laCreationHttpDonneUnGerantSansDroitPlateformeDansUneNouvelleBoutique() throws Exception {
        long avant = boutiques.count();
        mvc.perform(admin(creation()).with(csrf())).andExpect(status().is3xxRedirection());
        Utilisateur nouveau = utilisateurs.findByEmail(EMAIL_NOUVEAU).orElseThrow();
        assertThat(boutiques.count()).isEqualTo(avant + 1);
        assertThat(nouveau.getRole()).isEqualTo(Role.ADMIN);
        assertThat(nouveau.isAdministrateurPlateforme()).isFalse();
        assertThat(nouveau.getBoutique().getId()).isNotIn(principale.getId(), externe.getId());
        assertThat(nouveau.getBoutique().getNom()).isEqualTo("Nouvelle boutique HTTP");
        assertThat(nouveau.getBoutique().isActif()).isTrue();
        assertThat(nouveau.getBoutique().isAccesOffert()).isTrue();
        assertThat(passwords.matches(SECRET, nouveau.getMotDePasse())).isTrue();

        Categorie categorie = new Categorie(); categorie.setNom("Rayon externe"); categorie.setBoutique(externe);
        categorie = categories.saveAndFlush(categorie);
        Produit produit = new Produit(); produit.setNom("Produit confidentiel externe"); produit.setBoutique(externe);
        produit.setReference("PRODUIT-EXTERNE-HTTP");
        produit.setCategorie(categorie); produit.setQuantite(5L);
        produit.setPrixAchat(new BigDecimal("10")); produit.setPrixVente(new BigDecimal("20"));
        produit = produits.saveAndFlush(produit);
        var session = connexion(EMAIL_NOUVEAU, "/");
        mvc.perform(get("/produits").session(session)).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Produit confidentiel externe"))));
        mvc.perform(get("/produits/modifier/" + produit.getId()).session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/plateforme/boutiques").session(session)).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "role", "administrateurPlateforme", "boutique.id", "actif"})
    void lesChampsInternesSontRefusesALaCreation(String champ) throws Exception {
        long avant = boutiques.count();
        mvc.perform(admin(creation()).with(csrf()).param(champ, "true")).andExpect(status().isBadRequest());
        assertThat(boutiques.count()).isEqualTo(avant);
        assertThat(utilisateurs.existsByEmail(EMAIL_NOUVEAU)).isFalse();
    }

    @Test
    void creationEtModificationExigentUnJetonCsrfValide() throws Exception {
        long avant = boutiques.count();
        mvc.perform(admin(creation())).andExpect(status().isForbidden());
        mvc.perform(admin(creation()).with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        mvc.perform(admin(post(acces())).param("actif", "false").param("accesOffert", "true"))
                .andExpect(status().isForbidden());
        assertThat(boutiques.count()).isEqualTo(avant);
        assertThat(externe.isActif()).isTrue();
    }

    @Test
    void lesErreursDeCreationRetirentLeMotDePasseDuModeleEtDuBindingResult() throws Exception {
        MvcResult invalide = mvc.perform(admin(creation(EMAIL_NOUVEAU, "Court7")).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        sansSecret(invalide, "Court7");
        MvcResult doublon = mvc.perform(admin(creation(GERANT, SECRET)).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        sansSecret(doublon, SECRET);
        assertThat(utilisateurs.existsByEmail(EMAIL_NOUVEAU)).isFalse();
    }

    @Test
    void unFormulaireDAccesVidePartielOuInvalideNeChangeRien() throws Exception {
        mvc.perform(admin(post(acces())).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(admin(post(acces())).with(csrf()).param("accesOffert", "true")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(acces())).with(csrf()).param("actif", "faux-invalide").param("accesOffert", "true"))
                .andExpect(status().isBadRequest());
        mvc.perform(admin(post(acces())).with(csrf()).param("actif", "false").param("accesOffert", "true")
                .param("administrateurPlateforme", "true")).andExpect(status().isBadRequest());
        assertThat(externe.isActif()).isTrue();
        assertThat(externe.isAccesOffert()).isFalse();
    }

    @Test
    void laSuspensionBloqueUneSessionExistanteEtLaReactivationRendLAcces() throws Exception {
        MockHttpSession session = connexion(GERANT, "/");
        mvc.perform(get("/produits").session(session)).andExpect(status().isOk());
        mvc.perform(admin(post(acces())).with(csrf()).param("actif", "false").param("accesOffert", "true"))
                .andExpect(status().is3xxRedirection());
        for (String route : List.of("/", "/produits", "/ventes", "/clients", "/factures/liste", "/caisse/journal", "/register")) {
            mvc.perform(get(route).session(session)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/login").with(csrf()).param("username", GERANT).param("password", SECRET))
                .andExpect(redirectedUrl("/login?error"));
        var contexte = SecurityContextHolder.getContext();
        try {
            var temporaire = SecurityContextHolder.createEmptyContext();
            temporaire.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    GERANT, "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            SecurityContextHolder.setContext(temporaire);
            assertThatThrownBy(boutiqueService::boutiqueCourante).isInstanceOfSatisfying(ResponseStatusException.class,
                    erreur -> assertThat(erreur.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        } finally { SecurityContextHolder.setContext(contexte); }
        mvc.perform(admin(post(acces())).with(csrf()).param("actif", "true").param("accesOffert", "true"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/produits").session(session)).andExpect(status().isOk());
    }

    @Test
    void lOperateurPeutReactiverSaBoutiqueSansContournerSaSuspension() throws Exception {
        principale.setActif(false); boutiques.saveAndFlush(principale);
        MockHttpSession session = connexion(OPERATEUR, "/plateforme/boutiques");
        mvc.perform(get("/plateforme/boutiques/" + principale.getId()).session(session)).andExpect(status().isOk());
        mvc.perform(get("/produits").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/plateforme/boutiques/" + principale.getId() + "/acces").session(session).with(csrf())
                .param("actif", "true").param("accesOffert", "false")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/produits").session(session)).andExpect(status().isOk());
    }

    @Test
    void unIdentifiantDeBoutiqueAbsentRetourne404() throws Exception {
        mvc.perform(admin(get("/plateforme/boutiques/" + Long.MAX_VALUE))).andExpect(status().isNotFound());
        mvc.perform(admin(post("/plateforme/boutiques/" + Long.MAX_VALUE + "/acces")).with(csrf())
                .param("actif", "false").param("accesOffert", "false")).andExpect(status().isNotFound());
    }

    private void sansSecret(MvcResult resultat, String motDePasse) throws Exception {
        var modele = resultat.getModelAndView().getModel();
        assertThat(((CreationBoutiqueForm) modele.get("formulaire")).getPassword()).isNull();
        BindingResult erreurs = (BindingResult) modele.get(BindingResult.MODEL_KEY_PREFIX + "formulaire");
        assertThat(erreurs.getFieldErrors("password")).allMatch(erreur -> erreur.getRejectedValue() == null);
        assertThat(resultat.getResponse().getContentAsString()).doesNotContain(motDePasse);
    }
    private MockHttpSession connexion(String email, String destination) throws Exception {
        var resultat = mvc.perform(post("/login").with(csrf()).param("username", email).param("password", SECRET))
                .andExpect(redirectedUrl(destination)).andReturn();
        return (MockHttpSession) resultat.getRequest().getSession(false);
    }
    private MockHttpServletRequestBuilder creation() {
        return creation("  GERANT-NOUVELLE-HTTP@EXAMPLE.TEST  ", SECRET);
    }
    private MockHttpServletRequestBuilder creation(String email, String motDePasse) {
        return post("/plateforme/boutiques").param("nomBoutique", "  Nouvelle boutique HTTP  ")
                .param("nom", "Nouveau gérant").param("email", email)
                .param("password", motDePasse).param("accesOffert", "true");
    }
    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder requete) {
        return requete.with(user(OPERATEUR).roles("ADMIN", "PLATEFORME_ADMIN"));
    }
    private String acces() { return "/plateforme/boutiques/" + externe.getId() + "/acces"; }
    private Boutique boutique(String nom) {
        Boutique boutique = new Boutique(); boutique.setNom(nom); return boutiques.saveAndFlush(boutique);
    }
    private Utilisateur compte(String email, Boutique boutique, boolean plateforme) {
        Utilisateur compte = new Utilisateur(); compte.setNom("Compte " + email); compte.setEmail(email);
        compte.setBoutique(boutique); compte.setRole(Role.ADMIN); compte.setDateInscription(LocalDate.now());
        compte.setAdministrateurPlateforme(plateforme); compte.setMotDePasse(passwords.encode(SECRET));
        return utilisateurs.saveAndFlush(compte);
    }
}
