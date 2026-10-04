package org.example.stock.security;

import org.example.stock.enums.Role;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.example.stock.service.AchatService;
import org.example.stock.service.VenteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "integrite@example.test", roles = "ADMIN")
class FormIntegrityTests {
    @Autowired MockMvc mvc;
    @Autowired ProduitRepository produits;
    @Autowired CategorieRepository categories;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired ClientRepository clients;
    @Autowired AchatRepository achats;
    @Autowired VenteRepository ventes;
    @Autowired MouvementCaisseRepository caisse;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired AchatService achatService;
    @Autowired VenteService venteService;
    Produit produit;
    Categorie categorie;
    Fournisseur fournisseur;
    Client client;

    @BeforeEach
    void preparer() {
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setEmail("integrite@example.test");
        utilisateur.setNom("Test intégrité");
        utilisateur.setRole(Role.ADMIN);
        utilisateurs.save(utilisateur);
        categorie = new Categorie(); categorie.setNom("Catégorie initiale");
        categorie = categories.save(categorie);
        fournisseur = new Fournisseur(); fournisseur.setNom("Fournisseur initial");
        fournisseur = fournisseurs.save(fournisseur);
        client = new Client(); client.setNom("Client initial");
        client = clients.save(client);
        produit = new Produit(); produit.setNom("Produit initial"); produit.setReference("REF-INITIALE");
        produit.setQuantite(10L); produit.setPrixAchat(5.0); produit.setPrixVente(20.0);
        produit.setCategorie(categorie); produit.setFournisseur(fournisseur);
        produit = produits.saveAndFlush(produit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/produits/ajouter", "/clients/enregistrer", "/fournisseurs/enregistrer",
            "/categories/ajouter", "/achats/enregistrer", "/ventes/enregistrer"})
    void uneCreationRefuseUnIdentifiantClientSansRienModifier(String route) throws Exception {
        mvc.perform(post(route).with(csrf()).param("id", produit.getId().toString()))
                .andExpect(status().isBadRequest());
        verifierDonneesIntactes();
    }

    @ParameterizedTest
    @CsvSource({
            "/achats/enregistrer,lignes[0].id",
            "/achats/enregistrer,lignes[0].achat.id",
            "/achats/enregistrer,lignes[0].achat.lignes[0].quantite",
            "/achats/enregistrer,lignes[0].produit.nom",
            "/achats/enregistrer,fournisseur.nom",
            "/achats/enregistrer,dateAchat",
            "/achats/enregistrer,montantTotal",
            "/achats/enregistrer,lignes[256].quantite",
            "/ventes/enregistrer,lignes[0].id",
            "/ventes/enregistrer,lignes[0].prixUnitaire",
            "/ventes/enregistrer,lignes[0].vente.lignes[0].quantite",
            "/ventes/enregistrer,client.nom",
            "/ventes/enregistrer,dateVente",
            "/ventes/enregistrer,montantTotal",
            "/produits/ajouter,categorie.nom",
            "/produits/ajouter,fournisseur.nom",
            "/fournisseurs/enregistrer,produits[0].id",
            "/categories/ajouter,produits[0].id",
            "/clients/enregistrer,role"
    })
    void lesProprietesInternesEtLesTraversalsImprevusSontRefuses(String route, String champ) throws Exception {
        mvc.perform(post(route).with(csrf()).param(champ, "1"))
                .andExpect(status().isBadRequest());
        verifierDonneesIntactes();
    }

    @Test
    void leFormulaireProduitCreeUneNouvelleEntiteEtChargeLesRelations() throws Exception {
        mvc.perform(post("/produits/ajouter").with(csrf()).param("nom", "Nouveau produit")
                        .param("reference", "REF-NOUVELLE").param("prixAchat", "5")
                        .param("prixVente", "20").param("quantite", "0")
                        .param("categorie", categorie.getId().toString())
                        .param("fournisseur", fournisseur.getId().toString()))
                .andExpect(redirectedUrl("/produits"));
        Produit nouveau = produits.findAll().stream().filter(p -> p.getNom().equals("Nouveau produit")).findFirst().orElseThrow();
        assertThat(nouveau.getId()).isNotEqualTo(produit.getId());
        assertThat(nouveau.getCategorie()).isSameAs(categorie);
        assertThat(nouveau.getFournisseur()).isSameAs(fournisseur);
        assertThat(produit.getQuantite()).isEqualTo(10L);
    }

