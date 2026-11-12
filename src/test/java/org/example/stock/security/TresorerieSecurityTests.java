package org.example.stock.security;

import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.enums.Role;
import org.example.stock.form.OuvertureCaisseForm;
import org.example.stock.model.Boutique;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.BoutiqueRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.example.stock.service.CaisseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.stock.enums.TypeCompteTresorerie.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:tresorerie-http;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class TresorerieSecurityTests {
    @Autowired MockMvc mvc;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired CaisseService caisse;
    Utilisateur gerant, caissier;

    @BeforeEach void preparer() {
        var b = new Boutique(); b.setNom("Boutique trésorerie " + UUID.randomUUID()); b = boutiques.saveAndFlush(b);
        gerant = compte(b, Role.ADMIN); caissier = compte(b, Role.CAISSIER);
        connecter();
        try {
            for (var compte : TypeCompteTresorerie.values()) {
                var f = new OuvertureCaisseForm(); f.setComptePaiement(compte.name()); f.setMontant(new BigDecimal("1000"));
                caisse.initialiser(f);
            }
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test void lesFormulairesEtLeJournalAffichentLesTroisComptesAvecCsrf() throws Exception {
        for (var chemin : List.of("/caisse/transfert", "/caisse/depense", "/caisse/journal")) {
            mvc.perform(commeGerant(get(chemin))).andExpect(status().isOk())
                    .andExpect(content().string(containsString("Caisse")))
                    .andExpect(content().string(containsString("Orange Money")))
                    .andExpect(content().string(containsString("Banque")))
                    .andExpect(model().attributeExists("soldesParCompte"));
        }
        mvc.perform(commeGerant(get("/caisse/transfert"))).andExpect(content().string(containsString("name=\"jetonOperation\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("name=\"compteDestination\"")));
        mvc.perform(commeGerant(get("/caisse/depense"))).andExpect(content().string(containsString("name=\"compteSource\"")));
    }

    @Test void leTransfertRejoueExactementNeDoublePasLesDeuxEcritures() throws Exception {
        String jeton = UUID.randomUUID().toString();
        mvc.perform(commeGerant(transfert(jeton)).with(csrf())).andExpect(redirectedUrl("/caisse/journal"));
        mvc.perform(commeGerant(transfert(jeton)).with(csrf())).andExpect(redirectedUrl("/caisse/journal"));
        connecter();
        try {
            assertThat(caisse.getSoldesParType().get(ESPECES)).isEqualByComparingTo("900");
            assertThat(caisse.getSoldesParType().get(ORANGE_MONEY)).isEqualByComparingTo("1100");
            assertThat(caisse.getSoldeActuel()).isEqualByComparingTo("3000"); assertThat(caisse.listerTous()).hasSize(5);
        } finally { SecurityContextHolder.clearContext(); }
        mvc.perform(commeGerant(transfert(jeton).param("motif", "Contenu modifié")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(commeGerant(transfert(jeton).param("montant", "100", "200")).with(csrf())).andExpect(status().isBadRequest());
        var modifie = post("/caisse/transfert").param("compteSource", "CAISSE").param("compteDestination", "BANQUE")
                .param("montant", "100").param("motif", "Virement interne").param("jetonOperation", jeton);
        mvc.perform(commeGerant(modifie).with(csrf())).andExpect(status().isConflict())
                .andExpect(model().attributeHasErrors("transfertTresorerie"));
    }

    @Test void uneDepenseEstEnregistreeSurLeCompteChoisiEtNonSurLesAutres() throws Exception {
        String jeton = UUID.randomUUID().toString();
        mvc.perform(commeGerant(depense(jeton, "150")).with(csrf())).andExpect(redirectedUrl("/caisse/journal"));
        mvc.perform(commeGerant(depense(jeton, "150")).with(csrf())).andExpect(redirectedUrl("/caisse/journal"));
        connecter();
        try {
            assertThat(caisse.getSoldesParType().get(BANQUE)).isEqualByComparingTo("850");
            assertThat(caisse.getSoldesParType().get(ESPECES)).isEqualByComparingTo("1000"); assertThat(caisse.listerTous()).hasSize(4);
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test void unSoldeInsuffisantResteUneErreurDuFormulaireSansModifierLaTresorerie() throws Exception {
        mvc.perform(commeGerant(depense(UUID.randomUUID().toString(), "1001")).with(csrf()))
                .andExpect(status().isOk()).andExpect(model().attributeHasErrors("depenseTresorerie"))
                .andExpect(content().string(containsString("Solde insuffisant")));
        connecter();
        try { assertThat(caisse.getSoldeActuel()).isEqualByComparingTo("3000"); assertThat(caisse.listerTous()).hasSize(3); }
        finally { SecurityContextHolder.clearContext(); }
    }

    @Test void lesCaissiersEtVisiteursNAccedentPasALaGestionDeTresorerie() throws Exception {
        for (var chemin : List.of("/caisse/transfert", "/caisse/depense", "/caisse/journal", "/caisse/ouverture")) {
            mvc.perform(get(chemin).with(user(caissier.getEmail()).roles("CAISSIER"))).andExpect(status().isForbidden());
            mvc.perform(get(chemin)).andExpect(redirectedUrl("http://localhost/login"));
        }
        mvc.perform(transfert(UUID.randomUUID().toString()).with(user(caissier.getEmail()).roles("CAISSIER")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(depense(UUID.randomUUID().toString(), "10").with(user(caissier.getEmail()).roles("ADMIN")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test void chaqueMutationExigeUnJetonCsrfValide() throws Exception {
        mvc.perform(commeGerant(transfert(UUID.randomUUID().toString()))).andExpect(status().isForbidden());
        mvc.perform(commeGerant(depense(UUID.randomUUID().toString(), "10")).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "boutiqueId", "boutique.id", "utilisateurId", "source", "operationTresorerie.id", "compte", "type"})
    void aucunIdentifiantOuChampInterneNEntreDansLesNouveauxFormulaires(String champ) throws Exception {
        mvc.perform(commeGerant(transfert(UUID.randomUUID().toString()).param(champ, "1")).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(commeGerant(depense(UUID.randomUUID().toString(), "10").param(champ, "1")).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test void unCompteInconnuOuPlusieursComptesNeSontJamaisAcceptes() throws Exception {
        mvc.perform(commeGerant(post("/caisse/transfert").param("compteSource", "AUTRE").param("compteDestination", "BANQUE")
                        .param("montant", "1").param("motif", "Test").param("jetonOperation", UUID.randomUUID().toString())).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(commeGerant(transfert(UUID.randomUUID().toString()).param("compteSource", "BANQUE")).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder transfert(String jeton) {
        return post("/caisse/transfert").param("compteSource", "CAISSE").param("compteDestination", "ORANGE_MONEY")
                .param("montant", "100").param("motif", "Virement interne").param("jetonOperation", jeton);
    }
    private MockHttpServletRequestBuilder depense(String jeton, String montant) {
        return post("/caisse/depense").param("compteSource", "BANQUE").param("montant", montant)
                .param("motif", "Frais de fonctionnement").param("jetonOperation", jeton);
    }
    private MockHttpServletRequestBuilder commeGerant(MockHttpServletRequestBuilder requete) {
        return requete.with(user(gerant.getEmail()).roles("ADMIN"));
    }
    private void connecter() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(gerant.getEmail(), "unused",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
    private Utilisateur compte(Boutique boutique, Role role) {
        var u = new Utilisateur(); u.setBoutique(boutique); u.setRole(role); u.setNom("Compte trésorerie");
        u.setEmail(UUID.randomUUID() + "@example.test"); return utilisateurs.saveAndFlush(u);
    }
}
