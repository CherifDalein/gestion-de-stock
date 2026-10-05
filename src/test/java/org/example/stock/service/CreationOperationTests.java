package org.example.stock.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.example.stock.enums.Role;
import org.example.stock.enums.TypeOperationCreation;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Sans transaction englobante : les rejouements et les courses utilisent de vrais commits. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:creation-operations;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class CreationOperationTests {
    private static final String EMAIL = "creation@example.test";
    private static final String AUTRE_EMAIL = "autre-creation@example.test";

    @Autowired OperationCreationService service;
    @Autowired OperationCreationRepository operations;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired CategorieRepository categories;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired ProduitRepository produits;
    @Autowired AchatRepository achats;
    @Autowired VenteRepository ventes;
    @Autowired MouvementCaisseRepository caisse;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entityManager;
    @Autowired MockMvc mvc;

    Produit produit;
    Fournisseur fournisseur;
    Utilisateur auteur;

    @BeforeEach
    void preparer() {
        operations.deleteAll(); caisse.deleteAll(); ventes.deleteAll(); achats.deleteAll();
        produits.deleteAll(); categories.deleteAll(); fournisseurs.deleteAll(); utilisateurs.deleteAll();
        auteur = utilisateur(EMAIL);
        utilisateur(AUTRE_EMAIL);
        Categorie categorie = new Categorie(); categorie.setNom("Création"); categorie = categories.save(categorie);
        fournisseur = new Fournisseur(); fournisseur.setNom("Fournisseur création"); fournisseur = fournisseurs.save(fournisseur);
        produit = new Produit(); produit.setNom("Produit création"); produit.setReference("CREATION");
        produit.setCategorie(categorie); produit.setFournisseur(fournisseur); produit.setQuantite(10L);
        produit.setPrixAchat(new BigDecimal("5")); produit.setPrixVente(new BigDecimal("20"));
        produit = produits.saveAndFlush(produit);
    }

    @Test
    void chaqueOuvertureCreeUnJetonDistinctLieAuCompteEtAuType() {
        String achat = ouvrir(TypeOperationCreation.ACHAT);
        String vente = ouvrir(TypeOperationCreation.VENTE);
        assertThat(UUID.fromString(achat)).isNotEqualTo(UUID.fromString(vente));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var operation = operations.findById(achat).orElseThrow();
            assertThat(operation.getUtilisateur().getId()).isEqualTo(auteur.getId());
            assertThat(operation.getType()).isEqualTo(TypeOperationCreation.ACHAT);
            assertThat(operation.getDateCreation()).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(1));
            assertThat(operation.getEmpreinte()).isNull();
            assertThat(operation.getAchat()).isNull(); assertThat(operation.getVente()).isNull();
        });
        verifierAucunEffet();
    }

    @ParameterizedTest
    @EnumSource(TypeOperationCreation.class)
    void unRejeuIdentiqueRetrouveLeDocumentSansRepeterStockOuCaisse(TypeOperationCreation type) {
        String jeton = ouvrir(type);
        var premier = creer(type, jeton, 3);
        var copie = creer(type, jeton, 3);
        assertThat(premier.dejaEnregistre()).isFalse();
        assertThat(copie.dejaEnregistre()).isTrue();
        assertThat(copie.documentId()).isEqualTo(premier.documentId());
        verifierEffets(type, 1, 3);
    }

    @ParameterizedTest
    @EnumSource(TypeOperationCreation.class)
    void deuxFormulairesDistinctsPermettentDeuxOperationsIdentiques(TypeOperationCreation type) {
        var premier = creer(type, ouvrir(type), 3);
        var second = creer(type, ouvrir(type), 3);
        assertThat(second.documentId()).isNotEqualTo(premier.documentId());
        assertThat(second.dejaEnregistre()).isFalse();
        verifierEffets(type, 2, 6);
    }

    @Test
    void lesEchellesDecimalesEquivalentesNeChangentPasLeFormulaire() {
        String jeton = ouvrir(TypeOperationCreation.ACHAT);
        Achat premier = achat(3); premier.setMontantVerse(new BigDecimal("15.0"));
        premier.getLignes().getFirst().setPrixAchatUnitaire(new BigDecimal("5.0"));
        var resultat = authentifie(EMAIL, () -> service.creerAchat(jeton, premier));
        Achat copie = achat(3); copie.setMontantVerse(new BigDecimal("15.00"));
        copie.getLignes().getFirst().setPrixAchatUnitaire(new BigDecimal("5.00"));
        var rejoue = authentifie(EMAIL, () -> service.creerAchat(jeton, copie));
        assertThat(rejoue.dejaEnregistre()).isTrue();
        assertThat(rejoue.documentId()).isEqualTo(resultat.documentId());
        verifierEffets(TypeOperationCreation.ACHAT, 1, 3);
    }

    @ParameterizedTest
    @EnumSource(TypeOperationCreation.class)
    void modifierLeContenuDUnFormulaireDejaEnregistreEstUnConflit(TypeOperationCreation type) {
        String jeton = ouvrir(type); creer(type, jeton, 3);
        conflit(() -> creer(type, jeton, 4));
        verifierEffets(type, 1, 3);
    }

    @Test
    void changerLePrixOuLeVersementDUnAchatConsommeEstRefuse() {
        String jeton = ouvrir(TypeOperationCreation.ACHAT); creer(TypeOperationCreation.ACHAT, jeton, 3);
        Achat prixChange = achat(3); prixChange.getLignes().getFirst().setPrixAchatUnitaire(new BigDecimal("6"));
        conflit(() -> authentifie(EMAIL, () -> service.creerAchat(jeton, prixChange)));
        Achat versementChange = achat(3); versementChange.setMontantVerse(new BigDecimal("10"));
        conflit(() -> authentifie(EMAIL, () -> service.creerAchat(jeton, versementChange)));
        verifierEffets(TypeOperationCreation.ACHAT, 1, 3);
    }

    @Test
    void leVersementPartielDUneVenteEstRejoueSansDoublonEtNePeutPasEtreChange() {
        String jeton = ouvrir(TypeOperationCreation.VENTE);
        Vente premiere = vente(3); premiere.setMontantVerse(new BigDecimal("20.0"));
        var premier = authentifie(EMAIL, () -> service.creerVente(jeton, premiere));
        Vente copie = vente(3); copie.setMontantVerse(new BigDecimal("20.00"));
        var rejoue = authentifie(EMAIL, () -> service.creerVente(jeton, copie));
        assertThat(rejoue.dejaEnregistre()).isTrue(); assertThat(rejoue.documentId()).isEqualTo(premier.documentId());
        Vente changee = vente(3); changee.setMontantVerse(new BigDecimal("40"));
        conflit(() -> authentifie(EMAIL, () -> service.creerVente(jeton, changee)));
        assertThat(stock()).isEqualTo(7L); assertThat(ventes.count()).isEqualTo(1); assertThat(achats.count()).isZero();
        assertThat(caisse.count()).isEqualTo(1); assertThat(caisse.calculerSoldeTotal()).isEqualByComparingTo("20");
        assertThat(ventes.findById(premier.documentId()).orElseThrow().getResteAPayer()).isEqualByComparingTo("40");
    }

    @ParameterizedTest
    @EnumSource(TypeOperationCreation.class)
    void toutesLesLignesDuPanierParticipentAuControleDeRejeu(TypeOperationCreation type) {
        String jeton = ouvrir(type);
        var premier = creerPanier(type, jeton, 1);
        var copie = creerPanier(type, jeton, 1);
        assertThat(copie.dejaEnregistre()).isTrue(); assertThat(copie.documentId()).isEqualTo(premier.documentId());
        conflit(() -> creerPanier(type, jeton, 2));
        verifierEffets(type, 1, 3);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            if (type == TypeOperationCreation.ACHAT)
                assertThat(achats.findById(premier.documentId()).orElseThrow().getLignes()).extracting(DetailAchat::getQuantite).containsExactlyInAnyOrder(2, 1);
            else
                assertThat(ventes.findById(premier.documentId()).orElseThrow().getLignes()).extracting(DetailVente::getQuantite).containsExactlyInAnyOrder(2, 1);
        });
    }

    @ParameterizedTest
    @EnumSource(TypeOperationCreation.class)
    void unJetonDUnAutreCompteEstRefuseMemeSiLeFormulaireEstIdentique(TypeOperationCreation type) {
        String jeton = ouvrir(type);
        conflit(() -> authentifie(AUTRE_EMAIL, () -> creerAuthentifie(type, jeton, 3)));
        verifierAucunEffet();
        creer(type, jeton, 3);
        conflit(() -> authentifie(AUTRE_EMAIL, () -> creerAuthentifie(type, jeton, 3)));
        verifierEffets(type, 1, 3);
    }

    @ParameterizedTest
    @EnumSource(TypeOperationCreation.class)
    void leJetonNePeutPasEtreUtilisePourUnAutreTypeDeDocument(TypeOperationCreation type) {
        TypeOperationCreation autre = type == TypeOperationCreation.ACHAT ? TypeOperationCreation.VENTE : TypeOperationCreation.ACHAT;
        String jeton = ouvrir(type);
        conflit(() -> creer(autre, jeton, 3));
        verifierAucunEffet();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalide", "00000000-0000-0000-0000-00000000000"})
    void unJetonMalFormeEstRefuseAvantTouteEcriture(String jeton) {
        assertThatThrownBy(() -> creer(TypeOperationCreation.VENTE, jeton, 3))
                .isInstanceOfSatisfying(CreationOperationException.class,
                        erreur -> assertThat(erreur.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifierAucunEffet();
    }

    @Test
    void unJetonNullEstRefuseAvantTouteEcriture() {
        assertThatThrownBy(() -> creer(TypeOperationCreation.ACHAT, null, 3))
                .isInstanceOfSatisfying(CreationOperationException.class,
                        erreur -> assertThat(erreur.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifierAucunEffet();
    }

    @ParameterizedTest
    @EnumSource(TypeOperationCreation.class)
    void unUuidInconnuNeCreePasDOperation(TypeOperationCreation type) {
        conflit(() -> creer(type, UUID.randomUUID().toString(), 3));
        assertThat(operations.count()).isZero();
        verifierAucunEffet();
    }

    @ParameterizedTest
    @EnumSource(TypeOperationCreation.class)
    void unFormulaireNonConsommeExpireApresVingtQuatreHeures(TypeOperationCreation type) {
        String jeton = ouvrir(type); vieillir(jeton);
        conflit(() -> creer(type, jeton, 3));
        verifierAucunEffet();
    }

    @ParameterizedTest
    @EnumSource(TypeOperationCreation.class)
    void unRejeuAncienRetrouveLeDocumentSansRecalculerLeStockCourant(TypeOperationCreation type) {
        String jeton = ouvrir(type); var premier = creer(type, jeton, 3); vieillir(jeton);
        Produit actuel = produits.findById(produit.getId()).orElseThrow();
        actuel.setQuantite(0L); actuel.setPrixVente(new BigDecimal("99")); produits.saveAndFlush(actuel);
        var rejoue = creer(type, jeton, 3);
        assertThat(rejoue.dejaEnregistre()).isTrue(); assertThat(rejoue.documentId()).isEqualTo(premier.documentId());
        assertThat(stock()).isZero(); assertThat(achats.count() + ventes.count()).isEqualTo(1);
        assertThat(caisse.count()).isEqualTo(1);
        assertThat(caisse.calculerSoldeTotal()).isEqualByComparingTo(type == TypeOperationCreation.ACHAT ? "-15" : "60");
    }

    @ParameterizedTest
    @EnumSource(TypeOperationCreation.class)
    void unRollbackApresEcritureLibereLeJetonPourUneNouvelleTentative(TypeOperationCreation type) {
        String jeton = ouvrir(type);
        assertThatThrownBy(() -> authentifie(EMAIL, () -> new TransactionTemplate(transactions).execute(status -> {
            creerAuthentifie(type, jeton, 3); entityManager.flush();
            throw new IllegalStateException("Erreur simulée après écriture de caisse");
        }))).hasMessage("Erreur simulée après écriture de caisse");
        verifierAucunEffet();
        assertThat(operations.findById(jeton).orElseThrow().getEmpreinte()).isNull();
        var resultat = creer(type, jeton, 3);
        assertThat(resultat.dejaEnregistre()).isFalse();
        verifierEffets(type, 1, 3);
    }

    @ParameterizedTest
    @EnumSource(TypeOperationCreation.class)
    void deuxTransactionsConcurrentesSurLeMemeJetonCreentUnSeulDocument(TypeOperationCreation type) throws Exception {
        String jeton = ouvrir(type);
        CountDownLatch premierPret = new CountDownLatch(1), liberer = new CountDownLatch(1), secondEntre = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var premier = pool.submit(() -> authentifie(EMAIL, () -> new TransactionTemplate(transactions).execute(status -> {
                entityManager.find(OperationCreation.class, jeton, LockModeType.PESSIMISTIC_WRITE);
                var resultat = creerAuthentifie(type, jeton, 3); entityManager.flush();
                premierPret.countDown(); attendre(liberer); return resultat;
            })));
            attendre(premierPret);
            var second = pool.submit(() -> authentifie(EMAIL, () -> {
                secondEntre.countDown(); return creerAuthentifie(type, jeton, 3);
            }));
            try {
                attendre(secondEntre);
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { liberer.countDown(); }
            var a = premier.get(10, TimeUnit.SECONDS); var b = second.get(10, TimeUnit.SECONDS);
            assertThat(a.dejaEnregistre()).isFalse(); assertThat(b.dejaEnregistre()).isTrue();
            assertThat(b.documentId()).isEqualTo(a.documentId());
        } finally { liberer.countDown(); }
        verifierEffets(type, 1, 3);
    }

    @ParameterizedTest
    @EnumSource(TypeOperationCreation.class)
    void leFormulaireDuNavigateurContientLeJetonEtAccepteUnRejeuAvecLesEnTetes(TypeOperationCreation type) throws Exception {
        var page = mvc.perform(get(route(type) + "/nouveau").with(user(EMAIL).roles("ADMIN"))
                        .header("Accept-Language", "fr-FR")).andExpect(status().isOk()).andReturn();
        String jeton = champCache(page, "jetonCreation"), jetonCsrf = champCache(page, "_csrf");
        assertThat(operations.existsById(jeton)).isTrue();
        MockHttpSession session = (MockHttpSession) page.getRequest().getSession(false);
        for (int copie = 0; copie < 2; copie++) {
            mvc.perform(formulaire(type, jeton, 3).with(user(EMAIL).roles("ADMIN")).session(session)
                            .param("_csrf", jetonCsrf).header("Accept-Language", "fr-FR")
                            .header("User-Agent", "Navigateur de test").header("Accept-Encoding", "gzip, deflate, br")
                            .contentType("application/x-www-form-urlencoded"))
                    .andExpect(status().isFound()).andExpect(redirectedUrl(route(type)));
        }
        verifierEffets(type, 1, 3);
    }

    @ParameterizedTest
    @EnumSource(TypeOperationCreation.class)
    void deuxPostSimultanesDuMemeFormulaireRedirigentSansDoublerLesEffets(TypeOperationCreation type) throws Exception {
        String jeton = ouvrir(type);
        CyclicBarrier depart = new CyclicBarrier(2);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<MvcResult> envoyer = () -> {
                depart.await(10, TimeUnit.SECONDS);
                return mvc.perform(formulaire(type, jeton, 3).with(user(EMAIL).roles("ADMIN")).with(csrf()))
                        .andExpect(status().isFound()).andExpect(redirectedUrl(route(type))).andReturn();
            };
            var premier = pool.submit(envoyer); var second = pool.submit(envoyer);
            premier.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        }
        verifierEffets(type, 1, 3);
    }

    @ParameterizedTest
    @EnumSource(TypeOperationCreation.class)
    void unPostSansJetonCreationEstRefuse(TypeOperationCreation type) throws Exception {
        mvc.perform(formulaire(type, null, 3).with(user(EMAIL).roles("ADMIN")).with(csrf()))
                .andExpect(status().isBadRequest());
        verifierAucunEffet();
    }

    @ParameterizedTest
    @EnumSource(TypeOperationCreation.class)
    void unPostAvecJetonMalFormeEstRefuse(TypeOperationCreation type) throws Exception {
        mvc.perform(formulaire(type, "invalide", 3).with(user(EMAIL).roles("ADMIN")).with(csrf()))
                .andExpect(status().isBadRequest());
        verifierAucunEffet();
    }

    @ParameterizedTest
    @EnumSource(TypeOperationCreation.class)
    void leJetonCreationNeRemplacePasLaProtectionCsrf(TypeOperationCreation type) throws Exception {
        String jeton = ouvrir(type);
        mvc.perform(formulaire(type, jeton, 3).with(user(EMAIL).roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(formulaire(type, jeton, 3).with(user(EMAIL).roles("ADMIN")).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        verifierAucunEffet();
    }

    @ParameterizedTest
    @CsvSource({"ACHAT,id", "ACHAT,lignes[0].achat.id", "VENTE,montantTotal", "VENTE,lignes[0].prixUnitaire"})
    void ajouterUnJetonNAutorisePasLesChampsInternes(TypeOperationCreation type, String champ) throws Exception {
        mvc.perform(formulaire(type, ouvrir(type), 3).with(user(EMAIL).roles("ADMIN")).with(csrf()).param(champ, "1"))
                .andExpect(status().isBadRequest());
        verifierAucunEffet();
    }

    @ParameterizedTest
    @EnumSource(TypeOperationCreation.class)
    void uneErreurMetierConserveLeJetonEtPermetLaCorrectionDuFormulaire(TypeOperationCreation type) throws Exception {
        String jeton = ouvrir(type);
        var invalide = formulaire(type, jeton, type == TypeOperationCreation.VENTE ? 11 : 3);
        if (type == TypeOperationCreation.ACHAT) invalide.param("montantVerse", "999");
        mvc.perform(invalide.with(user(EMAIL).roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andExpect(view().name("dashboard"))
                .andExpect(model().attributeExists("error"))
                .andExpect(content().string(containsString(jeton)));
        verifierAucunEffet();
        assertThat(operations.findById(jeton).orElseThrow().getEmpreinte()).isNull();
        mvc.perform(formulaire(type, jeton, 3).with(user(EMAIL).roles("ADMIN")).with(csrf()))
                .andExpect(status().isFound()).andExpect(redirectedUrl(route(type)));
        verifierEffets(type, 1, 3);
    }

    @ParameterizedTest
    @EnumSource(TypeOperationCreation.class)
    void unPayloadModifieApresSuccesAfficheUnConflitSansNouvelleEcriture(TypeOperationCreation type) throws Exception {
        String jeton = ouvrir(type); creer(type, jeton, 3);
        mvc.perform(formulaire(type, jeton, 4).with(user(EMAIL).roles("ADMIN")).with(csrf()))
                .andExpect(status().isConflict()).andExpect(view().name("dashboard"))
                .andExpect(model().attributeExists("error"));
        verifierEffets(type, 1, 3);
    }

    private Utilisateur utilisateur(String email) {
        Utilisateur utilisateur = new Utilisateur(); utilisateur.setNom("Test création"); utilisateur.setEmail(email);
        utilisateur.setRole(Role.ADMIN); return utilisateurs.saveAndFlush(utilisateur);
    }

    private String ouvrir(TypeOperationCreation type) { return authentifie(EMAIL, () -> service.ouvrir(type)); }

    private OperationCreationService.ResultatCreation creer(TypeOperationCreation type, String jeton, int quantite) {
        return authentifie(EMAIL, () -> creerAuthentifie(type, jeton, quantite));
    }

    private OperationCreationService.ResultatCreation creerAuthentifie(TypeOperationCreation type, String jeton, int quantite) {
        return type == TypeOperationCreation.ACHAT ? service.creerAchat(jeton, achat(quantite)) : service.creerVente(jeton, vente(quantite));
    }

    private OperationCreationService.ResultatCreation creerPanier(TypeOperationCreation type, String jeton, int secondeQuantite) {
        return authentifie(EMAIL, () -> {
            if (type == TypeOperationCreation.ACHAT) {
                Achat achat = achat(2); achat.getLignes().add(achat(secondeQuantite).getLignes().getFirst());
                return service.creerAchat(jeton, achat);
            }
            Vente vente = vente(2); vente.getLignes().add(vente(secondeQuantite).getLignes().getFirst());
            return service.creerVente(jeton, vente);
        });
    }

    private Achat achat(int quantite) {
        Achat achat = new Achat(); Fournisseur reference = new Fournisseur(); reference.setId(fournisseur.getId());
        achat.setFournisseur(reference); DetailAchat ligne = new DetailAchat(); ligne.setProduit(referenceProduit());
        ligne.setQuantite(quantite); ligne.setPrixAchatUnitaire(new BigDecimal("5")); achat.getLignes().add(ligne); return achat;
    }

    private Vente vente(int quantite) {
        Vente vente = new Vente(); DetailVente ligne = new DetailVente(); ligne.setProduit(referenceProduit());
        ligne.setQuantite(quantite); vente.getLignes().add(ligne); return vente;
    }

    private Produit referenceProduit() { Produit reference = new Produit(); reference.setId(produit.getId()); return reference; }

    private void vieillir(String jeton) {
        var operation = operations.findById(jeton).orElseThrow(); operation.setDateCreation(Instant.now().minus(25, ChronoUnit.HOURS));
        operations.saveAndFlush(operation);
    }

    private void conflit(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CreationOperationException.class,
                erreur -> assertThat(erreur.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    private long stock() { return produits.findById(produit.getId()).orElseThrow().getQuantite(); }

    private void verifierAucunEffet() {
        assertThat(stock()).isEqualTo(10L); assertThat(achats.count()).isZero(); assertThat(ventes.count()).isZero();
        assertThat(caisse.count()).isZero();
    }

    private void verifierEffets(TypeOperationCreation type, long nombre, int quantiteTotale) {
        assertThat(achats.count()).isEqualTo(type == TypeOperationCreation.ACHAT ? nombre : 0);
        assertThat(ventes.count()).isEqualTo(type == TypeOperationCreation.VENTE ? nombre : 0);
        assertThat(stock()).isEqualTo(10L + (type == TypeOperationCreation.ACHAT ? quantiteTotale : -quantiteTotale));
        assertThat(caisse.count()).isEqualTo(nombre);
        assertThat(caisse.calculerSoldeTotal()).isEqualByComparingTo(
                BigDecimal.valueOf(quantiteTotale).multiply(BigDecimal.valueOf(type == TypeOperationCreation.ACHAT ? -5 : 20)));
    }

    private String route(TypeOperationCreation type) { return type == TypeOperationCreation.ACHAT ? "/achats" : "/ventes"; }

    private MockHttpServletRequestBuilder formulaire(TypeOperationCreation type, String jeton, int quantite) {
        var requete = post(route(type) + "/enregistrer").param("lignes[0].produit.id", produit.getId().toString())
                .param("lignes[0].quantite", Integer.toString(quantite));
        if (jeton != null) requete.param("jetonCreation", jeton);
        if (type == TypeOperationCreation.ACHAT) requete.param("fournisseur.id", fournisseur.getId().toString()).param("lignes[0].prixAchatUnitaire", "5");
        return requete;
    }

    private String champCache(MvcResult page, String nom) throws Exception {
        var champ = Pattern.compile("<input\\b(?=[^>]*name=\"" + Pattern.quote(nom) + "\")[^>]*value=\"([^\"]*)\"")
                .matcher(page.getResponse().getContentAsString());
        assertThat(champ.find()).as("Champ caché %s", nom).isTrue(); return HtmlUtils.htmlUnescape(champ.group(1));
    }

    private <T> T authentifie(String email, Callable<T> action) {
        var precedent = SecurityContextHolder.getContext(); var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(email, "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(contexte);
        try { return action.call(); }
        catch (RuntimeException erreur) { throw erreur; }
        catch (Exception erreur) { throw new IllegalStateException(erreur); }
        finally { SecurityContextHolder.setContext(precedent); }
    }

    private void attendre(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Synchronisation expirée"); }
        catch (InterruptedException erreur) { Thread.currentThread().interrupt(); throw new IllegalStateException(erreur); }
    }
}