    @Test
    @WithMockUser(username = "integrite@example.test", roles = "CAISSIER")
    void uneVenteNormaleCalculeSesMontantsEtUtiliseLeClientEnBase() throws Exception {
        mvc.perform(post("/ventes/enregistrer").with(csrf()).param("client", client.getId().toString())
                        .param("lignes[0].produit.id", produit.getId().toString())
                        .param("lignes[0].quantite", "2").param("montantVerse", "20"))
                .andExpect(redirectedUrl("/ventes"));
        ventes.flush();
        Vente vente = ventes.findAll().getFirst();
        assertThat(vente.getMontantTotal()).isEqualTo(40.0);
        assertThat(vente.getClient()).isSameAs(client);
        assertThat(vente.getLignes().getFirst().getProduit()).isSameAs(produit);
        assertThat(produit.getQuantite()).isEqualTo(8L);
        assertThat(caisse.findAll().getFirst().getMotif()).contains("Client initial");
    }

    @Test
    void unAchatNormalChargeLeFournisseurEtCreeSesPropresLignes() throws Exception {
        mvc.perform(post("/achats/enregistrer").with(csrf()).param("fournisseur", fournisseur.getId().toString())
                        .param("lignes[0].produit.id", produit.getId().toString())
                        .param("lignes[0].quantite", "3").param("lignes[0].prixAchatUnitaire", "7")
                        .param("montantVerse", "10"))
                .andExpect(redirectedUrl("/achats"));
        achats.flush();
        Achat achat = achats.findAll().getFirst();
        assertThat(achat.getMontantTotal()).isEqualTo(21.0);
        assertThat(achat.getFournisseur()).isSameAs(fournisseur);
        assertThat(achat.getLignes().getFirst().getProduit()).isSameAs(produit);
        assertThat(produit.getQuantite()).isEqualTo(13L);
        assertThat(caisse.findAll().getFirst().getMotif()).contains("Fournisseur initial");
    }

    @Test
    void laModificationDeFournisseurPreserveSesProduitsEtUtiliseIdDeRoute() throws Exception {
        fournisseur.setProduits(new java.util.ArrayList<>(List.of(produit)));
        mvc.perform(post("/fournisseurs/modifier/" + fournisseur.getId()).with(csrf())
                        .param("nom", "Nom mis à jour").param("email", "contact@example.test"))
                .andExpect(redirectedUrl("/fournisseurs"));
        assertThat(fournisseur.getNom()).isEqualTo("Nom mis à jour");
        assertThat(fournisseur.getProduits()).containsExactly(produit);
        assertThat(produit.getFournisseur()).isSameAs(fournisseur);
        mvc.perform(post("/fournisseurs/modifier/" + fournisseur.getId()).with(csrf())
                        .param("id", fournisseur.getId().toString()).param("nom", "Intrus"))
                .andExpect(status().isBadRequest());
        assertThat(fournisseur.getNom()).isEqualTo("Nom mis à jour");
    }

    @Test
    void unIdentifiantDeModificationInexistantNeCreePasDeClient() throws Exception {
        mvc.perform(post("/clients/modifier/999999").with(csrf()).param("nom", "Client intrus"))
                .andExpect(status().isNotFound());
        verifierDonneesIntactes();
    }

    @Test
    void laModificationDeProduitChangeUniquementEntiteDesigneeParLaRoute() throws Exception {
        mvc.perform(post("/produits/modifier/" + produit.getId()).with(csrf()).param("nom", "Produit modifié")
                        .param("reference", "REF-INITIALE").param("prixAchat", "5")
                        .param("prixVente", "22").param("quantite", "10")
                        .param("categorie", categorie.getId().toString())
                        .param("fournisseur", fournisseur.getId().toString()))
                .andExpect(redirectedUrl("/produits"));
        assertThat(produits.count()).isEqualTo(1);
        assertThat(produit.getNom()).isEqualTo("Produit modifié");
        assertThat(produit.getCategorie()).isSameAs(categorie);
        assertThat(produit.getFournisseur()).isSameAs(fournisseur);
    }

