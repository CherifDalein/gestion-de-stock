package org.example.stock.security;

import org.example.stock.enums.Role;
import org.example.stock.form.ParametresBoutiqueForm;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
class ParametresBoutiqueSecurityTests {
    private static final String ROUTE = "/boutique/parametres";
    @Autowired MockMvc mvc;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ClientRepository clients;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired VenteRepository ventes;
    @Autowired AchatRepository achats;
    Boutique boutiqueA, boutiqueB;
    Utilisateur gerantA, gerantB, caissier;
    Client clientA, clientB;
    Fournisseur fournisseurA, fournisseurB;
    Vente venteA, venteB;
    Achat achatA, achatB;

    @BeforeEach
    void preparer() {
        boutiqueA = boutique("Boutique A", "Rue A\nConakry", "+224 622 000 001", "contact-a@example.test");
        boutiqueB = boutique("Boutique B confidentielle", "Adresse secrète B", "+224 622 999 999", "secret-b@example.test");
        gerantA = compte("gerant-parametres-a@example.test", boutiqueA, Role.ADMIN);
        gerantB = compte("gerant-parametres-b@example.test", boutiqueB, Role.ADMIN);
        caissier = compte("caissier-parametres@example.test", boutiqueA, Role.CAISSIER);
        clientA = client(boutiqueA); clientB = client(boutiqueB);
        fournisseurA = fournisseur(boutiqueA); fournisseurB = fournisseur(boutiqueB);
        venteA = vente(boutiqueA, clientA); venteB = vente(boutiqueB, clientB);
        achatA = achat(boutiqueA, fournisseurA); achatB = achat(boutiqueB, fournisseurB);
    }

