package org.example.stock.service;

import jakarta.persistence.EntityManager;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Deux comptes réellement authentifiés, sans boutique fournie par le client HTTP. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:boutique-crud-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Transactional
class DeuxBoutiquesCrudTests {
    private static final String MOT_DE_PASSE = "Boutiques-Test-2026!";

    @Autowired MockMvc mvc;
    @Autowired EntityManager entityManager;
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
    @Autowired UserDetailsService userDetailsService;
    @Autowired PasswordEncoder passwordEncoder;

    Boutique boutiqueA;
    Boutique boutiqueB;
    Categorie categorieA;
    Categorie categorieB;
    Client clientA;
    Client clientB;
    Fournisseur fournisseurA;
    Fournisseur fournisseurB;
    Produit produitA;
    Produit produitB;
    Authentication authenticationA;
    Authentication authenticationB;

    @BeforeEach
    void preparerDeuxBoutiquesIndependantes() {
        boutiqueA = boutique("Boutique A");
        boutiqueB = boutique("Boutique B");
        String motDePasseEncode = passwordEncoder.encode(MOT_DE_PASSE);
        Utilisateur gerantA = gerant("gerant-a@boutiques.test", boutiqueA, motDePasseEncode);
        Utilisateur gerantB = gerant("gerant-b@boutiques.test", boutiqueB, motDePasseEncode);
        categorieA = categorie("Catégorie A", boutiqueA);
        categorieB = categorie("Catégorie B", boutiqueB);
        clientA = client("Client A", boutiqueA);
        clientB = client("Client B", boutiqueB);
        fournisseurA = fournisseur("Fournisseur A", boutiqueA);
        fournisseurB = fournisseur("Fournisseur B", boutiqueB);
        produitA = produit("Produit A", boutiqueA, categorieA, fournisseurA);
        produitB = produit("Produit B", boutiqueB, categorieB, fournisseurB);
        entityManager.flush();
        DaoAuthenticationProvider authentification = new DaoAuthenticationProvider(userDetailsService);
        authentification.setPasswordEncoder(passwordEncoder);
        authenticationA = authentification.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(gerantA.getEmail(), MOT_DE_PASSE));
        authenticationB = authentification.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(gerantB.getEmail(), MOT_DE_PASSE));
        connecter(authenticationA);
    }

    @AfterEach
    void deconnecter() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @EnumSource(Fiche.class)
    void chaqueGerantNeVoitQueSesPropresFiches(Fiche fiche) throws Exception {
        List<?> listeA = (List<?>) mvc.perform(get("/" + fiche.route).with(authentication(authenticationA)))
                .andExpect(status().isOk()).andReturn().getModelAndView().getModel().get(fiche.route);
        List<?> listeB = (List<?>) mvc.perform(get("/" + fiche.route).with(authentication(authenticationB)))
                .andExpect(status().isOk()).andReturn().getModelAndView().getModel().get(fiche.route);
        assertThat(listeA).extracting(DeuxBoutiquesCrudTests::identifiant).containsExactly(idA(fiche));
        assertThat(listeB).extracting(DeuxBoutiquesCrudTests::identifiant).containsExactly(idB(fiche));
    }

    @ParameterizedTest
    @EnumSource(Fiche.class)
    void lesFormulairesNePeuventPasOuvrirUneFicheDuneAutreBoutique(Fiche fiche) throws Exception {
        mvc.perform(get("/" + fiche.route + "/modifier/" + idB(fiche)).with(authentication(authenticationA)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/" + fiche.route + "/modifier/999999999").with(authentication(authenticationA)))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @EnumSource(Fiche.class)
    void uneModificationDirecteDuneFicheEtrangereEstRefusee(Fiche fiche) throws Exception {
        mvc.perform(champs(post("/" + fiche.route + "/modifier/" + idB(fiche)), fiche, "Intrusion")
                        .with(authentication(authenticationA)).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(nomB(fiche)).endsWith(" B");
        assertThat(boutiqueB(fiche).getId()).isEqualTo(boutiqueB.getId());
        assertThat(produitB.getQuantite()).isEqualTo(6L);
    }

    @ParameterizedTest
    @EnumSource(Fiche.class)
    void uneSuppressionDirecteDuneFicheEtrangereEstRefusee(Fiche fiche) throws Exception {
        mvc.perform(post("/" + fiche.route + "/supprimer/" + idB(fiche))
                        .with(authentication(authenticationA)).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(existe(fiche, idB(fiche))).isTrue();
    }

    @ParameterizedTest
    @EnumSource(Fiche.class)
    void lesCreationsModificationsEtSuppressionsRestentDisponiblesPourSaBoutique(Fiche fiche) throws Exception {
        mvc.perform(champs(post("/" + fiche.route + "/" + fiche.creation), fiche, "Nouvelle fiche")
                        .with(authentication(authenticationA)).with(csrf()))
                .andExpect(redirectedUrl("/" + fiche.route));
        entityManager.flush();
        Object creation = nouvellesFiches(fiche).stream().filter(f -> nom(f).equals("Nouvelle fiche"))
                .findFirst().orElseThrow();
        assertThat(boutique(creation).getId()).isEqualTo(boutiqueA.getId());
        Long id = identifiant(creation);
        MockHttpServletRequestBuilder modification = champs(post("/" + fiche.route + "/modifier/" + id),
                fiche, "Fiche modifiée");
        if (creation instanceof Produit produit) modification.param("version", produit.getVersion().toString());
        mvc.perform(modification.with(authentication(authenticationA)).with(csrf()))
                .andExpect(redirectedUrl("/" + fiche.route));
        assertThat(nom(creation)).isEqualTo("Fiche modifiée");
        assertThat(boutique(creation).getId()).isEqualTo(boutiqueA.getId());
        mvc.perform(post("/" + fiche.route + "/supprimer/" + id)
                        .with(authentication(authenticationA)).with(csrf()))
                .andExpect(redirectedUrl("/" + fiche.route));
        entityManager.flush();
        assertThat(existe(fiche, id)).isFalse();
        assertThat(existe(fiche, idB(fiche))).isTrue();
    }

    @ParameterizedTest
    @EnumSource(Fiche.class)
    void leFormulaireNePeutPasChoisirNiChangerDeBoutique(Fiche fiche) throws Exception {
        for (String champ : List.of("boutique", "boutique.id")) {
            mvc.perform(champs(post("/" + fiche.route + "/" + fiche.creation), fiche, "Boutique imposée")
                            .param(champ, boutiqueB.getId().toString())
                            .with(authentication(authenticationA)).with(csrf()))
                    .andExpect(status().isBadRequest());
            mvc.perform(champs(post("/" + fiche.route + "/modifier/" + idA(fiche)), fiche, "Boutique imposée")
                            .param(champ, boutiqueB.getId().toString())
                            .with(authentication(authenticationA)).with(csrf()))
                    .andExpect(status().isBadRequest());
        }
        assertThat(nouvellesFiches(fiche)).hasSize(1);
        assertThat(boutiqueA(fiche).getId()).isEqualTo(boutiqueA.getId());
    }

    @ParameterizedTest
    @EnumSource(Fiche.class)
    void lesServicesImposentAussiLaBoutiqueLorsDuneCreation(Fiche fiche) {
        connecter(authenticationA);
        Object nouvelle = nouvelleFiche(fiche, "Création serveur");
        affecterBoutique(nouvelle, boutiqueB);
        switch (fiche) {
            case PRODUIT -> produitService.ajouterProduit((Produit) nouvelle);
            case CATEGORIE -> categorieService.ajouterCategorie((Categorie) nouvelle);
            case CLIENT -> clientService.enregistrerClient((Client) nouvelle);
            case FOURNISSEUR -> fournisseurService.enregistrer((Fournisseur) nouvelle);
        }
        entityManager.flush();
        assertThat(boutique(nouvelle).getId()).isEqualTo(boutiqueA.getId());
        assertThat(nouvellesFiches(fiche)).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(Fiche.class)
    void lesServicesPreserventLaBoutiqueLorsDuneModification(Fiche fiche) {
        connecter(authenticationA);
        Object modifications = nouvelleFiche(fiche, "Modification serveur");
        affecterBoutique(modifications, boutiqueB);
        switch (fiche) {
            case PRODUIT -> {
                ((Produit) modifications).setVersion(produitA.getVersion());
                produitService.modifierProduit(idA(fiche), (Produit) modifications);
            }
            case CATEGORIE -> categorieService.modifierCategorie(idA(fiche), (Categorie) modifications);
            case CLIENT -> clientService.modifierClient(idA(fiche), (Client) modifications);
            case FOURNISSEUR -> fournisseurService.modifierFournisseur(idA(fiche), (Fournisseur) modifications);
        }
        entityManager.flush();
        assertThat(boutiqueA(fiche).getId()).isEqualTo(boutiqueA.getId());
        assertThat(nomB(fiche)).endsWith(" B");
    }

    @ParameterizedTest
    @EnumSource(Fiche.class)
    void lesServicesRefusentAussiLaLectureDuneFicheEtrangere(Fiche fiche) {
        connecter(authenticationA);
        assertThatThrownBy(() -> {
            switch (fiche) {
                case PRODUIT -> produitService.trouverParId(idB(fiche));
                case CATEGORIE -> categorieService.trouverParId(idB(fiche));
                case CLIENT -> clientService.trouverParId(idB(fiche));
                case FOURNISSEUR -> fournisseurService.trouverParId(idB(fiche));
            }
        }).isInstanceOf(ResponseStatusException.class)
                .satisfies(erreur -> assertThat(((ResponseStatusException) erreur).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void deuxBoutiquesPeuventAvoirLeMemeNomDeCategorie() throws Exception {
        String nom = "Catégorie commune";
        mvc.perform(post("/categories/ajouter").param("nom", nom)
                        .with(authentication(authenticationA)).with(csrf()))
                .andExpect(redirectedUrl("/categories"));
        mvc.perform(post("/categories/ajouter").param("nom", nom)
                        .with(authentication(authenticationB)).with(csrf()))
                .andExpect(redirectedUrl("/categories"));
        entityManager.flush();
        Categorie a = categories.findByNomAndBoutiqueId(nom, boutiqueA.getId()).orElseThrow();
        Categorie b = categories.findByNomAndBoutiqueId(nom, boutiqueB.getId()).orElseThrow();
        assertThat(a.getId()).isNotEqualTo(b.getId());
        mvc.perform(post("/categories/ajouter").param("nom", nom)
                        .with(authentication(authenticationA)).with(csrf()))
                .andExpect(status().isOk());
        assertThat(categories.findAllByBoutiqueId(boutiqueA.getId())).hasSize(2);
        connecter(authenticationA);
        assertThat(categorieService.existeDeja(categorieB.getNom())).isFalse();
        assertThat(categorieService.existeDeja(categorieA.getNom())).isTrue();
    }

    @Test
    void lesChoixDeRelationsDuFormulaireProduitRestentDansLaBoutique() throws Exception {
        var modele = mvc.perform(get("/produits/nouveau").with(authentication(authenticationA)))
                .andExpect(status().isOk()).andReturn().getModelAndView().getModel();
        assertThat((List<?>) modele.get("categories")).extracting(DeuxBoutiquesCrudTests::identifiant)
                .containsExactly(categorieA.getId());
        assertThat((List<?>) modele.get("fournisseurs")).extracting(DeuxBoutiquesCrudTests::identifiant)
                .containsExactly(fournisseurA.getId());
    }

    @Test
    void laCreationDeProduitRefuseUneCategorieEtrangere() throws Exception {
        mvc.perform(champs(post("/produits/ajouter"), Fiche.PRODUIT, "Produit intrus",
                        categorieB.getId(), fournisseurA.getId())
                        .with(authentication(authenticationA)).with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(produits.count()).isEqualTo(2);
    }

    @Test
    void laCreationDeProduitRefuseUnFournisseurEtranger() throws Exception {
        mvc.perform(champs(post("/produits/ajouter"), Fiche.PRODUIT, "Produit intrus",
                        categorieA.getId(), fournisseurB.getId())
                        .with(authentication(authenticationA)).with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(produits.count()).isEqualTo(2);
    }

    @Test
    void laModificationDeProduitRefuseUneCategorieEtrangere() throws Exception {
        mvc.perform(champs(post("/produits/modifier/" + produitA.getId()), Fiche.PRODUIT, "Produit intrus",
                        categorieB.getId(), fournisseurA.getId())
                        .with(authentication(authenticationA)).with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(produitA.getNom()).isEqualTo("Produit A");
        assertThat(produitA.getCategorie().getId()).isEqualTo(categorieA.getId());
    }

    @Test
    void laModificationDeProduitRefuseUnFournisseurEtranger() throws Exception {
        mvc.perform(champs(post("/produits/modifier/" + produitA.getId()), Fiche.PRODUIT, "Produit intrus",
                        categorieA.getId(), fournisseurB.getId())
                        .with(authentication(authenticationA)).with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(produitA.getNom()).isEqualTo("Produit A");
        assertThat(produitA.getFournisseur().getId()).isEqualTo(fournisseurA.getId());
    }

    private Boutique boutique(String nom) {
        Boutique boutique = new Boutique(); boutique.setNom(nom); return boutiques.save(boutique);
    }

    private Utilisateur gerant(String email, Boutique boutique, String motDePasseEncode) {
        Utilisateur gerant = new Utilisateur(); gerant.setNom(email); gerant.setEmail(email);
        gerant.setMotDePasse(motDePasseEncode); gerant.setRole(Role.ADMIN); gerant.setBoutique(boutique);
        return utilisateurs.save(gerant);
    }

    private Categorie categorie(String nom, Boutique boutique) {
        Categorie categorie = new Categorie(); categorie.setNom(nom); categorie.setBoutique(boutique);
        return categories.save(categorie);
    }

    private Client client(String nom, Boutique boutique) {
        Client client = new Client(); client.setNom(nom); client.setBoutique(boutique); return clients.save(client);
    }

    private Fournisseur fournisseur(String nom, Boutique boutique) {
        Fournisseur fournisseur = new Fournisseur(); fournisseur.setNom(nom); fournisseur.setBoutique(boutique);
        return fournisseurs.save(fournisseur);
    }

    private Produit produit(String nom, Boutique boutique, Categorie categorie, Fournisseur fournisseur) {
        Produit produit = produit(nom); produit.setBoutique(boutique);
        produit.setCategorie(categorie); produit.setFournisseur(fournisseur); return produits.save(produit);
    }

    private Produit produit(String nom) {
        Produit produit = new Produit(); produit.setNom(nom); produit.setReference("REF-" + nom);
        produit.setPrixAchat(new BigDecimal("2.10")); produit.setPrixVente(new BigDecimal("3.20"));
        produit.setQuantite(6L); produit.setCategorie(categorieA); produit.setFournisseur(fournisseurA);
        return produit;
    }

    private MockHttpServletRequestBuilder champs(MockHttpServletRequestBuilder requete, Fiche fiche, String nom) {
        return champs(requete, fiche, nom, categorieA.getId(), fournisseurA.getId());
    }

    private MockHttpServletRequestBuilder champs(MockHttpServletRequestBuilder requete, Fiche fiche, String nom,
                                                Long categorieId, Long fournisseurId) {
        requete.param("nom", nom);
        if (fiche == Fiche.PRODUIT) {
            requete.param("reference", "REF-TEST").param("prixVente", "3.20")
                    .param("categorie", categorieId.toString())
                    .param("fournisseur", fournisseurId.toString());
            if (requete.buildRequest(new org.springframework.mock.web.MockServletContext()).getRequestURI()
                    .contains("/modifier/")) requete.param("version", produitA.getVersion().toString());
        }
        return requete;
    }

    private Object nouvelleFiche(Fiche fiche, String nom) {
        return switch (fiche) {
            case PRODUIT -> produit(nom);
            case CATEGORIE -> { Categorie categorie = new Categorie(); categorie.setNom(nom); yield categorie; }
            case CLIENT -> { Client client = new Client(); client.setNom(nom); yield client; }
            case FOURNISSEUR -> { Fournisseur fournisseur = new Fournisseur(); fournisseur.setNom(nom); yield fournisseur; }
        };
    }

    private List<?> nouvellesFiches(Fiche fiche) {
        return switch (fiche) {
            case PRODUIT -> produits.findAllByBoutiqueId(boutiqueA.getId());
            case CATEGORIE -> categories.findAllByBoutiqueId(boutiqueA.getId());
            case CLIENT -> clients.findAllByBoutiqueId(boutiqueA.getId());
            case FOURNISSEUR -> fournisseurs.findAllByBoutiqueId(boutiqueA.getId());
        };
    }

    private boolean existe(Fiche fiche, Long id) {
        return switch (fiche) {
            case PRODUIT -> produits.existsById(id);
            case CATEGORIE -> categories.existsById(id);
            case CLIENT -> clients.existsById(id);
            case FOURNISSEUR -> fournisseurs.existsById(id);
        };
    }

    private Object ficheA(Fiche fiche) {
        return switch (fiche) {
            case PRODUIT -> produitA; case CATEGORIE -> categorieA; case CLIENT -> clientA; case FOURNISSEUR -> fournisseurA;
        };
    }

    private Object ficheB(Fiche fiche) {
        return switch (fiche) {
            case PRODUIT -> produitB; case CATEGORIE -> categorieB; case CLIENT -> clientB; case FOURNISSEUR -> fournisseurB;
        };
    }

    private Long idA(Fiche fiche) { return identifiant(ficheA(fiche)); }
    private Long idB(Fiche fiche) { return identifiant(ficheB(fiche)); }
    private String nomB(Fiche fiche) { return nom(ficheB(fiche)); }
    private Boutique boutiqueA(Fiche fiche) { return boutique(ficheA(fiche)); }
    private Boutique boutiqueB(Fiche fiche) { return boutique(ficheB(fiche)); }

    private static Long identifiant(Object fiche) {
        return switch (fiche) {
            case Produit produit -> produit.getId(); case Categorie categorie -> categorie.getId();
            case Client client -> client.getId(); case Fournisseur fournisseur -> fournisseur.getId();
            default -> throw new IllegalArgumentException("Fiche inconnue");
        };
    }

    private static String nom(Object fiche) {
        return switch (fiche) {
            case Produit produit -> produit.getNom(); case Categorie categorie -> categorie.getNom();
            case Client client -> client.getNom(); case Fournisseur fournisseur -> fournisseur.getNom();
            default -> throw new IllegalArgumentException("Fiche inconnue");
        };
    }

    private static Boutique boutique(Object fiche) {
        return switch (fiche) {
            case Produit produit -> produit.getBoutique(); case Categorie categorie -> categorie.getBoutique();
            case Client client -> client.getBoutique(); case Fournisseur fournisseur -> fournisseur.getBoutique();
            default -> throw new IllegalArgumentException("Fiche inconnue");
        };
    }

    private static void affecterBoutique(Object fiche, Boutique boutique) {
        switch (fiche) {
            case Produit produit -> produit.setBoutique(boutique); case Categorie categorie -> categorie.setBoutique(boutique);
            case Client client -> client.setBoutique(boutique); case Fournisseur fournisseur -> fournisseur.setBoutique(boutique);
            default -> throw new IllegalArgumentException("Fiche inconnue");
        }
    }

    private static void connecter(Authentication authentication) {
        var contexte = SecurityContextHolder.createEmptyContext(); contexte.setAuthentication(authentication);
        SecurityContextHolder.setContext(contexte);
    }

    enum Fiche {
        PRODUIT("produits", "ajouter"), CATEGORIE("categories", "ajouter"),
        CLIENT("clients", "enregistrer"), FOURNISSEUR("fournisseurs", "enregistrer");
        final String route;
        final String creation;
        Fiche(String route, String creation) { this.route = route; this.creation = creation; }
    }
}
