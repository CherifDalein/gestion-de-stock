package org.example.stock.service;

import org.example.stock.enums.NatureProduit;
import org.example.stock.enums.Role;
import org.example.stock.form.AjustementStockForm;
import org.example.stock.form.StockInitialForm;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Seuil d'alerte par produit, unités d'affichage et services vendus sans stock, sur deux boutiques réelles. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:seuils-unites-services;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class SeuilsUnitesServicesTests {
    private static final String GERANT_A = "seuils-gerant-a@example.test";
    private static final String CAISSIER_A = "seuils-caissier-a@example.test";
    private static final String GERANT_B = "seuils-gerant-b@example.test";

    @Autowired MockMvc mvc;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired CategorieRepository categories;
    @Autowired ProduitRepository produits;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired ClientRepository clients;
    @Autowired AchatRepository achats;
    @Autowired VenteRepository ventes;
    @Autowired MouvementCaisseRepository caisse;
    @Autowired MouvementStockRepository mouvementsStock;
    @Autowired OperationCreationRepository operations;
    @Autowired AchatService achatService;
    @Autowired VenteService venteService;
    @Autowired InventaireService inventaire;

    Boutique boutiqueA, boutiqueB;
    Categorie categorieA, categorieB;
    Fournisseur fournisseurA;
    Client clientA;
    Produit cimentA, livraisonA, serviceB;

    @BeforeEach
    void preparer() {
        operations.deleteAll(); mouvementsStock.deleteAll(); caisse.deleteAll(); ventes.deleteAll(); achats.deleteAll();
        produits.deleteAll(); clients.deleteAll(); fournisseurs.deleteAll(); categories.deleteAll(); utilisateurs.deleteAll(); boutiques.deleteAll();
        boutiqueA = boutique("Quincaillerie A"); boutiqueB = boutique("Boutique B");
        utilisateur(GERANT_A, boutiqueA, Role.ADMIN); utilisateur(CAISSIER_A, boutiqueA, Role.CAISSIER);
        utilisateur(GERANT_B, boutiqueB, Role.ADMIN);
        categorieA = categorie(boutiqueA); categorieB = categorie(boutiqueB);
        fournisseurA = new Fournisseur(); fournisseurA.setNom("Fournisseur A"); fournisseurA.setBoutique(boutiqueA);
        fournisseurA = fournisseurs.saveAndFlush(fournisseurA);
        clientA = new Client(); clientA.setNom("Client A"); clientA.setBoutique(boutiqueA); clientA = clients.saveAndFlush(clientA);
        cimentA = article(boutiqueA, categorieA, "Ciment", 10L, 3L, "sac");
        livraisonA = service(boutiqueA, categorieA, "Livraison", "50000", "20000");
        serviceB = service(boutiqueB, categorieB, "Service secret B", "1000", "0");
    }

    @AfterEach
    void deconnecter() { SecurityContextHolder.clearContext(); }

    // ----- Fiches -----

    @Test
    void leGerantCreeUnServiceAvecPrixEtCoutDefinisDesLaCreation() throws Exception {
        mvc.perform(gerantA(post("/produits/ajouter")).param("nature", "SERVICE").param("nom", "Pose de carrelage")
                        .param("reference", "SRV-POSE").param("categorie", categorieA.getId().toString())
                        .param("unite", "  mètre   carré ").param("prixVente", "15000").param("coutMoyen", "4000.50"))
                .andExpect(redirectedUrl("/produits"));
        Produit pose = parNom("Pose de carrelage");
        assertThat(pose.getNature()).isEqualTo(NatureProduit.SERVICE);
        assertThat(pose.isPrixDefinis()).isTrue();
        assertThat(pose.getPrixVente()).isEqualByComparingTo("15000");
        assertThat(pose.getCoutMoyen()).isEqualByComparingTo("4000.50");
        assertThat(pose.getQuantite()).isZero();
        assertThat(pose.getUnite()).isEqualTo("mètre carré");
        assertThat(pose.getBoutique().getId()).isEqualTo(boutiqueA.getId());
    }

    @Test
    void leCoutDuServiceEstFacultatifEtVautZeroParDefaut() throws Exception {
        mvc.perform(gerantA(post("/produits/ajouter")).param("nature", "SERVICE").param("nom", "Conseil")
                        .param("reference", "SRV-CONSEIL").param("categorie", categorieA.getId().toString())
                        .param("prixVente", "5000").param("coutMoyen", ""))
                .andExpect(redirectedUrl("/produits"));
        assertThat(parNom("Conseil").getCoutMoyen()).isZero();
    }

    @Test
    void unArticleEnStockGardeSonSeuilSonUniteEtAttendSesPrix() throws Exception {
        mvc.perform(gerantA(post("/produits/ajouter")).param("nature", "STOCK").param("nom", "Huile")
                        .param("reference", "HUILE-5L").param("categorie", categorieA.getId().toString())
                        .param("unite", "bidon").param("seuilAlerte", "12"))
                .andExpect(redirectedUrl("/produits"));
        Produit huile = parNom("Huile");
        assertThat(huile.getNature()).isEqualTo(NatureProduit.STOCK);
        assertThat(huile.getSeuilAlerte()).isEqualTo(12L);
        assertThat(huile.getUnite()).isEqualTo("bidon");
        assertThat(huile.isPrixDefinis()).isFalse();
        assertThat(huile.getCoutMoyen()).isZero();
    }

    @Test
    void leSeuilParDefautEstCinq() {
        assertThat(new Produit().getSeuilAlerte()).isEqualTo(5L);
    }

    @ParameterizedTest
    @CsvSource({"seuilAlerte,-1", "seuilAlerte,''", "unite,unité beaucoup trop longue pour être admise"})
    void lesSaisiesInvalidesSontSignaleesSansEnregistrer(String champ, String valeur) throws Exception {
        long avant = produits.count();
        mvc.perform(gerantA(post("/produits/ajouter")).param("nature", "STOCK").param("nom", "Invalide")
                        .param("reference", "INVALIDE").param("categorie", categorieA.getId().toString()).param(champ, valeur))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("produit", champ));
        assertThat(produits.count()).isEqualTo(avant);
    }

    @Test
    void unServiceSansPrixDeVenteEstRefuse() throws Exception {
        long avant = produits.count();
        mvc.perform(gerantA(post("/produits/ajouter")).param("nature", "SERVICE").param("nom", "Sans prix")
                        .param("reference", "SRV-SANS").param("categorie", categorieA.getId().toString()).param("prixVente", ""))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("produit", "prixVente"));
        assertThat(produits.count()).isEqualTo(avant);
    }

    @ParameterizedTest
    @CsvSource({
            "/produits/ajouter,prixVente,10",
            "/produits/ajouter,coutMoyen,10",
            "/produits/ajouter,quantite,10",
            "/produits/ajouter,prixDefinis,true",
            "/produits/ajouter,nature,INCONNUE",
            "/produits/ajouter,version,0"
    })
    void lesChampsNonAutorisesALaCreationSontToujoursRejetes(String route, String champ, String valeur) throws Exception {
        long avant = produits.count();
        mvc.perform(gerantA(post(route)).param("nom", "Intrus").param("reference", "INTRUS")
                        .param("categorie", categorieA.getId().toString()).param(champ, valeur))
                .andExpect(status().isBadRequest());
        assertThat(produits.count()).isEqualTo(avant);
    }

    @Test
    void deuxNaturesDansLaMemeRequeteSontRejetees() throws Exception {
        mvc.perform(gerantA(post("/produits/ajouter")).param("nature", "SERVICE", "STOCK").param("nom", "Double")
                        .param("reference", "DOUBLE").param("categorie", categorieA.getId().toString()).param("prixVente", "1"))
                .andExpect(status().isBadRequest());
        assertThat(produits.findAll()).noneMatch(p -> p.getNom().equals("Double"));
    }

    @Test
    void laNatureNePeutPasEtreChangeeApresLaCreation() throws Exception {
        for (Produit fiche : List.of(cimentA, livraisonA)) {
            mvc.perform(modification(fiche).param("nature", fiche.estService() ? "STOCK" : "SERVICE"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(produits.findById(cimentA.getId()).orElseThrow().getNature()).isEqualTo(NatureProduit.STOCK);
        assertThat(produits.findById(livraisonA.getId()).orElseThrow().getNature()).isEqualTo(NatureProduit.SERVICE);
    }

    @Test
    void leCoutDUnArticleEnStockNeSeSaisitPasSurLaFiche() throws Exception {
        BigDecimal avant = produits.findById(cimentA.getId()).orElseThrow().getCoutMoyen();
        mvc.perform(modification(cimentA).param("prixVente", "100").param("seuilAlerte", "3").param("coutMoyen", "1"))
                .andExpect(status().isBadRequest());
        assertThat(produits.findById(cimentA.getId()).orElseThrow().getCoutMoyen()).isEqualByComparingTo(avant);
    }

    @Test
    void laModificationMetAJourSeuilUniteEtPrixDuService() throws Exception {
        mvc.perform(modification(cimentA).param("prixVente", "120").param("seuilAlerte", "0").param("unite", "tonne"))
                .andExpect(redirectedUrl("/produits"));
        Produit ciment = produits.findById(cimentA.getId()).orElseThrow();
        assertThat(ciment.getSeuilAlerte()).isZero();
        assertThat(ciment.getUnite()).isEqualTo("tonne");
        assertThat(ciment.getQuantite()).isEqualTo(10L);

        mvc.perform(modification(livraisonA).param("prixVente", "60000").param("coutMoyen", "25000").param("unite", ""))
                .andExpect(redirectedUrl("/produits"));
        Produit livraison = produits.findById(livraisonA.getId()).orElseThrow();
        assertThat(livraison.getPrixVente()).isEqualByComparingTo("60000");
        assertThat(livraison.getCoutMoyen()).isEqualByComparingTo("25000");
        assertThat(livraison.getUnite()).isNull();
        assertThat(livraison.getQuantite()).isZero();
    }

    @Test
    void leCaissierNeGerePasLesFichesMaisVoitLeCatalogue() throws Exception {
        mvc.perform(post("/produits/ajouter").with(user(CAISSIER_A).roles("CAISSIER")).with(csrf())
                        .param("nature", "SERVICE").param("nom", "Pirate").param("reference", "P")
                        .param("categorie", categorieA.getId().toString()).param("prixVente", "1"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/produits/modifier/" + livraisonA.getId()).with(user(CAISSIER_A).roles("CAISSIER")).with(csrf())
                        .param("version", livraisonA.getVersion().toString()).param("nom", "Livraison").param("reference", "SRV")
                        .param("categorie", categorieA.getId().toString()).param("prixVente", "1"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/produits").with(user(CAISSIER_A).roles("CAISSIER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Livraison")))
                .andExpect(content().string(not(containsString("Prix d’achat"))));
        assertThat(produits.findAll()).noneMatch(p -> p.getNom().equals("Pirate"));
        assertThat(produits.findById(livraisonA.getId()).orElseThrow().getPrixVente()).isEqualByComparingTo("50000");
    }

    @Test
    void lesFormulairesAffichentLesChampsSelonLaNature() throws Exception {
        mvc.perform(gerantA(get("/produits/nouveau"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"nature\" value=\"SERVICE\"")))
                .andExpect(content().string(containsString("id=\"unitesSuggerees\"")))
                .andExpect(content().string(containsString("name=\"seuilAlerte\"")));
        mvc.perform(gerantA(get("/produits/modifier/" + livraisonA.getId()))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Service, vendu sans stock")))
                .andExpect(content().string(containsString("name=\"coutMoyen\"")))
                .andExpect(content().string(not(containsString("name=\"seuilAlerte\""))))
                .andExpect(content().string(not(containsString("name=\"nature\""))));
        mvc.perform(gerantA(get("/produits/modifier/" + cimentA.getId()))).andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"seuilAlerte\"")))
                .andExpect(content().string(not(containsString("name=\"coutMoyen\""))))
                .andExpect(content().string(containsString("10</strong> <span>sac</span>")));
        mvc.perform(gerantA(get("/produits/" + livraisonA.getId() + "/stock"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Service vendu sans stock")))
                .andExpect(content().string(not(containsString("stockInitialQuantite"))));
    }

    // ----- États du stock -----

    @ParameterizedTest
    @CsvSource({"0,5,rupture", "1,5,faible", "5,5,faible", "6,5,disponible", "1,0,disponible", "0,0,rupture", "12,20,faible"})
    void lEtatDuStockDependDuSeuilDeChaqueProduit(long quantite, long seuil, String attendu) {
        Produit produit = new Produit(); produit.setQuantite(quantite); produit.setSeuilAlerte(seuil);
        assertThat(produit.etatStock()).isEqualTo(attendu);
        produit.setNature(NatureProduit.SERVICE);
        assertThat(produit.etatStock()).isEqualTo("service");
    }

    @Test
    void laListeCalculeLesCompteursAvecLeSeuilEtSansLesServices() throws Exception {
        article(boutiqueA, categorieA, "Riz", 0L, 5L, "sac");          // rupture
        article(boutiqueA, categorieA, "Sucre", 20L, 25L, "kg");       // faible grâce à son propre seuil
        article(boutiqueA, categorieA, "Clous", 4L, 0L, null);         // disponible : seuil 0
        // cimentA : 10 avec un seuil de 3 → disponible ; livraisonA : service.
        var resultat = mvc.perform(gerantA(get("/produits"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("products-stock-badge--service")))
                .andExpect(content().string(containsString(">sac<")))
                .andExpect(content().string(not(containsString("Service secret B"))))
                .andReturn();
        var resume = (ProduitService.ResumeCatalogue) resultat.getModelAndView().getModel().get("resume");
        assertThat(resume.total()).isEqualTo(5);
        assertThat(resume.enStock()).isEqualTo(3);
        assertThat(resume.faibles()).isEqualTo(1);
        assertThat(resume.ruptures()).isEqualTo(1);
        assertThat(resume.services()).isEqualTo(1);
        String html = resultat.getResponse().getContentAsString();
        assertThat(html).contains("data-stock-state=\"faible\"").contains("data-stock-state=\"service\"");
    }

    // ----- Ventes, achats, stock initial et ajustement -----

    @Test
    void uneVenteMixteDiminueLeStockDuProduitEtRienPourLeService() throws Exception {
        String jeton = (String) mvc.perform(get("/ventes/nouveau").with(user(CAISSIER_A).roles("CAISSIER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Livraison · Service")))
                .andExpect(content().string(containsString("Ciment (10 sac en stock)")))
                .andReturn().getModelAndView().getModel().get("jetonCreation");
        mvc.perform(post("/ventes/enregistrer").with(user(CAISSIER_A).roles("CAISSIER")).with(csrf())
                        .param("jetonCreation", jeton).param("comptePaiement", "CAISSE").param("montantVerse", "170000")
                        .param("client", clientA.getId().toString())
                        .param("lignes[0].produit.id", cimentA.getId().toString()).param("lignes[0].quantite", "4")
                        .param("lignes[1].produit.id", livraisonA.getId().toString()).param("lignes[1].quantite", "3"))
                .andExpect(redirectedUrl("/ventes"));
        assertThat(produits.findById(cimentA.getId()).orElseThrow().getQuantite()).isEqualTo(6L);
        Produit livraison = produits.findById(livraisonA.getId()).orElseThrow();
        assertThat(livraison.getQuantite()).isZero();
        assertThat(livraison.getVersion()).isEqualTo(livraisonA.getVersion());
        assertThat(mouvementsStock.count()).isZero();
        Vente vente = ventes.findAll().getFirst();
        assertThat(vente.getMontantTotal()).isEqualByComparingTo("170000"); // 4 × 5 000 + 3 × 50 000
        assertThat(vente.getBoutique().getId()).isEqualTo(boutiqueA.getId());
        mvc.perform(get("/factures/vente/" + vente.getId()).with(user(CAISSIER_A).roles("CAISSIER")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("4 sac")));
        mvc.perform(get("/ventes").with(user(CAISSIER_A).roles("CAISSIER")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("4 sac")));
        mvc.perform(get("/factures/client/" + clientA.getId() + "/cumule").with(user(CAISSIER_A).roles("CAISSIER")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("× 4 sac")));
    }

    @Test
    void unServiceSeVendSansLimiteDeStock() {
        Vente vente = comme(CAISSIER_A, "CAISSIER", () -> venteService.effectuerVente(vente(livraisonA, 1000)));
        assertThat(vente.getMontantTotal()).isEqualByComparingTo("50000000");
        assertThat(produits.findById(livraisonA.getId()).orElseThrow().getQuantite()).isZero();
    }

    @Test
    void unServiceEstRefuseALAchatEtALaModificationDAchat() {
        assertThatThrownBy(() -> comme(GERANT_A, "ADMIN", () -> achatService.enregistrerAchat(achat(livraisonA))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Livraison").hasMessageContaining("service");
        assertThat(achats.count()).isZero();
        assertThat(caisse.count()).isZero();

        Achat achat = comme(GERANT_A, "ADMIN", () -> achatService.enregistrerAchat(achat(cimentA)));
        Achat modification = achat(livraisonA); modification.setMontantVerse(null);
        assertThatThrownBy(() -> comme(GERANT_A, "ADMIN", () -> { achatService.modifierAchat(achat.getId(), modification); return null; }))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("service");
        assertThat(produits.findById(cimentA.getId()).orElseThrow().getQuantite()).isEqualTo(12L);
        assertThat(produits.findById(livraisonA.getId()).orElseThrow().getQuantite()).isZero();
    }

    @Test
    void lAchatHttpNeProposePasLesServices() throws Exception {
        mvc.perform(gerantA(get("/achats/nouveau"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Ciment (sac)")))
                .andExpect(content().string(not(containsString("Livraison"))));
    }

    @Test
    void unServiceEstRefuseAuStockInitialEtALAjustement() {
        viderLeStockPourGarderLeDemarrageOuvert();
        var initial = new StockInitialForm(); initial.setVersion(livraisonA.getVersion()); initial.setQuantite(5L);
        initial.setCoutUnitaire(BigDecimal.TEN); initial.setPrixVente(BigDecimal.TEN); initial.setMotif("Déjà présent");
        assertThatThrownBy(() -> comme(GERANT_A, "ADMIN", () -> { inventaire.initialiser(livraisonA.getId(), initial); return null; }))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("service").hasMessageContaining("stock initial");
        var ajustement = new AjustementStockForm(); ajustement.setVersion(livraisonA.getVersion()); ajustement.setQuantite(3L);
        ajustement.setMotif("Inventaire");
        assertThatThrownBy(() -> comme(GERANT_A, "ADMIN", () -> { inventaire.ajuster(livraisonA.getId(), ajustement); return null; }))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("service").hasMessageContaining("ajustement");
        assertThat(mouvementsStock.count()).isZero();
        assertThat(produits.findById(livraisonA.getId()).orElseThrow().getQuantite()).isZero();
        assertThat(comme(GERANT_A, "ADMIN", () -> inventaire.consulter(livraisonA.getId(), 0).initialAutorise())).isFalse();
    }

    @Test
    void leStockInitialRestePossiblePourUnArticleDuDemarrage() {
        viderLeStockPourGarderLeDemarrageOuvert();
        Produit neuf = article(boutiqueA, categorieA, "Tôle", 0L, 5L, "pièce");
        var initial = new StockInitialForm(); initial.setVersion(neuf.getVersion()); initial.setQuantite(7L);
        initial.setCoutUnitaire(BigDecimal.TEN); initial.setPrixVente(new BigDecimal("15")); initial.setMotif("Déjà présent");
        comme(GERANT_A, "ADMIN", () -> { inventaire.initialiser(neuf.getId(), initial); return null; });
        assertThat(produits.findById(neuf.getId()).orElseThrow().getQuantite()).isEqualTo(7L);
    }

    // ----- Isolation -----

    @Test
    void unServiceDUneAutreBoutiqueNePeutNiEtreVenduNiModifieNiConsulte() throws Exception {
        assertThatThrownBy(() -> comme(GERANT_A, "ADMIN", () -> venteService.effectuerVente(vente(serviceB, 1))))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        Vente mixte = vente(cimentA, 1); mixte.getLignes().add(vente(serviceB, 1).getLignes().getFirst());
        assertThatThrownBy(() -> comme(GERANT_A, "ADMIN", () -> venteService.effectuerVente(mixte)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        mvc.perform(modification(serviceB).param("prixVente", "1").param("coutMoyen", "0")).andExpect(status().isNotFound());
        mvc.perform(gerantA(get("/produits/modifier/" + serviceB.getId()))).andExpect(status().isNotFound());
        assertThat(produits.findById(cimentA.getId()).orElseThrow().getQuantite()).isEqualTo(10L);
        assertThat(produits.findById(serviceB.getId()).orElseThrow().getPrixVente()).isEqualByComparingTo("1000");
        assertThat(ventes.count()).isZero();
        var resume = (ProduitService.ResumeCatalogue) mvc.perform(get("/produits").with(user(GERANT_B).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("resume");
        assertThat(resume.total()).isEqualTo(1);
        assertThat(resume.services()).isEqualTo(1);
    }

    // ----- Outils -----

    /** Un stock sans mouvement initial compte comme une activité qui clôt le démarrage. */
    private void viderLeStockPourGarderLeDemarrageOuvert() {
        Produit ciment = produits.findById(cimentA.getId()).orElseThrow(); ciment.setQuantite(0L); produits.saveAndFlush(ciment);
    }

    private MockHttpServletRequestBuilder gerantA(MockHttpServletRequestBuilder requete) {
        return requete.with(user(GERANT_A).roles("ADMIN")).with(csrf());
    }
    private MockHttpServletRequestBuilder modification(Produit produit) {
        Produit actuel = produits.findById(produit.getId()).orElseThrow();
        return gerantA(post("/produits/modifier/" + produit.getId())).param("version", actuel.getVersion().toString())
                .param("nom", actuel.getNom()).param("reference", actuel.getReference())
                .param("categorie", (produit.getBoutique().getId().equals(boutiqueA.getId()) ? categorieA : categorieB).getId().toString());
    }
    private Produit parNom(String nom) {
        return produits.findAll().stream().filter(p -> p.getNom().equals(nom)).findFirst().orElseThrow();
    }
    private Boutique boutique(String nom) { Boutique b = new Boutique(); b.setNom(nom); return boutiques.saveAndFlush(b); }
    private void utilisateur(String email, Boutique boutique, Role role) {
        Utilisateur u = new Utilisateur(); u.setNom("Utilisateur"); u.setEmail(email); u.setRole(role); u.setBoutique(boutique);
        utilisateurs.saveAndFlush(u);
    }
    private Categorie categorie(Boutique boutique) {
        Categorie c = new Categorie(); c.setNom("Matériaux"); c.setBoutique(boutique); return categories.saveAndFlush(c);
    }
    private Produit article(Boutique boutique, Categorie categorie, String nom, Long quantite, Long seuil, String unite) {
        Produit p = new Produit(); p.setBoutique(boutique); p.setCategorie(categorie); p.setNom(nom); p.setReference(nom.toUpperCase());
        p.setQuantite(quantite); p.setSeuilAlerte(seuil); p.setUnite(unite);
        p.setPrixAchat(new BigDecimal("4000")); p.setPrixVente(new BigDecimal("5000")); p.setCoutMoyen(new BigDecimal("4000"));
        return produits.saveAndFlush(p);
    }
    private Produit service(Boutique boutique, Categorie categorie, String nom, String prix, String cout) {
        Produit p = new Produit(); p.setBoutique(boutique); p.setCategorie(categorie); p.setNom(nom); p.setReference("SRV-" + nom.length());
        p.setNature(NatureProduit.SERVICE); p.setQuantite(0L);
        p.setPrixVente(new BigDecimal(prix)); p.setCoutMoyen(new BigDecimal(cout)); p.setPrixAchat(new BigDecimal(cout));
        return produits.saveAndFlush(p);
    }
    private Vente vente(Produit produit, int quantite) {
        Vente vente = new Vente(); vente.setMontantVerse(BigDecimal.ZERO);
        DetailVente ligne = new DetailVente(); ligne.setProduit(reference(produit)); ligne.setQuantite(quantite);
        vente.getLignes().add(ligne); return vente;
    }
    private Achat achat(Produit produit) {
        Achat achat = new Achat(); Fournisseur f = new Fournisseur(); f.setId(fournisseurA.getId()); achat.setFournisseur(f);
        achat.setMontantVerse(BigDecimal.ZERO);
        DetailAchat ligne = new DetailAchat(); ligne.setProduit(reference(produit)); ligne.setQuantite(2);
        ligne.setPrixAchatUnitaire(new BigDecimal("4000")); ligne.setPrixVenteUnitaire(new BigDecimal("5000"));
        achat.getLignes().add(ligne); return achat;
    }
    private Produit reference(Produit produit) { Produit r = new Produit(); r.setId(produit.getId()); return r; }
    private <T> T comme(String email, String role, Supplier<T> action) {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(email, "", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        SecurityContextHolder.setContext(contexte);
        try { return action.get(); } finally { SecurityContextHolder.clearContext(); }
    }
}
