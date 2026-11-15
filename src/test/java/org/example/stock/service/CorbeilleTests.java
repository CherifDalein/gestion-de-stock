package org.example.stock.service;

import jakarta.persistence.EntityManager;
import org.example.stock.controller.FormBindingAdvice;
import org.example.stock.enums.Role;
import org.example.stock.model.Boutique;
import org.example.stock.model.Categorie;
import org.example.stock.model.Client;
import org.example.stock.model.Fournisseur;
import org.example.stock.model.Produit;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.BoutiqueRepository;
import org.example.stock.repository.CategorieRepository;
import org.example.stock.repository.ClientRepository;
import org.example.stock.repository.FournisseurRepository;
import org.example.stock.repository.ProduitRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.ServletRequestDataBinder;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Corbeille (lot 6) : mise à la corbeille, disparition des écrans, restauration, unicité, effacement, purge, droits et boutiques. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:corbeille-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Transactional
class CorbeilleTests {
    private static final String MOT_DE_PASSE = "Corbeille-Test-2026!";

    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired CategorieRepository categories;
    @Autowired ClientRepository clients;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired ProduitRepository produits;
    @Autowired CategorieService categorieService;
    @Autowired ClientService clientService;
    @Autowired FournisseurService fournisseurService;
    @Autowired ProduitService produitService;
    @Autowired CorbeilleService corbeille;
    @Autowired UserDetailsService userDetailsService;
    @Autowired PasswordEncoder passwordEncoder;

    Boutique boutiqueA, boutiqueB;
    Categorie categorieA, categorieB;
    Client clientA, clientB;
    Fournisseur fournisseurA, fournisseurB;
    Produit produitA, produitB;
    Authentication gerantA, gerantB, caissierA;

