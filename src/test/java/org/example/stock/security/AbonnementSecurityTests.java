package org.example.stock.security;

import org.example.stock.enums.*;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.example.stock.service.VueAbonnement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AbonnementSecurityTests {
    @Autowired MockMvc mvc;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired HistoriqueAbonnementRepository historique;
    @Autowired PasswordEncoder passwords;
    Boutique a, b;
    Utilisateur gerant, caissier, operateur;

    @BeforeEach
    void preparer() {
        a = boutique("Boutique abonnement A"); b = boutique("Boutique B confidentielle");
        gerant = compte("gerant-abo@example.test", Role.ADMIN, a, false);
        caissier = compte("caissier-abo@example.test", Role.CAISSIER, a, false);
        operateur = compte("operateur-abo@example.test", Role.ADMIN, b, true);
    }

    @Test
    void leGerantVoitUniquementSaBoutiqueEtAucuneCommandeDePlateforme() throws Exception {
        var resultat = mvc.perform(comme(gerant, get("/mon-abonnement").param("boutiqueId", b.getId().toString())))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString(a.getNom())))
                .andExpect(content().string(not(containsString(b.getNom()))))
                .andExpect(content().string(not(containsString(gerant.getMotDePasse()))))
                .andExpect(content().string(not(containsString("id=\"activationMotif\""))))
                .andExpect(content().string(not(containsString("id=\"prolongationJours\"")))).andReturn();
        var vue = (VueAbonnement) resultat.getModelAndView().getModel().get("vueAbonnement");
        assertThat(vue.situation().boutiqueId()).isEqualTo(a.getId());
        assertThat(vue.situation().statut()).isEqualTo(StatutAbonnement.ACCES_EXISTANT);
        assertThat(vue.historique()).isEmpty();
    }

    @Test
    void leCaissierLeCompteDesactiveEtLeVisiteurNeConsultentPasLabonnement() throws Exception {
        mvc.perform(get("/mon-abonnement")).andExpect(status().is3xxRedirection());
        mvc.perform(comme(caissier, get("/mon-abonnement"))).andExpect(status().isForbidden());
        mvc.perform(get("/mon-abonnement").with(user(caissier.getEmail()).roles("ADMIN"))).andExpect(status().isForbidden());
        gerant.setActif(false); utilisateurs.saveAndFlush(gerant);
        mvc.perform(comme(gerant, get("/mon-abonnement"))).andExpect(status().isForbidden());
    }

    @Test
    void leGerantPeutLireLaSituationExpireeEtSuspendue() throws Exception {
        expirer(a);
        mvc.perform(comme(gerant, get("/produits"))).andExpect(redirectedUrl("/acces-boutique"));
        mvc.perform(comme(gerant, get("/mon-abonnement"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Accès expiré")));
        a.setActif(false); boutiques.saveAndFlush(a);
        mvc.perform(comme(gerant, get("/mon-abonnement"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Boutique suspendue")));
        mvc.perform(comme(gerant, get("/produits"))).andExpect(status().isForbidden());
    }

    @Test
    void unAbonnementActifRouvreLeMetierApresLessaiSansModifierLesSuspensions() throws Exception {
        expirer(a);
        mvc.perform(plateforme(activation(a, UUID.randomUUID().toString(), "Activation manuelle")))
                .andExpect(redirectedUrl("/plateforme/boutiques/" + a.getId()));
        assertThat(a.getAbonnementFin()).isAfter(LocalDateTime.now(ZoneOffset.UTC));
        mvc.perform(comme(gerant, get("/produits"))).andExpect(status().isOk());
        mvc.perform(comme(gerant, get("/mon-abonnement"))).andExpect(content().string(containsString("Abonnement actif")));
        a.setActif(false); boutiques.saveAndFlush(a);
        mvc.perform(plateforme(activation(a, UUID.randomUUID().toString(), "Nouveau mois")))
                .andExpect(status().is3xxRedirection());
        assertThat(a.isActif()).isFalse();
        mvc.perform(comme(gerant, get("/produits"))).andExpect(status().isForbidden());
    }

    @Test
    void labonnementExpireBloqueLeMetierMemePourUneBoutiqueSansAncienEssai() throws Exception {
        a.setAbonnementDebut(maintenant().minusMonths(2)); a.setAbonnementFin(maintenant().minusMonths(1));
        boutiques.saveAndFlush(a);
        mvc.perform(comme(gerant, get("/produits"))).andExpect(redirectedUrl("/acces-boutique"));
        mvc.perform(comme(gerant, post("/boutique/parametres")).with(csrf()).param("nom", "Changement interdit"))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/acces-boutique"));
        assertThat(a.getNom()).isEqualTo("Boutique abonnement A");
        mvc.perform(comme(gerant, get("/acces-boutique"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("/mon-abonnement")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"essai", "activer"})
    void seulUnVeritableAdministrateurPlateformePeutAgir(String action) throws Exception {
        var route = "/plateforme/boutiques/" + a.getId() + "/abonnement/" + action;
        mvc.perform(comme(gerant, post(route)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(route).with(user(gerant.getEmail()).roles("ADMIN", "PLATEFORME_ADMIN")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(plateforme(post(route))).andExpect(status().isForbidden());
        assertThat(historique.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"actif", "accesOffert", "abonnementFin", "boutiqueId", "id", "acteurId"})
    void lesChampsDePrivilegeEtDeDateSontRefuses(String champ) throws Exception {
        mvc.perform(plateforme(activation(a, UUID.randomUUID().toString(), "Motif")).param(champ, "1"))
                .andExpect(status().isBadRequest());
        assertThat(historique.count()).isZero(); assertThat(a.getAbonnementFin()).isNull();
    }

    @Test
    void lesChampsDupliquesEtLesDatesVenantDesEntetesSontProteges() throws Exception {
        mvc.perform(plateforme(activation(a, UUID.randomUUID().toString(), "Motif")).param("motif", "Autre"))
                .andExpect(status().isBadRequest());
        mvc.perform(plateforme(post("/plateforme/boutiques/" + a.getId() + "/abonnement/activer")).with(csrf())
                        .header("jetonAction", UUID.randomUUID().toString()).header("motif", "Entête"))
                .andExpect(status().isOk());
        assertThat(historique.count()).isZero();
    }

    @Test
    void uneActivationRejoueeNaccordePasUnSecondMois() throws Exception {
        String jeton = UUID.randomUUID().toString();
        a.setEssaiDebut(maintenant().minusDays(1)); a.setEssaiFin(maintenant().plusDays(29)); boutiques.saveAndFlush(a);
        var finEssai = a.getEssaiFin();
        mvc.perform(plateforme(activation(a, jeton, "Un seul mois"))).andExpect(status().is3xxRedirection());
        var fin = a.getAbonnementFin();
        assertThat(a.getAbonnementDebut()).isEqualTo(finEssai); assertThat(fin).isEqualTo(finEssai.plusMonths(1));
        mvc.perform(plateforme(activation(a, jeton, "Un seul mois"))).andExpect(status().is3xxRedirection());
        assertThat(a.getAbonnementFin()).isEqualTo(fin); assertThat(historique.count()).isEqualTo(1);
        mvc.perform(plateforme(activation(a, jeton, "Un motif changé"))).andExpect(status().isConflict());
        mvc.perform(plateforme(activation(b, jeton, "Un seul mois"))).andExpect(status().isConflict());
        assertThat(b.getAbonnementFin()).isNull(); assertThat(historique.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "3651", ""})
    void lesProlongationsInvalidesNeModifientPasLessai(String jours) throws Exception {
        expirer(a); var fin = a.getEssaiFin();
        mvc.perform(plateforme(post("/plateforme/boutiques/" + a.getId() + "/abonnement/essai")).with(csrf())
                        .param("jetonAction", UUID.randomUUID().toString()).param("jours", jours))
                .andExpect(status().isOk());
        assertThat(a.getEssaiFin()).isEqualTo(fin); assertThat(historique.count()).isZero();
    }

    @Test
    void lesChangementsOffertsEtSuspendusSontHistorisesEtLeMotifEstEchappe() throws Exception {
        mvc.perform(plateforme(post("/plateforme/boutiques/" + a.getId() + "/acces")).with(csrf())
                .param("actif", "false").param("accesOffert", "true")).andExpect(status().is3xxRedirection());
        assertThat(historique.count()).isEqualTo(2);
        mvc.perform(plateforme(post("/plateforme/boutiques/" + a.getId() + "/acces")).with(csrf())
                .param("actif", "false").param("accesOffert", "true")).andExpect(status().is3xxRedirection());
        assertThat(historique.count()).isEqualTo(2);
        mvc.perform(plateforme(activation(a, UUID.randomUUID().toString(), "<script>injection()</script>")))
                .andExpect(status().is3xxRedirection());
        mvc.perform(comme(gerant, get("/mon-abonnement"))).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<script>injection()</script>"))));
    }

    @Test
    void historiquePagineEtLimiteALaBoutique() throws Exception {
        for (int i = 0; i < 23; i++) evenement(a, "A-" + i);
        evenement(b, "Historique secret B"); historique.flush();
        var resultat = mvc.perform(comme(gerant, get("/mon-abonnement"))).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Historique secret B")))).andReturn();
        var vue = (VueAbonnement) resultat.getModelAndView().getModel().get("vueAbonnement");
        assertThat(vue.historique()).hasSize(20); assertThat(vue.pageSuivante()).isTrue();
        var suivant = mvc.perform(comme(gerant, get("/mon-abonnement").param("page", "1")))
                .andExpect(status().isOk()).andReturn();
        assertThat(((VueAbonnement) suivant.getModelAndView().getModel().get("vueAbonnement")).historique()).hasSize(3);
        mvc.perform(comme(gerant, get("/mon-abonnement").param("page", "-1"))).andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder activation(Boutique boutique, String jeton, String motif) {
        return post("/plateforme/boutiques/" + boutique.getId() + "/abonnement/activer").with(csrf())
                .param("jetonAction", jeton).param("motif", motif);
    }
    private MockHttpServletRequestBuilder plateforme(MockHttpServletRequestBuilder requete) {
        return requete.with(user(operateur.getEmail()).roles("ADMIN", "PLATEFORME_ADMIN"));
    }
    private MockHttpServletRequestBuilder comme(Utilisateur u, MockHttpServletRequestBuilder requete) {
        return requete.with(user(u.getEmail()).roles(u.getRole().name()));
    }
    private Boutique boutique(String nom) { Boutique v = new Boutique(); v.setNom(nom); return boutiques.saveAndFlush(v); }
    private Utilisateur compte(String email, Role role, Boutique boutique, boolean plateforme) {
        var u = new Utilisateur(); u.setNom("Gérant abonnement"); u.setEmail(email); u.setRole(role);
        u.setBoutique(boutique); u.setAdministrateurPlateforme(plateforme); u.setMotDePasse(passwords.encode("Phrase de test abonnement"));
        return utilisateurs.saveAndFlush(u);
    }
    private void expirer(Boutique boutique) { boutique.setEssaiDebut(maintenant().minusDays(31)); boutique.setEssaiFin(maintenant().minusDays(1)); boutiques.saveAndFlush(boutique); }
    private LocalDateTime maintenant() { return LocalDateTime.now(ZoneOffset.UTC).withNano(0); }
    private void evenement(Boutique boutique, String motif) {
        var e = new HistoriqueAbonnement(); e.setBoutique(boutique); e.setActeur(operateur); e.setActeurNom(operateur.getNom());
        e.setAction(ActionAbonnement.ACCES_OFFERT); e.setCreeLe(maintenant()); e.setMotif(motif);
        e.setJetonAction(UUID.randomUUID().toString()); historique.save(e);
    }
}
