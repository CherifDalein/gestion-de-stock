package org.example.stock.security;

import org.example.stock.enums.Role;
import org.example.stock.model.Boutique;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.BoutiqueRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MonCompteSessionsTests {
    private static final String ANCIEN = "Ancienne phrase de passe";
    private static final String NOUVEAU = "Nouvelle phrase de passe";
    @Autowired MockMvc mvc;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired BoutiqueRepository boutiques;
    @Autowired PasswordEncoder passwords;
    Utilisateur compte;

    @BeforeEach
    void preparer() {
        Boutique boutique = new Boutique(); boutique.setNom("Boutique sessions");
        boutiques.saveAndFlush(boutique);
        compte = new Utilisateur(); compte.setNom("Compte sessions"); compte.setEmail("sessions-compte@example.test");
        compte.setRole(Role.CAISSIER); compte.setBoutique(boutique); compte.setMotDePasse(passwords.encode(ANCIEN));
        utilisateurs.saveAndFlush(compte);
    }

    @Test
    void laSessionResteValideApresEffacementDuSecretDAuthentification() throws Exception {
        MockHttpSession session = connexion(ANCIEN);
        SecurityContext contexte = (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(contexte.getAuthentication().getCredentials()).isNull();
        assertThat(contexte.getAuthentication().getPrincipal()).isInstanceOf(StockUserDetails.class);
        StockUserDetails principal = (StockUserDetails) contexte.getAuthentication().getPrincipal();
        assertThat(principal.getPassword()).isNull();
        assertThat(principal.correspond(compte)).isTrue();
        mvc.perform(get("/mon-compte").session(session)).andExpect(status().isOk());
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void leChangementDeconnecteAussiUneAutreSessionEtExigeLeNouveauMotDePasse() throws Exception {
        MockHttpSession principale = connexion(ANCIEN);
        MockHttpSession autre = connexion(ANCIEN);
        mvc.perform(post("/mon-compte/mot-de-passe").session(principale).with(csrf())
                        .param("ancienMotDePasse", ANCIEN).param("nouveauMotDePasse", NOUVEAU)
                        .param("confirmationMotDePasse", NOUVEAU))
                .andExpect(redirectedUrl("/login?passwordChanged"));
        assertThat(principale.isInvalid()).isTrue();
        mvc.perform(get("/mon-compte").session(autre)).andExpect(redirectedUrl("/login?expired"));
        assertThat(autre.isInvalid()).isTrue();
        mvc.perform(post("/login").with(csrf()).param("username", compte.getEmail()).param("password", ANCIEN))
                .andExpect(redirectedUrl("/login?error"));
        MockHttpSession nouvelle = connexion(NOUVEAU);
        mvc.perform(get("/mon-compte").session(nouvelle)).andExpect(status().isOk());
    }

    @Test
    void uneSessionAncienneNePeutPasExecuterUneNouvelleModification() throws Exception {
        MockHttpSession session = connexion(ANCIEN);
        compte.setMotDePasse(passwords.encode(NOUVEAU)); utilisateurs.saveAndFlush(compte);
        String hash = compte.getMotDePasse();
        mvc.perform(post("/mon-compte/mot-de-passe").session(session).with(csrf())
                        .param("ancienMotDePasse", NOUVEAU).param("nouveauMotDePasse", "Autre phrase de passe valide")
                        .param("confirmationMotDePasse", "Autre phrase de passe valide"))
                .andExpect(redirectedUrl("/login?expired"));
        assertThat(compte.getMotDePasse()).isEqualTo(hash);
    }

    @Test
    void laDesactivationDuCompteConserveLeRefusDAccesExistant() throws Exception {
        MockHttpSession session = connexion(ANCIEN);
        compte.setActif(false); utilisateurs.saveAndFlush(compte);
        mvc.perform(get("/mon-compte").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void unNouveauCompteAvecLeMemeEmailNeRecuperePasLaSessionSupprimee() throws Exception {
        MockHttpSession session = connexion(ANCIEN);
        Boutique boutique = compte.getBoutique();
        String email = compte.getEmail(); String hash = compte.getMotDePasse();
        utilisateurs.delete(compte); utilisateurs.flush();
        Utilisateur remplace = new Utilisateur(); remplace.setBoutique(boutique); remplace.setRole(Role.CAISSIER);
        remplace.setEmail(email); remplace.setNom("Autre compte"); remplace.setMotDePasse(hash);
        utilisateurs.saveAndFlush(remplace);
        mvc.perform(get("/mon-compte").session(session)).andExpect(redirectedUrl("/login?expired"));
    }

    @Test
    void uneSessionInvalideePeutToujoursAfficherLaPagePubliqueDeConnexion() throws Exception {
        MockHttpSession session = connexion(ANCIEN);
        compte.setMotDePasse(passwords.encode(NOUVEAU)); utilisateurs.saveAndFlush(compte);
        mvc.perform(get("/login").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Connexion")));
        assertThat(session.isInvalid()).isTrue();
    }

    private MockHttpSession connexion(String motDePasse) throws Exception {
        var resultat = mvc.perform(post("/login").with(csrf()).param("username", compte.getEmail()).param("password", motDePasse))
                .andExpect(redirectedUrl("/")).andReturn();
        return (MockHttpSession) resultat.getRequest().getSession(false);
    }
}
