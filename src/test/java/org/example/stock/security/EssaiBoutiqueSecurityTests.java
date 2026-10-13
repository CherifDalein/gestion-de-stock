package org.example.stock.security;

import org.example.stock.enums.Role;
import org.example.stock.model.Boutique;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.BoutiqueRepository;
import org.example.stock.repository.ParametresPlateformeRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.example.stock.service.BoutiqueService;
import org.example.stock.service.EssaiBoutiqueService;
import org.example.stock.service.ParametresPlateformeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EssaiBoutiqueSecurityTests {
    @Autowired MockMvc mvc;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ParametresPlateformeRepository parametres;
    @Autowired EssaiBoutiqueService essais;
    @Autowired ParametresPlateformeService reglages;
    @Autowired BoutiqueService boutiqueService;
    @Autowired PasswordEncoder passwords;
    Boutique boutique;
    Utilisateur gerant, plateforme;

    @BeforeEach
    void preparer() {
        parametres.deleteAll();
        boutique = new Boutique(); boutique.setNom("Boutique essai");
        boutique = boutiques.saveAndFlush(boutique);
        gerant = compte("essai-gerant@example.test", false);
        plateforme = compte("essai-plateforme@example.test", true);
    }

    @Test
    void lesVisiteursVoientLaPresentationEtLaDureeSansDonneesDeBoutique() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(view().name("accueil"))
                .andExpect(model().attribute("dureeEssaiJours", 30)).andExpect(model().attributeDoesNotExist("boutique", "utilisateur", "soldeCaisse"));
        mvc.perform(get("/accueil").with(user(gerant.getEmail()).roles("ADMIN")))
                .andExpect(view().name("accueil")).andExpect(model().attributeDoesNotExist("boutique", "utilisateur"));
        mvc.perform(get("/plateforme/parametres")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/acces-boutique")).andExpect(status().is3xxRedirection());
    }

    @Test
    void leReglageEstReserveAuVraiAdministrateurPlateforme() throws Exception {
        mvc.perform(get("/plateforme/parametres").with(user(gerant.getEmail()).roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(get("/plateforme/parametres").with(user(gerant.getEmail()).roles("ADMIN", "PLATEFORME_ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/plateforme/parametres").with(user(plateforme.getEmail()).roles("ADMIN", "PLATEFORME_ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(model().attributeExists("parametresPlateforme"));
    }

    @Test
    void changerLaDureeNeRaccourcitPasUnEssaiCommence() throws Exception {
        essais.demarrerEssai(boutique); boutiques.saveAndFlush(boutique);
        var debut = boutique.getEssaiDebut(); var fin = boutique.getEssaiFin();
        mvc.perform(post("/plateforme/parametres").with(user(plateforme.getEmail()).roles("ADMIN", "PLATEFORME_ADMIN"))
                        .with(csrf()).param("dureeEssaiJours", "7"))
                .andExpect(redirectedUrl("/plateforme/parametres"));
        assertThat(essais.dureeEssaiJours()).isEqualTo(7);
        assertThat(boutique.getEssaiDebut()).isEqualTo(debut); assertThat(boutique.getEssaiFin()).isEqualTo(fin);
        Boutique nouvelle = new Boutique(); essais.demarrerEssai(nouvelle);
        assertThat(nouvelle.getEssaiFin()).isEqualTo(nouvelle.getEssaiDebut().plusDays(7));
        mvc.perform(get("/accueil")).andExpect(model().attribute("dureeEssaiJours", 7));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "3651", "abc", ""})
    void lesDureesInvalidesNeSontPasEnregistrees(String valeur) throws Exception {
        var requete = post("/plateforme/parametres").with(user(plateforme.getEmail()).roles("ADMIN", "PLATEFORME_ADMIN"))
                .with(csrf()).param("dureeEssaiJours", valeur);
        // Les erreurs de conversion sont rejetées par la protection générale du binding.
        mvc.perform(requete).andExpect(status().is(valeur.equals("abc") ? 400 : 200));
        assertThat(parametres.count()).isZero();
    }

    @Test
    void csrfEtChampsSupplementairesSontRefuses() throws Exception {
        mvc.perform(post("/plateforme/parametres").with(user(plateforme.getEmail()).roles("ADMIN", "PLATEFORME_ADMIN"))
                .param("dureeEssaiJours", "20")).andExpect(status().isForbidden());
        mvc.perform(post("/plateforme/parametres").with(user(plateforme.getEmail()).roles("ADMIN", "PLATEFORME_ADMIN"))
                .with(csrf()).param("dureeEssaiJours", "20").param("accesOffert", "true")).andExpect(status().isBadRequest());
        mvc.perform(post("/plateforme/parametres").with(user(plateforme.getEmail()).roles("ADMIN", "PLATEFORME_ADMIN"))
                .with(csrf()).param("dureeEssaiJours", "20", "30")).andExpect(status().isBadRequest());
        assertThat(parametres.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/produits", "/clients", "/ventes", "/achats", "/boutique/parametres", "/utilisateurs", "/caisse/journal", "/factures/liste"})
    void unEssaiExpireBloqueToutesLesPagesMetierMemeDansUneSessionOuverte(String route) throws Exception {
        expirer();
        mvc.perform(get(route).with(user(gerant.getEmail()).roles("ADMIN")))
                .andExpect(redirectedUrl("/acces-boutique"));
    }

    @Test
    void lesEcrituresSontBloqueesEtLeCompteResteAccessible() throws Exception {
        expirer();
        mvc.perform(post("/boutique/parametres").with(user(gerant.getEmail()).roles("ADMIN")).with(csrf())
                        .param("nom", "Nom modifié par essai terminé")).andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/acces-boutique"));
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().getNom()).isEqualTo("Boutique essai");
        mvc.perform(get("/mon-compte").with(user(gerant.getEmail()).roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/acces-boutique").with(user(gerant.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(view().name("compte/essai-termine"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/plateforme/parametres").with(user(plateforme.getEmail()).roles("ADMIN", "PLATEFORME_ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void uneBoutiqueExistanteEtUnAccesOffertGardentLeurAcces() throws Exception {
        assertThat(essais.estExpire(boutique)).isFalse();
        mvc.perform(get("/produits").with(user(gerant.getEmail()).roles("ADMIN"))).andExpect(status().isOk());
        expirer(); boutique.setAccesOffert(true); boutiques.saveAndFlush(boutique);
        mvc.perform(get("/produits").with(user(gerant.getEmail()).roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/acces-boutique").with(user(gerant.getEmail()).roles("ADMIN"))).andExpect(redirectedUrl("/"));
        boutique.setActif(false); boutiques.saveAndFlush(boutique);
        mvc.perform(get("/produits").with(user(gerant.getEmail()).roles("ADMIN"))).andExpect(status().isForbidden());
    }

    @Test
    void laProtectionMetierEtLesReglagesVerifientAussiLesAppelsDirects() {
        expirer();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                gerant.getEmail(), null, AuthorityUtils.createAuthorityList("ROLE_ADMIN", "ROLE_PLATEFORME_ADMIN")));
        try {
            assertThatThrownBy(() -> boutiqueService.idBoutiqueCourante()).hasMessageContaining("essai gratuit");
            assertThatThrownBy(() -> reglages.modifierDureeEssai(5)).hasMessageContaining("inaccessible");
        } finally { SecurityContextHolder.clearContext(); }
    }

    private void expirer() {
        var maintenant = LocalDateTime.now(ZoneOffset.UTC);
        boutique.setEssaiDebut(maintenant.minusDays(31)); boutique.setEssaiFin(maintenant.minusDays(1));
        boutiques.saveAndFlush(boutique);
    }

    private Utilisateur compte(String email, boolean estPlateforme) {
        var compte = new Utilisateur(); compte.setBoutique(boutique); compte.setNom("Gérant essai");
        compte.setEmail(email); compte.setRole(Role.ADMIN); compte.setAdministrateurPlateforme(estPlateforme);
        compte.setMotDePasse(passwords.encode("Une phrase valide de test"));
        return utilisateurs.saveAndFlush(compte);
    }
}