    @BeforeEach
    void preparer() {
        boutiqueA = boutique("Boutique A");
        boutiqueB = boutique("Boutique B");
        String mdp = passwordEncoder.encode(MOT_DE_PASSE);
        Utilisateur ua = utilisateur("gerant-a@corbeille.test", boutiqueA, Role.ADMIN, mdp);
        Utilisateur ub = utilisateur("gerant-b@corbeille.test", boutiqueB, Role.ADMIN, mdp);
        Utilisateur uc = utilisateur("caissier-a@corbeille.test", boutiqueA, Role.CAISSIER, mdp);
        categorieA = categorie("Catégorie A", boutiqueA);
        categorieB = categorie("Catégorie B", boutiqueB);
        clientA = client("Client A", boutiqueA);
        clientB = client("Client B", boutiqueB);
        fournisseurA = fournisseur("Fournisseur A", boutiqueA);
        fournisseurB = fournisseur("Fournisseur B", boutiqueB);
        produitA = produit("Produit A", boutiqueA, categorieA, fournisseurA);
        produitB = produit("Produit B", boutiqueB, categorieB, fournisseurB);
        em.flush();
        var fournisseurDeComptes = new DaoAuthenticationProvider(userDetailsService);
        fournisseurDeComptes.setPasswordEncoder(passwordEncoder);
        gerantA = fournisseurDeComptes.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(ua.getEmail(), MOT_DE_PASSE));
        gerantB = fournisseurDeComptes.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(ub.getEmail(), MOT_DE_PASSE));
        caissierA = fournisseurDeComptes.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(uc.getEmail(), MOT_DE_PASSE));
        connecter(gerantA);
    }

    @AfterEach
    void deconnecter() { SecurityContextHolder.clearContext(); }

    // ---- Mise à la corbeille et disparition des écrans -----------------------------------------------------------------------------

    @Test
    void supprimerUnClientLePlaceALaCorbeilleAvecLeMessageEtLeLienAnnuler() throws Exception {
        MvcResult resultat = mvc.perform(post("/clients/supprimer/" + clientA.getId()).with(authentication(gerantA)).with(csrf()))
                .andExpect(redirectedUrl("/clients")).andReturn();
        assertThat(resultat.getFlashMap().get("success")).isEqualTo("Client Client A placé dans la corbeille pour 30 jours.");
        assertThat(resultat.getFlashMap().get("annulerCorbeille")).isEqualTo(Map.of("type", "client", "id", clientA.getId()));
        connecter(gerantA);
        var ligne = corbeille.lister(null, "").get(0);
        assertThat(ligne.nom()).isEqualTo("Client A");
        assertThat(ligne.supprimePar()).isNotNull();
        assertThat(ligne.joursRestants()).isBetween(29L, 30L);
        // Le lien « Annuler » de la page de liste restaure immédiatement et revient à la liste.
        mvc.perform(post("/corbeille/restaurer/client/" + clientA.getId()).param("depuis", "liste")
                        .with(authentication(gerantA)).with(csrf()))
                .andExpect(redirectedUrl("/clients"));
        connecter(gerantA);
        assertThat(clientService.listerTous()).extracting(Client::getNom).contains("Client A");
        assertThat(corbeille.lister(null, "")).isEmpty();
    }

    @Test
    void laPageDeListeAfficheLeMessageEtLeLienAnnuler() throws Exception {
        String html = mvc.perform(get("/clients").flashAttr("success", "Client Client A placé dans la corbeille pour 30 jours.")
                        .flashAttr("annulerCorbeille", Map.of("type", "client", "id", clientA.getId()))
                        .with(authentication(gerantA)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("placé dans la corbeille pour 30 jours")
                .contains("/corbeille/restaurer/client/" + clientA.getId()).contains("Annuler");
    }

    @Test
    void uneFicheALaCorbeilleDisparaitDeToutesLesListesEtDesSelecteurs() throws Exception {
        produitA.setQuantite(0L);
        corbeille.placer(TypeCorbeille.CLIENT, clientA);
        corbeille.placer(TypeCorbeille.FOURNISSEUR, fournisseurA);
        Categorie vide = categorie("Vide", boutiqueA);
        em.flush();
        corbeille.placer(TypeCorbeille.CATEGORIE, vide);
        corbeille.placer(TypeCorbeille.PRODUIT, produitA);
        em.flush(); em.clear();

        assertThat(clientService.listerTous()).isEmpty();
        assertThat(fournisseurService.listerTous()).isEmpty();
        assertThat(produitService.listerTous()).isEmpty();
        assertThat(categorieService.listerToutes()).extracting(Categorie::getNom).containsExactly("Catégorie A");
        assertThat(categorieService.existeDeja("Vide")).isFalse();
        assertThat(clients.findAllByBoutiqueId(boutiqueA.getId())).isEmpty();
        assertThat(produits.findAllByBoutiqueId(boutiqueA.getId())).isEmpty();

        for (String url : List.of("/clients", "/fournisseurs", "/produits", "/categories")) {
            String html = mvc.perform(get(url).with(authentication(gerantA))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(html).doesNotContain("Client A", "Fournisseur A", "Produit A");
        }
        // Sélecteurs des ventes, achats, devis et produits.
        var vente = mvc.perform(get("/ventes/nouveau").with(authentication(gerantA))).andExpect(status().isOk()).andReturn().getModelAndView().getModel();
        assertThat((List<?>) vente.get("clients")).isEmpty();
        assertThat((List<?>) vente.get("produits")).isEmpty();
        var achat = mvc.perform(get("/achats/nouveau").with(authentication(gerantA))).andExpect(status().isOk()).andReturn().getModelAndView().getModel();
        assertThat((List<?>) achat.get("fournisseurs")).isEmpty();
        assertThat((List<?>) achat.get("produits")).isEmpty();
        var produit = mvc.perform(get("/produits/nouveau").with(authentication(gerantA))).andExpect(status().isOk()).andReturn().getModelAndView().getModel();
        assertThat((List<?>) produit.get("categories")).hasSize(1);
        assertThat((List<?>) produit.get("fournisseurs")).isEmpty();
        // Exports : ils partent des mêmes services de liste.
        for (String url : List.of("/clients/export/excel", "/clients/export/pdf", "/fournisseurs/export/excel", "/produits/export/excel", "/produits/export/pdf")) {
            mvc.perform(get(url).with(authentication(gerantA))).andExpect(status().isOk());
        }
    }

    @Test
    void lesCompteursEtLesTotauxIgnorentLesFichesDeLaCorbeille() {
        Produit autre = produit("Produit A2", boutiqueA, categorieA, fournisseurA);
        autre.setQuantite(0L);
        em.flush();
        assertThat(categories.compterProduitsParCategorie(boutiqueA.getId(), org.springframework.data.domain.PageRequest.of(0, 10))
                .get(0)[2]).isEqualTo(2L);
        corbeille.placer(TypeCorbeille.PRODUIT, autre);
        em.flush(); em.clear();
        assertThat(categories.compterProduitsParCategorie(boutiqueA.getId(), org.springframework.data.domain.PageRequest.of(0, 10))
                .get(0)[2]).isEqualTo(1L);
        assertThat(produits.count()).isEqualTo(2L);
    }

    // ---- Règles de suppression inchangées ------------------------------------------------------------------------------------------

    @Test
    void ceQuiEtaitRefuseResteRefuse() {
        assertThatThrownBy(() -> categorieService.supprimer(categorieA.getId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fournisseurService.supprimerFournisseur(fournisseurA.getId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> produitService.supprimerProduit(produitA.getId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stock");
        em.flush(); em.clear();
        assertThat(corbeille.lister(null, "")).isEmpty();
        assertThat(categorieService.listerToutes()).hasSize(1);
    }

    @Test
    void uneCategorieNeSupprimeQuApresSesProduits() {
        produitA.setQuantite(0L);
        em.flush();
        produitService.supprimerProduit(produitA.getId());
        em.flush(); em.clear();
        // Seul un produit à la corbeille la référence : la catégorie peut y aller (limite documentée).
        categorieService.supprimer(categorieA.getId());
        em.flush(); em.clear();
        assertThat(corbeille.lister(null, "")).hasSize(2);
        // Le produit ne revient pas avec une catégorie à la corbeille.
        assertThatThrownBy(() -> corbeille.restaurer(TypeCorbeille.PRODUIT, produitA.getId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Restaurez d’abord");
        corbeille.restaurer(TypeCorbeille.CATEGORIE, categorieA.getId());
        corbeille.restaurer(TypeCorbeille.PRODUIT, produitA.getId());
        em.flush(); em.clear();
        assertThat(produitService.listerTous()).extracting(Produit::getNom).containsExactly("Produit A");
    }

    // ---- Unicité --------------------------------------------------------------------------------------------------------------------

    @Test
    void onPeutRecreerUneCategorieDontLeNomEstALaCorbeille() throws Exception {
        Categorie boissons = categorie("Boissons", boutiqueA);
        em.flush();
        categorieService.supprimer(boissons.getId());
        em.flush(); em.clear();
        assertThat(categorieService.existeDeja("Boissons")).isFalse();
        mvc.perform(post("/categories/ajouter").param("nom", "Boissons").with(authentication(gerantA)).with(csrf()))
                .andExpect(redirectedUrl("/categories"));
        connecter(gerantA);
        em.flush(); em.clear();
        assertThat(categorieService.listerToutes()).extracting(Categorie::getNom).contains("Boissons");
        // Deux actives du même nom restent impossibles.
        mvc.perform(post("/categories/ajouter").param("nom", "Boissons").with(authentication(gerantA)).with(csrf()))
                .andExpect(status().isOk());
        connecter(gerantA);
        // Et on peut en mettre une seconde à la corbeille sous le même nom.
        Long active = categories.findByNomAndBoutiqueId("Boissons", boutiqueA.getId()).orElseThrow().getId();
        categorieService.supprimer(active);
        em.flush(); em.clear();
        assertThat(corbeille.lister(TypeCorbeille.CATEGORIE, "Boissons")).hasSize(2);
    }

    @Test
    void restaurerUneCategorieDontLeNomEstPrisEstRefuseAvecUnMessageClair() {
        Categorie ancienne = categorie("Boissons", boutiqueA);
        em.flush();
        categorieService.supprimer(ancienne.getId());
        em.flush(); em.clear();
        categorieService.ajouterCategorie(nouvelleCategorie("Boissons"));
        em.flush(); em.clear();
        assertThatThrownBy(() -> corbeille.restaurer(TypeCorbeille.CATEGORIE, ancienne.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Boissons").hasMessageContaining("Renommez d’abord la catégorie active");
        assertThat(corbeille.lister(TypeCorbeille.CATEGORIE, "")).hasSize(1);
    }

    @Test
    void lesClientsEtFournisseursHomonymesRestaurentSansConflit() {
        // Pas d'unicité sur le nom d'un client ou d'un fournisseur : la restauration réussit.
        Client homonyme = client("Client A", boutiqueA);
        em.flush();
        clientService.supprimerClient(clientA.getId());
        em.flush(); em.clear();
        corbeille.restaurer(TypeCorbeille.CLIENT, clientA.getId());
        em.flush(); em.clear();
        assertThat(clientService.listerTous()).extracting(Client::getId).contains(clientA.getId(), homonyme.getId());
    }

    // ---- Restauration, effacement, vidage ---------------------------------------------------------------------------------------------

    @Test
    void restaurerRendLaFicheAuxEcransEtVideLaCorbeille() throws Exception {
        clientService.supprimerClient(clientA.getId());
        em.flush(); em.clear();
        MvcResult r = mvc.perform(post("/corbeille/restaurer/client/" + clientA.getId()).with(authentication(gerantA)).with(csrf()))
                .andExpect(redirectedUrl("/corbeille")).andReturn();
        assertThat(r.getFlashMap().get("success")).isEqualTo("Client Client A restauré.");
        connecter(gerantA);
        em.flush(); em.clear();
        assertThat(clientService.listerTous()).extracting(Client::getNom).containsExactly("Client A");
        assertThat(corbeille.lister(null, "")).isEmpty();
    }

    @Test
    void supprimerDefinitivementEfface() throws Exception {
        clientService.supprimerClient(clientA.getId());
        em.flush(); em.clear();
        mvc.perform(post("/corbeille/supprimer/client/" + clientA.getId()).with(authentication(gerantA)).with(csrf()))
                .andExpect(redirectedUrl("/corbeille"));
        connecter(gerantA);
        em.flush(); em.clear();
        assertThat(corbeille.lister(null, "")).isEmpty();
        assertThat(((Number) em.createNativeQuery("select count(*) from client where id = :id").setParameter("id", clientA.getId()).getSingleResult()).intValue()).isZero();
        // Une fiche qui n'est pas à la corbeille ne s'efface pas par cette route.
        mvc.perform(post("/corbeille/supprimer/client/" + clientB.getId()).with(authentication(gerantA)).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void uneCategorieEncoreReferenceeParUnProduitDeLaCorbeilleNEstPasEffacee() {
        produitA.setQuantite(0L);
        em.flush();
        produitService.supprimerProduit(produitA.getId());
        em.flush(); em.clear();
        categorieService.supprimer(categorieA.getId());
        em.flush(); em.clear();
        assertThatThrownBy(() -> corbeille.supprimerDefinitivement(TypeCorbeille.CATEGORIE, categorieA.getId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("encore utilisé");
        var bilan = corbeille.vider();
        assertThat(bilan.effaces()).isEqualTo(2);
        assertThat(bilan.conserves()).isZero();
        assertThat(corbeille.lister(null, "")).isEmpty();
    }

    @Test
    void viderLaCorbeilleEffaceToutLeContenuDeLaBoutiqueSeulement() throws Exception {
        clientService.supprimerClient(clientA.getId());
        categorie("Vide", boutiqueA);
        em.flush();
        connecter(gerantB);
        clientService.supprimerClient(clientB.getId());
        em.flush(); em.clear();
        connecter(gerantA);
        mvc.perform(post("/corbeille/vider").with(authentication(gerantA)).with(csrf())).andExpect(redirectedUrl("/corbeille"));
        connecter(gerantB);
        em.flush(); em.clear();
        connecter(gerantA);
        assertThat(corbeille.lister(null, "")).isEmpty();
        connecter(gerantB);
        assertThat(corbeille.lister(null, "")).extracting(CorbeilleService.Ligne::nom).containsExactly("Client B");
    }

    @Test
    void laPageAfficheLesFichesFiltreEtRecherche() throws Exception {
        clientService.supprimerClient(clientA.getId());
        fournisseurService.supprimerFournisseur(fournisseur("Four Z").getId());
        em.flush(); em.clear();
        String tout = mvc.perform(get("/corbeille").with(authentication(gerantA))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(tout).contains("Client A", "Four Z");
        String filtre = mvc.perform(get("/corbeille").param("type", "client").with(authentication(gerantA))).andReturn().getResponse().getContentAsString();
        assertThat(filtre).contains("Client A").doesNotContain("Four Z");
        String recherche = mvc.perform(get("/corbeille").param("q", "four z").with(authentication(gerantA))).andReturn().getResponse().getContentAsString();
        assertThat(recherche).contains("Four Z").doesNotContain("Client A");
        mvc.perform(get("/corbeille").param("type", "inconnu").with(authentication(gerantA))).andExpect(status().isBadRequest());
    }

    // ---- Purge automatique ----------------------------------------------------------------------------------------------------------

    @Test
    void laPurgeEffaceSeulementCeQuiDepasseLaDureeDeConservation() {
        clientService.supprimerClient(clientA.getId());
        fournisseurService.supprimerFournisseur(fournisseur("Four Z", boutiqueA).getId());
        em.flush(); em.clear();
        var dans29Jours = corbeille.purgerExpires(LocalDateTime.now().plusDays(29));
        assertThat(dans29Jours.effaces()).isZero();
        var dans31Jours = corbeille.purgerExpires(LocalDateTime.now().plusDays(31));
        assertThat(dans31Jours.effaces()).isEqualTo(2);
        em.flush(); em.clear();
        assertThat(corbeille.lister(null, "")).isEmpty();
        assertThat(clients.findAllByBoutiqueId(boutiqueB.getId())).hasSize(1);
    }

    @Test
    void laPurgeTouteLesBoutiquesEtLaisseLesFichesActives() {
        connecter(gerantB);
        clientService.supprimerClient(clientB.getId());
        connecter(gerantA);
        clientService.supprimerClient(clientA.getId());
        em.flush(); em.clear();
        assertThat(corbeille.purgerExpires(LocalDateTime.now().plusDays(31)).effaces()).isEqualTo(2);
        em.flush(); em.clear();
        assertThat(produits.count()).isEqualTo(2L);
    }

    // ---- Droits, boutiques, liaison, CSRF -------------------------------------------------------------------------------------------

    @Test
    void leCaissierNaAucunAccesALaCorbeille() throws Exception {
        clientService.supprimerClient(clientA.getId());
        em.flush(); em.clear();
        mvc.perform(get("/corbeille").with(authentication(caissierA))).andExpect(status().isForbidden());
        mvc.perform(post("/corbeille/restaurer/client/" + clientA.getId()).with(authentication(caissierA)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/corbeille/supprimer/client/" + clientA.getId()).with(authentication(caissierA)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/corbeille/vider").with(authentication(caissierA)).with(csrf())).andExpect(status().isForbidden());
        connecter(caissierA);
        assertThatThrownBy(() -> corbeille.lister(null, "")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> corbeille.restaurer(TypeCorbeille.CLIENT, clientA.getId())).isInstanceOf(RuntimeException.class);
        assertThat(((Number) em.createNativeQuery("select count(*) from client where id = :id and supprime_le is not null").setParameter("id", clientA.getId()).getSingleResult()).intValue()).isOne();
    }

    @Test
    void leMenuDuCaissierNeMontrePasLaCorbeilleEtCeluiDuGerantOui() throws Exception {
        assertThat(mvc.perform(get("/clients").with(authentication(caissierA))).andReturn().getResponse().getContentAsString()).doesNotContain("/corbeille\"");
        assertThat(mvc.perform(get("/clients").with(authentication(gerantA))).andReturn().getResponse().getContentAsString()).contains("/corbeille");
    }

    @Test
    void uneFicheALaCorbeilleDunAutreMagasinEstIntrouvable() throws Exception {
        connecter(gerantB);
        clientService.supprimerClient(clientB.getId());
        em.flush(); em.clear();
        mvc.perform(post("/corbeille/restaurer/client/" + clientB.getId()).with(authentication(gerantA)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/corbeille/supprimer/client/" + clientB.getId()).with(authentication(gerantA)).with(csrf())).andExpect(status().isNotFound());
        connecter(gerantA);
        assertThat(corbeille.lister(null, "")).isEmpty();
        assertThat(String.valueOf(mvc.perform(get("/corbeille").with(authentication(gerantA))).andReturn().getResponse().getContentAsString())).doesNotContain("Client B");
    }

    @Test
    void lesPostSansCsrfEtLesTypesInconnusSontRefuses() throws Exception {
        clientService.supprimerClient(clientA.getId());
        em.flush(); em.clear();
        mvc.perform(post("/corbeille/restaurer/client/" + clientA.getId()).with(authentication(gerantA))).andExpect(status().isForbidden());
        mvc.perform(post("/corbeille/supprimer/client/" + clientA.getId()).with(authentication(gerantA))).andExpect(status().isForbidden());
        mvc.perform(post("/corbeille/vider").with(authentication(gerantA))).andExpect(status().isForbidden());
        mvc.perform(post("/corbeille/restaurer/utilisateur/1").with(authentication(gerantA)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/corbeille/restaurer/client/999999999").with(authentication(gerantA)).with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void lesChampsDeCorbeilleNeSontPasLiablesDepuisUnFormulaire() {
        Map<String, Object> cibles = Map.of("client", new Client(), "fournisseur", new Fournisseur(), "produit", new Produit(), "categorie", new Categorie());
        for (var entree : cibles.entrySet()) {
            for (String champ : List.of("supprimeLe", "supprimePar", "nomCle")) {
                MockHttpServletRequest requete = new MockHttpServletRequest("POST", "/clients/modifier/1");
                requete.addParameter(champ, "2020-01-01T00:00:00");
                ServletRequestDataBinder binder = new ServletRequestDataBinder(entree.getValue(), entree.getKey());
                assertThatThrownBy(() -> new FormBindingAdvice().limiterChamps(binder, requete))
                        .isInstanceOf(ResponseStatusException.class);
            }
        }
    }

    // ---- Aides ----------------------------------------------------------------------------------------------------------------------

    private void connecter(Authentication authentification) {
        SecurityContextHolder.getContext().setAuthentication(authentification);
    }

    private Boutique boutique(String nom) { Boutique b = new Boutique(); b.setNom(nom); return boutiques.save(b); }

    private Utilisateur utilisateur(String email, Boutique boutique, Role role, String mdp) {
        Utilisateur u = new Utilisateur(); u.setNom(email); u.setEmail(email); u.setMotDePasse(mdp); u.setRole(role); u.setBoutique(boutique);
        return utilisateurs.save(u);
    }

    private Categorie categorie(String nom, Boutique boutique) { Categorie c = new Categorie(); c.setNom(nom); c.setBoutique(boutique); return categories.save(c); }

    private Categorie nouvelleCategorie(String nom) { Categorie c = new Categorie(); c.setNom(nom); return c; }

    private Client client(String nom, Boutique boutique) { Client c = new Client(); c.setNom(nom); c.setBoutique(boutique); return clients.save(c); }

    private Fournisseur fournisseur(String nom, Boutique boutique) { Fournisseur f = new Fournisseur(); f.setNom(nom); f.setBoutique(boutique); return fournisseurs.save(f); }

    private Fournisseur fournisseur(String nom) { return fournisseur(nom, boutiqueA); }

    private Produit produit(String nom, Boutique boutique, Categorie categorie, Fournisseur fournisseur) {
        Produit p = new Produit(); p.setNom(nom); p.setReference("REF-" + nom); p.setPrixAchat(new BigDecimal("2.10")); p.setPrixVente(new BigDecimal("3.20"));
        p.setQuantite(6L); p.setBoutique(boutique); p.setCategorie(categorie); p.setFournisseur(fournisseur);
        return produits.save(p);
    }
}
