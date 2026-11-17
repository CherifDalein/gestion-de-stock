package org.example.stock.pays;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.stock.enums.Role;
import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.example.stock.service.CaisseService;
import org.example.stock.service.VenteService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Une boutique au Sénégal ne voit que « FCFA » (aucune trace de « GNF » sur aucun écran ni document) ; une boutique en Guinée voit « GNF ».
 * Inscription avec pays, compte mobile money avec l'opérateur Wave, Excel sans décimales et PDF avec la bonne monnaie. H2, vraies transactions.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pays-monnaie-mvc;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class PaysMonnaieMvcTests {
    static final String SN = "gerant-sn@example.test", GN = "gerant-gn@example.test", CAISSIER_SN = "caissier-sn@example.test";
    static final String NB = " ";

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
    @Autowired VenteService venteService;
    @Autowired CaisseService caisseService;
    @Autowired MockMvc mvc;

    Boutique dakar, conakry;
    Produit produitDakar, produitConakry;
    Client clientDakar;
    long venteDakar;

    @BeforeEach
    void preparer() {
        autorisationsRepo.deleteAll(); caisse.deleteAll(); operationsTresorerie.deleteAll(); operations.deleteAll(); retours.deleteAll(); ventes.deleteAll();
        achats.deleteAll(); produits.deleteAll(); categories.deleteAll(); fournisseurs.deleteAll(); clients.deleteAll(); utilisateurs.deleteAll();
        dakar = boutiques.saveAndFlush(boutique(new Boutique(Pays.SENEGAL), "Boutique de Dakar"));
        conakry = boutiques.saveAndFlush(boutique(new Boutique(), "Boutique de Conakry"));
        utilisateur(SN, Role.ADMIN, dakar); utilisateur("plateforme@example.test", Role.ADMIN, dakar); utilisateur(CAISSIER_SN, Role.CAISSIER, dakar); utilisateur(GN, Role.ADMIN, conakry);
        clientDakar = client(dakar, "Awa Diop");
        produitDakar = produit(dakar, "DK-1"); produitConakry = produit(conakry, "CK-1");
        connecter(SN, "ADMIN");
        Vente v = new Vente(); v.setBoutique(dakar); v.setClient(clientDakar); v.setMontantVerse(new BigDecimal("20000"));
        Produit p = new Produit(); p.setId(produitDakar.getId());
        DetailVente ligne = new DetailVente(); ligne.setProduit(p); ligne.setQuantite(3); v.getLignes().add(ligne);
        venteDakar = venteService.effectuerVente(v).getId();
        connecter(GN, "ADMIN");
        Vente w = new Vente(); w.setBoutique(conakry); w.setMontantVerse(new BigDecimal("30000"));
        Produit q = new Produit(); q.setId(produitConakry.getId());
        DetailVente l2 = new DetailVente(); l2.setProduit(q); l2.setQuantite(3); w.getLignes().add(l2);
        venteService.effectuerVente(w);
    }

    @AfterEach void deconnecter() { SecurityContextHolder.clearContext(); }

    private static final List<String> ECRANS = List.of("/", "/ventes", "/ventes/nouveau", "/clients", "/fournisseurs", "/produits", "/caisse/journal", "/caisse/comptes",
            "/caisse/ouverture", "/caisse/transfert", "/caisse/depense", "/controles/comptages/nouveau", "/controles/comptages", "/etats/bilan", "/etats/creances", "/etats/dettes",
            "/etats/encaissements", "/etats/depenses", "/etats/stock", "/etats/justificatifs", "/comptabilite", "/comptabilite/balance", "/comptabilite/journal",
            "/comptabilite/etats-financiers/bilan?du=2026-01-01&au=2026-12-31", "/boutique/parametres");

    @Test
    void uneBoutiqueDuSenegalNeVoitQueDesFcfaSurTousSesEcransEtDocuments() throws Exception {
        for (String ecran : ECRANS) {
            String html = lire(SN, ecran);
            assertThat(html).as(ecran).doesNotContain("GNF").doesNotContainIgnoringCase("francs guinéens").doesNotContain("Orange Money Labé");
        }
        // Les écrans qui portent des montants affichent le symbole du Sénégal, sans décimales.
        for (String ecran : List.of("/", "/ventes", "/clients", "/caisse/journal", "/caisse/comptes", "/produits", "/etats/bilan", "/etats/justificatifs")) {
            String html = lire(SN, ecran);
            assertThat(html).as(ecran).contains("FCFA");
            assertThat(html).as(ecran).doesNotContain(",00 FCFA").doesNotContain(",00" + NB + "FCFA");
        }
        assertThat(lire(SN, "/ventes")).contains("30" + NB + "000" + NB + "FCFA");
        assertThat(lire(SN, "/caisse/journal")).contains("20" + NB + "000" + NB + "FCFA");
        assertThat(lire(SN, "/clients")).contains("10" + NB + "000" + NB + "FCFA");   // reste dû : 30 000 - 20 000
    }

    @Test
    void uneBoutiqueEnGuineeVoitDesGnf() throws Exception {
        for (String ecran : List.of("/", "/ventes", "/caisse/journal", "/caisse/comptes", "/produits", "/etats/bilan")) {
            String html = lire(GN, ecran);
            assertThat(html).as(ecran).contains("GNF").doesNotContain("FCFA");
        }
        assertThat(lire(GN, "/ventes")).contains("30" + NB + "000" + NB + "GNF");
        assertThat(lire(GN, "/caisse/comptes")).contains("Orange Money");
    }

    @Test
    void leBilletageDuComptageSuitLaMonnaieDeLaBoutique() throws Exception {
        String sn = lire(SN, "/controles/comptages/nouveau");
        for (int coupure : Monnaie.XOF.getCoupures()) assertThat(sn).as("coupure " + coupure).contains("data-coupure=\"" + coupure + "\"");
        assertThat(sn).doesNotContain("data-coupure=\"20000\"");
        String gn = lire(GN, "/controles/comptages/nouveau");
        for (int coupure : Monnaie.GNF.getCoupures()) assertThat(gn).contains("data-coupure=\"" + coupure + "\"");
        assertThat(gn).doesNotContain("data-coupure=\"250\"").contains("data-coupure=\"20000\"");
        // Le symbole est posé sur la page pour le JavaScript.
        assertThat(lire(SN, "/controles/comptages/nouveau")).contains("data-monnaie=\"FCFA\"");
        assertThat(lire(GN, "/controles/comptages/nouveau")).contains("data-monnaie=\"GNF\"");
    }

    @Test
    void unCompteMobileMoneyAvecLOperateurWaveEstEnregistreEtAffiche() throws Exception {
        mvc.perform(post("/caisse/comptes").with(csrf()).with(user(SN).roles("ADMIN")).param("type", "MOBILE_MONEY").param("nom", "Wave Dakar")
                .param("operateur", "Wave").param("numero", "77 000 00 00").param("soldeDepart", "5000")).andExpect(status().is3xxRedirection());
        CompteTresorerieBoutique wave = comptesRepo.lister(dakar.getId()).stream().filter(c -> c.getNom().equals("Wave Dakar")).findFirst().orElseThrow();
        assertThat(wave.getType()).isEqualTo(TypeCompteTresorerie.MOBILE_MONEY);
        assertThat(wave.getOperateur()).isEqualTo("Wave");
        String page = lire(SN, "/caisse/comptes");
        assertThat(page).contains("Wave Dakar", "Mobile money · Wave", "Mobile money");
        // Opérateur libre.
        mvc.perform(post("/caisse/comptes").with(csrf()).with(user(SN).roles("ADMIN")).param("type", "MOBILE_MONEY").param("nom", "Autre compte")
                .param("operateur", "AUTRE").param("operateurAutre", "Kpay")).andExpect(status().is3xxRedirection());
        assertThat(comptesRepo.lister(dakar.getId()).stream().filter(c -> c.getNom().equals("Autre compte")).findFirst().orElseThrow().getOperateur()).isEqualTo("Kpay");
        // Un opérateur d'un autre pays est refusé : aucun compte n'est créé.
        mvc.perform(post("/caisse/comptes").with(csrf()).with(user(SN).roles("ADMIN")).param("type", "MOBILE_MONEY").param("nom", "Refusé").param("operateur", "MTN MoMo"));
        assertThat(comptesRepo.lister(dakar.getId())).extracting(CompteTresorerieBoutique::getNom).doesNotContain("Refusé");
        // L'ancienne valeur de formulaire ORANGE_MONEY est lue comme MOBILE_MONEY.
        mvc.perform(post("/caisse/comptes").with(csrf()).with(user(SN).roles("ADMIN")).param("type", "ORANGE_MONEY").param("nom", "Ancien formulaire").param("operateur", "Orange Money"))
                .andExpect(status().is3xxRedirection());
        assertThat(comptesRepo.lister(dakar.getId()).stream().filter(c -> c.getNom().equals("Ancien formulaire")).findFirst().orElseThrow().getType())
                .isEqualTo(TypeCompteTresorerie.MOBILE_MONEY);
        // Les opérateurs proposés sont ceux du Sénégal.
        assertThat(page).contains("Free Money").doesNotContain("MTN MoMo").doesNotContain("Celtiis");
    }

    @Test
    void lePaysEtLaMonnaieNeSontPasModifiablesParLesParametres() throws Exception {
        for (String champ : List.of("pays", "monnaie")) {
            mvc.perform(post("/boutique/parametres").with(csrf()).with(user(SN).roles("ADMIN")).param("nom", "Boutique de Dakar").param(champ, champ.equals("pays") ? "GN" : "GNF"))
                    .andExpect(status().isBadRequest());
        }
        Boutique relue = boutiques.findById(dakar.getId()).orElseThrow();
        assertThat(relue.getPays()).isEqualTo(Pays.SENEGAL);
        assertThat(relue.getMonnaie()).isEqualTo(Monnaie.XOF);
        String parametres = lire(SN, "/boutique/parametres");
        assertThat(parametres).contains("Sénégal", "franc CFA").doesNotContain("name=\"pays\"").doesNotContain("name=\"monnaie\"");
    }

    @Test
    void laPlateformeChoisitLePaysALaCreationEtAfficheSesBoutiquesParMonnaie() throws Exception {
        String nouvelle = lire("plateforme@example.test", "/plateforme/boutiques/nouvelle", "ADMIN", "PLATEFORME_ADMIN");
        assertThat(nouvelle).contains("name=\"pays\"", "Sénégal", "Cameroun");
        assertThat(nouvelle).containsPattern("value=\"GN\"[^>]*selected");
        String liste = lire("plateforme@example.test", "/plateforme/boutiques", "ADMIN", "PLATEFORME_ADMIN");
        assertThat(liste).contains("Sénégal", "Guinée", "FCFA", "GNF", "Boutiques par monnaie").doesNotContain("FCFA UEMOA : 0");
        // Aucun total de montants : seulement des nombres de boutiques par monnaie.
        assertThat(liste).doesNotContainPattern("\\d" + NB + "FCFA").doesNotContainPattern("\\d" + NB + "GNF");
    }

    @Test
    void lExcelAGardeDeVraisNombresSansDecimalesEtLeSymboleDeLaBoutique() throws Exception {
        for (var cas : List.of(new String[] {SN, "FCFA"}, new String[] {GN, "GNF"})) {
            MockHttpServletResponse reponse = mvc.perform(get("/ventes/export/excel").with(user(cas[0]).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse();
            try (Workbook classeur = new XSSFWorkbook(new ByteArrayInputStream(reponse.getContentAsByteArray()))) {
                Sheet feuille = classeur.getSheetAt(0);
                int montants = 0;
                for (Row ligne : feuille) for (Cell cellule : ligne) {
                    if (cellule.getCellType() != CellType.NUMERIC || org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cellule)) continue;
                    String format = cellule.getCellStyle().getDataFormatString();
                    if (!format.contains(cas[1])) continue;
                    montants++;
                    assertThat(format).as("format de cellule").isEqualTo("#,##0 \"" + cas[1] + "\"").doesNotContain(".00").doesNotContain("0.0");
                }
                assertThat(montants).as("cellules de montant (" + cas[1] + ")").isPositive();
                String autre = cas[1].equals("FCFA") ? "GNF" : "FCFA";
                for (Row ligne : feuille) for (Cell cellule : ligne) {
                    if (cellule.getCellType() == CellType.STRING) assertThat(cellule.getStringCellValue()).doesNotContain(autre);
                    else assertThat(cellule.getCellStyle().getDataFormatString()).doesNotContain(autre);
                }
            }
        }
    }

    @Test
    void lesPdfPortentLaMonnaieDeLaBoutique() throws Exception {
        String recu = pdf(mvc.perform(get("/ventes/recu/" + venteDakar).with(user(SN).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse());
        assertThat(recu).contains("FCFA").doesNotContain("GNF").doesNotContain("guinéens");
        assertThat(recu).contains("30 000 FCFA").doesNotContain("30 000,00");
        assertThat(recu).contains("francs CFA");
        String journal = pdf(mvc.perform(get("/caisse/export/pdf").with(user(SN).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse());
        assertThat(journal).contains("FCFA").doesNotContain("GNF");
        String bilan = pdf(mvc.perform(get("/etats/bilan/export/pdf").with(user(SN).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse());
        assertThat(bilan).contains("FCFA").doesNotContain("GNF");
        String justificatif = pdf(mvc.perform(get("/etats/justificatifs/tresorerie/pdf").with(user(SN).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse());
        assertThat(justificatif).contains("FCFA", "francs CFA").doesNotContain("GNF").doesNotContain("guinéens");
        String bilanComptable = pdf(mvc.perform(get("/comptabilite/etats-financiers/bilan/export/pdf").param("du", "2026-01-01").param("au", "2026-12-31").with(user(SN).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse());
        assertThat(bilanComptable).contains("FCFA").doesNotContain("GNF");
        // Même documents en Guinée : GNF.
        String recuGn = pdf(mvc.perform(get("/etats/bilan/export/pdf").with(user(GN).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse());
        assertThat(recuGn).contains("GNF").doesNotContain("FCFA");
    }

    @Test
    void lesTelephonesPrennentLIndicatifDuPays() throws Exception {
        String dakarNouveau = lire(SN, "/clients/nouveau");
        assertThat(dakarNouveau).contains("+221");
        assertThat(lire(GN, "/clients/nouveau")).contains("+224").doesNotContain("+221");
        assertThat(lire(SN, "/fournisseurs/nouveau")).contains("+221");
        Client c = clients.findAll().stream().filter(x -> x.getBoutique().getId().equals(dakar.getId())).findFirst().orElseThrow();
        c.setTelephone("77 123 45 67"); clients.saveAndFlush(c);
        assertThat(lire(SN, "/clients")).contains("href=\"tel:+221771234567\"");
        // Un numéro déjà saisi avec son indicatif ne change pas.
        c.setTelephone("+224 622 00 00 00"); clients.saveAndFlush(c);
        assertThat(lire(SN, "/clients")).contains("href=\"tel:+224622000000\"").contains("+224 622 00 00 00");
    }

    // ---- Outils ------------------------------------------------------------------------------------------------------

    private String lire(String email, String url) throws Exception { return lire(email, url, "ADMIN"); }

    private String lire(String email, String url, String... role) throws Exception {
        MockHttpServletRequestBuilder requete = get(url).with(user(email).roles(role));
        MockHttpServletResponse reponse = mvc.perform(requete).andReturn().getResponse();
        assertThat(reponse.getStatus()).as(url).isEqualTo(200);
        return reponse.getContentAsString();
    }

    private static String pdf(MockHttpServletResponse reponse) throws Exception {
        try (PDDocument document = Loader.loadPDF(reponse.getContentAsByteArray())) {
            return new PDFTextStripper().getText(document).replace(' ', ' ').replace(' ', ' ').replaceAll("\\s+", " ");
        }
    }

    private static Boutique boutique(Boutique b, String nom) { b.setNom(nom); return b; }

    private Utilisateur utilisateur(String email, Role role, Boutique b) {
        Utilisateur u = new Utilisateur(); u.setBoutique(b); u.setEmail(email); u.setNom(email); u.setRole(role);
        if (email.startsWith("plateforme")) u.setAdministrateurPlateforme(true);
        return utilisateurs.saveAndFlush(u);
    }

    private Client client(Boutique b, String nom) { Client c = new Client(); c.setBoutique(b); c.setNom(nom); return clients.saveAndFlush(c); }

    private Produit produit(Boutique b, String reference) {
        Categorie categorie = new Categorie(); categorie.setBoutique(b); categorie.setNom("Catégorie " + reference); categorie = categories.save(categorie);
        Produit p = new Produit(); p.setBoutique(b); p.setNom("Produit " + reference); p.setReference(reference); p.setCategorie(categorie); p.setQuantite(500L);
        p.setPrixAchat(new BigDecimal("5000.00")); p.setPrixVente(new BigDecimal("10000.00")); p.setPrixDefinis(true);
        return produits.saveAndFlush(p);
    }

    private static void connecter(String email, String role) {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(email, "", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        SecurityContextHolder.setContext(contexte);
    }

    @SuppressWarnings("unused") private static LocalDate aujourdhui() { return LocalDate.now(); }
}
