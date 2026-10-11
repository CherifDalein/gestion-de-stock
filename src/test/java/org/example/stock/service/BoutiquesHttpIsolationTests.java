package org.example.stock.service;

import org.example.stock.enums.Role;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Vérifie les vraies routes, le modèle rendu et les écritures avec deux boutiques persistées. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:boutiques-http-isolation;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class BoutiquesHttpIsolationTests {
    private static final String EMAIL_A = "http-gerant-a@example.test";
    private static final String EMAIL_B = "http-gerant-b@example.test";
    private static final String SECRET_B = "DONNEES_CONFIDENTIELLES_B";
    private static final String PASSWORD_TEST = "Mot de passe de test 2026 !";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired CategorieRepository categories;
    @Autowired ProduitRepository produits;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired ClientRepository clients;
    @Autowired AchatRepository achats;
    @Autowired VenteRepository ventes;
    @Autowired MouvementCaisseRepository mouvements;
    @Autowired OperationCreationRepository operations;

    Boutique boutiqueA, boutiqueB;
    Utilisateur gerantA, gerantB;
    Produit produitA, produitB;
    Client clientA, clientB;
    Fournisseur fournisseurA, fournisseurB;
    Achat achatA, achatAncienA, achatB, achatAncienB;
    Vente venteA, venteAncienneA, venteB, venteAncienneB;

    @BeforeEach
    void preparer() {
        operations.deleteAll(); mouvements.deleteAll(); ventes.deleteAll(); achats.deleteAll();
        produits.deleteAll(); clients.deleteAll(); fournisseurs.deleteAll(); categories.deleteAll();
        utilisateurs.deleteAll(); boutiques.deleteAll();
        boutiqueA = boutique("Boutique publique A"); boutiqueB = boutique(SECRET_B);
        gerantA = utilisateur(EMAIL_A, boutiqueA); gerantB = utilisateur(EMAIL_B, boutiqueB);
        clientA = client("Client public A", boutiqueA); clientB = client(SECRET_B, boutiqueB);
        fournisseurA = fournisseur("Fournisseur public A", boutiqueA); fournisseurB = fournisseur(SECRET_B, boutiqueB);
        produitA = produit("Produit public A", boutiqueA, fournisseurA);
        produitB = produit(SECRET_B, boutiqueB, fournisseurB);
        LocalDateTime aujourdHui = LocalDate.now().atTime(12, 0);
        LocalDateTime ancien = aujourdHui.minusYears(2);
        achatA = achat(boutiqueA, fournisseurA, produitA, aujourdHui, "20", "4");
        achatAncienA = achat(boutiqueA, fournisseurA, produitA, ancien, "10", "0");
        achatB = achat(boutiqueB, fournisseurB, produitB, aujourdHui, "1200", "50");
        achatAncienB = achat(boutiqueB, fournisseurB, produitB, ancien, "5000", "1000");
        venteA = vente(boutiqueA, clientA, produitA, aujourdHui, "40", "15");
        venteAncienneA = vente(boutiqueA, clientA, produitA, ancien, "10", "5");
        venteB = vente(boutiqueB, clientB, produitB, aujourdHui, "1800", "900");
        venteAncienneB = vente(boutiqueB, clientB, produitB, ancien, "1700", "800");
        mouvement(boutiqueA, gerantA, "Ouverture publique A", aujourdHui.minusDays(1), "100", null, null);
        mouvement(boutiqueA, gerantA, "Versement public A", aujourdHui, "15", null, venteA);
        mouvement(boutiqueA, gerantA, "Paiement public A", aujourdHui, "-4", achatA, null);
        mouvement(boutiqueB, gerantB, SECRET_B, aujourdHui.minusDays(1), "9000", null, null);
        mouvement(boutiqueB, gerantB, SECRET_B, aujourdHui, "900", null, venteB);
        mouvement(boutiqueB, gerantB, SECRET_B, aujourdHui, "-50", achatB, null);
    }

    @Test
    void lesFacturesIndividuellesEtrangeresSontIntrouvables() throws Exception {
        mvc.perform(auth(get("/factures/vente/" + venteB.getId()))).andExpect(status().isNotFound());
        mvc.perform(auth(get("/factures/achat/" + achatB.getId()))).andExpect(status().isNotFound());
        String vente = mvc.perform(auth(get("/factures/vente/" + venteA.getId()))).andExpect(status().isOk())
                .andExpect(model().attribute("vente", org.hamcrest.Matchers.hasProperty("id", org.hamcrest.Matchers.is(venteA.getId()))))
                .andReturn().getResponse().getContentAsString();
        String achat = mvc.perform(auth(get("/factures/achat/" + achatA.getId()))).andExpect(status().isOk())
                .andExpect(model().attribute("achat", org.hamcrest.Matchers.hasProperty("id", org.hamcrest.Matchers.is(achatA.getId()))))
                .andReturn().getResponse().getContentAsString();
        assertThat(vente).contains("Client public A").doesNotContain(SECRET_B);
        assertThat(achat).contains("Fournisseur public A").doesNotContain(SECRET_B);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "jour", "semaine", "mois", "annee"})
    void lesHistoriquesEtCumulsDunContactEtrangerSontIntrouvables(String periode) throws Exception {
        for (String route : List.of("/factures/client/" + clientB.getId(), "/factures/client/" + clientB.getId() + "/cumule",
                "/factures/fournisseur/" + fournisseurB.getId(), "/factures/fournisseur/" + fournisseurB.getId() + "/cumule")) {
            mvc.perform(auth(get(route).param("periode", periode))).andExpect(status().isNotFound());
        }
    }

    @Test
    void toutesLesListesEtLesCumulsNeRendentQueLesDocumentsDeLaBoutique() throws Exception {
        verifierVentes(mvc.perform(auth(get("/ventes"))).andExpect(status().isOk()).andReturn(), venteA, venteAncienneA);
        verifierAchats(mvc.perform(auth(get("/achats"))).andExpect(status().isOk()).andReturn(), achatA, achatAncienA);
        verifierVentes(mvc.perform(auth(get("/factures/liste"))).andExpect(status().isOk()).andReturn(), venteA, venteAncienneA);
        for (String suffixe : List.of("", "/cumule")) {
            verifierVentes(mvc.perform(auth(get("/factures/client/" + clientA.getId() + suffixe)))
                    .andExpect(status().isOk()).andReturn(), venteA, venteAncienneA);
            verifierAchats(mvc.perform(auth(get("/factures/fournisseur/" + fournisseurA.getId() + suffixe)))
                    .andExpect(status().isOk()).andReturn(), achatA, achatAncienA);
        }
        MvcResult boutiqueB = mvc.perform(get("/ventes").with(user(EMAIL_B).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn();
        assertThat(ventesDuModele(boutiqueB)).extracting(Vente::getId).containsExactlyInAnyOrder(venteB.getId(), venteAncienneB.getId());
        assertThat(boutiqueB.getResponse().getContentAsString()).contains(SECRET_B).doesNotContain("Client public A");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jour", "semaine", "mois", "annee"})
    void chaqueFiltreDePeriodeResteLimiteALaBoutiqueEtExclutLesAnciensDocuments(String periode) throws Exception {
        verifierVentes(mvc.perform(auth(get("/factures/liste").param("periode", periode)))
                .andExpect(status().isOk()).andReturn(), venteA);
        for (String suffixe : List.of("", "/cumule")) {
            verifierVentes(mvc.perform(auth(get("/factures/client/" + clientA.getId() + suffixe).param("periode", periode)))
                    .andExpect(status().isOk()).andReturn(), venteA);
            verifierAchats(mvc.perform(auth(get("/factures/fournisseur/" + fournisseurA.getId() + suffixe).param("periode", periode)))
                    .andExpect(status().isOk()).andReturn(), achatA);
        }
    }

    @Test
    void lAccueilEtLaCaisseCalculentUniquementLesIndicateursDeLaBoutique() throws Exception {
        MvcResult accueil = mvc.perform(auth(get("/"))).andExpect(status().isOk()).andReturn();
        montant(accueil, "soldeCaisse", "111"); montant(accueil, "ventesDuJour", "15");
        montant(accueil, "soldeOuverture", "100"); montant(accueil, "entreesJour", "15");
        montant(accueil, "sortiesJour", "4"); montant(accueil, "bilanJour", "11");
        montant(accueil, "caisseDuJour", "11"); montant(accueil, "soldeCloture", "111");
        assertThat(accueil.getResponse().getContentAsString()).doesNotContain(SECRET_B);
        MvcResult caisse = mvc.perform(auth(get("/caisse/journal"))).andExpect(status().isOk()).andReturn();
        montant(caisse, "soldeTotal", "111"); montant(caisse, "soldeOuverture", "100");
        montant(caisse, "entreesJour", "15"); montant(caisse, "sortiesJour", "4");
        montant(caisse, "bilanJour", "11"); montant(caisse, "soldeCloture", "111");
        assertThat(mouvementsDuModele(caisse, "mouvements")).hasSize(3)
                .allSatisfy(m -> assertThat(m.getBoutique().getId()).isEqualTo(boutiqueA.getId()));
        assertThat(mouvementsDuModele(caisse, "mouvementsDuJour")).hasSize(2)
                .allSatisfy(m -> assertThat(m.getBoutique().getId()).isEqualTo(boutiqueA.getId()));
        assertThat(caisse.getResponse().getContentAsString()).contains("Versement public A", "Paiement public A").doesNotContain(SECRET_B);
        MvcResult autre = mvc.perform(get("/").with(user(EMAIL_B).roles("ADMIN"))).andExpect(status().isOk()).andReturn();
        montant(autre, "soldeCaisse", "9850"); montant(autre, "ventesDuJour", "900");
    }

    @Test
    void lesReglementsEtrangersSontRefusesSansChangerNiLaDetteNiLeStockNiLaCaisse() throws Exception {
        long mouvementsAvant = mouvements.count();
        for (String route : List.of("/achats/regler/" + achatB.getId(), "/ventes/regler/" + venteB.getId())) {
            mvc.perform(auth(get(route))).andExpect(status().isNotFound());
        }
        mvc.perform(auth(post("/achats/regler/" + achatB.getId()).with(csrf()).param("montant", "1").param("montantVerseAttendu", "50")))
                .andExpect(status().isNotFound());
        mvc.perform(auth(post("/ventes/regler/" + venteB.getId()).with(csrf()).param("montant", "1").param("montantVerseAttendu", "900")))
                .andExpect(status().isNotFound());
        assertThat(achats.findById(achatB.getId()).orElseThrow().getMontantVerse()).isEqualByComparingTo("50");
        assertThat(ventes.findById(venteB.getId()).orElseThrow().getMontantVerse()).isEqualByComparingTo("900");
        assertThat(mouvements.count()).isEqualTo(mouvementsAvant);
        assertThat(produits.findById(produitA.getId()).orElseThrow().getQuantite()).isEqualTo(77L);
        assertThat(produits.findById(produitB.getId()).orElseThrow().getQuantite()).isEqualTo(77L);
    }

    @Test
    void unGerantCreeUnCaissierDansSaBoutiqueSansPouvoirChoisirUneAutre() throws Exception {
        String email = "nouveau-caissier@example.test";
        mvc.perform(auth(post("/register").with(csrf()).param("nom", "Caissier de A")
                        .param("email", email).param("password", PASSWORD_TEST)))
                .andExpect(redirectedUrl("/register?created"));
        Utilisateur caissier = utilisateurs.findByEmail(email).orElseThrow();
        assertThat(caissier.getBoutique().getId()).isEqualTo(boutiqueA.getId());
        assertThat(caissier.getRole()).isEqualTo(Role.CAISSIER);
        assertThat(passwordEncoder.matches(PASSWORD_TEST, caissier.getMotDePasse())).isTrue();
        long nombre = utilisateurs.count();
        mvc.perform(auth(post("/register").with(csrf()).param("nom", "Caissier falsifié")
                        .param("email", "falsifie@example.test").param("password", PASSWORD_TEST)
                        .param("boutique.id", boutiqueB.getId().toString())))
                .andExpect(status().isBadRequest());
        assertThat(utilisateurs.count()).isEqualTo(nombre);
        assertThat(utilisateurs.existsByEmail("falsifie@example.test")).isFalse();
        mvc.perform(get("/ventes").with(user(email).roles("CAISSIER"))).andExpect(status().isOk());
        mvc.perform(get("/factures/vente/" + venteB.getId()).with(user(email).roles("CAISSIER"))).andExpect(status().isNotFound());
        mvc.perform(post("/register").with(user(email).roles("CAISSIER")).with(csrf())
                        .param("nom", "Interdit").param("email", "interdit@example.test").param("password", PASSWORD_TEST))
                .andExpect(status().isForbidden());
        assertThat(utilisateurs.count()).isEqualTo(nombre);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CAISSIER"})
    void uneSessionDontLeCompteNexistePlusNePeutPasAccederAuxEcransMetier(String role) throws Exception {
        for (String route : List.of("/", "/produits", "/ventes", "/factures/liste", "/caisse/journal")) {
            mvc.perform(get(route).with(user("compte-supprime@example.test").roles(role)))
                    .andExpect(status().isForbidden());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CAISSIER"})
    void unCompteSansBoutiqueNePeutPasAccederAuxEcransMetier(String role) throws Exception {
        // Simule une ancienne base invalide ; les écritures normales sont protégées par NOT NULL.
        jdbc.execute("ALTER TABLE utilisateur ALTER COLUMN boutique_id DROP NOT NULL");
        try {
            jdbc.update("UPDATE utilisateur SET boutique_id = NULL WHERE id = ?", gerantA.getId());
            for (String route : List.of("/", "/produits", "/ventes", "/factures/liste", "/caisse/journal")) {
                mvc.perform(get(route).with(user(EMAIL_A).roles(role))).andExpect(status().isForbidden());
            }
        } finally {
            jdbc.update("UPDATE utilisateur SET boutique_id = ? WHERE id = ?", boutiqueA.getId(), gerantA.getId());
            jdbc.execute("ALTER TABLE utilisateur ALTER COLUMN boutique_id SET NOT NULL");
        }
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder requete) {
        return requete.with(user(EMAIL_A).roles("ADMIN"));
    }
    private void verifierVentes(MvcResult resultat, Vente... attendues) throws Exception {
        assertThat(ventesDuModele(resultat)).extracting(Vente::getId).containsExactlyInAnyOrder(
                java.util.Arrays.stream(attendues).map(Vente::getId).toArray(Long[]::new));
        assertThat(resultat.getResponse().getContentAsString()).doesNotContain(SECRET_B);
    }
    private void verifierAchats(MvcResult resultat, Achat... attendus) throws Exception {
        assertThat(achatsDuModele(resultat)).extracting(Achat::getId).containsExactlyInAnyOrder(
                java.util.Arrays.stream(attendus).map(Achat::getId).toArray(Long[]::new));
        assertThat(resultat.getResponse().getContentAsString()).doesNotContain(SECRET_B);
    }
    @SuppressWarnings("unchecked")
    private List<Vente> ventesDuModele(MvcResult resultat) { return (List<Vente>) resultat.getModelAndView().getModel().get("ventes"); }
    @SuppressWarnings("unchecked")
    private List<Achat> achatsDuModele(MvcResult resultat) { return (List<Achat>) resultat.getModelAndView().getModel().get("achats"); }
    @SuppressWarnings("unchecked")
    private List<MouvementCaisse> mouvementsDuModele(MvcResult resultat, String attribut) {
        return (List<MouvementCaisse>) resultat.getModelAndView().getModel().get(attribut);
    }
    private void montant(MvcResult resultat, String attribut, String attendu) {
        assertThat((BigDecimal) resultat.getModelAndView().getModel().get(attribut)).as(attribut).isEqualByComparingTo(attendu);
    }
    private Boutique boutique(String nom) { Boutique boutique = new Boutique(); boutique.setNom(nom); return boutiques.saveAndFlush(boutique); }
    private Utilisateur utilisateur(String email, Boutique boutique) {
        Utilisateur utilisateur = new Utilisateur(); utilisateur.setNom("Gérant " + boutique.getNom()); utilisateur.setEmail(email);
        utilisateur.setRole(Role.ADMIN); utilisateur.setBoutique(boutique); utilisateur.setMotDePasse(passwordEncoder.encode(PASSWORD_TEST));
        return utilisateurs.saveAndFlush(utilisateur);
    }
    private Client client(String nom, Boutique boutique) { Client client = new Client(); client.setNom(nom); client.setBoutique(boutique); return clients.saveAndFlush(client); }
    private Fournisseur fournisseur(String nom, Boutique boutique) {
        Fournisseur fournisseur = new Fournisseur(); fournisseur.setNom(nom); fournisseur.setBoutique(boutique); return fournisseurs.saveAndFlush(fournisseur);
    }
    private Produit produit(String nom, Boutique boutique, Fournisseur fournisseur) {
        Categorie categorie = new Categorie(); categorie.setNom("Rayon commun"); categorie.setBoutique(boutique); categorie = categories.saveAndFlush(categorie);
        Produit produit = new Produit(); produit.setNom(nom); produit.setReference("REFERENCE-COMMUNE"); produit.setBoutique(boutique);
        produit.setCategorie(categorie); produit.setFournisseur(fournisseur); produit.setQuantite(77L);
        produit.setPrixAchat(new BigDecimal("10")); produit.setPrixVente(new BigDecimal("20")); return produits.saveAndFlush(produit);
    }
    private Achat achat(Boutique boutique, Fournisseur fournisseur, Produit produit, LocalDateTime date, String total, String verse) {
        Achat achat = new Achat(); achat.setBoutique(boutique); achat.setFournisseur(fournisseur); achat.setDateAchat(date);
        achat.setMontantTotal(new BigDecimal(total)); achat.setMontantVerse(new BigDecimal(verse));
        DetailAchat ligne = new DetailAchat(); ligne.setAchat(achat); ligne.setProduit(produit); ligne.setQuantite(1); ligne.setPrixAchatUnitaire(new BigDecimal(total));
        achat.getLignes().add(ligne); return achats.saveAndFlush(achat);
    }
    private Vente vente(Boutique boutique, Client client, Produit produit, LocalDateTime date, String total, String verse) {
        Vente vente = new Vente(); vente.setBoutique(boutique); vente.setClient(client); vente.setDateVente(date);
        vente.setMontantTotal(new BigDecimal(total)); vente.setMontantVerse(new BigDecimal(verse));
        DetailVente ligne = new DetailVente(); ligne.setVente(vente); ligne.setProduit(produit); ligne.setQuantite(1); ligne.setPrixUnitaire(new BigDecimal(total));
        vente.getLignes().add(ligne); return ventes.saveAndFlush(vente);
    }
    private void mouvement(Boutique boutique, Utilisateur utilisateur, String motif, LocalDateTime date, String montant, Achat achat, Vente vente) {
        MouvementCaisse mouvement = new MouvementCaisse(); mouvement.setBoutique(boutique); mouvement.setUtilisateur(utilisateur);
        mouvement.setMotif(motif); mouvement.setDateMouvement(date); mouvement.setMontant(new BigDecimal(montant));
        mouvement.setType(mouvement.getMontant().signum() < 0 ? "SORTIE" : "ENTREE");
        mouvement.setSource(achat != null ? "ACHAT" : vente != null ? "VENTE" : "AJUSTEMENT");
        mouvement.setAchat(achat); mouvement.setVente(vente); mouvements.saveAndFlush(mouvement);
    }
}
