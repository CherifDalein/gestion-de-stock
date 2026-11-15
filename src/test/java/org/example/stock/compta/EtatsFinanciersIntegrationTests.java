package org.example.stock.compta;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.stock.compta.ComptabiliteService.Statut;
import org.example.stock.compta.EtatsFinanciersService.Etats;
import org.example.stock.enums.NatureProduit;
import org.example.stock.enums.Periodicite;
import org.example.stock.enums.Role;
import org.example.stock.enums.SensEmprunt;
import org.example.stock.enums.SystemeComptable;
import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.enums.TypeOperationTresorerie;
import org.example.stock.enums.TypePartenaireFinancier;
import org.example.stock.form.EmpruntForm;
import org.example.stock.form.OperationFinanciereForm;
import org.example.stock.form.OuvertureCaisseForm;
import org.example.stock.form.PartenaireFinancierForm;
import org.example.stock.form.DepenseTresorerieForm;
import org.example.stock.form.RemboursementForm;
import org.example.stock.form.RetourAchatForm;
import org.example.stock.form.RetourVenteForm;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.example.stock.service.*;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * États financiers (lot 5b) sur un jeu de données FIXE, construit avec les vrais services (ventes, achats, retours, avance de client, avance
 * de fournisseur, emprunt avec intérêts, dépenses, écart de caisse, apport en capital) puis daté ; chaque total est comparé à un calcul fait à
 * la main (voir le calendrier ci-dessous). Les stocks, les tiers et la trésorerie du bilan doivent aussi égaler ceux de l'application.
 *
 * <p>Calendrier (GNF), boutique F. Coût moyen constant de 5 000 et prix de vente de 10 000 pour l'unique produit.
 * 2025 : fonds de départ 700 000 en espèces et 300 000 en banque (02/11), stock de départ 100 unités, vente comptant de 10 unités (10/12).
 * 2026 : achat de 40 unités chez Sidi, 120 000 versés en banque (01/02) ; vente de 20 unités à Aminata, 50 000 versés (15/02) ; vente de 5 unités à
 * Mariama, 80 000 versés donc 30 000 d'avance (20/02) ; achat de 10 unités chez Diallo, 70 000 versés donc 20 000 d'avance (01/03) ; retour de 2 unités
 * par Aminata imputé sur sa dette (10/03) ; retour de 4 unités à Sidi imputé sur la dette (15/03) ; loyer 40 000 et transport 15 000 en espèces,
 * salaires 60 000 en banque (01/04) ; emprunt de 600 000 en banque à 12 % sur 6 mois, première échéance 106 000 dont 6 000 d'intérêts (01/05) ;
 * comptage de caisse en manque de 5 000 (10/06) ; apport en capital de 100 000 en espèces (01/07).</p>
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:etats-financiers;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class EtatsFinanciersIntegrationTests {
    static final LocalDate JOUR = LocalDate.of(2026, 12, 20);
    static final LocalDate DU = LocalDate.of(2026, 1, 1), AU = LocalDate.of(2026, 12, 31);
    static final String GERANT = "etats-gerant@example.test", CAISSIER = "etats-caissier@example.test", GERANT_B = "etats-gerant-b@example.test";
    static final String BASE = "/comptabilite/etats-financiers", PERIODE = "?du=2026-01-01&au=2026-12-31";

    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired CategorieRepository categories;
    @Autowired ProduitRepository produits;
    @Autowired ClientRepository clients;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired VenteRepository ventes;
    @Autowired AchatRepository achats;
    @Autowired RetourRepository retours;
    @Autowired MouvementCaisseRepository caisseRepo;
    @Autowired OperationTresorerieRepository operations;
    @Autowired ReglageComptableBoutiqueRepository reglages;
    @Autowired MouvementStockRepository mouvementsStock;
    @Autowired VenteService venteService;
    @Autowired AchatService achatService;
    @Autowired RetourVenteService retourVenteService;
    @Autowired RetourAchatService retourAchatService;
    @Autowired CaisseService caisse;
    @Autowired EmpruntService empruntService;
    @Autowired PartenairesFinanciersService partenaireService;
    @Autowired OperationsFinancieresService operationService;
    @Autowired ComptageCaisseService comptages;
    @Autowired ComptabiliteService comptabilite;
    @Autowired EtatsFinanciersService financiers;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @MockitoBean(name = "recoveryClock") Clock horloge;

    static boolean construit;
    static Boutique boutiqueF, boutiqueB;
    static Produit produit;
    static long reperePourMouvements, reperePourVentes, reperePourAchats, reperePourRetours, reperePourOperations;
    static int minute;

    @BeforeEach
    void preparer() {
        fixer(JOUR);
        if (!construit) { construire(); construit = true; }
    }

    @AfterEach
    void nettoyer() { SecurityContextHolder.clearContext(); }

    private void fixer(LocalDate jour) {
        Instant instant = jour.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant();
        when(horloge.instant()).thenReturn(instant);
        when(horloge.getZone()).thenReturn(ZoneId.systemDefault());
        when(horloge.withZone(any())).thenReturn(horloge);
    }

    private void construire() {
        boutiqueF = boutique("Boutique états F"); boutiqueB = boutique("Boutique états B");
        utilisateur(GERANT, Role.ADMIN, boutiqueF); utilisateur(CAISSIER, Role.CAISSIER, boutiqueF); utilisateur(GERANT_B, Role.ADMIN, boutiqueB);
        Categorie cat = new Categorie(); cat.setBoutique(boutiqueF); cat.setNom("Divers"); cat = categories.saveAndFlush(cat);
        Fournisseur parDefaut = fournisseur("Fournisseur de référence");
        produit = new Produit(); produit.setBoutique(boutiqueF); produit.setNom("Jus de bissap"); produit.setReference("REF-F"); produit.setCategorie(cat); produit.setFournisseur(parDefaut);
        produit.setNature(NatureProduit.STOCK); produit.setQuantite(0L); produit.setPrixAchat(new BigDecimal("5000")); produit.setPrixVente(new BigDecimal("10000"));
        produit.setPrixDefinis(true); produit.setCoutMoyen(new BigDecimal("5000")); produit = produits.saveAndFlush(produit);
        Client aminata = client("Aminata Camara"), mariama = client("Mariama Sow");
        Fournisseur sidi = fournisseur("Sidi Import"), diallo = fournisseur("Diallo Distribution");

        // --- 2025
        fonds(TypeCompteTresorerie.ESPECES, "700000"); fonds(TypeCompteTresorerie.BANQUE, "300000"); dater("2025-11-02");
        Produit cible = produits.findById(produit.getId()).orElseThrow(); cible.setQuantite(100L); produits.saveAndFlush(cible);
        MouvementStock initial = new MouvementStock();   // le stock de départ est une entrée « stock initial » au coût de 5 000, datée du jour de la saisie
        initial.setBoutique(boutiqueF); initial.setProduit(cible); initial.setActeurId(1L); initial.setActeurNom("Gérant"); initial.setType(org.example.stock.enums.TypeMouvementStock.INITIAL);
        initial.setCreeLe(LocalDate.of(2025, 11, 2).atTime(9, 5)); initial.setQuantiteAvant(0L); initial.setQuantiteApres(100L); initial.setCoutUnitaire(new BigDecimal("5000"));
        initial.setPrixVente(new BigDecimal("10000")); initial.setMotif("Stock de départ"); mouvementsStock.saveAndFlush(initial);
        vente(null, 10, "100000", TypeCompteTresorerie.ESPECES); dater("2025-12-10");
        // --- 2026
        Achat a1 = achat(sidi, 40, "120000", TypeCompteTresorerie.BANQUE); dater("2026-02-01");
        Vente v1 = vente(aminata, 20, "50000", TypeCompteTresorerie.ESPECES); dater("2026-02-15");
        vente(mariama, 5, "80000", TypeCompteTresorerie.ESPECES); dater("2026-02-20");
        achat(diallo, 10, "70000", TypeCompteTresorerie.ESPECES); dater("2026-03-01");
        retourVente(v1, 2); dater("2026-03-10");
        retourAchat(a1, 4); dater("2026-03-15");
        depenser(TypeCompteTresorerie.ESPECES, "40000", "Loyer d'avril"); depenser(TypeCompteTresorerie.ESPECES, "15000", "Transport");
        depenser(TypeCompteTresorerie.BANQUE, "60000", "Salaires"); dater("2026-04-01");
        PartenaireFinancier banque = authentifie(GERANT, "ADMIN", () -> { var f = new PartenaireFinancierForm(); f.setNom("BICIGUI"); f.setType(TypePartenaireFinancier.BANQUE); return partenaireService.creer(f); });
        PartenaireFinancier associe = authentifie(GERANT, "ADMIN", () -> { var f = new PartenaireFinancierForm(); f.setNom("Mamadou Diallo"); f.setType(TypePartenaireFinancier.ASSOCIE); return partenaireService.creer(f); });
        Long emprunt = authentifie(GERANT, "ADMIN", () -> {
            var f = new EmpruntForm();
            f.setSens(SensEmprunt.EMPRUNT); f.setPartenaireId(banque.getId()); f.setCompte("BANQUE"); f.setDateEmprunt(LocalDate.of(2026, 5, 1)); f.setDatePremiereEcheance(LocalDate.of(2026, 6, 1));
            f.setCapital(new BigDecimal("600000")); f.setTauxAnnuel(new BigDecimal("12")); f.setNombreEcheances(6); f.setPeriodicite(Periodicite.MENSUELLE); f.setJetonOperation(UUID.randomUUID().toString());
            return empruntService.creer(f).id();
        });
        authentifie(GERANT, "ADMIN", () -> {
            var s = empruntService.vue(emprunt).situation();
            var f = new RemboursementForm(); f.setMontant(new BigDecimal("106000")); f.setCompte("BANQUE"); f.setJetonOperation(UUID.randomUUID().toString());
            f.setRangAttendu(s.prochaine().rang()); f.setResteAttendu(s.totalRestantDu());
            return empruntService.rembourser(emprunt, f);
        });
        dater("2026-05-01");
        authentifie(GERANT, "ADMIN", () -> {
            ComptageCaisse c = comptages.saisir("ESPECES", new BigDecimal("800000"), null, null, UUID.randomUUID().toString());
            assertThat(c.getSoldeTheorique()).isEqualByComparingTo("805000");
            comptages.valider(c.getId(), "Manque constaté au comptage");
            return null;
        });
        dater("2026-06-10");
        authentifie(GERANT, "ADMIN", () -> {
            var f = new OperationFinanciereForm();
            f.setType(TypeOperationTresorerie.APPORT_CAPITAL); f.setPartenaireId(associe.getId()); f.setCompte("ESPECES"); f.setMontant(new BigDecimal("100000"));
            f.setMotif("Apport en capital"); f.setJetonOperation(UUID.randomUUID().toString());
            return operationService.enregistrer(f);
        });
        dater("2026-07-01");

        // --- Boutique B : ne doit jamais apparaître dans les états de F
        Categorie catB = new Categorie(); catB.setBoutique(boutiqueB); catB.setNom("Catégorie secrète B"); categories.saveAndFlush(catB);
        authentifie(GERANT_B, "ADMIN", () -> { caisse.initialiser(ouverture(TypeCompteTresorerie.ESPECES, "900000")); return null; });
        authentifie(GERANT_B, "ADMIN", () -> { caisse.depenser(depense(TypeCompteTresorerie.ESPECES, "77700", "Loyer secret B")); return null; });
        dater("2026-06-11");
    }

    // ==== Valeurs de référence ========================================================================================

    private Etats etats() {
        authentifier(GERANT);
        return financiers.calculer(boutiqueF.getId(), DU, AU);
    }

    private static void egal(BigDecimal reel, String attendu, String quoi) { assertThat(reel).as(quoi).isEqualByComparingTo(attendu); }

    @Test
    void leCompteDeResultatDonneLesSoldesIntermediairesCalculesALaMain() {
        var cr = etats().n().compteResultat();
        // Ventes 250 000 moins retour 20 000 ; achats 250 000 moins retour 20 000 ; stock 450 000 -> 565 000.
        egal(cr.montant("TA"), "230000", "TA"); egal(cr.montant("RA"), "-230000", "RA"); egal(cr.montant("RB"), "115000", "RB");
        egal(cr.montant("XA"), "115000", "marge commerciale"); egal(cr.montant("XB"), "230000", "chiffre d'affaires");
        egal(cr.montant("RG"), "-15000", "transports"); egal(cr.montant("RH"), "-40000", "services extérieurs"); egal(cr.montant("RJ"), "-5000", "écart de caisse");
        egal(cr.montant("XC"), "55000", "valeur ajoutée");
        egal(cr.montant("RK"), "-60000", "personnel"); egal(cr.montant("XD"), "-5000", "excédent brut d'exploitation"); egal(cr.montant("XE"), "-5000", "résultat d'exploitation");
        egal(cr.montant("RM"), "-6000", "intérêts"); egal(cr.montant("XF"), "-6000", "résultat financier"); egal(cr.montant("XG"), "-11000", "activités ordinaires");
        egal(cr.montant("XH"), "0", "HAO"); egal(cr.montant("XI"), "-11000", "résultat net");
        assertThat(cr.ecart()).isEqualByComparingTo("0");
        assertThat(cr.nonRattaches()).isEmpty();
    }

    @Test
    void lExerciceNMoinsUnEstCalculeQuandIlYADesDonnees() {
        Etats e = etats();
        assertThat(e.n1()).isNotNull();
        var cr = e.n1().compteResultat();
        // 2025 : vente 100 000, stock 500 000 -> 450 000 (variation -50 000) : marge 50 000 ; aucun autre produit ni charge.
        egal(cr.montant("TA"), "100000", "TA N-1"); egal(cr.montant("RB"), "-50000", "RB N-1"); egal(cr.montant("XA"), "50000", "marge N-1"); egal(cr.montant("XI"), "50000", "résultat N-1");
        egal(e.n1().bilan().netActif("BB"), "450000", "stock N-1"); egal(e.n1().bilan().netActif("BS"), "1100000", "trésorerie N-1");
        egal(e.n1().bilan().passif("CH"), "1500000", "report N-1"); egal(e.n1().bilan().passif("CJ"), "50000", "résultat bilan N-1");
        assertThat(e.n1().bilan().ecart()).isEqualByComparingTo("0");
        // Avant toute activité : pas d'exercice N-1.
        authentifier(GERANT);
        assertThat(financiers.calculer(boutiqueF.getId(), LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)).n1()).isNull();
    }

    @Test
    void leBilanEquilibreEtClasseLesComptesADoubleSensDuBonCote() {
        var b = etats().n().bilan();
        egal(b.netActif("BB"), "565000", "stocks"); egal(b.netActif("BI"), "130000", "clients"); egal(b.netActif("BH"), "20000", "fournisseur débiteur à l'actif");
        egal(b.netActif("BS"), "1514000", "banques et caisse"); egal(b.netActif("BZ"), "2229000", "total général de l'actif");
        egal(b.passif("DI"), "30000", "client créditeur au passif"); egal(b.passif("DJ"), "60000", "fournisseurs"); egal(b.passif("DA"), "500000", "emprunt");
        egal(b.passif("CA"), "100000", "capital"); egal(b.passif("CH"), "1550000", "report à nouveau"); egal(b.passif("CJ"), "-11000", "résultat net");
        egal(b.passif("CP"), "1639000", "capitaux propres"); egal(b.passif("DZ"), "2229000", "total général du passif");
        assertThat(b.ecart()).isEqualByComparingTo("0");
        // Aucun solde créditeur à l'actif ni débiteur au passif : chaque poste garde son signe.
        assertThat(b.brut().values()).allSatisfy(v -> assertThat(v.signum()).isGreaterThanOrEqualTo(0));
        assertThat(b.passif().values().stream().filter(v -> v.signum() < 0)).hasSize(1); // seul le résultat (perte) est négatif
        // Le stock, les tiers et la trésorerie du bilan sont ceux de l'application.
        authentifier(GERANT);
        BigDecimal tresorerieAppli = caisse.getSoldesParCompte().stream().map(CaisseService.SoldeCompte::solde).reduce(BigDecimal.ZERO, BigDecimal::add);
        egal(tresorerieAppli, "1514000", "trésorerie de l'application");
        egal(b.tresorerieNette(), "1514000", "trésorerie nette du bilan");
    }

    @Test
    void leTableauDesFluxVarieCommeLaTresorerieReelle() {
        var e = etats().n();
        var f = e.flux();
        egal(f.get("ZA"), "1100000", "trésorerie d'ouverture"); egal(f.get("FA"), "-11000", "CAFG");
        egal(f.get("FC"), "-115000", "variation des stocks"); egal(f.get("FD"), "-150000", "variation des créances"); egal(f.get("FE"), "90000", "variation du passif circulant");
        egal(f.get("ZB"), "-186000", "activités opérationnelles"); egal(f.get("ZC"), "0", "investissement");
        egal(f.get("ZD"), "100000", "capitaux propres"); egal(f.get("ZE"), "500000", "capitaux étrangers"); egal(f.get("ZF"), "600000", "financement");
        egal(f.get("ZG"), "414000", "variation de la trésorerie"); egal(f.get("ZH"), "1514000", "trésorerie finale");
        assertThat(e.ecartFlux()).isEqualByComparingTo("0");
        // La variation du tableau est la variation réelle des comptes de trésorerie, vue par la balance (classe 5 hors 59).
        authentifier(GERANT);
        BigDecimal finBalance = soldeClasse5(comptabilite.balance(comptabilite.calculer(boutiqueF.getId(), DU, AU)));
        BigDecimal avantBalance = soldeClasse5(comptabilite.balance(comptabilite.calculer(boutiqueF.getId(), LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))));
        assertThat(finBalance.subtract(avantBalance)).isEqualByComparingTo(f.get("ZG"));
        egal(e.tresorerieBalance(), "1514000", "trésorerie de la balance");
    }

    private static BigDecimal soldeClasse5(ComptabiliteService.Balance b) {
        return b.lignes().stream().filter(l -> l.numero().startsWith("5") && !l.numero().startsWith("59")).map(l -> l.finDebit().subtract(l.finCredit())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void leSystemeMinimalDeTresorerieEquilibreSonBilanEtRetrouveLeResultat() {
        var s = etats().n().smt();
        egal(s.resultat().get("VENTES"), "130000", "recettes sur ventes"); egal(s.resultat().get("A"), "130000", "A");
        egal(s.resultat().get("ACHATS"), "190000", "dépenses sur achats"); egal(s.resultat().get("LOYERS"), "40000", "loyers"); egal(s.resultat().get("SALAIRES"), "60000", "salaires");
        egal(s.resultat().get("INTERETS"), "6000", "intérêts"); egal(s.resultat().get("AUTRES_DEPENSES"), "20000", "autres dépenses"); egal(s.resultat().get("B"), "316000", "B");
        egal(s.resultat().get("C"), "-186000", "solde C"); egal(s.resultat().get("G"), "-11000", "résultat G");
        egal(s.bilan().get("STOCKS"), "565000", "stocks SMT"); egal(s.bilan().get("CLIENTS"), "150000", "clients et débiteurs divers");
        egal(s.bilan().get("CAISSE"), "900000", "caisse"); egal(s.bilan().get("BANQUE"), "614000", "banque");
        egal(s.bilan().get("TOTAL_ACTIF"), "2229000", "total actif SMT"); egal(s.bilan().get("TOTAL_PASSIF"), "2229000", "total passif SMT");
        assertThat(s.ecart()).isEqualByComparingTo("0");
    }

    @Test
    void lesQuatreControlesDesEtatsSontConformesSurLaPageDesControles() throws Exception {
        List<ComptabiliteService.Controle> controles = EtatsFinanciersService.controles(etats());
        assertThat(controles).extracting(ComptabiliteService.Controle::numero).containsExactly(6, 7, 8, 9);
        assertThat(controles).allSatisfy(c -> assertThat(c.statut()).as(c.libelle() + " : " + c.detail()).isEqualTo(Statut.OK));
        String html = lire(GERANT, "/comptabilite/controles" + PERIODE);
        assertThat(html).contains("Bilan : actif = passif", "Résultat du bilan = résultat du compte de résultat", "Tableau des flux : trésorerie finale = balance", "Système minimal de trésorerie : bilan équilibré");
        assertThat(html).doesNotContain("Écart");
        assertThat(lire(GERANT, "/comptabilite")).contains("Neuf vérifications").contains("/comptabilite/etats-financiers");
    }

    // ==== Écrans, exports, droits ========================================================================================

    private String lire(String compte, String url) throws Exception {
        return normaliser(mvc.perform(get(url).with(user(compte).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void lesPagesMontrentLaMentionLesReferencesOfficiellesEtLesMontants() throws Exception {
        for (String page : List.of("compte-resultat", "bilan", "flux", "smt")) {
            String html = lire(GERANT, BASE + "/" + page + PERIODE);
            assertThat(html).as(page).contains("Comptabilité en cours de validation par un expert-comptable").doesNotContain("Whitelabel").doesNotContain("th:text")
                    .contains("data-etat-export=\"pdf\"").contains("data-etat-export=\"excel\"");
        }
        String cr = lire(GERANT, BASE + "/compte-resultat" + PERIODE);
        assertThat(cr).contains("Ventes de marchandises", "MARGE COMMERCIALE", "VALEUR AJOUTEE", "EXCEDENT BRUT D'EXPLOITATION", "RESULTAT NET", "230 000", "115 000", "-11 000");
        String bilan = lire(GERANT, BASE + "/bilan" + PERIODE);
        assertThat(bilan).contains("TOTAL GENERAL", "2 229 000", "Clients, avances reçues", "Fournisseurs avances versées", "Brut", "Net N-1").doesNotContain("data-etat-ecart");
        String flux = lire(GERANT, BASE + "/flux" + PERIODE);
        assertThat(flux).contains("Capacité d'Autofinancement Globale", "VARIATION DE LA TRÉSORERIE NETTE", "1 514 000");
        assertThat(lire(GERANT, BASE)).contains("Compte de résultat", "Bilan", "Tableau des flux", "Rattachement des comptes").doesNotContain("Whitelabel");
        // Sans période : l'année civile en cours (2026 ici).
        assertThat(lire(GERANT, BASE + "/bilan")).contains("du 01/01/2026 au 31/12/2026");
        // Période invalide : avertissement et année civile ; pour un export, 400.
        assertThat(lire(GERANT, BASE + "/bilan?du=2026-12-01&au=2026-01-01")).contains("La période demandée est invalide");
        mvc.perform(get(BASE + "/bilan/export/pdf?du=2026-12-01&au=2026-01-01").with(user(GERANT).roles("ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/bilan/export/pdf?du=2026-01-01&du=2026-02-01&au=2026-12-31").with(user(GERANT).roles("ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/bilan/export/word" + PERIODE).with(user(GERANT).roles("ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/inconnu" + PERIODE).with(user(GERANT).roles("ADMIN"))).andExpect(status().isNotFound());
    }

    @Test
    void lePdfSOuvreAvecLEnteteDeLaBoutiqueEtLExcelADesNombres() throws Exception {
        MockHttpServletResponse pdf = mvc.perform(get(BASE + "/bilan/export/pdf" + PERIODE).with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(pdf.getContentType()).isEqualTo("application/pdf");
        try (PDDocument document = Loader.loadPDF(pdf.getContentAsByteArray())) {
            assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(1);
            assertThat(document.getPage(0).getMediaBox().getHeight()).isGreaterThan(document.getPage(0).getMediaBox().getWidth());
            String texte = normaliser(new PDFTextStripper().getText(document));
            assertThat(texte).contains("Boutique états F", "Bilan", "Comptabilité en cours de validation", "TOTAL GENERAL", "2 229 000", "Page 1");
        }
        for (String page : List.of("compte-resultat", "flux", "smt")) {
            byte[] octets = mvc.perform(get(BASE + "/" + page + "/export/pdf" + PERIODE).with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            try (PDDocument document = Loader.loadPDF(octets)) { assertThat(normaliser(new PDFTextStripper().getText(document))).contains("Boutique états F", "en cours de validation"); }
        }
        byte[] excel = mvc.perform(get(BASE + "/compte-resultat/export/excel" + PERIODE).with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook classeur = new XSSFWorkbook(new ByteArrayInputStream(excel))) {
            Sheet feuille = classeur.getSheetAt(0);
            assertThat(nombre(feuille, "XA", 4)).isEqualByComparingTo("115000");
            assertThat(nombre(feuille, "XI", 4)).isEqualByComparingTo("-11000");
        }
        byte[] bilanExcel = mvc.perform(get(BASE + "/bilan/export/excel" + PERIODE).with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook classeur = new XSSFWorkbook(new ByteArrayInputStream(bilanExcel))) {
            assertThat(classeur.getNumberOfSheets()).isEqualTo(2);
            assertThat(nombre(classeur.getSheet("Actif"), "BZ", 5)).isEqualByComparingTo("2229000");
            assertThat(nombre(classeur.getSheet("Passif"), "DZ", 3)).isEqualByComparingTo("2229000");
        }
    }

    @Test
    void leCaissierNAccedeANiAuxEtatsNiAuxExportsNiAuReglage() throws Exception {
        for (String url : List.of(BASE, BASE + "/bilan" + PERIODE, BASE + "/compte-resultat" + PERIODE, BASE + "/flux" + PERIODE, BASE + "/smt" + PERIODE, BASE + "/rattachements",
                BASE + "/bilan/export/pdf" + PERIODE, BASE + "/bilan/export/excel" + PERIODE, BASE + "/rattachements/export/pdf"))
            mvc.perform(get(url).with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/systeme").param("systeme", "MINIMAL").with(user(CAISSIER).roles("CAISSIER")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/bilan" + PERIODE)).andExpect(status().is3xxRedirection());
    }

    @Test
    void laBoutiqueBNeVoitRienDeF() throws Exception {
        authentifier(GERANT_B);
        Etats b = financiers.calculer(boutiqueB.getId(), DU, AU);
        egal(b.n().compteResultat().montant("TA"), "0", "ventes de B"); egal(b.n().compteResultat().montant("RH"), "-77700", "loyer de B");
        egal(b.n().bilan().netActif("BS"), "822300", "trésorerie de B"); assertThat(b.n().bilan().ecart()).isEqualByComparingTo("0");
        String htmlB = lire(GERANT_B, BASE + "/bilan" + PERIODE);
        assertThat(htmlB).contains("822 300").doesNotContain("2 229 000").doesNotContain("Boutique états F");
        assertThat(lire(GERANT, BASE + "/compte-resultat" + PERIODE)).doesNotContain("77 700").doesNotContain("Boutique états B");
        // Un identifiant de boutique passé dans l'adresse est ignoré : la boutique vient du compte.
        assertThat(lire(GERANT, BASE + "/bilan" + PERIODE + "&boutiqueId=" + boutiqueB.getId())).contains("2 229 000").doesNotContain("822 300");
    }

    @Test
    void leReglageDuSystemeEstParBoutiqueEtProtegeContreLesChampsInconnus() throws Exception {
        assertThat(lire(GERANT, BASE)).contains("href=\"" + BASE + "/compte-resultat\"").doesNotContain("href=\"" + BASE + "/smt\"");
        mvc.perform(post(BASE + "/systeme").param("systeme", "MINIMAL").with(user(GERANT).roles("ADMIN")).with(csrf())).andExpect(status().is3xxRedirection());
        try {
            assertThat(lire(GERANT, BASE)).contains("href=\"" + BASE + "/smt\"").doesNotContain("href=\"" + BASE + "/bilan\"");
            assertThat(lire(GERANT, BASE + "/bilan" + PERIODE)).contains("système minimal de trésorerie : cet état est affiché à titre indicatif");
            assertThat(lire(GERANT_B, BASE)).contains("href=\"" + BASE + "/bilan\"");
            assertThat(reglages.findByBoutiqueId(boutiqueF.getId()).orElseThrow().getSysteme()).isEqualTo(SystemeComptable.MINIMAL);
            assertThat(reglages.findByBoutiqueId(boutiqueB.getId())).isEmpty();
            mvc.perform(post(BASE + "/systeme").param("systeme", "AUTRE").with(user(GERANT).roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest());
            mvc.perform(post(BASE + "/systeme").param("systeme", "NORMAL").param("boutiqueId", "1").with(user(GERANT).roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest());
            mvc.perform(post(BASE + "/systeme").param("systeme", "NORMAL").param("systeme", "MINIMAL").with(user(GERANT).roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest());
            mvc.perform(post(BASE + "/systeme").param("systeme", "NORMAL").with(user(GERANT).roles("ADMIN"))).andExpect(status().isForbidden());
        } finally {
            mvc.perform(post(BASE + "/systeme").param("systeme", "NORMAL").with(user(GERANT).roles("ADMIN")).with(csrf())).andExpect(status().is3xxRedirection());
        }
        assertThat(reglages.findByBoutiqueId(boutiqueF.getId()).orElseThrow().getSysteme()).isEqualTo(SystemeComptable.NORMAL);
    }

    @Test
    void laTableDeRattachementEstEnLectureSeule() throws Exception {
        String html = lire(GERANT, BASE + "/rattachements");
        assertThat(html).contains("Bilan : comptes rattachés à chaque poste", "Compte de résultat : comptes rattachés à chaque poste", "Officiel", "Choix :", "solde créditeur").doesNotContain("<form method=\"post\"");
        mvc.perform(post(BASE + "/rattachements").with(user(GERANT).roles("ADMIN")).with(csrf())).andExpect(status().isMethodNotAllowed());
        mvc.perform(get(BASE + "/rattachements/export/excel").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk());
    }


    // ==== Outils ============================================================================================================

    static String normaliser(String texte) { return org.springframework.web.util.HtmlUtils.htmlUnescape(texte).replace(' ', ' ').replace(' ', ' '); }

    static BigDecimal nombre(Sheet feuille, String reference, int colonne) {
        assertThat(feuille).isNotNull();
        for (Row ligne : feuille) {
            Cell premiere = ligne.getCell(0);
            if (premiere == null || premiere.getCellType() != CellType.STRING || !premiere.getStringCellValue().equals(reference)) continue;
            Cell c = ligne.getCell(colonne);
            assertThat(c).as(reference).isNotNull();
            assertThat(c.getCellType()).as("cellule numérique de " + reference).isEqualTo(CellType.NUMERIC);
            return BigDecimal.valueOf(c.getNumericCellValue());
        }
        throw new AssertionError("Poste introuvable dans la feuille " + feuille.getSheetName() + " : " + reference);
    }

    private void authentifier(String email) {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(email, "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(contexte);
    }

    private <T> T authentifie(String email, String role, Callable<T> action) {
        var precedent = SecurityContextHolder.getContext(); var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(email, "", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        SecurityContextHolder.setContext(contexte);
        try { return action.call(); }
        catch (RuntimeException erreur) { throw erreur; }
        catch (Exception erreur) { throw new IllegalStateException(erreur); }
        finally { SecurityContextHolder.setContext(precedent); }
    }

    private Boutique boutique(String nom) { Boutique b = new Boutique(); b.setNom(nom); return boutiques.saveAndFlush(b); }

    private void utilisateur(String email, Role role, Boutique b) {
        Utilisateur u = new Utilisateur(); u.setBoutique(b); u.setEmail(email); u.setNom(email); u.setRole(role); utilisateurs.saveAndFlush(u);
    }

    private Client client(String nom) { Client c = new Client(); c.setBoutique(boutiqueF); c.setNom(nom); c.setSoldeInitial(BigDecimal.ZERO); return clients.saveAndFlush(c); }

    private Fournisseur fournisseur(String nom) { Fournisseur f = new Fournisseur(); f.setBoutique(boutiqueF); f.setNom(nom); f.setSoldeInitial(BigDecimal.ZERO); return fournisseurs.saveAndFlush(f); }

    private OuvertureCaisseForm ouverture(TypeCompteTresorerie compte, String montant) {
        OuvertureCaisseForm f = new OuvertureCaisseForm(); f.setComptePaiement(compte.name()); f.setMontant(new BigDecimal(montant)); return f;
    }

    private void fonds(TypeCompteTresorerie compte, String montant) { authentifie(GERANT, "ADMIN", () -> { caisse.initialiser(ouverture(compte, montant)); return null; }); }

    private Vente vente(Client acheteur, int quantite, String verse, TypeCompteTresorerie compte) {
        return authentifie(GERANT, "ADMIN", () -> {
            Vente vente = new Vente(); vente.setBoutique(boutiqueF);
            if (acheteur != null) { Client reference = new Client(); reference.setId(acheteur.getId()); vente.setClient(reference); }
            vente.setMontantVerse(new BigDecimal(verse)); vente.setComptePaiement(compte.name());
            Produit reference = new Produit(); reference.setId(produit.getId());
            DetailVente ligne = new DetailVente(); ligne.setProduit(reference); ligne.setQuantite(quantite); vente.getLignes().add(ligne);
            return venteService.effectuerVente(vente);
        });
    }

    private Achat achat(Fournisseur vendeur, int quantite, String verse, TypeCompteTresorerie compte) {
        return authentifie(GERANT, "ADMIN", () -> {
            Achat achat = new Achat(); achat.setBoutique(boutiqueF);
            Fournisseur ref = new Fournisseur(); ref.setId(vendeur.getId()); achat.setFournisseur(ref);
            achat.setMontantVerse(new BigDecimal(verse)); achat.setComptePaiement(compte.name());
            Produit r = new Produit(); r.setId(produit.getId());
            DetailAchat ligne = new DetailAchat(); ligne.setProduit(r); ligne.setQuantite(quantite);
            ligne.setPrixAchatUnitaire(new BigDecimal("5000")); ligne.setPrixVenteUnitaire(new BigDecimal("10000")); achat.getLignes().add(ligne);
            return achatService.enregistrerAchat(achat);
        });
    }

    private void retourVente(Vente v, int quantite) {
        authentifie(GERANT, "ADMIN", () -> {
            RetourVenteForm f = new RetourVenteForm();
            RetourVenteForm.Ligne l = new RetourVenteForm.Ligne(); l.setDetailVenteId(v.getLignes().get(0).getId()); l.setQuantite(quantite);
            f.setLignes(new ArrayList<>(List.of(l))); f.setRetoursConnus(retours.countByVenteId(v.getId()));
            return retourVenteService.enregistrer(v.getId(), f);
        });
    }

    private void retourAchat(Achat a, int quantite) {
        authentifie(GERANT, "ADMIN", () -> {
            RetourAchatForm f = new RetourAchatForm();
            RetourAchatForm.Ligne l = new RetourAchatForm.Ligne(); l.setDetailAchatId(a.getLignes().get(0).getId()); l.setQuantite(quantite);
            f.setLignes(new ArrayList<>(List.of(l))); f.setRetoursConnus(retours.countByAchatId(a.getId()));
            return retourAchatService.enregistrer(a.getId(), f);
        });
    }

    private DepenseTresorerieForm depense(TypeCompteTresorerie compte, String montant, String motif) {
        DepenseTresorerieForm f = new DepenseTresorerieForm(); f.setCompteSource(compte.name()); f.setMontant(new BigDecimal(montant));
        f.setMotif(motif); f.setJetonOperation(UUID.randomUUID().toString()); return f;
    }

    private void depenser(TypeCompteTresorerie compte, String montant, String motif) { authentifie(GERANT, "ADMIN", () -> { caisse.depenser(depense(compte, montant, motif)); return null; }); }

    /** Donne le jour indiqué (à 9 h, une minute de plus à chaque appel) à tout ce qui a été créé depuis le dernier appel. */
    private void dater(String jour) {
        LocalDate date = LocalDate.parse(jour);
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            for (MouvementCaisse m : caisseRepo.findAll()) if (m.getId() > reperePourMouvements) { m.setDateMouvement(date.atTime(9, 0).plusMinutes(minute++)); reperePourMouvements = Math.max(reperePourMouvements, m.getId()); }
            for (Vente v : ventes.findAll()) if (v.getId() > reperePourVentes) { v.setDateVente(date.atTime(9, 0).plusMinutes(minute++)); reperePourVentes = Math.max(reperePourVentes, v.getId()); }
            for (Achat a : achats.findAll()) if (a.getId() > reperePourAchats) { a.setDateAchat(date.atTime(9, 0).plusMinutes(minute++)); reperePourAchats = Math.max(reperePourAchats, a.getId()); }
            for (Retour r : retours.findAll()) if (r.getId() > reperePourRetours) { r.setDateRetour(date.atTime(9, 0).plusMinutes(minute++)); reperePourRetours = Math.max(reperePourRetours, r.getId()); }
            for (OperationTresorerie o : operations.findAll()) if (o.getId() > reperePourOperations) { o.setDateOperation(date.atTime(9, 0).plusMinutes(minute++)); reperePourOperations = Math.max(reperePourOperations, o.getId()); }
        });
    }
}
