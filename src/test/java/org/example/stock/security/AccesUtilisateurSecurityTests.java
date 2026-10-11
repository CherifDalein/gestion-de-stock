package org.example.stock.security;

import org.example.stock.enums.Role;
import org.example.stock.model.Boutique;
import org.example.stock.model.MouvementCaisse;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.BoutiqueRepository;
import org.example.stock.repository.MouvementCaisseRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.example.stock.service.BoutiqueService;
import org.example.stock.service.PlateformeService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
class AccesUtilisateurSecurityTests {
    private static final String SECRET = "Secret-collaborateur-test-2026!";
    @Autowired MockMvc mvc;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired MouvementCaisseRepository mouvements;
    @Autowired PasswordEncoder passwords;
    @Autowired BoutiqueService boutiqueService;
    @Autowired PlateformeService plateformeService;
    Boutique boutiqueA, boutiqueB;
    Utilisateur operateur, gerant, caissier, gerantB;
    MouvementCaisse mouvement;

    @BeforeEach
    void preparer() {
        boutiqueA = boutique("Boutique accès A"); boutiqueB = boutique("Boutique accès B confidentielle");
        operateur = compte("operateur-acces@example.test", boutiqueA, Role.ADMIN, true);
        gerant = compte("gerant-acces@example.test", boutiqueA, Role.ADMIN, false);
        caissier = compte("caissier-acces@example.test", boutiqueA, Role.CAISSIER, false);
        gerantB = compte("gerant-acces-b@example.test", boutiqueB, Role.ADMIN, false);
        mouvement = new MouvementCaisse(); mouvement.setBoutique(boutiqueA); mouvement.setUtilisateur(caissier);
        mouvement.setDateMouvement(LocalDateTime.now()); mouvement.setMontant(new BigDecimal("1200"));
        mouvement.setType("ENTREE"); mouvement.setSource("TEST"); mouvement.setMotif("Historique du collaborateur");
        mouvement = mouvements.saveAndFlush(mouvement);
    }

