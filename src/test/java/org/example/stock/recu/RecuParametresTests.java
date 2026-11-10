package org.example.stock.recu;

import org.example.stock.enums.FormatRecu;
import org.example.stock.enums.Role;
import org.example.stock.model.Boutique;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.BoutiqueRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Lot 4, tâche E : réglages des reçus dans les paramètres de la boutique (format, bouton d'impression). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:recu-parametres;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class RecuParametresTests {
    private static final String ROUTE = "/boutique/parametres";
    private static final String GERANT = "recu-param-gerant@example.test", CAISSIER = "recu-param-caissier@example.test", AUTRE = "recu-param-autre@example.test";

    @Autowired MockMvc mvc;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    Boutique a, b;

    @BeforeEach
    void preparer() {
        utilisateurs.deleteAll(); boutiques.deleteAll();
        a = boutique("Boutique A reçus"); b = boutique("Boutique B reçus");
        compte(GERANT, Role.ADMIN, a); compte(CAISSIER, Role.CAISSIER, a); compte(AUTRE, Role.ADMIN, b);
    }

    @Test
    void parDefautLesReglagesSontA4EtBoutonPropose() throws Exception {
        assertThat(a.getFormatRecu()).isEqualTo(FormatRecu.A4);
        assertThat(a.isProposerImpressionRecu()).isTrue();
        mvc.perform(gerant(get(ROUTE))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Format des reçus")))
                .andExpect(content().string(containsString("Proposer l")))
                .andExpect(content().string(containsString("Ticket 80 mm")));
    }

    @Test
    void leGerantEnregistreLeFormatEtLeBoutonPourSaSeuleBoutique() throws Exception {
        mvc.perform(gerant(envoi("TICKET_80", "false"))).andExpect(redirectedUrl(ROUTE)).andExpect(flash().attributeExists("success"));
        Boutique relue = boutiques.findById(a.getId()).orElseThrow();
        assertThat(relue.getFormatRecu()).isEqualTo(FormatRecu.TICKET_80);
        assertThat(relue.isProposerImpressionRecu()).isFalse();
        Boutique autre = boutiques.findById(b.getId()).orElseThrow();
        assertThat(autre.getFormatRecu()).isEqualTo(FormatRecu.A4);
        assertThat(autre.isProposerImpressionRecu()).isTrue();
        mvc.perform(gerant(get(ROUTE))).andExpect(status().isOk());
        mvc.perform(gerant(envoi("A4", "true"))).andExpect(redirectedUrl(ROUTE));
        Boutique remise = boutiques.findById(a.getId()).orElseThrow();
        assertThat(remise.getFormatRecu()).isEqualTo(FormatRecu.A4);
        assertThat(remise.isProposerImpressionRecu()).isTrue();
    }

    @Test
    void unEnvoiSansLesChampsDeRecuConserveLesReglages() throws Exception {
        mvc.perform(gerant(envoi("TICKET_80", "false"))).andExpect(redirectedUrl(ROUTE));
        mvc.perform(gerant(post(ROUTE).param("nom", "Nouveau nom").with(csrf()))).andExpect(redirectedUrl(ROUTE));
        Boutique relue = boutiques.findById(a.getId()).orElseThrow();
        assertThat(relue.getNom()).isEqualTo("Nouveau nom");
        assertThat(relue.getFormatRecu()).isEqualTo(FormatRecu.TICKET_80);
        assertThat(relue.isProposerImpressionRecu()).isFalse();
    }

    @Test
    void unFormatInconnuOuDoubleEstRefuseSansRienChanger() throws Exception {
        mvc.perform(gerant(envoi("A5", "true"))).andExpect(status().isBadRequest());
        mvc.perform(gerant(envoi("ticket_80", "true"))).andExpect(status().isBadRequest());
        mvc.perform(gerant(envoi("A4", "peut-être"))).andExpect(status().isBadRequest());
        mvc.perform(gerant(envoi("A4", "true").param("formatRecu", "TICKET_80"))).andExpect(status().isBadRequest());
        Boutique relue = boutiques.findById(a.getId()).orElseThrow();
        assertThat(relue.getFormatRecu()).isEqualTo(FormatRecu.A4);
        assertThat(relue.isProposerImpressionRecu()).isTrue();
    }

    @Test
    void unChampInconnuEstRefuseEn400() throws Exception {
        mvc.perform(gerant(envoi("TICKET_80", "false").param("boutiqueId", "" + b.getId()))).andExpect(status().isBadRequest());
        mvc.perform(gerant(envoi("TICKET_80", "false").param("formatRecuAutre", "x"))).andExpect(status().isBadRequest());
        assertThat(boutiques.findById(a.getId()).orElseThrow().getFormatRecu()).isEqualTo(FormatRecu.A4);
        assertThat(boutiques.findById(b.getId()).orElseThrow().getFormatRecu()).isEqualTo(FormatRecu.A4);
    }

    @Test
    void lesReglagesRestentReservesAuGerant() throws Exception {
        mvc.perform(post(ROUTE).param("formatRecu", "TICKET_80").with(csrf()).with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        mvc.perform(post(ROUTE).param("formatRecu", "TICKET_80").with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(boutiques.findById(a.getId()).orElseThrow().getFormatRecu()).isEqualTo(FormatRecu.A4);
    }

    private MockHttpServletRequestBuilder envoi(String format, String proposer) {
        return post(ROUTE).param("nom", a.getNom()).param("formatRecu", format).param("proposerImpressionRecu", proposer).with(csrf());
    }

    private MockHttpServletRequestBuilder gerant(MockHttpServletRequestBuilder requete) { return requete.with(user(GERANT).roles("ADMIN")); }

    private Boutique boutique(String nom) { Boutique x = new Boutique(); x.setNom(nom); return boutiques.saveAndFlush(x); }

    private void compte(String email, Role role, Boutique boutique) {
        Utilisateur u = new Utilisateur(); u.setBoutique(boutique); u.setEmail(email); u.setNom(email); u.setRole(role); u.setActif(true); utilisateurs.saveAndFlush(u);
    }
}
