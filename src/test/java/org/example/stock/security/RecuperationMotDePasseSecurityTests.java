package org.example.stock.security;

import org.example.stock.form.ReinitialisationMotDePasseForm;
import org.example.stock.service.RecuperationMotDePasseException;
import org.example.stock.service.RecuperationMotDePasseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:recuperation-security;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class RecuperationMotDePasseSecurityTests {
    private static final String JETON = "A".repeat(43);
    private static final String NOUVEAU = "NouveauMotDePasse42";
    @Autowired MockMvc mvc;
    @MockitoBean RecuperationMotDePasseService service;

    @BeforeEach void preparer() { when(service.lienValide(JETON)).thenReturn(true); }

    @Test
    void lesPagesSontPubliquesEtInterdisentCacheEtReferrer() throws Exception {
        mvc.perform(get("/mot-de-passe-oublie")).andExpect(status().isOk())
                .andExpect(view().name("compte/mot-de-passe-oublie"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        mvc.perform(get("/reinitialiser-mot-de-passe").param("jeton", JETON)).andExpect(status().isOk())
                .andExpect(view().name("compte/reinitialiser-mot-de-passe"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        verify(service, never()).reinitialiser(any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/mot-de-passe-oublie", "/reinitialiser-mot-de-passe"})
    void lesPostsPublicsExigentQuandMemeCsrf(String route) throws Exception {
        mvc.perform(post(route)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void lesEmailsValidesRecupereSontNormalisesEtLaReponseEstGenerique() throws Exception {
        mvc.perform(post("/mot-de-passe-oublie").with(csrf()).param("email", " COMPTE@EXAMPLE.TEST ")
                .header("Host", "attaque.example.test").header("X-Forwarded-For", "adresse-factice"))
                .andExpect(redirectedUrl("/mot-de-passe-oublie?envoye"));
        verify(service).demander(eq("compte@example.test"), eq("127.0.0.1"));
        mvc.perform(post("/mot-de-passe-oublie").with(csrf()).param("email", "absent@example.test"))
                .andExpect(redirectedUrl("/mot-de-passe-oublie?envoye"));
        String texte = mvc.perform(get("/mot-de-passe-oublie").param("envoye", ""))
                .andExpect(model().attribute("demandeEnvoyee", true)).andReturn().getResponse().getContentAsString();
        assertThat(texte.contains("compte@example.test")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "utilisateurId", "role", "actif", "boutique.id", "motDePasse", "emailSupprime"})
    void lesChampsInconnusSontRefusesSurLaDemande(String champ) throws Exception {
        mvc.perform(post("/mot-de-passe-oublie").with(csrf()).param("email", "compte@example.test").param(champ, "1"))
                .andExpect(status().isBadRequest());
        verify(service, never()).demander(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "utilisateurId", "email", "role", "actif", "boutique.id", "motDePasse"})
    void lesChampsInconnusSontRefusesSurLaConsommation(String champ) throws Exception {
        mvc.perform(post("/reinitialiser-mot-de-passe").with(csrf()).param("jeton", JETON)
                .param("nouveauMotDePasse", NOUVEAU).param("confirmationMotDePasse", NOUVEAU).param(champ, "1"))
                .andExpect(status().isBadRequest());
        verify(service, never()).reinitialiser(any(), anyString());
    }

    @Test
    void lesEntetesNePeuventPasRemplirUnChampDeMotDePasse() throws Exception {
        String html = mvc.perform(post("/reinitialiser-mot-de-passe").with(csrf()).param("jeton", JETON)
                .header("nouveauMotDePasse", NOUVEAU).header("confirmationMotDePasse", NOUVEAU))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("reinitialisationMotDePasse", "nouveauMotDePasse"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html.contains(NOUVEAU)).isFalse();
        verify(service, never()).reinitialiser(any(), anyString());
    }

    @Test
    void lesErreursNeReaffichentPasLesMotsDePasseNiLeursValeursRejetees() throws Exception {
        var resultat = mvc.perform(post("/reinitialiser-mot-de-passe").with(csrf()).param("jeton", JETON)
                .param("nouveauMotDePasse", "court-secret").param("confirmationMotDePasse", "court-secret"))
                .andExpect(status().isOk()).andReturn();
        assertThat(resultat.getResponse().getContentAsString().contains("court-secret")).isFalse();
        var binding = (org.springframework.validation.BindingResult) resultat.getModelAndView().getModel()
                .get(org.springframework.validation.BindingResult.MODEL_KEY_PREFIX + "reinitialisationMotDePasse");
        assertThat(binding.getFieldErrors().stream().allMatch(e -> e.getRejectedValue() == null)).isTrue();
    }

    @Test
    void uneErreurMetierDeConfirmationResteLisibleSansReafficherDeSecret() throws Exception {
        doThrow(new RecuperationMotDePasseException("confirmationMotDePasse", "Les deux nouveaux mots de passe doivent être identiques."))
                .when(service).reinitialiser(any(), anyString());
        String html = mvc.perform(post("/reinitialiser-mot-de-passe").with(csrf()).param("jeton", JETON)
                .param("nouveauMotDePasse", NOUVEAU).param("confirmationMotDePasse", "DifferentMotDePasse42"))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("reinitialisationMotDePasse", "confirmationMotDePasse"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html.contains(NOUVEAU)).isFalse(); assertThat(html.contains("DifferentMotDePasse42")).isFalse();
    }

    @Test
    void unLienInvalideNestPasRecopieDansLeFormulaire() throws Exception {
        var resultat = mvc.perform(get("/reinitialiser-mot-de-passe").param("jeton", "lien-invalide"))
                .andExpect(status().isOk()).andExpect(model().attribute("lienInvalide", true)).andReturn();
        ReinitialisationMotDePasseForm formulaire = (ReinitialisationMotDePasseForm) resultat.getModelAndView().getModel().get("reinitialisationMotDePasse");
        assertThat(formulaire.getJeton()).isNull();
    }

    @Test
    void laReinitialisationRenvoieALaConnexionSansAuthentifierLeCompte() throws Exception {
        mvc.perform(post("/reinitialiser-mot-de-passe").with(csrf()).param("jeton", JETON)
                .param("nouveauMotDePasse", NOUVEAU).param("confirmationMotDePasse", NOUVEAU))
                .andExpect(redirectedUrl("/login?passwordReset"))
                .andExpect(org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated());
        verify(service).reinitialiser(any(), eq("127.0.0.1"));
    }
}
