package org.example.stock.service;

import org.example.stock.enums.Role;
import org.example.stock.form.*;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:stock-inventaire-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test") @AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class InventaireServiceTests {
    @Autowired InventaireService inventaire;
    @Autowired ProduitService produitService;
    @Autowired CaisseService caisseService;
    @Autowired AchatService achatService;
    @Autowired VenteService venteService;
    @Autowired ProduitRepository produits;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired CategorieRepository categories;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired MouvementStockRepository mouvements;
    @Autowired MouvementCaisseRepository caisse;
    @Autowired AchatRepository achats;
    @Autowired VenteRepository ventes;
    @Autowired MockMvc mvc;
    Boutique boutique;
    Produit produit;
    Categorie categorie;
    Fournisseur fournisseur;
    String email;
    @BeforeEach void preparer() {
        boutique=new Boutique();boutique.setNom("Boutique inventaire");boutique=boutiques.saveAndFlush(boutique);
        email="inventaire-"+UUID.randomUUID()+"@example.test";
        var utilisateur=new Utilisateur();utilisateur.setBoutique(boutique);utilisateur.setEmail(email);utilisateur.setNom("Gérant test");utilisateur.setRole(Role.ADMIN);utilisateurs.saveAndFlush(utilisateur);
        categorie=new Categorie();categorie.setNom("Inventaire");categorie.setBoutique(boutique);categorie=categories.saveAndFlush(categorie);
        fournisseur=new Fournisseur();fournisseur.setNom("Fournisseur");fournisseur.setBoutique(boutique);fournisseur=fournisseurs.saveAndFlush(fournisseur);
        connecter("ADMIN");
        produit=new Produit();produit.setNom("Article");produit.setReference("REF");produit.setCategorie(categorie);produit.setPrixVente(new BigDecimal("30"));produit=produitService.ajouterProduit(produit);
    }
    @AfterEach void deconnecter(){SecurityContextHolder.clearContext();}
    void connecter(String role){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(email,"unused",List.of(new SimpleGrantedAuthority("ROLE_"+role))));}
    Produit actuel(){return produits.findById(produit.getId()).orElseThrow();}
    StockInitialForm initial(long quantite){var f=new StockInitialForm();f.setVersion(actuel().getVersion());f.setQuantite(quantite);f.setCoutUnitaire(new BigDecimal("10"));f.setMotif("Déjà présent");return f;}
    AjustementStockForm correction(long quantite){var f=new AjustementStockForm();f.setVersion(actuel().getVersion());f.setQuantite(quantite);f.setMotif("Inventaire physique");return f;}
    OuvertureCaisseForm ouverture(String montant){var f=new OuvertureCaisseForm();f.setMontant(new BigDecimal(montant));return f;}
    long historique(){return mouvements.findByBoutiqueIdAndProduitIdOrderByCreeLeDescIdDesc(boutique.getId(),produit.getId(),org.springframework.data.domain.PageRequest.of(0,20)).getNumberOfElements();}
    Achat achat(int q){var a=new Achat();a.setFournisseur(fournisseur);a.setMontantVerse(BigDecimal.ZERO);var d=new DetailAchat();d.setProduit(produit);d.setQuantite(q);d.setPrixAchatUnitaire(new BigDecimal("20"));a.getLignes().add(d);return a;}
    Vente vente(int q){var v=new Vente();v.setMontantVerse(BigDecimal.ZERO);var d=new DetailVente();d.setProduit(produit);d.setQuantite(q);v.getLignes().add(d);return v;}

    @Test void laCreationNeReprendPasLeStockOuLeCoutDuCatalogue() {
        var p=new Produit();p.setNom("Autre");p.setReference("AUTRE");p.setCategorie(categorie);p.setPrixVente(BigDecimal.TEN);p.setQuantite(999L);p.setPrixAchat(new BigDecimal("900"));
        p=produitService.ajouterProduit(p);assertThat(p.getQuantite()).isZero();assertThat(p.getPrixAchat()).isZero();
        assertThat(caisseService.getSoldeActuel()).isZero();assertThat(achats.findByBoutiqueIdOrderByDateAchatDesc(boutique.getId())).isEmpty();
    }
    @Test void initialPuisAchatPuisVenteConserventStockEtDocumentsDistincts() {
        inventaire.initialiser(produit.getId(),initial(10));
        assertThat(actuel().getQuantite()).isEqualTo(10);assertThat(actuel().getPrixAchat()).isEqualByComparingTo("10");
        assertThat(caisseService.getSoldeActuel()).isZero();assertThat(achats.findByBoutiqueIdOrderByDateAchatDesc(boutique.getId())).isEmpty();
        achatService.enregistrerAchat(achat(5));venteService.effectuerVente(vente(3));
        assertThat(actuel().getQuantite()).isEqualTo(12);assertThat(actuel().getPrixAchat()).isEqualByComparingTo("20");assertThat(historique()).isEqualTo(1);
    }
    @Test void laFicheNeChangePlusLeStockNiLeCout() {
        inventaire.initialiser(produit.getId(),initial(10));
        var modif=actuel();modif.setNom("Renommé");modif.setPrixAchat(new BigDecimal("999"));modif.setQuantite(999L);
        produitService.modifierProduit(produit.getId(),modif);
        assertThat(actuel().getNom()).isEqualTo("Renommé");assertThat(actuel().getQuantite()).isEqualTo(10);assertThat(actuel().getPrixAchat()).isEqualByComparingTo("10");
    }
    @Test void correctionJournaliseeNeChangeNiCaisseNiPrix() {
        inventaire.initialiser(produit.getId(),initial(10));inventaire.ajuster(produit.getId(),correction(8));
        var h=inventaire.consulter(produit.getId(),0).historique();assertThat(h).hasSize(2);
        assertThat(h.getFirst().getQuantiteAvant()).isEqualTo(10);assertThat(h.getFirst().getQuantiteApres()).isEqualTo(8);assertThat(h.getFirst().getMotif()).isEqualTo("Inventaire physique");
        assertThat(actuel().getPrixAchat()).isEqualByComparingTo("10");assertThat(caisseService.getSoldeActuel()).isZero();
        assertThatThrownBy(()->produitService.supprimerProduit(produit.getId())).hasMessageContaining("historique");
    }
    @Test void unFormulaireRepeteOuPerimeNeRemodifiePasLeStock() {
        var f=initial(10);inventaire.initialiser(produit.getId(),f);
        assertThatThrownBy(()->inventaire.initialiser(produit.getId(),f)).isInstanceOf(ProduitModifieException.class);
        var c=correction(8);inventaire.ajuster(produit.getId(),c);assertThatThrownBy(()->inventaire.ajuster(produit.getId(),c)).isInstanceOf(ProduitModifieException.class);
        assertThat(actuel().getQuantite()).isEqualTo(8);assertThat(historique()).isEqualTo(2);
    }
    @Test void unStockInitialNePeutPasEtreRecreeApresConsommation() {
        inventaire.initialiser(produit.getId(),initial(2));venteService.effectuerVente(vente(2));
        assertThatThrownBy(()->inventaire.initialiser(produit.getId(),initial(4))).hasMessageContaining("réservé");assertThat(actuel().getQuantite()).isZero();
    }
    @Test void unAncienAchatConsommeInterditAussiLeStockInitial() {
        achatService.enregistrerAchat(achat(2));venteService.effectuerVente(vente(2));
        assertThatThrownBy(()->inventaire.initialiser(produit.getId(),initial(4))).hasMessageContaining("réservé");assertThat(historique()).isZero();
    }
    @Test void correctionSansMotifOuSansDifferenceEstRefusee() {
        var f=correction(2);f.setMotif(" ");assertThatThrownBy(()->inventaire.ajuster(produit.getId(),f)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->inventaire.ajuster(produit.getId(),correction(0))).hasMessageContaining("identique");assertThat(historique()).isZero();assertThat(actuel().getQuantite()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"-1","0.001","1000000000000000"})
    void coutInitialInvalideNeCreeRien(String cout) {
        var f=initial(10);f.setCoutUnitaire(new BigDecimal(cout));assertThatThrownBy(()->inventaire.initialiser(produit.getId(),f)).isInstanceOf(IllegalArgumentException.class);
        assertThat(actuel().getQuantite()).isZero();assertThat(historique()).isZero();
    }
    @Test void argentInitialContribueAuSoldeEtALOuvertureSansEtreUneRecetteDuJour() {
        caisseService.initialiser(ouverture("30000"));LocalDate today=LocalDate.now();
        assertThat(caisseService.getSoldeActuel()).isEqualByComparingTo("30000");assertThat(caisseService.getSoldeOuverture(today)).isEqualByComparingTo("30000");
        assertThat(caisseService.getSoldeOuverture(today.minusDays(1))).isZero();
        assertThat(caisseService.getEntreesDuJour(today)).isZero();assertThat(caisseService.getNetDuJour(today)).isZero();assertThat(caisseService.getSoldeCloture(today)).isEqualByComparingTo("30000");
        caisseService.enregistrerEntree(new BigDecimal("5000"),"Vente de test","VENTE",null);
        assertThat(caisseService.getSoldeActuel()).isEqualByComparingTo("35000");assertThat(caisseService.getEntreesDuJour(today)).isEqualByComparingTo("5000");assertThat(caisseService.getSoldeCloture(today)).isEqualByComparingTo("35000");
        assertThat(caisseService.getSoldeOuverture(today.plusDays(1))).isEqualByComparingTo("35000");
        assertThatThrownBy(()->caisseService.initialiser(ouverture("30000"))).hasMessageContaining("une seule fois");assertThat(caisseService.listerTous()).hasSize(2);
    }
    @Test void initialZeroEstEnregistreEtNonRepetable() {
        caisseService.initialiser(ouverture("0"));assertThat(caisseService.listerTous()).hasSize(1);assertThat(caisseService.peutInitialiser()).isFalse();
        assertThatThrownBy(()->caisseService.initialiser(ouverture("0"))).hasMessageContaining("une seule fois");
    }
    @Test void uneCaisseExistanteNeRecoitPasUnFauxSoldeInitial() {
        caisseService.enregistrerEntree(BigDecimal.TEN,"Existant","VENTE",null);
        assertThatThrownBy(()->caisseService.initialiser(ouverture("30000"))).hasMessageContaining("premier mouvement");assertThat(caisseService.getSoldeActuel()).isEqualByComparingTo("10");
    }
    @Test void caissierEtRoleForgeSontRefusesAussiParLesServices() {
        connecter("CAISSIER");assertThatThrownBy(()->inventaire.consulter(produit.getId(),0)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->caisseService.initialiser(ouverture("1"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var u=utilisateurs.findByEmail(email).orElseThrow();u.setRole(Role.CAISSIER);utilisateurs.saveAndFlush(u);connecter("ADMIN");
        assertThatThrownBy(()->inventaire.initialiser(produit.getId(),initial(10))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void stockDAutreBoutiqueInaccessible()throws Exception {
        var autre=new Boutique();autre.setNom("Autre");autre=boutiques.saveAndFlush(autre);var cat=new Categorie();cat.setNom("Autre");cat.setBoutique(autre);cat=categories.saveAndFlush(cat);
        var p=new Produit();p.setBoutique(autre);p.setCategorie(cat);p.setNom("Secret autre boutique");p.setReference("AUTRE");p.setPrixVente(BigDecimal.TEN);p=produits.saveAndFlush(p);
        mvc.perform(get("/produits/"+p.getId()+"/stock").with(user(email).roles("ADMIN"))).andExpect(status().isNotFound());
        final Long id=p.getId();assertThatThrownBy(()->inventaire.initialiser(id,initial(10))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);assertThat(actuel().getQuantite()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"quantite","prixAchat","boutique.id","id"})
    void ficheProduitRefuseLesChampsDeStockOuLesIdentifiants(String champ)throws Exception {
        mvc.perform(post("/produits/ajouter").with(user(email).roles("ADMIN")).with(csrf()).param(champ,"10")).andExpect(status().isBadRequest());
        mvc.perform(post("/produits/modifier/"+produit.getId()).with(user(email).roles("ADMIN")).with(csrf()).param(champ,"10")).andExpect(status().isBadRequest());assertThat(actuel().getQuantite()).isZero();
    }
    @Test void formulairesStockEtOuvertureSontProtegesEtAffichentLesErreurs()throws Exception {
        String path="/produits/"+produit.getId()+"/stock";
        mvc.perform(get(path).with(user(email).roles("ADMIN"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("stockInitialQuantite")));
        mvc.perform(post(path+"/initial").with(user(email).roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(get(path).with(user(email).roles("CAISSIER"))).andExpect(status().isForbidden());
        mvc.perform(post(path+"/initial").with(user(email).roles("ADMIN")).with(csrf()).param("version","0").param("quantite","0").param("coutUnitaire","10").param("motif","Origine"))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("stockInitial","quantite"));
        mvc.perform(post(path+"/ajuster").with(user(email).roles("ADMIN")).with(csrf()).param("version","0").param("quantite","3").param("motif",""))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("ajustementStock","motif"));
        mvc.perform(post("/caisse/ouverture").with(user(email).roles("ADMIN")).with(csrf()).param("montant","-1").param("motif","Origine"))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("ouvertureCaisse","montant"));
        mvc.perform(post("/caisse/ouverture").with(user(email).roles("ADMIN")).with(csrf()).param("montant","1").param("source","VENTE")).andExpect(status().isBadRequest());
        mvc.perform(post(path+"/initial").with(user(email).roles("ADMIN")).with(csrf()).param("quantite","1","2")).andExpect(status().isBadRequest());
        connecter("ADMIN");assertThat(historique()).isZero();assertThat(caisseService.getSoldeActuel()).isZero();
    }
    @Test void concurrenceNInitialiseQuUneFoisStockEtCaisse()throws Exception {
        var f=initial(10);var erreurs=simultane(()->inventaire.initialiser(produit.getId(),f));assertThat(erreurs).hasSize(1);assertThat(actuel().getQuantite()).isEqualTo(10);assertThat(historique()).isEqualTo(1);
        erreurs=simultane(()->caisseService.initialiser(ouverture("0")));assertThat(erreurs).hasSize(1);assertThat(caisseService.listerTous()).hasSize(1);
    }
    List<Throwable> simultane(Runnable action)throws Exception {
        List<Throwable> errors=new CopyOnWriteArrayList<>();var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures=new ArrayList<>();for(int i=0;i<2;i++)futures.add(pool.submit(()->{connecter("ADMIN");try{barrier.await(5,TimeUnit.SECONDS);action.run();}catch(Throwable e){errors.add(e);}finally{SecurityContextHolder.clearContext();}}));
            for(var f:futures)f.get(10,TimeUnit.SECONDS);
        }
        return errors;
    }
}