    @Test
    void uneCategorieInexistanteEstRefuseeAvantLaCreationDeProduit() throws Exception {
        mvc.perform(post("/produits/ajouter").with(csrf()).param("nom", "Produit intrus")
                        .param("reference", "REF-TEST").param("prixAchat", "5")
                        .param("prixVente", "20").param("quantite", "0").param("categorie", "999999"))
                .andExpect(status().isBadRequest());
        assertThat(produits.count()).isEqualTo(1);
    }

    @Test
    void leFormulaireDeModificationConserveLaSelectionDesRelationsSansIdCache() throws Exception {
        var resultat = mvc.perform(get("/produits/modifier/" + produit.getId()))
                .andExpect(status().isOk()).andReturn();
        String html = resultat.getResponse().getContentAsString();
        assertThat(html).doesNotContain("name=\"id\"");
        var optionFournisseur = java.util.regex.Pattern.compile("<option[^>]*value=\"" + fournisseur.getId()
                + "\"[^>]*selected=\"selected\"[^>]*>Fournisseur initial</option>").matcher(html);
        assertThat(optionFournisseur.find()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/produits", "/clients", "/clients/nouveau", "/ventes", "/ventes/nouveau", "/factures/liste"})
    @WithMockUser(username = "integrite@example.test", roles = "CAISSIER")
    void leCaissierAccedeAuxPagesDeVenteEtAuxClients(String route) throws Exception {
        mvc.perform(get(route)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/register", "/categories", "/categories/nouveau", "/fournisseurs", "/fournisseurs/nouveau",
            "/achats", "/achats/nouveau", "/achats/modifier/1", "/caisse/journal",
            "/factures/achat/1", "/factures/fournisseur/1", "/factures/fournisseur/1/cumule",
            "/produits/nouveau", "/produits/modifier/1"})
    @WithMockUser(username = "integrite@example.test", roles = "CAISSIER")
    void leCaissierNePeutPasOuvrirLesPagesAdministrateur(String route) throws Exception {
        mvc.perform(get(route)).andExpect(status().isForbidden());
        verifierDonneesIntactes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/achats/enregistrer", "/achats/modifier/1", "/produits/ajouter", "/produits/modifier/1",
            "/produits/supprimer/1", "/fournisseurs/enregistrer", "/fournisseurs/modifier/1", "/fournisseurs/supprimer/1",
            "/categories/ajouter", "/categories/modifier/1", "/categories/supprimer/1", "/clients/supprimer/1", "/register"})
    @WithMockUser(username = "integrite@example.test", roles = "CAISSIER")
    void leCaissierNePeutPasExecuterLesActionsAdministrateurMemeAvecCsrf(String route) throws Exception {
        mvc.perform(post(route).with(csrf())).andExpect(status().isForbidden());
        verifierDonneesIntactes();
    }

    @Test
    @WithMockUser(username = "integrite@example.test", roles = "CAISSIER")
    void leCaissierPeutCreerEtModifierUnClient() throws Exception {
        mvc.perform(post("/clients/enregistrer").with(csrf()).param("nom", "Nouveau client")
                        .param("email", "nouveau@example.test").param("telephone", "12345"))
                .andExpect(redirectedUrl("/clients"));
        Client nouveau = clients.findAll().stream().filter(c -> c.getNom().equals("Nouveau client")).findFirst().orElseThrow();
        mvc.perform(get("/clients/modifier/" + nouveau.getId())).andExpect(status().isOk());
        mvc.perform(post("/clients/modifier/" + nouveau.getId()).with(csrf()).param("nom", "Nom corrigé")
                        .param("email", "nouveau@example.test").param("telephone", "12345"))
                .andExpect(redirectedUrl("/clients"));
        assertThat(clients.count()).isEqualTo(2);
        assertThat(nouveau.getNom()).isEqualTo("Nom corrigé");
        assertThat(client.getNom()).isEqualTo("Client initial");
    }

    @Test
    @WithMockUser(username = "integrite@example.test", roles = "CAISSIER")
    void leCaissierConsulteLesFacturesDeVenteEtLesRelevesClient() throws Exception {
        Vente vente = new Vente(); vente.setClient(client); vente.setDateVente(java.time.LocalDateTime.now());
        vente.setMontantTotal(20.0); vente.setMontantVerse(20.0);
        DetailVente ligne = new DetailVente(); ligne.setProduit(produit); ligne.setQuantite(1);
        ligne.setPrixUnitaire(20.0); ligne.setVente(vente); vente.getLignes().add(ligne);
        vente = ventes.saveAndFlush(vente);
        mvc.perform(get("/factures/vente/" + vente.getId())).andExpect(status().isOk());
        mvc.perform(get("/factures/client/" + client.getId())).andExpect(status().isOk());
        mvc.perform(get("/factures/client/" + client.getId() + "/cumule")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "integrite@example.test", roles = "CAISSIER")
    void leCaissierNeRecoitNiIndicateursGlobauxNiActionsInventaire() throws Exception {
        var accueil = mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("soldeCaisse", "soldeOuverture", "entreesJour", "sortiesJour", "bilanJour", "soldeCloture"))
                .andReturn().getResponse().getContentAsString();
        assertThat(accueil).contains("Espace de vente", "href=\"/ventes/nouveau\"")
                .doesNotContain("Caisse Centrale", "href=\"/caisse/journal\"", "href=\"/achats\"", "href=\"/fournisseurs\"", "href=\"/register\"");
        var catalogue = mvc.perform(get("/produits")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(catalogue).contains("Produit initial", "Prix Vente")
                .doesNotContain("Prix Achat", "Fournisseur initial", "/produits/nouveau", "/produits/modifier/", "/produits/supprimer/");
        var repertoire = mvc.perform(get("/clients")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(repertoire).contains("/clients/modifier/", "href=\"/clients/nouveau\"")
                .doesNotContain("/clients/supprimer/");
    }

    @Test
    void administrateurConserveSesIndicateursEtLesActionsInventaire() throws Exception {
        var accueil = mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(model().attributeExists("soldeCaisse", "soldeOuverture", "entreesJour", "sortiesJour"))
                .andReturn().getResponse().getContentAsString();
        assertThat(accueil).contains("Caisse Centrale", "href=\"/achats\"", "href=\"/fournisseurs\"", "href=\"/caisse/journal\"");
        var catalogue = mvc.perform(get("/produits")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(catalogue).contains("Prix Achat", "Fournisseur initial", "/produits/nouveau", "/produits/modifier/", "/produits/supprimer/");
    }

    @Test
    @WithMockUser(username = "integrite@example.test", roles = "INCONNU")
    void unRoleInconnuNeRecupertPasLesDroitsParDefaut() throws Exception {
        for (String route : List.of("/", "/produits", "/clients", "/ventes", "/achats", "/caisse/journal", "/factures/liste")) {
            mvc.perform(get(route)).andExpect(status().isForbidden());
        }
    }

    @Test
    void lesServicesRefusentAussiUnIdentifiantDeDocumentExistant() {
        Achat achat = new Achat(); achat.setId(1L);
        Vente vente = new Vente(); vente.setId(1L);
        assertThatThrownBy(() -> achatService.enregistrerAchat(achat)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> venteService.effectuerVente(vente)).isInstanceOf(IllegalArgumentException.class);
        // Ne pas exécuter de requête JPA après ces erreurs : la transaction de test est marquée rollback-only.
        assertThat(produit.getQuantite()).isEqualTo(10L);
    }

    private void verifierDonneesIntactes() {
        assertThat(produits.count()).isEqualTo(1);
        assertThat(fournisseurs.count()).isEqualTo(1);
        assertThat(categories.count()).isEqualTo(1);
        assertThat(clients.count()).isEqualTo(1);
        assertThat(achats.count()).isZero();
        assertThat(ventes.count()).isZero();
        assertThat(caisse.count()).isZero();
        assertThat(produit.getNom()).isEqualTo("Produit initial");
        assertThat(produit.getQuantite()).isEqualTo(10L);
    }
}
