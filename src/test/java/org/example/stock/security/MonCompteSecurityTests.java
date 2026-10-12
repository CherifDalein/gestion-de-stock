package org.example.stock.security;

import org.example.stock.enums.Role;
import org.example.stock.form.ChangementMotDePasseForm;
import org.example.stock.model.Boutique;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.BoutiqueRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.example.stock.service.ComptePersonnel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MonCompteSecurityTests {
    private static final String ROUTE = "/mon-compte";
    private static final String POST = ROUTE + "/mot-de-passe";
    private static final String ANCIEN = "Ancien42";
    private static final String NOUVEAU = "Une nouvelle phrase secrète";
    private static String hashInitial;
    @Autowired MockMvc mvc;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired PasswordEncoder encoder;
    Boutique boutiqueA, boutiqueB;
    Utilisateur gerant, caissier, autre;

    @BeforeEach
    void preparer() {
        if (hashInitial == null) hashInitial = encoder.encode(ANCIEN);
        boutiqueA = boutique("Commerce Alpha"); boutiqueB = boutique("Commerce Beta confidentiel");
        gerant = compte("gerant-mon-compte@example.test", boutiqueA, Role.ADMIN);
        caissier = compte("caissier-mon-compte@example.test", boutiqueA, Role.CAISSIER);
        autre = compte("autre-mon-compte@example.test", boutiqueB, Role.ADMIN);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void chaqueRoleConsulteSonCompteSansSecret(Role role) throws Exception {
        Utilisateur courant = role == Role.ADMIN ? gerant : caissier;
        MvcResult resultat = mvc.perform(comme(courant, get(ROUTE)))
                .andExpect(status().isOk()).andExpect(view().name("dashboard"))
                .andExpect(model().attribute("view", "compte/mon-compte"))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString(courant.getEmail())))
                .andExpect(content().string(not(containsString(hashInitial))))
                .andExpect(content().string(not(containsString(ANCIEN))))
                .andExpect(content().string(not(containsString(autre.getEmail())))).andReturn();
        ComptePersonnel compte = (ComptePersonnel) resultat.getModelAndView().getModel().get("compte");
        assertThat(compte.email()).isEqualTo(courant.getEmail()); assertThat(compte.nomBoutique()).isEqualTo(boutiqueA.getNom());
        assertThat(compte.role()).isEqualTo(role);
        ChangementMotDePasseForm formulaire = (ChangementMotDePasseForm) resultat.getModelAndView().getModel().get("changementMotDePasse");
        assertThat(formulaire.getAncienMotDePasse()).isNull(); assertThat(formulaire.getNouveauMotDePasse()).isNull();
        assertThat(formulaire.getConfirmationMotDePasse()).isNull();
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void leChangementFermeLaSessionEtNeModifieQueLeMotDePassePersonnel(Role role) throws Exception {
        Utilisateur courant = role == Role.ADMIN ? gerant : caissier;
        MockHttpSession session = new MockHttpSession();
        mvc.perform(comme(courant, saisie(ANCIEN, NOUVEAU, NOUVEAU)).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login?passwordChanged"));
        assertThat(session.isInvalid()).isTrue();
        Utilisateur apres = utilisateurs.findById(courant.getId()).orElseThrow();
        assertThat(encoder.matches(NOUVEAU, apres.getMotDePasse())).isTrue();
        assertThat(encoder.matches(ANCIEN, apres.getMotDePasse())).isFalse();
        assertThat(apres.getNom()).isEqualTo(courant.getNom()); assertThat(apres.getEmail()).isEqualTo(courant.getEmail());
        assertThat(apres.getRole()).isEqualTo(role); assertThat(apres.getBoutique().getId()).isEqualTo(boutiqueA.getId());
        assertThat(apres.getDateInscription()).isEqualTo(courant.getDateInscription());
        assertThat(apres.isActif()).isTrue(); assertThat(apres.isAdministrateurPlateforme()).isFalse();
        assertThat(utilisateurs.findById(autre.getId()).orElseThrow().getMotDePasse()).isEqualTo(hashInitial);
        Utilisateur autreLocal = role == Role.ADMIN ? caissier : gerant;
        assertThat(utilisateurs.findById(autreLocal.getId()).orElseThrow().getMotDePasse()).isEqualTo(hashInitial);
    }

    @Test
    void unVisiteurDoitSeConnecterEtLePostExigeCsrf() throws Exception {
        mvc.perform(get(ROUTE)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(saisie(ANCIEN, NOUVEAU, NOUVEAU).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(comme(gerant, saisie(ANCIEN, NOUVEAU, NOUVEAU))).andExpect(status().isForbidden());
        mvc.perform(comme(gerant, saisie(ANCIEN, NOUVEAU, NOUVEAU)).with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        assertThat(utilisateurs.findById(gerant.getId()).orElseThrow().getMotDePasse()).isEqualTo(hashInitial);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "email", "motDePasse", "boutique.id", "role", "actif", "administrateurPlateforme", "_nouveauMotDePasse", "!nouveauMotDePasse"})
    void aucunParametreNePermetDeChoisirUneAutreCibleOuDeModifierSesDroits(String champ) throws Exception {
        mvc.perform(comme(gerant, saisie(ANCIEN, NOUVEAU, NOUVEAU)).param(champ, autre.getId().toString()).with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(utilisateurs.findById(gerant.getId()).orElseThrow().getMotDePasse()).isEqualTo(hashInitial);
        assertThat(utilisateurs.findById(autre.getId()).orElseThrow().getMotDePasse()).isEqualTo(hashInitial);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ancien-incorrect", "ancien-vide", "nouveau-court", "nouveau-trop-long", "confirmation-vide", "confirmation-differente", "confirmation-espaces"})
    void lesErreursSontAfficheesSansRenvoyerAucunMotDePasse(String cas) throws Exception {
        String ancien = ANCIEN, nouveau = NOUVEAU, confirmation = NOUVEAU, champ;
        switch (cas) {
            case "ancien-incorrect" -> { ancien = "Saisie incorrecte"; champ = "ancienMotDePasse"; }
            case "ancien-vide" -> { ancien = " \t "; champ = "ancienMotDePasse"; }
            case "nouveau-court" -> { nouveau = "Petit42"; confirmation = nouveau; champ = "nouveauMotDePasse"; }
            case "nouveau-trop-long" -> { nouveau = "é".repeat(37); confirmation = nouveau; champ = "nouveauMotDePasse"; }
            case "confirmation-vide" -> { confirmation = ""; champ = "confirmationMotDePasse"; }
            case "confirmation-differente" -> { confirmation = "Une phrase différente"; champ = "confirmationMotDePasse"; }
            case "confirmation-espaces" -> { confirmation = " " + nouveau; champ = "confirmationMotDePasse"; }
            default -> throw new IllegalArgumentException(cas);
        }
        MvcResult resultat = mvc.perform(comme(gerant, saisie(ancien, nouveau, confirmation)).with(csrf()))
                .andExpect(status().isOk()).andExpect(view().name("dashboard"))
                .andExpect(model().attributeHasFieldErrors("changementMotDePasse", champ))
                .andExpect(content().string(not(containsString(hashInitial))))
                .andExpect(content().string(not(containsString(ANCIEN))))
                .andExpect(content().string(not(containsString(NOUVEAU))))
                .andExpect(content().string(not(containsString("Saisie incorrecte"))))
                .andExpect(content().string(not(containsString("Petit42"))))
                .andExpect(content().string(not(containsString("Une phrase différente")))).andReturn();
        verifierAucunSecret(resultat);
        assertThat(utilisateurs.findById(gerant.getId()).orElseThrow().getMotDePasse()).isEqualTo(hashInitial);
    }

    @Test
    void lesEntetesNeSontJamaisUtilisesPourAlimenterLesMotsDePasse() throws Exception {
        MvcResult resultat = mvc.perform(comme(gerant, post(POST)).with(csrf())
                        .header("ancienMotDePasse", ANCIEN).header("nouveauMotDePasse", NOUVEAU)
                        .header("confirmationMotDePasse", NOUVEAU))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("changementMotDePasse", "ancienMotDePasse", "nouveauMotDePasse", "confirmationMotDePasse"))
                .andReturn();
        verifierAucunSecret(resultat);
        assertThat(utilisateurs.findById(gerant.getId()).orElseThrow().getMotDePasse()).isEqualTo(hashInitial);
    }

    @Test
    void unCompteDesactiveNePeutPlusConsulterOuModifierSonCompte() throws Exception {
        gerant.setActif(false); utilisateurs.saveAndFlush(gerant);
        mvc.perform(comme(gerant, get(ROUTE))).andExpect(status().isForbidden());
        mvc.perform(comme(gerant, saisie(ANCIEN, NOUVEAU, NOUVEAU)).with(csrf())).andExpect(status().isForbidden());
        assertThat(utilisateurs.findById(gerant.getId()).orElseThrow().getMotDePasse()).isEqualTo(hashInitial);
    }

    @Test
    void uneBoutiqueSuspendueBloqueLeCompteOrdinaire() throws Exception {
        boutiqueA.setActif(false); boutiques.saveAndFlush(boutiqueA);
        mvc.perform(comme(gerant, get(ROUTE))).andExpect(status().isForbidden());
        mvc.perform(comme(gerant, saisie(ANCIEN, NOUVEAU, NOUVEAU)).with(csrf())).andExpect(status().isForbidden());
        assertThat(utilisateurs.findById(gerant.getId()).orElseThrow().getMotDePasse()).isEqualTo(hashInitial);
    }

    @Test
    void leComptePlateformeResteDisponibleSiSaBoutiqueEstSuspendue() throws Exception {
        gerant.setAdministrateurPlateforme(true); utilisateurs.saveAndFlush(gerant);
        boutiqueA.setActif(false); boutiques.saveAndFlush(boutiqueA);
        mvc.perform(get(ROUTE).with(user(gerant.getEmail()).roles("ADMIN", "PLATEFORME_ADMIN"))).andExpect(status().isOk());
        mvc.perform(saisie(ANCIEN, NOUVEAU, NOUVEAU).with(user(gerant.getEmail()).roles("ADMIN", "PLATEFORME_ADMIN")).with(csrf()))
                .andExpect(redirectedUrl("/login?passwordChanged"));
        assertThat(encoder.matches(NOUVEAU, utilisateurs.findById(gerant.getId()).orElseThrow().getMotDePasse())).isTrue();
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().isActif()).isFalse();
    }

    private static void verifierAucunSecret(MvcResult resultat) {
        var model = resultat.getModelAndView().getModel();
        ChangementMotDePasseForm formulaire = (ChangementMotDePasseForm) model.get("changementMotDePasse");
        assertThat(formulaire.getAncienMotDePasse()).isNull(); assertThat(formulaire.getNouveauMotDePasse()).isNull();
        assertThat(formulaire.getConfirmationMotDePasse()).isNull();
        BindingResult erreurs = (BindingResult) model.get(BindingResult.MODEL_KEY_PREFIX + "changementMotDePasse");
        assertThat(erreurs.hasErrors()).isTrue();
        assertThat(erreurs.getFieldErrors()).allSatisfy(erreur -> { assertThat(erreur.getRejectedValue()).isNull(); assertThat(erreur.getArguments()).isNull(); });
    }
    private MockHttpServletRequestBuilder comme(Utilisateur compte, MockHttpServletRequestBuilder requete) {
        return requete.with(user(compte.getEmail()).roles(compte.getRole().name()));
    }
    private MockHttpServletRequestBuilder saisie(String ancien, String nouveau, String confirmation) {
        return post(POST).param("ancienMotDePasse", ancien).param("nouveauMotDePasse", nouveau).param("confirmationMotDePasse", confirmation);
    }
    private Boutique boutique(String nom) { Boutique boutique = new Boutique(); boutique.setNom(nom); return boutiques.saveAndFlush(boutique); }
    private Utilisateur compte(String email, Boutique boutique, Role role) {
        Utilisateur compte = new Utilisateur(); compte.setNom("Compte " + role); compte.setEmail(email); compte.setMotDePasse(hashInitial);
        compte.setDateInscription(LocalDate.of(2026, 10, 13)); compte.setBoutique(boutique); compte.setRole(role); return utilisateurs.saveAndFlush(compte);
    }
}