    @Test
    void leGerantConsulteUniquementLesParametresDeSaBoutique() throws Exception {
        var resultat = mvc.perform(comme(gerantA, get(ROUTE))).andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("contact-a@example.test")))
                .andExpect(content().string(not(containsString("secret-b@example.test"))))
                .andExpect(content().string(not(containsString(gerantA.getMotDePasse())))).andReturn();
        ParametresBoutiqueForm formulaire = (ParametresBoutiqueForm) resultat.getModelAndView().getModel().get("parametresBoutique");
        assertThat(formulaire.getNom()).isEqualTo(boutiqueA.getNom());
        assertThat(formulaire.getAdresse()).isEqualTo(boutiqueA.getAdresse());
        assertThat(formulaire.getTelephone()).isEqualTo(boutiqueA.getTelephone());
        assertThat(formulaire.getEmail()).isEqualTo(boutiqueA.getEmail());
        mvc.perform(comme(gerantB, get(ROUTE))).andExpect(status().isOk())
                .andExpect(content().string(containsString("secret-b@example.test")))
                .andExpect(content().string(not(containsString("contact-a@example.test"))));
    }

    @Test
    void lePostConserveLesDroitsEtLesDocumentsEtNeChangeQueSaBoutique() throws Exception {
        Long id = boutiqueA.getId(); LocalDateTime creation = boutiqueA.getDateCreation();
        boutiqueA.setAccesOffert(true); boutiques.saveAndFlush(boutiqueA);
        String hash = gerantA.getMotDePasse();
        long boutiquesAvant = boutiques.count(), utilisateursAvant = utilisateurs.count();
        long achatsAvant = achats.count(), ventesAvant = ventes.count();
        mvc.perform(comme(gerantA, saisie("  Commerce Cherif  ", "  Quartier Camayenne\nConakry  ",
                        "  +224 622 123 456  ", "  boutique@example.test  ")).with(csrf()))
                .andExpect(redirectedUrl(ROUTE)).andExpect(flash().attributeExists("success"));
        Boutique relue = boutiques.findById(id).orElseThrow();
        assertThat(relue.getNom()).isEqualTo("Commerce Cherif");
        assertThat(relue.getAdresse()).isEqualTo("Quartier Camayenne\nConakry");
        assertThat(relue.getTelephone()).isEqualTo("+224 622 123 456");
        assertThat(relue.getEmail()).isEqualTo("boutique@example.test");
        assertThat(relue.getDateCreation()).isEqualTo(creation); assertThat(relue.isActif()).isTrue();
        assertThat(relue.isAccesOffert()).isTrue();
        assertThat(boutiques.findById(boutiqueB.getId()).orElseThrow().getEmail()).isEqualTo("secret-b@example.test");
        assertThat(utilisateurs.findById(gerantA.getId()).orElseThrow().getMotDePasse()).isEqualTo(hash);
        assertThat(utilisateurs.findById(gerantA.getId()).orElseThrow().getRole()).isEqualTo(Role.ADMIN);
        assertThat(boutiques.count()).isEqualTo(boutiquesAvant); assertThat(utilisateurs.count()).isEqualTo(utilisateursAvant);
        assertThat(achats.count()).isEqualTo(achatsAvant); assertThat(ventes.count()).isEqualTo(ventesAvant);
        assertThat(venteA.getMontantTotal()).isEqualByComparingTo("12000");
        mvc.perform(comme(gerantA, get(ROUTE))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Commerce Cherif")));
    }

    @Test
    void lesCoordonneesFacultativesPeuventEtreEffacees() throws Exception {
        mvc.perform(comme(gerantA, saisie("Commerce sans coordonnées", " \n\t ", "  ", "  ")).with(csrf()))
                .andExpect(redirectedUrl(ROUTE));
        Boutique relue = boutiques.findById(boutiqueA.getId()).orElseThrow();
        assertThat(relue.getAdresse()).isNull(); assertThat(relue.getTelephone()).isNull(); assertThat(relue.getEmail()).isNull();
        for (String route : facturesA()) {
            mvc.perform(comme(gerantA, get(route))).andExpect(status().isOk())
                    .andExpect(content().string(containsString("Commerce sans coordonnées")))
                    .andExpect(content().string(not(containsString("contact-a@example.test"))));
        }
    }

    @Test
    void visiteurEtCaissierNePeuventPasAccederAuxParametres() throws Exception {
        mvc.perform(get(ROUTE)).andExpect(redirectedUrl("http://localhost/login"));
        mvc.perform(saisie("Interdit", "", "", "").with(csrf())).andExpect(redirectedUrl("http://localhost/login"));
        mvc.perform(comme(caissier, get(ROUTE))).andExpect(status().isForbidden());
        mvc.perform(comme(caissier, saisie("Interdit", "", "", "")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(comme(caissier, get("/produits"))).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("href=\"/boutique/parametres\""))));
        assertThat(boutiqueA.getNom()).isEqualTo("Boutique A");
    }

    @Test
    void unDroitAdminDeSessionRetireEnBaseEstRefuse() throws Exception {
        gerantA.setRole(Role.CAISSIER); utilisateurs.saveAndFlush(gerantA);
        mvc.perform(get(ROUTE).with(user(gerantA.getEmail()).roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(saisie("Interdit", "", "", "").with(user(gerantA.getEmail()).roles("ADMIN")).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(boutiqueA.getNom()).isEqualTo("Boutique A");
    }

    @Test
    void compteDesactiveEtBoutiqueSuspendueSontRefuses() throws Exception {
        gerantA.setActif(false); utilisateurs.saveAndFlush(gerantA);
        mvc.perform(comme(gerantA, get(ROUTE))).andExpect(status().isForbidden());
        mvc.perform(comme(gerantA, saisie("Interdit", "", "", "")).with(csrf())).andExpect(status().isForbidden());
        gerantA.setActif(true); utilisateurs.saveAndFlush(gerantA);
        boutiqueA.setActif(false); boutiques.saveAndFlush(boutiqueA);
        mvc.perform(comme(gerantA, get(ROUTE))).andExpect(status().isForbidden());
        mvc.perform(comme(gerantA, saisie("Interdit", "", "", "")).with(csrf())).andExpect(status().isForbidden());
        assertThat(boutiqueA.getNom()).isEqualTo("Boutique A");
    }

    @Test
    void laModificationExigeUnJetonCsrf() throws Exception {
        mvc.perform(comme(gerantA, saisie("Interdit", "", "", ""))).andExpect(status().isForbidden());
        mvc.perform(comme(gerantA, saisie("Interdit", "", "", "")).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        assertThat(boutiqueA.getNom()).isEqualTo("Boutique A");
    }

    @Test
    void lesEntetesHttpNeRemplacentPasUneSaisieAbsente() throws Exception {
        var resultat = mvc.perform(comme(gerantA, post(ROUTE)).with(csrf())
                        .header("nom", "Nom injecté dans un en-tête").header("email", "injection@example.test"))
                .andExpect(status().isOk()).andReturn();
        BindingResult validation = (BindingResult) resultat.getModelAndView().getModel().get(BindingResult.MODEL_KEY_PREFIX + "parametresBoutique");
        assertThat(validation.hasFieldErrors("nom")).isTrue();
        assertThat(boutiqueA.getNom()).isEqualTo("Boutique A");
        assertThat(boutiqueA.getEmail()).isEqualTo("contact-a@example.test");
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "boutiqueId", "boutique.id", "actif", "accesOffert", "dateCreation", "administrateurPlateforme"})
    void lesChampsInternesOuUneBoutiqueEtrangereNePeuventPasEtreEnvoyes(String champ) throws Exception {
        mvc.perform(comme(gerantA, saisie("Interdit", "", "", "")).with(csrf()).param(champ, boutiqueB.getId().toString()))
                .andExpect(status().isBadRequest());
        assertThat(boutiqueA.getNom()).isEqualTo("Boutique A");
        assertThat(boutiqueB.getNom()).isEqualTo("Boutique B confidentielle");
    }

    @ParameterizedTest
    @ValueSource(strings = {"nom", "adresse", "telephone", "email"})
    void lesChampsInvalidesAffichentUneErreurEtConserventLaSaisieSansEnregistrer(String champ) throws Exception {
        String nom = champ.equals("nom") ? "  " : "Nouveau commerce";
        String adresse = champ.equals("adresse") ? "a".repeat(501) : "Adresse conservée";
        String telephone = champ.equals("telephone") ? "1".repeat(51) : "+224 622 000 000";
        String email = champ.equals("email") ? "email-invalide" : "nouveau@example.test";
        var resultat = mvc.perform(comme(gerantA, saisie(nom, adresse, telephone, email)).with(csrf()))
                .andExpect(status().isOk()).andExpect(view().name("dashboard")).andReturn();
        BindingResult validation = (BindingResult) resultat.getModelAndView().getModel().get(BindingResult.MODEL_KEY_PREFIX + "parametresBoutique");
        assertThat(validation.hasFieldErrors(champ)).isTrue();
        ParametresBoutiqueForm formulaire = (ParametresBoutiqueForm) resultat.getModelAndView().getModel().get("parametresBoutique");
        assertThat(formulaire.getAdresse()).isEqualTo(adresse);
        assertThat(formulaire.getEmail()).isEqualTo(email);
        assertThat(boutiqueA.getNom()).isEqualTo("Boutique A");
        assertThat(boutiqueA.getEmail()).isEqualTo("contact-a@example.test");
    }

    @Test
    void lesQuatreDocumentsAffichentLaBonneIdentiteAvecEchappementHtml() throws Exception {
        String nom = "Commerce <script>alert('nom')</script>";
        String adresse = "Rue <img src=x onerror=alert('adresse')>\nConakry";
        mvc.perform(comme(gerantA, saisie(nom, adresse, "+224 622 123 456", "commerce@example.test")).with(csrf()))
                .andExpect(redirectedUrl(ROUTE));
        for (String route : facturesA()) {
            var resultat = mvc.perform(comme(gerantA, get(route))).andExpect(status().isOk())
                    .andExpect(content().string(containsString("Commerce &lt;script&gt;")))
                    .andExpect(content().string(containsString("Rue &lt;img")))
                    .andExpect(content().string(containsString("+224 622 123 456")))
                    .andExpect(content().string(containsString("commerce@example.test")))
                    .andExpect(content().string(not(containsString("secret-b@example.test")))).andReturn();
            assertThat(resultat.getResponse().getContentAsString()).doesNotContain("<script>alert", "<img src=x onerror=");
        }
        mvc.perform(comme(caissier, get("/factures/vente/" + venteA.getId()))).andExpect(status().isOk())
                .andExpect(content().string(containsString("commerce@example.test")));
        for (String route : List.of("/factures/vente/" + venteB.getId(), "/factures/achat/" + achatB.getId(),
                "/factures/client/" + clientB.getId() + "/cumule", "/factures/fournisseur/" + fournisseurB.getId() + "/cumule")) {
            mvc.perform(comme(gerantA, get(route))).andExpect(status().isNotFound());
        }
        mvc.perform(comme(gerantB, get("/factures/vente/" + venteB.getId()))).andExpect(status().isOk())
                .andExpect(content().string(containsString("secret-b@example.test")))
                .andExpect(content().string(not(containsString("commerce@example.test"))));
    }

    private List<String> facturesA() {
        return List.of("/factures/vente/" + venteA.getId(), "/factures/achat/" + achatA.getId(),
                "/factures/client/" + clientA.getId() + "/cumule", "/factures/fournisseur/" + fournisseurA.getId() + "/cumule");
    }
    private MockHttpServletRequestBuilder comme(Utilisateur compte, MockHttpServletRequestBuilder requete) {
        return requete.with(user(compte.getEmail()).roles(compte.getRole().name()));
    }
    private MockHttpServletRequestBuilder saisie(String nom, String adresse, String telephone, String email) {
        return post(ROUTE).param("nom", nom).param("adresse", adresse).param("telephone", telephone).param("email", email);
    }
    private Boutique boutique(String nom, String adresse, String telephone, String email) {
        Boutique boutique = new Boutique(); boutique.setNom(nom); boutique.setAdresse(adresse);
        boutique.setTelephone(telephone); boutique.setEmail(email); return boutiques.saveAndFlush(boutique);
    }
    private Utilisateur compte(String email, Boutique boutique, Role role) {
        Utilisateur compte = new Utilisateur(); compte.setNom("Gérant des paramètres"); compte.setEmail(email);
        compte.setMotDePasse("Empreinte-de-test-jamais-affichee"); compte.setDateInscription(LocalDate.now());
        compte.setBoutique(boutique); compte.setRole(role); return utilisateurs.saveAndFlush(compte);
    }
    private Client client(Boutique boutique) {
        Client client = new Client(); client.setNom("Client des paramètres"); client.setBoutique(boutique); return clients.saveAndFlush(client);
    }
    private Fournisseur fournisseur(Boutique boutique) {
        Fournisseur fournisseur = new Fournisseur(); fournisseur.setNom("Fournisseur des paramètres");
        fournisseur.setBoutique(boutique); return fournisseurs.saveAndFlush(fournisseur);
    }
    private Vente vente(Boutique boutique, Client client) {
        Vente vente = new Vente(); vente.setBoutique(boutique); vente.setClient(client); vente.setDateVente(LocalDateTime.now());
        vente.setMontantTotal(new BigDecimal("12000")); vente.setMontantVerse(new BigDecimal("3000")); return ventes.saveAndFlush(vente);
    }
    private Achat achat(Boutique boutique, Fournisseur fournisseur) {
        Achat achat = new Achat(); achat.setBoutique(boutique); achat.setFournisseur(fournisseur); achat.setDateAchat(LocalDateTime.now());
        achat.setMontantTotal(new BigDecimal("10000")); achat.setMontantVerse(new BigDecimal("2000")); return achats.saveAndFlush(achat);
    }
}