    @Test
    void leGerantNeVoitQueLesComptesDeSaBoutiqueSansEmpreinte() throws Exception {
        var resultat = mvc.perform(comme(gerant, get("/utilisateurs"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Désactiver")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(not(containsString(gerantB.getEmail()))))
                .andExpect(content().string(not(containsString(caissier.getMotDePasse())))).andReturn();
        @SuppressWarnings("unchecked")
        List<Utilisateur> comptes = (List<Utilisateur>) resultat.getModelAndView().getModel().get("utilisateurs");
        assertThat(comptes).extracting(Utilisateur::getEmail).containsExactlyInAnyOrder(operateur.getEmail(), gerant.getEmail(), caissier.getEmail());
        assertThat(comptes).allMatch(compte -> compte.getMotDePasse() == null && compte.isActif());
    }

    @Test
    void unCaissierEtUnVisiteurNePeuventPasGererLesAcces() throws Exception {
        mvc.perform(comme(caissier, get("/utilisateurs"))).andExpect(status().isForbidden());
        mvc.perform(comme(caissier, changement(caissier, false)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/utilisateurs")).andExpect(redirectedUrl("http://localhost/login"));
        mvc.perform(changement(caissier, false).with(csrf())).andExpect(redirectedUrl("http://localhost/login"));
        assertThat(utilisateurs.findById(caissier.getId()).orElseThrow().isActif()).isTrue();
    }

    @Test
    void laDesactivationBloqueConnexionEtSessionSansChangerLeCompteNiSonHistorique() throws Exception {
        MockHttpSession session = connecter(caissier);
        String hash = caissier.getMotDePasse(); LocalDate date = caissier.getDateInscription();
        long nombreComptes = utilisateurs.count(), nombreMouvements = mouvements.count();
        mvc.perform(get("/produits").session(session)).andExpect(status().isOk());
        mvc.perform(comme(gerant, changement(caissier, false)).with(csrf())).andExpect(redirectedUrl("/utilisateurs"));
        for (String route : List.of("/", "/produits", "/ventes", "/clients", "/factures/liste")) {
            mvc.perform(get(route).session(session)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/ventes/enregistrer").session(session).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/login").with(csrf()).param("username", caissier.getEmail()).param("password", SECRET))
                .andExpect(redirectedUrl("/login?error"));
        Utilisateur bloque = utilisateurs.findById(caissier.getId()).orElseThrow();
        assertThat(bloque.isActif()).isFalse(); assertThat(bloque.getMotDePasse()).isEqualTo(hash);
        assertThat(bloque.getRole()).isEqualTo(Role.CAISSIER); assertThat(bloque.getDateInscription()).isEqualTo(date);
        assertThat(bloque.getBoutique().getId()).isEqualTo(boutiqueA.getId());
        assertThat(utilisateurs.count()).isEqualTo(nombreComptes); assertThat(mouvements.count()).isEqualTo(nombreMouvements);
        assertThat(mouvements.findById(mouvement.getId()).orElseThrow().getUtilisateur().getId()).isEqualTo(caissier.getId());
        mvc.perform(comme(gerant, get("/produits"))).andExpect(status().isOk());
        mvc.perform(comme(gerantB, get("/produits"))).andExpect(status().isOk());
        mvc.perform(comme(gerant, changement(caissier, true)).with(csrf())).andExpect(redirectedUrl("/utilisateurs"));
        connecter(caissier);
        mvc.perform(get("/produits").session(session)).andExpect(status().isOk());
    }

    @Test
    void unGerantNePeutNiSeBloquerNiChangerUnComptePlateformeOuEtranger() throws Exception {
        mvc.perform(comme(gerant, changement(gerant, false)).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(comme(gerant, changement(operateur, false)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(comme(gerant, changement(gerantB, false)).with(csrf())).andExpect(status().isNotFound());
        assertThat(utilisateurs.findById(gerant.getId()).orElseThrow().isActif()).isTrue();
        assertThat(utilisateurs.findById(operateur.getId()).orElseThrow().isActif()).isTrue();
        assertThat(utilisateurs.findById(gerantB.getId()).orElseThrow().isActif()).isTrue();
    }

    @Test
    void laPlateformeGereLesComptesDuDetailAvecControleDeLeurBoutique() throws Exception {
        mvc.perform(comme(operateur, changementPlateforme(boutiqueB, gerantB, false)).with(csrf()))
                .andExpect(redirectedUrl("/plateforme/boutiques/" + boutiqueB.getId()));
        assertThat(utilisateurs.findById(gerantB.getId()).orElseThrow().isActif()).isFalse();
        mvc.perform(comme(operateur, get("/plateforme/boutiques/" + boutiqueB.getId())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Réactiver")));
        mvc.perform(comme(operateur, changementPlateforme(boutiqueA, gerantB, true)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(comme(operateur, changementPlateforme(boutiqueA, operateur, false)).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(comme(gerant, changementPlateforme(boutiqueA, caissier, false)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(comme(operateur, changementPlateforme(boutiqueB, gerantB, true)).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void lesDeuxParcoursExigentUnJetonCsrfValide() throws Exception {
        for (boolean plateforme : List.of(false, true)) {
            var acteur = plateforme ? operateur : gerant;
            var sansJeton = plateforme ? changementPlateforme(boutiqueA, caissier, false) : changement(caissier, false);
            mvc.perform(comme(acteur, sansJeton)).andExpect(status().isForbidden());
            var invalide = plateforme ? changementPlateforme(boutiqueA, caissier, false) : changement(caissier, false);
            mvc.perform(comme(acteur, invalide).with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        }
        assertThat(utilisateurs.findById(caissier.getId()).orElseThrow().isActif()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "utilisateurId", "boutique.id", "role", "motDePasse", "administrateurPlateforme"})
    void lesChampsInternesNePeuventPasEtreModifiesParLesBoutons(String champ) throws Exception {
        mvc.perform(comme(gerant, changement(caissier, false)).with(csrf()).param(champ, "true"))
                .andExpect(status().isBadRequest());
        mvc.perform(comme(operateur, changementPlateforme(boutiqueA, caissier, false)).with(csrf()).param(champ, "true"))
                .andExpect(status().isBadRequest());
        assertThat(utilisateurs.findById(caissier.getId()).orElseThrow().isActif()).isTrue();
    }

    @Test
    void unEtatAbsentOuInvalideNeChangePasLeCompte() throws Exception {
        for (String route : List.of("/utilisateurs/" + caissier.getId() + "/acces",
                "/plateforme/boutiques/" + boutiqueA.getId() + "/utilisateurs/" + caissier.getId() + "/acces")) {
            mvc.perform(comme(operateur, post(route)).with(csrf())).andExpect(status().isBadRequest());
            mvc.perform(comme(operateur, post(route)).with(csrf()).param("actif", "valeur-invalide"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(utilisateurs.findById(caissier.getId()).orElseThrow().isActif()).isTrue();
    }

    @Test
    void unOperateurDesactiveNePeutPasContournerLeBlocageGraceASonDroitPlateforme() throws Exception {
        MockHttpSession session = connecter(operateur);
        operateur.setActif(false); utilisateurs.saveAndFlush(operateur);
        for (String route : List.of("/", "/utilisateurs", "/plateforme", "/plateforme/boutiques")) {
            mvc.perform(get(route).session(session)).andExpect(status().isForbidden());
        }
        mvc.perform(comme(operateur, changementPlateforme(boutiqueA, caissier, false)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/login").with(csrf()).param("username", operateur.getEmail()).param("password", SECRET))
                .andExpect(redirectedUrl("/login?error"));
        var precedent = SecurityContextHolder.getContext();
        try {
            var temporaire = SecurityContextHolder.createEmptyContext();
            temporaire.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(operateur.getEmail(), "",
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_PLATEFORME_ADMIN"))));
            SecurityContextHolder.setContext(temporaire);
            assertThatThrownBy(boutiqueService::boutiqueCourante).isInstanceOfSatisfying(ResponseStatusException.class,
                    erreur -> assertThat(erreur.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
            assertThatThrownBy(plateformeService::verifierAdministrationPlateforme).isInstanceOfSatisfying(ResponseStatusException.class,
                    erreur -> assertThat(erreur.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        } finally { SecurityContextHolder.setContext(precedent); }
    }

    private MockHttpSession connecter(Utilisateur compte) throws Exception {
        var resultat = mvc.perform(post("/login").with(csrf()).param("username", compte.getEmail()).param("password", SECRET))
                .andExpect(redirectedUrl(compte.isAdministrateurPlateforme() ? "/plateforme/boutiques" : "/")).andReturn();
        return (MockHttpSession) resultat.getRequest().getSession(false);
    }
    private MockHttpServletRequestBuilder comme(Utilisateur compte, MockHttpServletRequestBuilder requete) {
        return requete.with(user(compte.getEmail()).roles(compte.isAdministrateurPlateforme()
                ? new String[]{compte.getRole().name(), "PLATEFORME_ADMIN"} : new String[]{compte.getRole().name()}));
    }
    private MockHttpServletRequestBuilder changement(Utilisateur compte, boolean actif) {
        return post("/utilisateurs/" + compte.getId() + "/acces").param("actif", Boolean.toString(actif));
    }
    private MockHttpServletRequestBuilder changementPlateforme(Boutique boutique, Utilisateur compte, boolean actif) {
        return post("/plateforme/boutiques/" + boutique.getId() + "/utilisateurs/" + compte.getId() + "/acces")
                .param("actif", Boolean.toString(actif));
    }
    private Boutique boutique(String nom) {
        Boutique boutique = new Boutique(); boutique.setNom(nom); return boutiques.saveAndFlush(boutique);
    }
    private Utilisateur compte(String email, Boutique boutique, Role role, boolean plateforme) {
        Utilisateur compte = new Utilisateur(); compte.setEmail(email); compte.setNom("Compte " + email);
        compte.setBoutique(boutique); compte.setRole(role); compte.setAdministrateurPlateforme(plateforme);
        compte.setDateInscription(LocalDate.now()); compte.setMotDePasse(passwords.encode(SECRET));
        return utilisateurs.saveAndFlush(compte);
    }
}
