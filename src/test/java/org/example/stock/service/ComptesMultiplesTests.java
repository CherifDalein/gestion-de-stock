package org.example.stock.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.example.stock.enums.Role;
import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.form.CompteTresorerieForm;
import org.example.stock.form.TransfertTresorerieForm;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.example.stock.enums.TypeCompteTresorerie.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Comptes de trésorerie multiples (lot 3) : création, limite de 12, unicité du nom, compte principal, archivage, solde de départ,
 * droits du caissier, isolation entre boutiques, choix du compte dans les règlements, transferts, exports et reçus. H2, vraies transactions.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:comptes-multiples;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class ComptesMultiplesTests {
    static final String ADMIN = "gerant-cm@example.test", CAISSIER = "caissier-cm@example.test", ADMIN2 = "gerant2-cm@example.test";

    @Autowired BoutiqueRepository boutiques;
    @Autowired ProduitRepository produits;
    @Autowired CategorieRepository categories;
    @Autowired ClientRepository clients;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired VenteRepository ventes;
    @Autowired MouvementCaisseRepository caisse;
    @Autowired OperationTresorerieRepository operationsTresorerie;
    @Autowired OperationCreationRepository operations;
    @Autowired RetourRepository retours;
    @Autowired AchatRepository achats;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired CompteTresorerieBoutiqueRepository comptesRepo;
    @Autowired CompteTresorerieAutoriseRepository autorisationsRepo;
    @Autowired ComptesTresorerieService comptes;
    @Autowired CaisseService caisseService;
    @Autowired VenteService venteService;
    @Autowired MockMvc mvc;

    Boutique boutique, autre;
    Utilisateur caissier;
    Client client;
    Produit produit;

    @BeforeEach
    void preparer() {
        autorisationsRepo.deleteAll(); caisse.deleteAll(); operationsTresorerie.deleteAll(); operations.deleteAll(); retours.deleteAll(); ventes.deleteAll();
        achats.deleteAll(); produits.deleteAll(); categories.deleteAll(); fournisseurs.deleteAll(); clients.deleteAll(); utilisateurs.deleteAll();
        boutique = boutique("Boutique comptes"); autre = boutique("Autre boutique comptes");
        utilisateur(ADMIN, Role.ADMIN, boutique); caissier = utilisateur(CAISSIER, Role.CAISSIER, boutique); utilisateur(ADMIN2, Role.ADMIN, autre);
        Client c = new Client(); c.setBoutique(boutique); c.setNom("Client comptes"); client = clients.saveAndFlush(c);
        Categorie categorie = new Categorie(); categorie.setBoutique(boutique); categorie.setNom("Catégorie"); categorie = categories.save(categorie);
        Produit p = new Produit(); p.setBoutique(boutique); p.setNom("Produit"); p.setReference("CM-1"); p.setCategorie(categorie); p.setQuantite(500L);
        p.setPrixAchat(new BigDecimal("5000.00")); p.setPrixVente(new BigDecimal("10000.00")); p.setPrixDefinis(true); produit = produits.saveAndFlush(p);
        connecter(ADMIN, "ADMIN");
    }

    @AfterEach void deconnecter() { SecurityContextHolder.clearContext(); }

    // ---- Création, nom, limite ------------------------------------------------------------------------------

    @Test
    void uneBoutiqueAPlusieursComptesDuMemeTypeEtLesTotauxParTypeSontLeurSomme() {
        CompteTresorerieBoutique labe = creer(ORANGE_MONEY, "Orange Money Labé", "5000");
        CompteTresorerieBoutique conakry = creer(ORANGE_MONEY, "Orange Money Conakry", "2500.50");
        var ecran = comptes.ecran();
        assertThat(ecran.actifs()).isEqualTo(5);
        var groupe = ecran.groupes().stream().filter(g -> g.type() == ORANGE_MONEY).findFirst().orElseThrow();
        assertThat(groupe.comptes()).extracting(v -> v.compte().getNom()).containsExactly("Orange Money", "Orange Money Labé", "Orange Money Conakry");
        assertThat(groupe.total()).isEqualByComparingTo("7500.50");
        assertThat(caisseService.getSoldeCompte(labe.getId())).isEqualByComparingTo("5000");
        assertThat(caisseService.getSoldeCompte(conakry.getId())).isEqualByComparingTo("2500.50");
        assertThat(caisseService.getSoldesParType().get(ORANGE_MONEY)).isEqualByComparingTo("7500.50");
        assertThat(caisseService.getSoldesParType().get(ESPECES)).isZero();
        assertThat(caisseService.getSoldeActuel()).isEqualByComparingTo("7500.50");
        assertThat(labe.isPrincipal()).isFalse();
        assertThat(labe.getType()).isEqualTo(ORANGE_MONEY);
    }

    @Test
    void leNomEstObligatoireEtUniqueSansTenirCompteDeLaCasse() {
        creer(BANQUE, "Ecobank", null);
        assertThatThrownBy(() -> creer(BANQUE, "  ecobank ", null)).hasMessageContaining("déjà");
        assertThatThrownBy(() -> creer(ORANGE_MONEY, "ECOBANK", null)).hasMessageContaining("déjà");
        assertThatThrownBy(() -> creer(BANQUE, "Banque", null)).hasMessageContaining("déjà");
        assertThatThrownBy(() -> creer(BANQUE, "   ", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> creer(BANQUE, "x".repeat(61), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> creer(BANQUE, "BICIGUI", "-1")).hasMessageContaining("négatif");
        // Le même nom reste libre dans une autre boutique.
        connecter(ADMIN2, "ADMIN");
        assertThat(creer(BANQUE, "Ecobank", null).getNom()).isEqualTo("Ecobank");
    }

    @Test
    void uneBoutiqueNePeutPasDepasserDouzeComptesEtUnArchivageLibereUnePlace() {
        for (int i = 1; i <= 9; i++) creer(ORANGE_MONEY, "Orange " + i, null);
        assertThat(comptes.ecran().actifs()).isEqualTo(12);
        assertThat(comptes.ecran().limiteAtteinte()).isTrue();
        assertThatThrownBy(() -> creer(BANQUE, "Treizième", null)).hasMessageContaining("12");
        assertThat(comptesRepo.listerActifs(boutique.getId())).hasSize(12);
        CompteTresorerieBoutique dernier = comptesRepo.lister(boutique.getId()).stream().filter(c -> c.getNom().equals("Orange 9")).findFirst().orElseThrow();
        comptes.archiver(dernier.getId());
        assertThat(comptes.ecran().actifs()).isEqualTo(11);
        creer(BANQUE, "Treizième", null);
        assertThatThrownBy(() -> comptes.desarchiver(dernier.getId())).hasMessageContaining("12");
    }

    // ---- Principal, archivage, solde de départ ---------------------------------------------------------------

    @Test
    void unSeulCompteEstPrincipalParTypeEtChoisirUnAutreRetireLAncien() {
        CompteTresorerieBoutique labe = creer(ORANGE_MONEY, "Orange Money Labé", null);
        assertThat(principaux(ORANGE_MONEY)).extracting(CompteTresorerieBoutique::getNom).containsExactly("Orange Money");
        comptes.definirPrincipal(labe.getId());
        assertThat(principaux(ORANGE_MONEY)).extracting(CompteTresorerieBoutique::getNom).containsExactly("Orange Money Labé");
        assertThat(principaux(ESPECES)).hasSize(1);
        assertThat(principaux(BANQUE)).hasSize(1);
        assertThat(comptes.principal(ORANGE_MONEY).getId()).isEqualTo(labe.getId());
    }

    @Test
    void unCompteArchivableADesSoldeNulEtN_EstPasPrincipal() {
        CompteTresorerieBoutique labe = creer(ORANGE_MONEY, "Orange Money Labé", "100");
        assertThatThrownBy(() -> comptes.archiver(labe.getId())).hasMessageContaining("solde");
        CompteTresorerieBoutique vide = creer(ORANGE_MONEY, "Orange vide", null);
        CompteTresorerieBoutique principal = comptes.principal(ORANGE_MONEY);
        assertThatThrownBy(() -> comptes.archiver(principal.getId())).hasMessageContaining("principal");
        comptes.archiver(vide.getId());
        assertThat(comptesRepo.findById(vide.getId()).orElseThrow().isActif()).isFalse();
        assertThat(comptes.utilisables()).extracting(CompteTresorerieBoutique::getNom).doesNotContain("Orange vide");
        // L'historique reste lisible : le compte archivé figure toujours dans la liste complète.
        assertThat(comptes.listerTous()).extracting(CompteTresorerieBoutique::getNom).contains("Orange vide");
        comptes.desarchiver(vide.getId());
        assertThat(comptes.utilisables()).extracting(CompteTresorerieBoutique::getNom).contains("Orange vide");
    }

    @Test
    void leSoldeDeDepartSeSaisitUneFoisEtNeChangePlusApresUnMouvement() {
        CompteTresorerieBoutique compte = creer(BANQUE, "Ecobank", null);
        comptes.definirSoldeDepart(compte.getId(), new BigDecimal("1000"));
        assertThat(caisseService.getSoldeCompte(compte.getId())).isEqualByComparingTo("1000");
        assertThatThrownBy(() -> comptes.definirSoldeDepart(compte.getId(), new BigDecimal("2000"))).hasMessageContaining("figé");
        CompteTresorerieBoutique avecMouvement = creer(BANQUE, "BICIGUI", "500");
        assertThatThrownBy(() -> comptes.definirSoldeDepart(avecMouvement.getId(), new BigDecimal("700"))).hasMessageContaining("figé");
        assertThat(caisseService.getSoldeCompte(avecMouvement.getId())).isEqualByComparingTo("500");
        assertThat(caisse.findAll().stream().filter(m -> m.getCompte().getId().equals(avecMouvement.getId()))).singleElement()
                .satisfies(m -> { assertThat(m.getSource()).isEqualTo("SOLDE_INITIAL"); assertThat(m.getType()).isEqualTo("OUVERTURE"); });
    }

    // ---- Droits du caissier ----------------------------------------------------------------------------------

    @Test
    void unCaissierSansAutorisationUtiliseLesComptesPrincipauxPuisSeulementCeuxQueLeGerantCoche() {
        CompteTresorerieBoutique labe = creer(ORANGE_MONEY, "Orange Money Labé", null);
        CompteTresorerieBoutique ecobank = creer(BANQUE, "Ecobank", null);
        connecter(CAISSIER, "CAISSIER");
        assertThat(comptes.utilisables()).extracting(CompteTresorerieBoutique::getNom).containsExactly("Caisse", "Orange Money", "Banque");
        connecter(ADMIN, "ADMIN");
        assertThat(comptes.utilisables()).hasSize(5);
        comptes.autoriser(caissier.getId(), List.of(labe.getId(), ecobank.getId()));
        connecter(CAISSIER, "CAISSIER");
        assertThat(comptes.utilisables()).extracting(CompteTresorerieBoutique::getNom).containsExactlyInAnyOrder("Orange Money Labé", "Ecobank");
        assertThat(comptes.resoudreUtilisable(String.valueOf(labe.getId())).getId()).isEqualTo(labe.getId());
        // Le nom d'un type désigne le principal ; il n'est plus autorisé à ce caissier.
        assertThatThrownBy(() -> comptes.resoudreUtilisable("ESPECES")).isInstanceOf(ResponseStatusException.class);
        // Archiver un compte coché le retire des choix.
        connecter(ADMIN, "ADMIN");
        comptes.archiver(labe.getId());
        connecter(CAISSIER, "CAISSIER");
        assertThat(comptes.utilisables()).extracting(CompteTresorerieBoutique::getNom).containsExactly("Ecobank");
        assertThatThrownBy(() -> comptes.resoudreUtilisable(String.valueOf(labe.getId()))).isInstanceOf(ResponseStatusException.class);
        // Tout décocher ramène aux comptes principaux.
        connecter(ADMIN, "ADMIN");
        comptes.autoriser(caissier.getId(), List.of());
        connecter(CAISSIER, "CAISSIER");
        assertThat(comptes.utilisables()).extracting(CompteTresorerieBoutique::getNom).containsExactly("Caisse", "Orange Money", "Banque");
    }

    @Test
    void leGerantNeBeneficieQueDesComptesDeSaBoutiqueEtLesAutorisationsRestentDansLaBoutique() {
        CompteTresorerieBoutique etranger = comptesRepo.listerActifs(autre.getId()).getFirst();
        assertThatThrownBy(() -> comptes.autoriser(caissier.getId(), List.of(etranger.getId()))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> comptes.modifier(etranger.getId(), "Pirate", null)).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> comptes.archiver(etranger.getId())).isInstanceOf(ResponseStatusException.class);
        assertThat(comptesRepo.findById(etranger.getId()).orElseThrow().getNom()).isNotEqualTo("Pirate");
        assertThat(autorisationsRepo.count()).isZero();
    }

    // ---- Règlements sur un compte choisi -----------------------------------------------------------------------

    @Test
    void unReglementUtiliseLeCompteChoisiEtSonAnnulationLeContrePasseSurLeMemeCompte() throws Exception {
        CompteTresorerieBoutique labe = creer(ORANGE_MONEY, "Orange Money Labé", null);
        Vente vente = vente("20000");
        venteService.reglerVente(vente.getId(), new BigDecimal("30000"), new BigDecimal("20000"), String.valueOf(labe.getId()), null);
        MouvementCaisse reglement = caisse.findAll().stream().filter(m -> "REGLEMENT_VENTE".equals(m.getSource())).findFirst().orElseThrow();
        assertThat(reglement.getCompte().getId()).isEqualTo(labe.getId());
        assertThat(caisseService.getSoldeCompte(labe.getId())).isEqualByComparingTo("30000");
        assertThat(caisseService.getSoldeCompte(comptes.principal(ORANGE_MONEY).getId())).isZero();
        // Le compte est archivé après un règlement : l'annulation se fait quand même sur le compte d'origine.
        mvc.perform(post("/ventes/regler/" + vente.getId() + "/annuler/" + reglement.getId()).with(csrf()).with(user(ADMIN).roles("ADMIN")).param("motif", "Erreur"))
                .andExpect(status().is3xxRedirection());
        connecter(ADMIN, "ADMIN");
        MouvementCaisse inverse = caisse.findAll().stream().filter(m -> "ANNULATION".equals(m.getSource())).findFirst().orElseThrow();
        assertThat(inverse.getCompte().getId()).isEqualTo(labe.getId());
        assertThat(inverse.getMontant()).isEqualByComparingTo("-30000");
        assertThat(caisseService.getSoldeCompte(labe.getId())).isZero();
        comptes.archiver(labe.getId());
    }

    @Test
    void unCompteInconnuArchiveNonAutoriseOuDUneAutreBoutiqueEstRefuseSansRienEcrire() throws Exception {
        CompteTresorerieBoutique labe = creer(ORANGE_MONEY, "Orange Money Labé", null);
        CompteTresorerieBoutique archive = creer(BANQUE, "Ancienne banque", null);
        comptes.archiver(archive.getId());
        CompteTresorerieBoutique etranger = comptesRepo.listerActifs(autre.getId()).getFirst();
        Vente vente = vente("20000");
        long avant = caisse.count();
        for (String saisie : List.of(String.valueOf(archive.getId()), String.valueOf(etranger.getId()), "999999", "inconnu")) {
            assertThatThrownBy(() -> venteService.reglerVente(vente.getId(), new BigDecimal("1000"), new BigDecimal("20000"), saisie, null))
                    .isInstanceOf(ResponseStatusException.class).satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
            mvc.perform(post("/ventes/regler/" + vente.getId()).with(csrf()).with(user(ADMIN).roles("ADMIN")).param("montant", "1000")
                    .param("montantVerseAttendu", "20000").param("comptePaiement", saisie)).andExpect(status().isBadRequest());
            connecter(ADMIN, "ADMIN");
        }
        // Un caissier qui n'a pas reçu l'autorisation sur le compte d'Orange Money Labé est refusé aussi.
        mvc.perform(post("/ventes/regler/" + vente.getId()).with(csrf()).with(user(CAISSIER).roles("CAISSIER")).param("montant", "1000")
                .param("montantVerseAttendu", "20000").param("comptePaiement", String.valueOf(labe.getId()))).andExpect(status().isBadRequest());
        assertThat(caisse.count()).isEqualTo(avant);
        assertThat(ventes.findById(vente.getId()).orElseThrow().getMontantVerse()).isEqualByComparingTo("20000");
        // Autorisé, il règle sur ce compte.
        connecter(ADMIN, "ADMIN");
        comptes.autoriser(caissier.getId(), List.of(labe.getId()));
        mvc.perform(post("/ventes/regler/" + vente.getId()).with(csrf()).with(user(CAISSIER).roles("CAISSIER")).param("montant", "1000")
                .param("montantVerseAttendu", "20000").param("comptePaiement", String.valueOf(labe.getId()))).andExpect(status().is3xxRedirection());
        connecter(ADMIN, "ADMIN");
        assertThat(caisseService.getSoldeCompte(labe.getId())).isEqualByComparingTo("1000");
    }

    // ---- Transferts ------------------------------------------------------------------------------------------

    @Test
    void unTransfertEntreDeuxComptesDuMemeTypeConserveLeTotalDuType() {
        CompteTresorerieBoutique labe = creer(ORANGE_MONEY, "Orange Money Labé", "1000");
        CompteTresorerieBoutique conakry = creer(ORANGE_MONEY, "Orange Money Conakry", "0");
        BigDecimal totalAvant = caisseService.getSoldeActuel();
        assertThat(caisseService.transferer(transfert(labe.getId(), conakry.getId(), "400"))).isFalse();
        assertThat(caisseService.getSoldeCompte(labe.getId())).isEqualByComparingTo("600");
        assertThat(caisseService.getSoldeCompte(conakry.getId())).isEqualByComparingTo("400");
        assertThat(caisseService.getSoldesParType().get(ORANGE_MONEY)).isEqualByComparingTo("1000");
        assertThat(caisseService.getSoldeActuel()).isEqualByComparingTo(totalAvant);
        assertThatThrownBy(() -> caisseService.transferer(transfert(labe.getId(), labe.getId(), "1"))).hasMessageContaining("différents");
        assertThatThrownBy(() -> caisseService.transferer(transfert(labe.getId(), conakry.getId(), "601"))).hasMessageContaining("insuffisant");
        CompteTresorerieBoutique etranger = comptesRepo.listerActifs(autre.getId()).getFirst();
        assertThatThrownBy(() -> caisseService.transferer(transfert(labe.getId(), etranger.getId(), "1"))).isInstanceOf(ResponseStatusException.class);
        assertThat(caisseService.getSoldeCompte(labe.getId())).isEqualByComparingTo("600");
    }

    // ---- Exports et reçus --------------------------------------------------------------------------------------

    @Test
    void lePdfDuJournalEtLeRecuPortentLeNomDuCompte() throws Exception {
        CompteTresorerieBoutique labe = creer(ORANGE_MONEY, "Orange Money Labé", null);
        Vente vente = vente("20000");
        venteService.reglerVente(vente.getId(), new BigDecimal("30000"), new BigDecimal("20000"), String.valueOf(labe.getId()), null);
        MouvementCaisse reglement = caisse.findAll().stream().filter(m -> "REGLEMENT_VENTE".equals(m.getSource())).findFirst().orElseThrow();
        String journal = texte(mvc.perform(get("/caisse/export/pdf").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse());
        assertThat(journal).contains("Orange Money Labé");
        String recu = texte(mvc.perform(get("/ventes/regler/" + vente.getId() + "/recu/" + reglement.getId()).with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse());
        assertThat(recu).contains("Orange Money Labé");
        String page = mvc.perform(get("/caisse/journal").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Orange Money Labé");
        String excel = mvc.perform(get("/caisse/export/excel").param("compte", String.valueOf(labe.getId())).with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentType();
        assertThat(excel).contains("spreadsheetml");
        mvc.perform(get("/caisse/export/excel").param("compte", "ORANGE_MONEY").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isBadRequest());
    }

    // ---- Page de gestion : droits et liste blanche --------------------------------------------------------------

    @Test
    void laPageDeGestionEstReserveeAuGerantEtRefuseLesChampsInconnus() throws Exception {
        mvc.perform(get("/caisse/comptes").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk()).andExpect(model().attributeExists("ecran"));
        mvc.perform(get("/caisse/comptes").with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        mvc.perform(get("/caisse/comptes").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/caisse/comptes").with(csrf()).with(user(CAISSIER).roles("CAISSIER")).param("type", "BANQUE").param("nom", "X")).andExpect(status().isForbidden());
        mvc.perform(post("/caisse/comptes").with(user(ADMIN).roles("ADMIN")).param("type", "BANQUE").param("nom", "X")).andExpect(status().isForbidden());
        for (String champ : List.of("boutiqueId", "boutique.id", "principal", "actif", "id", "nomCle", "principalType")) {
            mvc.perform(post("/caisse/comptes").with(csrf()).with(user(ADMIN).roles("ADMIN")).param("type", "BANQUE").param("nom", "Pirate " + champ).param(champ, "1"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(comptesRepo.lister(boutique.getId())).extracting(CompteTresorerieBoutique::getNom).noneMatch(n -> n.startsWith("Pirate"));
        mvc.perform(post("/caisse/comptes").with(csrf()).with(user(ADMIN).roles("ADMIN")).param("type", "BANQUE").param("nom", "BICIGUI").param("numero", "001")
                .param("soldeDepart", "2500")).andExpect(status().is3xxRedirection());
        CompteTresorerieBoutique cree = comptesRepo.lister(boutique.getId()).stream().filter(c -> c.getNom().equals("BICIGUI")).findFirst().orElseThrow();
        assertThat(cree.getNumero()).isEqualTo("001");
        connecter(ADMIN, "ADMIN");
        assertThat(caisseService.getSoldeCompte(cree.getId())).isEqualByComparingTo("2500");
        // Le compte d'une autre boutique est introuvable (404), jamais modifié.
        CompteTresorerieBoutique etranger = comptesRepo.listerActifs(autre.getId()).getFirst();
        mvc.perform(post("/caisse/comptes/" + etranger.getId() + "/modifier").with(csrf()).with(user(ADMIN).roles("ADMIN")).param("nom", "Pirate").param("numero", ""))
                .andExpect(status().isNotFound());
        mvc.perform(post("/caisse/comptes/" + cree.getId() + "/modifier").with(csrf()).with(user(ADMIN).roles("ADMIN")).param("nom", "BICIGUI Labé").param("numero", "002").param("actif", "false"))
                .andExpect(status().isBadRequest());
        // Les cases cochées d'un caissier se limitent aux comptes de la boutique.
        mvc.perform(post("/caisse/comptes/autorisations/" + caissier.getId()).with(csrf()).with(user(ADMIN).roles("ADMIN")).param("comptes", String.valueOf(etranger.getId())))
                .andExpect(status().is4xxClientError());
        assertThat(autorisationsRepo.count()).isZero();
    }


    // ---- Outils ------------------------------------------------------------------------------------------------

    private CompteTresorerieBoutique creer(TypeCompteTresorerie type, String nom, String solde) {
        var form = new CompteTresorerieForm(); form.setType(type); form.setNom(nom); form.setSoldeDepart(solde == null ? null : new BigDecimal(solde));
        return comptes.creer(form);
    }

    private List<CompteTresorerieBoutique> principaux(TypeCompteTresorerie type) {
        return comptesRepo.lister(boutique.getId()).stream().filter(c -> c.getType() == type && c.isPrincipal()).toList();
    }

    private TransfertTresorerieForm transfert(Long source, Long destination, String montant) {
        var f = new TransfertTresorerieForm(); f.setCompteSource(String.valueOf(source)); f.setCompteDestination(String.valueOf(destination));
        f.setMontant(new BigDecimal(montant)); f.setMotif("Transfert de test"); f.setJetonOperation(UUID.randomUUID().toString()); return f;
    }

    private Vente vente(String verse) {
        Vente vente = new Vente(); vente.setBoutique(boutique);
        Client c = new Client(); c.setId(client.getId()); vente.setClient(c);
        vente.setMontantVerse(new BigDecimal(verse));
        Produit p = new Produit(); p.setId(produit.getId());
        DetailVente ligne = new DetailVente(); ligne.setProduit(p); ligne.setQuantite(10); vente.getLignes().add(ligne);
        return venteService.effectuerVente(vente);
    }

    private static String texte(MockHttpServletResponse reponse) throws Exception {
        try (PDDocument document = Loader.loadPDF(reponse.getContentAsByteArray())) {
            return new PDFTextStripper().getText(document).replace(' ', ' ').replace(' ', ' ').replaceAll("\\s+", " ");
        }
    }

    private Boutique boutique(String nom) { Boutique b = new Boutique(); b.setNom(nom); return boutiques.saveAndFlush(b); }

    private Utilisateur utilisateur(String email, Role role, Boutique b) {
        Utilisateur u = new Utilisateur(); u.setBoutique(b); u.setEmail(email); u.setNom(email); u.setRole(role); return utilisateurs.saveAndFlush(u);
    }

    private static void connecter(String email, String role) {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(email, "", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        SecurityContextHolder.setContext(contexte);
    }
}
