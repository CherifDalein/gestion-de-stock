package org.example.stock.security;

import org.example.stock.form.ConfirmationInscriptionForm;
import org.example.stock.service.EssaiBoutiqueService;
import org.example.stock.service.InscriptionBoutiqueException;
import org.example.stock.service.InscriptionBoutiqueService;
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
import org.springframework.validation.BindingResult;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:inscription-security;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class InscriptionBoutiqueSecurityTests {
    private static final String JETON = "A".repeat(43);
    private static final String MOT_DE_PASSE = "Une phrase de passe complète";
    @Autowired MockMvc mvc;
    @MockitoBean InscriptionBoutiqueService service;
    @MockitoBean EssaiBoutiqueService essais;

    @BeforeEach void preparer() {
        when(service.emailPourLien(JETON)).thenReturn(Optional.of("gerant@example.test"));
        when(essais.dureeEssaiJours()).thenReturn(30);
    }

    @Test
    void lesPagesPubliquesAffichentLEssaiConfigureEtProtegentLesLiens() throws Exception {
        mvc.perform(get("/inscription")).andExpect(status().isOk()).andExpect(view().name("inscription/demande"))
                .andExpect(model().attribute("dureeEssaiJours", 30))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        mvc.perform(get("/inscription/confirmer").param("jeton", JETON)).andExpect(status().isOk())
                .andExpect(view().name("inscription/confirmer"))
                .andExpect(model().attribute("emailInscription", "gerant@example.test"))
                .andExpect(model().attribute("lienInvalide", false))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        verify(service, never()).confirmer(any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/inscription", "/inscription/confirmer"})
    void lesPostsPublicsExigentCsrf(String route) throws Exception {
        mvc.perform(post(route)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void laDemandeNormaliseLEmailEtUtiliseLIpDuServeurAvecUneReponseGenerique() throws Exception {
        mvc.perform(post("/inscription").with(csrf()).param("email", " GERANT@EXAMPLE.TEST ")
                .header("Host", "attaque.example.test").header("X-Forwarded-For", "adresse-factice"))
                .andExpect(redirectedUrl("/inscription?envoye"));
        verify(service).demander("gerant@example.test", "127.0.0.1");
        mvc.perform(post("/inscription").with(csrf()).param("email", "deja-utilise@example.test"))
                .andExpect(redirectedUrl("/inscription?envoye"));
        var resultat = mvc.perform(get("/inscription").param("envoye", ""))
                .andExpect(model().attribute("demandeEnvoyee", true)).andReturn();
        assertThat(resultat.getResponse().getContentAsString().contains("gerant@example.test")).isFalse();
        assertThat(resultat.getResponse().getContentAsString().contains("deja-utilise@example.test")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "actif", "administrateurPlateforme", "accesOffert", "boutique.id", "utilisateurId", "essaiFin"})
    void laDemandeRefuseLesChampsDePermissionsOuDeDonneesMetier(String champ) throws Exception {
        mvc.perform(post("/inscription").with(csrf()).param("email", "gerant@example.test").param(champ, "1"))
                .andExpect(status().isBadRequest());
        verify(service, never()).demander(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "email", "actif", "administrateurPlateforme", "accesOffert", "boutique.id", "utilisateurId", "essaiDebut", "essaiFin"})
    void laConfirmationRefuseLEmailEtLesChampsDePermissions(String champ) throws Exception {
        mvc.perform(postValide().param(champ, "1")).andExpect(status().isBadRequest());
        verify(service, never()).confirmer(any(), anyString());
    }

    @Test
    void lesChampsDupliquesSontRefusesAuLieuDEtreFusionnes() throws Exception {
        mvc.perform(post("/inscription").with(csrf()).param("email", "a@example.test", "b@example.test"))
                .andExpect(status().isBadRequest());
        mvc.perform(postValide().param("nom", "Premier", "Second")).andExpect(status().isBadRequest());
        verify(service, never()).demander(anyString(), anyString());
        verify(service, never()).confirmer(any(), anyString());
    }

    @Test
    void lesEntetesNePeuventPasFournirLesChampsDeCreation() throws Exception {
        String html = mvc.perform(post("/inscription/confirmer").with(csrf()).param("jeton", JETON)
                .header("nom", "Nom depuis entete").header("nomBoutique", "Boutique depuis entete")
                .header("nouveauMotDePasse", MOT_DE_PASSE).header("confirmationMotDePasse", MOT_DE_PASSE))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("confirmationInscription", "nom", "nomBoutique", "nouveauMotDePasse"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html.contains(MOT_DE_PASSE)).isFalse();
        verify(service, never()).confirmer(any(), anyString());
    }

    @Test
    void lesSecretsEtValeursRejeteesSontEffacesApresUneErreurDeValidation() throws Exception {
        var resultat = mvc.perform(post("/inscription/confirmer").with(csrf()).param("jeton", JETON)
                .param("nom", "Gérant").param("nomBoutique", "Boutique").param("nouveauMotDePasse", "court-secret")
                .param("confirmationMotDePasse", "court-secret"))
                .andExpect(status().isOk()).andReturn();
        assertThat(resultat.getResponse().getContentAsString().contains("court-secret")).isFalse();
        var formulaire = (ConfirmationInscriptionForm) resultat.getModelAndView().getModel().get("confirmationInscription");
        assertThat(formulaire.getNouveauMotDePasse()).isNull(); assertThat(formulaire.getConfirmationMotDePasse()).isNull();
        var binding = (BindingResult) resultat.getModelAndView().getModel().get(BindingResult.MODEL_KEY_PREFIX + "confirmationInscription");
        assertThat(binding.getFieldErrors().stream().allMatch(erreur -> erreur.getRejectedValue() == null)).isTrue();
    }

    @Test
    void lesErreursMetierSontLisiblesSansReafficherLeMotDePasse() throws Exception {
        doThrow(new InscriptionBoutiqueException("confirmationMotDePasse", "Les deux mots de passe doivent être identiques."))
                .when(service).confirmer(any(), anyString());
        String html = mvc.perform(postValide()).andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("confirmationInscription", "confirmationMotDePasse"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html.contains(MOT_DE_PASSE)).isFalse();
    }

    @Test
    void unLienInvalideNestPasReflechiEtNeRevelePasDEmail() throws Exception {
        var resultat = mvc.perform(get("/inscription/confirmer").param("jeton", "lien-invalide"))
                .andExpect(status().isOk()).andExpect(model().attribute("lienInvalide", true)).andReturn();
        var formulaire = (ConfirmationInscriptionForm) resultat.getModelAndView().getModel().get("confirmationInscription");
        assertThat(formulaire.getJeton()).isNull();
        assertThat(resultat.getResponse().getContentAsString().contains("lien-invalide")).isFalse();
    }

    @Test
    void laConfirmationRenvoieALaConnexionSansOuvrirDeSession() throws Exception {
        mvc.perform(postValide()).andExpect(redirectedUrl("/login?boutiqueCreated"))
                .andExpect(org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated());
        verify(service).confirmer(any(), eq("127.0.0.1"));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postValide() {
        return post("/inscription/confirmer").with(csrf()).param("jeton", JETON).param("nom", "Gérant")
                .param("nomBoutique", "Boutique").param("nouveauMotDePasse", MOT_DE_PASSE).param("confirmationMotDePasse", MOT_DE_PASSE);
    }
}
