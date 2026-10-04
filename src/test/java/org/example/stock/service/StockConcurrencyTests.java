package org.example.stock.service;

import jakarta.persistence.EntityManager;
import org.example.stock.enums.Role;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.math.BigDecimal;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Vraies transactions sur des connexions distinctes, sans transaction englobante de test. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:stock-concurrence;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class StockConcurrencyTests {
    @Autowired ProduitRepository produits;
    @Autowired CategorieRepository categories;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired VenteRepository ventes;
    @Autowired AchatRepository achats;
    @Autowired MouvementCaisseRepository caisse;
    @Autowired VenteService venteService;
    @Autowired AchatService achatService;
    @Autowired ProduitService produitService;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entityManager;
    @Autowired MockMvc mvc;
    @SpyBean StockLockRepository verrous;
    Produit produit;
    Fournisseur fournisseur;

    @BeforeEach
    void preparer() {
        ventes.deleteAll(); caisse.deleteAll(); achats.deleteAll(); produits.deleteAll();
        categories.deleteAll(); fournisseurs.deleteAll(); utilisateurs.deleteAll();
        Utilisateur utilisateur = new Utilisateur(); utilisateur.setEmail("concurrence@example.test");
        utilisateur.setNom("Test concurrence"); utilisateur.setRole(Role.ADMIN); utilisateurs.save(utilisateur);
        Categorie categorie = new Categorie(); categorie.setNom("Concurrence"); categorie = categories.save(categorie);
        fournisseur = new Fournisseur(); fournisseur.setNom("Concurrence"); fournisseur = fournisseurs.save(fournisseur);
        produit = creerProduit(categorie, "PREMIER", 10L);
    }

    @Test
    void deuxVentesConserventToutesLesSortiesQuandLeStockSuffit() throws Exception {
        assertThat(executerAvecVerrouRetenu(() -> venteService.effectuerVente(vente(3)),
                () -> venteService.effectuerVente(vente(4)), false)).isNull();
        assertThat(stock()).isEqualTo(3L);
        assertThat(ventes.count()).isEqualTo(2);
        assertThat(caisse.calculerSoldeTotal()).isEqualTo(140.0);
    }

    @Test
    void deuxVentesNePeuventPasVendreLesMemesDernieresUnites() throws Exception {
        Throwable erreur = executerAvecVerrouRetenu(() -> venteService.effectuerVente(vente(7)),
                () -> venteService.effectuerVente(vente(7)), false);
        assertThat(erreur).isInstanceOf(RuntimeException.class).hasMessageContaining("Stock insuffisant");
        assertThat(stock()).isEqualTo(3L);
        assertThat(ventes.count()).isEqualTo(1);
        assertThat(caisse.count()).isEqualTo(1);
        assertThat(caisse.calculerSoldeTotal()).isEqualTo(140.0);
    }

    @Test
    void unAchatConcurrentAvecUneVenteConserveEntreesEtSorties() throws Exception {
        assertThat(executerAvecVerrouRetenu(() -> venteService.effectuerVente(vente(4)),
                () -> achatService.enregistrerAchat(achat(3, 15.0)), false)).isNull();
        assertThat(stock()).isEqualTo(9L);
        assertThat(ventes.count()).isEqualTo(1);
        assertThat(achats.count()).isEqualTo(1);
        assertThat(caisse.calculerSoldeTotal()).isEqualTo(65.0);
    }

    @Test
    void deuxAchatsConserventToutesLesEntreesDeStock() throws Exception {
        assertThat(executerAvecVerrouRetenu(() -> achatService.enregistrerAchat(achat(3, 15.0)),
                () -> achatService.enregistrerAchat(achat(4, 20.0)), false)).isNull();
        assertThat(stock()).isEqualTo(17L);
        assertThat(achats.count()).isEqualTo(2);
        assertThat(caisse.calculerSoldeTotal()).isEqualTo(-35.0);
    }

    @Test
    void laCorrectionDAchatAttendUneVenteEtConserveSaSortieDeStock() throws Exception {
        Long id = authentifie(() -> achatService.enregistrerAchat(achat(3, 15.0)).getId());
        assertThat(executerAvecVerrouRetenu(() -> venteService.effectuerVente(vente(4)),
                () -> achatService.modifierAchat(id, modification(5)), false)).isNull();
        assertThat(stock()).isEqualTo(11L);
        assertThat(achats.count()).isEqualTo(1);
        assertThat(caisse.calculerSoldeTotal()).isEqualTo(65.0);
    }

    @Test
    void deuxCorrectionsDuMemeAchatConserventLeStockSansChangerLePaiement() throws Exception {
        Long id = authentifie(() -> achatService.enregistrerAchat(achat(3, 0.0)).getId());
        assertThat(executerAvecVerrouRetenu(() -> achatService.modifierAchat(id, modification(4)),
                () -> achatService.modifierAchat(id, modification(5)), true)).isNull();
        assertThat(stock()).isEqualTo(15L);
        assertThat(achats.count()).isEqualTo(1);
        assertThat(achats.findById(id).orElseThrow().getMontantVerse()).isEqualTo(0.0);
        assertThat(caisse.calculerSoldeTotal()).isEqualTo(0.0);
    }

    @Test
    void deuxReglementsDuMemeFormulaireNeCreentQuUneSortieDeCaisse() throws Exception {
        Long id = authentifie(() -> achatService.enregistrerAchat(achat(10, 0.0)).getId());
        Throwable erreur = executerAvecVerrouRetenu(
                () -> achatService.reglerAchat(id, new BigDecimal("20"), BigDecimal.ZERO),
                () -> achatService.reglerAchat(id, new BigDecimal("20"), BigDecimal.ZERO), true);
        assertThat(erreur).isInstanceOf(ReglementAchatObsoleteException.class);
        assertThat(stock()).isEqualTo(20L);
        assertThat(achats.findById(id).orElseThrow().getMontantVerse()).isEqualTo(20.0);
        assertThat(caisse.count()).isEqualTo(1);
        assertThat(caisse.calculerSoldeTotal()).isEqualTo(-20.0);
    }

    @Test
    void unReglementPendantUneVenteNeVerrouilleNiNeModifieLeStock() throws Exception {
        Long id = authentifie(() -> achatService.enregistrerAchat(achat(10, 0.0)).getId());
        assertThat(executerAvecVerrouRetenu(() -> venteService.effectuerVente(vente(18)),
                () -> achatService.reglerAchat(id, new BigDecimal("50"), BigDecimal.ZERO), true, false)).isNull();
        assertThat(stock()).isEqualTo(2L);
        assertThat(achats.findById(id).orElseThrow().getMontantVerse()).isEqualTo(50.0);
        assertThat(caisse.calculerSoldeTotal()).isEqualTo(310.0);
    }

    @Test
    void uneEntiteDejaChargeeEstActualiseeAvantLeControleDuStock() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Produit charge = produits.findById(produit.getId()).orElseThrow();
            TransactionTemplate autreTransaction = new TransactionTemplate(transactions);
            autreTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            autreTransaction.executeWithoutResult(autre -> authentifie(() -> venteService.effectuerVente(vente(4))));
            assertThat(charge.getQuantite()).isEqualTo(10L);
            authentifie(() -> venteService.effectuerVente(vente(2)));
            assertThat(charge.getQuantite()).isEqualTo(4L);
        });
        assertThat(stock()).isEqualTo(4L);
        assertThat(ventes.count()).isEqualTo(2);
    }

    @Test
    void uneVenteImpossibleAnnuleAussiLesLignesDejaDeduites() {
        Vente vente = vente(3);
        vente.getLignes().add(ligneVente(produit.getId(), 8));
        assertThatThrownBy(() -> authentifie(() -> venteService.effectuerVente(vente)))
                .hasMessageContaining("Stock insuffisant");
        assertThat(stock()).isEqualTo(10L);
        assertThat(ventes.count()).isZero();
        assertThat(caisse.count()).isZero();
    }

    @Test
    void desPaniersEnOrdreInversePeuventEtreEnregistresSimultanement() throws Exception {
        Produit second = creerProduit(produit.getCategorie(), "SECOND", 10L);
        Vente premiere = vente(2); premiere.getLignes().add(ligneVente(second.getId(), 3));
        Vente seconde = new Vente(); seconde.getLignes().add(ligneVente(second.getId(), 4));
        seconde.getLignes().add(ligneVente(produit.getId(), 5));
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CyclicBarrier depart = new CyclicBarrier(2);
            Future<?> a = pool.submit(() -> { attendre(depart); authentifie(() -> venteService.effectuerVente(premiere)); });
            Future<?> b = pool.submit(() -> { attendre(depart); authentifie(() -> venteService.effectuerVente(seconde)); });
            a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        }
        assertThat(stock()).isEqualTo(3L);
        assertThat(produits.findById(second.getId()).orElseThrow().getQuantite()).isEqualTo(3L);
        assertThat(ventes.count()).isEqualTo(2);
        assertThat(caisse.calculerSoldeTotal()).isEqualTo(280.0);
    }

    @Test
    void unAncienFormulaireNePeutPasRestaurerLeStockVendu() throws Exception {
        authentifie(() -> venteService.effectuerVente(vente(2)));
        mvc.perform(post("/produits/modifier/" + produit.getId()).with(csrf())
                        .with(user("concurrence@example.test").roles("ADMIN"))
                        .param("version", produit.getVersion().toString()).param("nom", "Ancien formulaire")
                        .param("reference", produit.getReference()).param("prixAchat", "5").param("prixVente", "20")
                        .param("quantite", "10").param("categorie", produit.getCategorie().getId().toString()))
                .andExpect(status().isConflict()).andExpect(view().name("dashboard"))
                .andExpect(model().attributeHasErrors("produit"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Rechargez la fiche")));
        assertThat(stock()).isEqualTo(8L);
        assertThat(produits.findById(produit.getId()).orElseThrow().getNom()).isEqualTo("PREMIER");
    }

    @Test
    void uneModificationSansVersionEstRefusee() throws Exception {
        mvc.perform(post("/produits/modifier/" + produit.getId()).with(csrf())
                        .with(user("concurrence@example.test").roles("ADMIN"))
                        .param("nom", "Sans version").param("reference", "REF").param("prixAchat", "5")
                        .param("prixVente", "20").param("quantite", "10")
                        .param("categorie", produit.getCategorie().getId().toString()))
                .andExpect(status().isBadRequest());
        assertThat(stock()).isEqualTo(10L);
    }

    @Test
    void laModificationManuelleAttendLaVentePuisRefuseSaVersionPerimee() throws Exception {
        Throwable erreur = executerAvecVerrouRetenu(() -> venteService.effectuerVente(vente(2)),
                () -> produitService.modifierProduit(produit.getId(), produit), false);
        assertThat(erreur).isInstanceOf(ProduitModifieException.class);
        assertThat(stock()).isEqualTo(8L);
        assertThat(caisse.calculerSoldeTotal()).isEqualTo(40.0);
    }

    @Test
    void uneErreurDePaiementAnnuleStockDocumentEtCaisse() {
        Achat achat = achat(3, 999.0);
        assertThatThrownBy(() -> authentifie(() -> achatService.enregistrerAchat(achat)))
                .hasMessageContaining("ne peut pas depasser");
        assertThat(stock()).isEqualTo(10L);
        assertThat(achats.count()).isZero();
        assertThat(caisse.count()).isZero();
    }

    private Throwable executerAvecVerrouRetenu(Runnable premiere, Runnable seconde, boolean document) throws Exception {
        return executerAvecVerrouRetenu(premiere, seconde, document, true);
    }

    private Throwable executerAvecVerrouRetenu(Runnable premiere, Runnable seconde, boolean document, boolean doitAttendre) throws Exception {
        CountDownLatch modificationEffectuee = new CountDownLatch(1);
        CountDownLatch autoriserCommit = new CountDownLatch(1);
        CountDownLatch tentative = new CountDownLatch(1);
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        org.mockito.stubbing.Answer<Object> observer = invocation -> {
            if (Thread.currentThread() == secondThread.get()) tentative.countDown();
            return invocation.callRealMethod();
        };
        StockLockRepository cible = AopTestUtils.getUltimateTargetObject(verrous);
        if (document) doAnswer(observer).when(cible).verrouillerAchat(any());
        else doAnswer(observer).when(cible).verrouillerProduits(any());
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> a = pool.submit(() -> authentifie(() -> new TransactionTemplate(transactions).execute(status -> {
                premiere.run(); entityManager.flush(); modificationEffectuee.countDown(); attendre(autoriserCommit); return null;
            })));
            try {
                assertThat(modificationEffectuee.await(10, TimeUnit.SECONDS)).isTrue();
                Future<?> b = pool.submit(() -> {
                    secondThread.set(Thread.currentThread());
                    authentifie(() -> { seconde.run(); return null; });
                });
                assertThat(tentative.await(10, TimeUnit.SECONDS)).isTrue();
                if (doitAttendre) {
                    assertThatThrownBy(() -> b.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                } else {
                    b.get(5, TimeUnit.SECONDS);
                }
                autoriserCommit.countDown();
                a.get(10, TimeUnit.SECONDS);
                try { b.get(10, TimeUnit.SECONDS); return null; }
                catch (ExecutionException erreur) { return erreur.getCause(); }
            } finally { autoriserCommit.countDown(); }
        }
    }

    private <T> T authentifie(Callable<T> action) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("concurrence@example.test", "",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var precedent = SecurityContextHolder.getContext(); SecurityContextHolder.setContext(context);
        try { return action.call(); }
        catch (RuntimeException erreur) { throw erreur; }
        catch (Exception erreur) { throw new IllegalStateException(erreur); }
        finally { SecurityContextHolder.setContext(precedent); }
    }

    private Produit creerProduit(Categorie categorie, String reference, Long quantite) {
        Produit nouveau = new Produit(); nouveau.setNom(reference); nouveau.setReference(reference);
        nouveau.setCategorie(categorie); nouveau.setFournisseur(fournisseur);
        nouveau.setQuantite(quantite); nouveau.setPrixAchat(5.0); nouveau.setPrixVente(20.0);
        return produits.saveAndFlush(nouveau);
    }

    private Vente vente(int quantite) {
        Vente vente = new Vente(); vente.getLignes().add(ligneVente(produit.getId(), quantite)); return vente;
    }

    private DetailVente ligneVente(Long id, int quantite) {
        Produit reference = new Produit(); reference.setId(id);
        DetailVente ligne = new DetailVente(); ligne.setProduit(reference); ligne.setQuantite(quantite); return ligne;
    }

    private Achat achat(int quantite, double verse) {
        Achat achat = new Achat(); achat.setFournisseur(fournisseur); achat.setMontantVerse(verse);
        Produit reference = new Produit(); reference.setId(produit.getId());
        DetailAchat ligne = new DetailAchat(); ligne.setProduit(reference); ligne.setQuantite(quantite);
        ligne.setPrixAchatUnitaire(5.0); achat.getLignes().add(ligne); return achat;
    }

    private Achat modification(int quantite) {
        Achat achat = achat(quantite, 0.0); achat.setMontantVerse(null); return achat;
    }

    private long stock() { return produits.findById(produit.getId()).orElseThrow().getQuantite(); }

    private void attendre(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Synchronisation expirée"); }
        catch (InterruptedException erreur) { Thread.currentThread().interrupt(); throw new IllegalStateException(erreur); }
    }

    private void attendre(CyclicBarrier barrier) {
        try { barrier.await(10, TimeUnit.SECONDS); }
        catch (Exception erreur) { throw new IllegalStateException(erreur); }
    }
}
