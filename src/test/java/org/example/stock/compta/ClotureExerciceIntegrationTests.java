package org.example.stock.compta;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.stock.compta.EtatsFinanciersService.Etats;
import org.example.stock.enums.*;
import org.example.stock.form.*;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.example.stock.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Clôture d'exercice (lot 5c) sur le jeu de données de l'étape 5b (voir {@link EtatsFinanciersIntegrationTests} pour le calendrier) : instantané, report à
 * nouveau, résultats identiques avant et après la clôture, affectation, annulation, pré-clôture, droits, isolation des boutiques, puis, en dernier, le verrou
 * de tous les parcours datés (l'exercice qui contient aujourd'hui est alors clôturé pour de bon dans cette base d'essai).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:cloture-exercice;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClotureExerciceIntegrationTests {
    static final LocalDate JOUR = LocalDate.of(2026, 12, 20);
    static final LocalDate DU = LocalDate.of(2026, 1, 1), AU = LocalDate.of(2026, 12, 31), DEBUT_2025 = LocalDate.of(2025, 1, 1), DEBUT_2026 = DU;
    static final String GERANT = "etats-gerant@example.test", CAISSIER = "etats-caissier@example.test", GERANT_B = "etats-gerant-b@example.test";
    static final String EXERCICES = "/comptabilite/exercices", ETATS = "/comptabilite/etats-financiers";

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
    @Autowired MouvementStockRepository mouvementsStock;
    @Autowired ComptageCaisseRepository comptagesRepo;
    @Autowired CompteTresorerieBoutiqueRepository comptesRepo;
    @Autowired ExerciceComptableRepository exerciceRepo;
    @Autowired SoldeOuvertureRepository soldesRepo;
    @Autowired InstantaneExerciceRepository instantaneRepo;
    @Autowired InstantaneDocumentRepository documentRepo;
    @Autowired AffectationResultatRepository affectationRepo;
    @Autowired ReglageComptableBoutiqueRepository reglages;
    @Autowired VenteService venteService;
    @Autowired AchatService achatService;
    @Autowired RetourVenteService retourVenteService;
    @Autowired RetourAchatService retourAchatService;
    @Autowired CaisseService caisse;
    @Autowired EmpruntService empruntService;
    @Autowired PartenairesFinanciersService partenaireService;
    @Autowired OperationsFinancieresService operationService;
    @Autowired ComptageCaisseService comptages;
    @Autowired ReleveBancaireService releveService;
    @Autowired AjustementSoldeDepartService ajustements;
    @Autowired ProduitService produitService;
    @Autowired ClientService clientService;
    @Autowired ComptabiliteService comptabilite;
    @Autowired EtatsFinanciersService financiers;
    @Autowired ExercicesService exercices;
    @Autowired ClotureService cloture;
    @Autowired InstantanesService instantanes;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @MockitoBean(name = "recoveryClock") Clock horloge;

    static boolean construit;
    static Boutique boutiqueF, boutiqueB;
    static Produit produit;
    static Vente venteAminata;
    static Achat achatSidi;
    static Client clientAminata;
    static Long associeId, banqueId;
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
        Vente v1 = vente(aminata, 20, "50000", TypeCompteTresorerie.ESPECES); dater("2026-02-15"); venteAminata = v1; clientAminata = aminata; achatSidi = a1;
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
        associeId = associe.getId(); banqueId = banque.getId();
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

    // ==== Outils propres à la clôture ====================================================================================

    private Etats etats() { authentifier(GERANT); return financiers.calculer(boutiqueF.getId(), DU, AU); }

    private ExerciceComptable cloturer(LocalDate debut) {
        LocalDate fin = exercices.exercicePour(boutiqueF.getId(), debut).fin();
        fixer(fin.plusDays(10));
        try { return authentifie(GERANT, "ADMIN", () -> cloture.cloturer(debut, null)); } finally { fixer(JOUR); }
    }

    private void annulerClotures() {
        fixer(JOUR);
        var liste = authentifie(GERANT, "ADMIN", () -> exercices.liste(boutiqueF.getId()));
        for (var e : liste.reversed()) {
            if (!e.estCloture()) continue;
            authentifie(GERANT, "ADMIN", () -> {
                if (affectationRepo.findByExerciceId(e.ligne().getId()).isPresent()) cloture.supprimerAffectation(e.debut());
                cloture.annulerCloture(e.debut(), "Fin de l'essai");
                return null;
            });
        }
    }

    /** Une empreinte lisible des montants des états de 2026 : résultat, bilan, flux et totaux de la balance. */
    private List<String> signature() {
        Etats e = etats();
        var cr = e.n().compteResultat(); var b = e.n().bilan(); var f = e.n().flux();
        List<String> s = new ArrayList<>();
        for (String c : List.of("TA", "RA", "RB", "XA", "XB", "RG", "RH", "RJ", "XC", "RK", "XD", "XE", "RM", "XF", "XG", "XH", "XI")) s.add("CR " + c + "=" + cr.montant(c).setScale(2));
        for (String c : List.of("BB", "BH", "BI", "BS", "BZ")) s.add("A " + c + "=" + b.netActif(c).setScale(2));
        for (String c : List.of("CA", "CH", "CJ", "CP", "DA", "DI", "DJ", "DZ")) s.add("P " + c + "=" + b.passif(c).setScale(2));
        for (String c : List.of("ZA", "FA", "FC", "FD", "FE", "ZB", "ZC", "ZD", "ZE", "ZF", "ZG", "ZH")) s.add("F " + c + "=" + f.get(c).setScale(2));
        s.add("ecart bilan=" + b.ecart().setScale(2)); s.add("ecart flux=" + e.n().ecartFlux().setScale(2));
        s.add("balance classe 5=" + comptabilite.balance(comptabilite.calculer(boutiqueF.getId(), DU, AU)).lignes().stream().filter(l -> l.numero().startsWith("5"))
                .map(l -> l.numero() + ":" + l.finDebit().subtract(l.finCredit()).setScale(2)).toList());
        return s;
    }

    private String page(String compte, String url) throws Exception {
        return EtatsFinanciersIntegrationTests.normaliser(mvc.perform(get(url).with(user(compte).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static String texte(byte[] pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf)) { return EtatsFinanciersIntegrationTests.normaliser(new PDFTextStripper().getText(d)).replace("-\n", "-").replaceAll("\\s+", " "); }
    }

    private ClotureService.Verification verification(ClotureService.PreCloture pre, String code) {
        return pre.verifications().stream().filter(v -> v.code().equals(code)).findFirst().orElseThrow(() -> new AssertionError("Vérification absente : " + code));
    }

    private ClotureService.PreCloture pre(LocalDate debut) { return authentifie(GERANT, "ADMIN", () -> cloture.preCloture(debut)); }

    private static String refus(String libelle) { return "L'exercice " + libelle + " est clôturé : cette opération ne peut plus être enregistrée à cette date."; }

    // ==== Clôture, instantané, report à nouveau ====================================================================================

    @Test @Order(1)
    void laClotureFigeLesEtatsReporteLesSoldesEtMetLeResultatEn131() throws Exception {
        try {
            ExerciceComptable ex = cloturer(DEBUT_2025);
            assertThat(ex.estCloture()).isTrue();
            assertThat(ex.getResultat()).isEqualByComparingTo("50000");
            assertThat(ex.getCloturePar()).isEqualTo(GERANT);
            // Soldes d'ouverture : équilibrés, le résultat net (bénéfice) est au crédit du 131, l'actif repris est celui du bilan figé.
            List<SoldeOuverture> soldes = soldesRepo.findByExerciceIdOrderByCompteAscTiersTypeAscTiersIdAsc(ex.getId());
            assertThat(soldes.stream().map(SoldeOuverture::getSolde).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0");
            assertThat(soldes).filteredOn(s -> s.getCompte().equals("131")).singleElement().satisfies(s -> assertThat(s.getSolde()).isEqualByComparingTo("-50000"));
            assertThat(soldes).noneMatch(s -> s.getCompte().startsWith("6") || s.getCompte().startsWith("7"));
            BigDecimal debit = soldes.stream().map(SoldeOuverture::getSolde).filter(v -> v.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
            InstantaneExercice i = instantaneRepo.findByExerciceIdAndStatut(ex.getId(), StatutInstantane.ACTIF).orElseThrow();
            ContenuInstantane contenu = ContenuInstantane.depuis(i.getContenu());
            assertThat(debit).isEqualByComparingTo(contenu.exercice().bilan().netActif("BZ")).isEqualByComparingTo("1550000");
            assertThat(contenu.exercice().compteResultat().montant("XI")).isEqualByComparingTo("50000");
            assertThat(i.getResultat()).isEqualByComparingTo("50000");
            // Empreinte SHA-256 du contenu et des documents ; quatre documents, avec PDF et Excel.
            List<InstantaneDocument> docs = documentRepo.findByInstantaneIdOrderById(i.getId());
            assertThat(docs).extracting(InstantaneDocument::getCode).containsExactlyInAnyOrder("balance", "compte-resultat", "bilan", "flux");
            assertThat(i.getEmpreinte()).hasSize(64).matches("[0-9a-f]{64}").isEqualTo(InstantanesService.empreinte(i.getContenu(), docs));
            assertThat(instantanes.empreinteConforme(i)).isTrue();
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(i.getContenu()); for (InstantaneDocument d : docs) { sha.update(d.getPdf()); sha.update(d.getExcel()); }
            assertThat(i.getEmpreinte()).isNotEqualTo(HexFormat.of().formatHex(new byte[32]));
            for (InstantaneDocument d : docs) {
                String t = texte(d.getPdf());
                assertThat(t).contains("Boutique états F", "Exercice clôturé le 10/01/2026 par " + GERANT).as(d.getCode()).doesNotContain("Exercice clôturé le 10/01/2026 par " + GERANT + "Exercice");
                assertThat(t).contains("en cours de validation par un expert-comptable");   // réglage de validation non rempli : la mention reste
                try (XSSFWorkbook classeur = new XSSFWorkbook(new ByteArrayInputStream(d.getExcel()))) { assertThat(classeur.getNumberOfSheets()).isGreaterThanOrEqualTo(1); }
            }
            assertThat(texte(docs.stream().filter(d -> d.getCode().equals("bilan")).findFirst().orElseThrow().getPdf())).contains("1 550 000");
            // L'état de 2025 est celui de l'instantané ; l'export PDF est le document figé, octet pour octet.
            Etats figes = authentifie(GERANT, "ADMIN", () -> financiers.calculer(boutiqueF.getId(), DEBUT_2025, LocalDate.of(2025, 12, 31)));
            assertThat(figes.fige()).isNotNull();
            assertThat(figes.n().bilan().netActif("BZ")).isEqualByComparingTo("1550000");
            byte[] pdf = mvc.perform(get(ETATS + "/bilan/export/pdf?exercice=2025-01-01").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertThat(pdf).isEqualTo(docs.stream().filter(d -> d.getCode().equals("bilan")).findFirst().orElseThrow().getPdf());
            String html = page(GERANT, EXERCICES);
            assertThat(html).contains("2025", "Clôturé", "50 000", "Ouvert", i.getEmpreinte());
            assertThat(page(GERANT, ETATS + "/bilan?exercice=2025-01-01")).contains("Exercice clôturé", "instantané figé le 10/01/2026", "Exercice clôturé le 10/01/2026 par " + GERANT);
        } finally { annulerClotures(); }
    }

    @Test @Order(2)
    void lesEtatsDe2026SontIdentiquesAvantEtApresLaClotureDe2025EtPartentDesSoldesReportes() {
        List<String> avant = signature();
        assertThat(avant).contains("A BZ=2229000.00", "P CH=1550000.00", "P CJ=-11000.00", "F ZA=1100000.00", "ecart bilan=0.00");
        try {
            ExerciceComptable ex = cloturer(DEBUT_2025);
            List<String> apres = signature();
            assertThat(apres).isEqualTo(avant);
            // Le calcul part des soldes stockés : la trésorerie d'ouverture est celle du report, et le bilan reste équilibré.
            assertThat(soldesRepo.countByExerciceId(ex.getId())).isPositive();
            Etats e = etats();
            assertThat(e.n().flux().get("ZA")).isEqualByComparingTo("1100000");
            assertThat(e.n().bilan().ecart()).isEqualByComparingTo("0");
            assertThat(e.fige()).isNull();
            assertThat(comptabilite.balance(comptabilite.calculer(boutiqueF.getId(), DU, AU)).lignes().stream()
                    .map(l -> l.debit().subtract(l.credit())).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0");
            // Une ré-ouverture rend les mêmes montants (rien n'a été écrit dans les opérations).
            annulerClotures();
            assertThat(signature()).isEqualTo(avant);
        } finally { annulerClotures(); }
    }

    // ==== Affectation du résultat ====================================================================================================

    @Test @Order(3)
    void l_affectationDuResultatEstUneODDeLExerciceSuivantQuiSoldeLe13() throws Exception {
        List<String> avant = signature();
        try {
            cloturer(DEBUT_2025);
            // Refus : exercice non clôturé, destination inconnue, date hors de l'exercice suivant.
            assertThatThrownBy(() -> authentifie(GERANT, "ADMIN", () -> cloture.affecter(DEBUT_2026, "12", null))).hasMessageContaining("n'est pas clôturé");
            assertThatThrownBy(() -> authentifie(GERANT, "ADMIN", () -> cloture.affecter(DEBUT_2025, "99", null))).hasMessageContaining("Choisissez où affecter");
            assertThatThrownBy(() -> authentifie(GERANT, "ADMIN", () -> cloture.affecter(DEBUT_2025, "12", LocalDate.of(2025, 12, 31)))).hasMessageContaining("dans l'exercice 2026");
            AffectationResultat a = authentifie(GERANT, "ADMIN", () -> cloture.affecter(DEBUT_2025, "11", LocalDate.of(2026, 1, 2)));
            assertThat(a.getCompteDestination()).isEqualTo("118");
            assertThat(a.getMontant()).isEqualByComparingTo("50000");
            assertThat(a.getLibelle()).contains("Affectation du résultat de l'exercice 2025");
            assertThat(pre(DEBUT_2026).verifications()).anySatisfy(v -> { if (v.code().equals("affectation")) assertThat(v.ok()).isTrue(); });
            // Une seule affectation par exercice.
            assertThatThrownBy(() -> authentifie(GERANT, "ADMIN", () -> cloture.affecter(DEBUT_2025, "12", null))).hasMessageContaining("déjà affecté");
            // Les états de 2026 restent équilibrés et le résultat de l'exercice n'a pas bougé : le 13 est soldé vers les réserves (capitaux propres inchangés).
            Etats e = etats();
            assertThat(e.n().bilan().ecart()).isEqualByComparingTo("0");
            assertThat(e.n().compteResultat().montant("XI")).isEqualByComparingTo("-11000");
            assertThat(e.n().bilan().passif("CP")).isEqualByComparingTo("1639000");
            assertThat(e.n().bilan().passif("CG")).isEqualByComparingTo("50000");
            assertThat(e.n().bilan().passif("CH")).isEqualByComparingTo("1500000");
            assertThat(page(GERANT, EXERCICES + "/2025-01-01")).contains("Affectation du résultat", "réserves libres", "Supprimer l'affectation").doesNotContain("data-affecter");
            // L'annulation de la clôture est refusée tant que le résultat est affecté.
            assertThatThrownBy(() -> authentifie(GERANT, "ADMIN", () -> { cloture.annulerCloture(DEBUT_2025, "Erreur de saisie"); return null; })).hasMessageContaining("déjà affecté");
            authentifie(GERANT, "ADMIN", () -> { cloture.supprimerAffectation(DEBUT_2025); return null; });
            assertThat(signature()).isEqualTo(avant);
            // Dividendes : le compte 465 reçoit le bénéfice (dette envers les associés) sans fausser le tableau des flux.
            authentifie(GERANT, "ADMIN", () -> cloture.affecter(DEBUT_2025, "465", LocalDate.of(2026, 1, 1)));
            Etats d = etats();
            assertThat(d.n().bilan().ecart()).isEqualByComparingTo("0");
            assertThat(d.n().ecartFlux()).isEqualByComparingTo("0");
            assertThat(d.n().bilan().passif("CH")).isEqualByComparingTo("1500000");
        } finally { annulerClotures(); }
    }

    // ==== Annulation de la clôture ===================================================================================================

    @Test @Order(4)
    void l_annulationRouvreLExerciceEtGardeLInstantaneMarqueAnnule() throws Exception {
        try {
            long dejaConserves = instantaneRepo.findByBoutiqueIdOrderByDateDebutAscVersionAsc(boutiqueF.getId()).size();
            ExerciceComptable ex = cloturer(DEBUT_2025);
            Long exerciceId = ex.getId();
            assertThatThrownBy(() -> authentifie(GERANT, "ADMIN", () -> { cloture.annulerCloture(DEBUT_2025, "  "); return null; })).hasMessageContaining("motif de l'annulation est obligatoire");
            fixer(LocalDate.of(2027, 1, 10));
            authentifie(GERANT, "ADMIN", () -> cloture.cloturer(DEBUT_2026, "Clôture de contrôle"));
            fixer(JOUR);
            // Seul le dernier exercice clôturé peut être rouvert.
            assertThatThrownBy(() -> authentifie(GERANT, "ADMIN", () -> { cloture.annulerCloture(DEBUT_2025, "Mauvaise date"); return null; })).hasMessageContaining("Le dernier exercice clôturé est 2026");
            authentifie(GERANT, "ADMIN", () -> { cloture.annulerCloture(DEBUT_2026, "Clôture trop tôt"); return null; });
            authentifie(GERANT, "ADMIN", () -> { cloture.annulerCloture(DEBUT_2025, "Facture oubliée"); return null; });
            ExerciceComptable rouvert = exerciceRepo.findById(exerciceId).orElseThrow();
            assertThat(rouvert.estCloture()).isFalse();
            assertThat(soldesRepo.countByExerciceId(exerciceId)).isZero();
            assertThat(exerciceRepo.derniereDateCloturee(boutiqueF.getId())).isNull();
            List<InstantaneExercice> tous = instantaneRepo.findByBoutiqueIdOrderByDateDebutAscVersionAsc(boutiqueF.getId());
            InstantaneExercice annule = tous.stream().filter(i -> i.getExercice().getId().equals(exerciceId) && i.getMotifAnnulation() != null && i.getMotifAnnulation().equals("Facture oubliée")).findFirst().orElseThrow();
            assertThat(annule.getStatut()).isEqualTo(StatutInstantane.ANNULE);
            assertThat(annule.getMotifAnnulation()).isEqualTo("Facture oubliée");
            assertThat(annule.getAnnulePar()).isEqualTo(GERANT);
            assertThat(annule.getAnnuleLe()).isNotNull();
            assertThat(instantanes.empreinteConforme(annule)).isTrue();   // conservé tel quel
            // Les états de 2025 sont de nouveau calculés ; l'instantané annulé reste visible dans l'historique.
            assertThat(authentifie(GERANT, "ADMIN", () -> financiers.calculer(boutiqueF.getId(), DEBUT_2025, LocalDate.of(2025, 12, 31))).fige()).isNull();
            assertThat(page(GERANT, EXERCICES)).contains("Annulé", "Facture oubliée");
            // Une seconde clôture crée la version 2.
            cloturer(DEBUT_2025);
            assertThat(instantaneRepo.countByExerciceId(exerciceId)).isEqualTo(dejaConserves + 2);
            assertThat(instantaneRepo.findByExerciceIdAndStatut(exerciceId, StatutInstantane.ACTIF)).get().extracting(InstantaneExercice::getVersion).isEqualTo((int) dejaConserves + 2);
        } finally { annulerClotures(); }
    }

    // ==== Pré-clôture ================================================================================================================

    @Test @Order(5)
    void laPreClotureSepareLesPointsBloquantsDesRecommandations() throws Exception {
        // 2026 n'est pas terminé et 2025 n'est pas clôturé : deux points bloquants, chacun avec son lien.
        var p = pre(DEBUT_2026);
        assertThat(verification(p, "termine").bloque()).isTrue();
        assertThat(verification(p, "precedent").bloque()).isTrue();
        assertThat(verification(p, "precedent").lien()).isEqualTo("/comptabilite/exercices");
        assertThat(p.peutCloturer()).isFalse();
        assertThatThrownBy(() -> authentifie(GERANT, "ADMIN", () -> cloture.cloturer(DEBUT_2026, null))).hasMessageContaining("impossible");
        assertThat(exerciceRepo.derniereDateCloturee(boutiqueF.getId())).isNull();
        String html = page(GERANT, EXERCICES + "/2026-01-01");
        assertThat(html).contains("Avant de clôturer", "Bloquant", "Recommandé", "Corriger", "data-cloture-bloquee").contains("disabled");
        // Recommandations seules : l'exercice 2025 se clôture même sans validation de l'expert-comptable ni inventaire récent.
        var p25 = pre(DEBUT_2025);
        assertThat(p25.peutCloturer()).as(p25.verifications().toString()).isTrue();
        assertThat(verification(p25, "validation").niveau()).isEqualTo(ClotureService.Niveau.RECOMMANDE);
        assertThat(verification(p25, "validation").ok()).isFalse();
        assertThat(verification(p25, "inventaire").niveau()).isEqualTo(ClotureService.Niveau.RECOMMANDE);
        assertThat(verification(p25, "controles-etats").niveau()).isEqualTo(ClotureService.Niveau.BLOQUANT);
        assertThat(verification(p25, "controles-etats").ok()).isTrue();
        // Un comptage de caisse en attente bloque ; un relevé bancaire en brouillon bloque ; un produit en stock sans coût bloque.
        Long comptageId = authentifie(GERANT, "ADMIN", () -> comptages.saisir("ESPECES", new BigDecimal("1"), null, null, UUID.randomUUID().toString())).getId();
        new TransactionTemplate(transactions).executeWithoutResult(s -> { ComptageCaisse c = comptagesRepo.findById(comptageId).orElseThrow(); c.setDateComptage(LocalDate.of(2025, 12, 30).atTime(10, 0)); comptagesRepo.saveAndFlush(c); });
        try {
            assertThat(verification(pre(DEBUT_2025), "comptages").bloque()).isTrue();
            assertThat(verification(pre(DEBUT_2025), "comptages").lien()).isEqualTo("/controles/comptages/en-attente");
            assertThatThrownBy(() -> cloturer(DEBUT_2025)).hasMessageContaining("Aucun comptage de caisse en attente");
        } finally {
            authentifie(GERANT, "ADMIN", () -> { comptages.refuser(comptageId, "Comptage d'essai refusé"); return null; });
        }
        assertThat(verification(pre(DEBUT_2025), "comptages").ok()).isTrue();
        Long banque = new TransactionTemplate(transactions).execute(s -> comptesRepo.findAll().stream().filter(c -> c.getBoutique().getId().equals(boutiqueF.getId()) && c.getType() == TypeCompteTresorerie.BANQUE).findFirst().orElseThrow().getId());
        ReleveBancaire releve = authentifie(GERANT, "ADMIN", () -> releveService.creer(banque, LocalDate.of(2025, 11, 1), LocalDate.of(2025, 11, 30), new BigDecimal("300000"), new BigDecimal("300000"), UUID.randomUUID().toString()));
        try {
            assertThat(verification(pre(DEBUT_2025), "releves").bloque()).isTrue();
            assertThat(verification(pre(DEBUT_2025), "releves").lien()).isEqualTo("/rapprochements");
        } finally { authentifie(GERANT, "ADMIN", () -> { releveService.supprimer(releve.getId()); return null; }); }
        assertThat(verification(pre(DEBUT_2025), "releves").ok()).isTrue();
        BigDecimal cout = produits.findById(produit.getId()).orElseThrow().getCoutMoyen();
        setCout(BigDecimal.ZERO);
        try { assertThat(verification(pre(DEBUT_2025), "stock").bloque()).isTrue(); } finally { setCout(cout); }
        assertThat(verification(pre(DEBUT_2025), "stock").ok()).isTrue();
        // Réglage de la validation : cabinet et date ensemble, sinon refus ; une fois renseignée, la recommandation est satisfaite et la mention change.
        assertThatThrownBy(() -> authentifie(GERANT, "ADMIN", () -> { exercices.enregistrerValidation("Cabinet Diallo", null); return null; })).hasMessageContaining("cabinet et la date");
        authentifie(GERANT, "ADMIN", () -> { exercices.enregistrerValidation("Cabinet Diallo et Associés", LocalDate.of(2026, 12, 1)); return null; });
        try {
            assertThat(verification(pre(DEBUT_2025), "validation").ok()).isTrue();
            String html25 = page(GERANT, ETATS + "/bilan?exercice=2025-01-01");
            assertThat(html25).contains("États validés par un expert-comptable : Cabinet Diallo et Associés, le 01/12/2026.").doesNotContain("Comptabilité en cours de validation");
            ExerciceComptable ex = cloturer(DEBUT_2025);
            try {
                InstantaneExercice i = instantaneRepo.findByExerciceIdAndStatut(ex.getId(), StatutInstantane.ACTIF).orElseThrow();
                String t = texte(documentRepo.findByInstantaneIdAndCode(i.getId(), "bilan").orElseThrow().getPdf()).replaceAll("\\s+", " ");
                assertThat(t).contains("Exercice clôturé le 10/01/2026 par " + GERANT, "États validés par un expert-comptable : Cabinet Diallo et Associés").doesNotContain("en cours de validation");
            } finally { annulerClotures(); }
        } finally { authentifie(GERANT, "ADMIN", () -> { exercices.enregistrerValidation("", null); return null; }); }
        assertThat(page(GERANT, ETATS + "/bilan?exercice=2025-01-01")).contains("Comptabilité en cours de validation");
        // Début du premier exercice : réglable tant qu'aucun exercice n'est clôturé, pas après la première opération.
        assertThatThrownBy(() -> authentifie(GERANT, "ADMIN", () -> { exercices.fixerDebutPremierExercice(LocalDate.of(2025, 11, 3)); return null; })).hasMessageContaining("première opération");
    }

    private void setCout(BigDecimal valeur) {
        new TransactionTemplate(transactions).executeWithoutResult(s -> { Produit p = produits.findById(produit.getId()).orElseThrow(); p.setCoutMoyen(valeur); produits.saveAndFlush(p); });
    }

    // ==== Droits, boutiques, formulaires =============================================================================================

    @Test @Order(6)
    void seulLeGerantAccedeAuxExercicesEtChaqueBoutiqueNeVoitQueLesSiens() throws Exception {
        for (String url : List.of(EXERCICES, EXERCICES + "/2025-01-01", EXERCICES + "/instantanes/1/bilan/pdf"))
            mvc.perform(get(url).with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        for (String url : List.of(EXERCICES + "/2025-01-01/cloturer", EXERCICES + "/2025-01-01/annuler", EXERCICES + "/2025-01-01/affecter", EXERCICES + "/validation", EXERCICES + "/premier-exercice"))
            mvc.perform(post(url).param("motif", "Essai").with(user(CAISSIER).roles("CAISSIER")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get(EXERCICES)).andExpect(status().is3xxRedirection());
        // Sans jeton CSRF : refusé.
        mvc.perform(post(EXERCICES + "/2025-01-01/cloturer").with(user(GERANT).roles("ADMIN"))).andExpect(status().isForbidden());
        // Liste blanche des champs : champ inconnu ou répété, 400 ; adresse invalide, 400 ; exercice qui n'existe pas dans le calendrier, 404.
        mvc.perform(post(EXERCICES + "/2025-01-01/cloturer").param("motif", "a").param("boutiqueId", "9").with(user(GERANT).roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(post(EXERCICES + "/2025-01-01/cloturer").param("motif", "a").param("motif", "b").with(user(GERANT).roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(post(EXERCICES + "/2025-01-01/affecter").param("destination", "12").param("statut", "CLOTURE").with(user(GERANT).roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(post(EXERCICES + "/validation").param("cabinet", "x").param("le", "2026-01-01").param("id", "1").with(user(GERANT).roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(post(EXERCICES + "/pas-une-date/cloturer").with(user(GERANT).roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(get(EXERCICES + "/2025-03-01").with(user(GERANT).roles("ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(get(EXERCICES + "/instantanes/999999/bilan/pdf").with(user(GERANT).roles("ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(get(EXERCICES + "/instantanes/1/bilan/word").with(user(GERANT).roles("ADMIN"))).andExpect(status().isNotFound());
        try {
            cloturer(DEBUT_2025);
            Long instantaneF = instantaneRepo.findByBoutiqueIdOrderByDateDebutAscVersionAsc(boutiqueF.getId()).getFirst().getId();
            // La boutique B ne voit ni l'exercice, ni l'instantané, ni les documents de F, et ne peut pas les clôturer.
            mvc.perform(get(EXERCICES + "/instantanes/" + instantaneF + "/bilan/pdf").with(user(GERANT_B).roles("ADMIN"))).andExpect(status().isNotFound());
            mvc.perform(get(EXERCICES + "/instantanes/" + instantaneF + "/bilan/excel").with(user(GERANT_B).roles("ADMIN"))).andExpect(status().isNotFound());
            mvc.perform(get(EXERCICES + "/instantanes/" + instantaneF + "/bilan/pdf").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk());
            mvc.perform(get(EXERCICES + "/instantanes/" + instantaneF + "/bilan/excel").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk());
            String htmlB = page(GERANT_B, EXERCICES);
            assertThat(htmlB).doesNotContain("Clôturé le").doesNotContain("50 000").doesNotContain(instantaneRepo.findById(instantaneF).orElseThrow().getEmpreinte());
            assertThat(authentifie(GERANT_B, "ADMIN", () -> exercices.liste(boutiqueB.getId()))).noneMatch(ExercicesService.Exercice::estCloture);
            assertThat(authentifie(GERANT_B, "ADMIN", () -> exercices.reprisePourDebut(boutiqueB.getId(), LocalDate.of(2026, 1, 1)))).isEmpty();
            assertThat(page(GERANT_B, ETATS + "/bilan?exercice=2025-01-01")).doesNotContain("Exercice clôturé").doesNotContain("instantané figé");
            // B n'est pas verrouillée par la clôture de F : ses opérations datées de 2025 ou 2026 restent possibles.
            authentifie(GERANT_B, "ADMIN", () -> { caisse.depenser(depense(TypeCompteTresorerie.ESPECES, "1000", "Dépense B après clôture de F")); return null; });
        } finally { annulerClotures(); }
        // Boutique B : l'exercice 2025 n'existe pas dans son calendrier (sa première opération est en 2026) ; rien n'est clôturé nulle part.
        mvc.perform(post(EXERCICES + "/2025-01-01/cloturer").with(user(GERANT_B).roles("ADMIN")).with(csrf())).andExpect(status().isNotFound());
        assertThat(exerciceRepo.derniereDateCloturee(boutiqueF.getId())).isNull();
        assertThat(exerciceRepo.derniereDateCloturee(boutiqueB.getId())).isNull();
    }

    // ==== Une opération dans l'exercice ouvert fonctionne ================================================================================

    @Test @Order(90)
    void uneOperationDatee_DansLExerciceOuvertSEnregistreApresLaClotureDe2025() {
        try {
            cloturer(DEBUT_2025);
            Vente v = vente(null, 1, "10000", TypeCompteTresorerie.ESPECES);
            assertThat(v.getId()).isNotNull();
            depenser(TypeCompteTresorerie.ESPECES, "1000", "Dépense après la clôture de 2025");
            assertThat(etats().n().bilan().ecart()).isEqualByComparingTo("0");
        } finally { annulerClotures(); }
    }

    // ==== Le verrou : un test par parcours ===============================================================================================

    /** Clôture, dans l'ordre, tous les exercices jusqu'à celui qui contient aujourd'hui : les opérations (datées de maintenant) tombent alors dans un exercice clôturé. */
    private String cloturerJusquAujourdhui() {
        LocalDate aujourdhui = LocalDate.now();
        for (var e : authentifie(GERANT, "ADMIN", () -> exercices.liste(boutiqueF.getId()))) {
            if (e.debut().isAfter(aujourdhui)) break;
            if (!e.estCloture()) cloturer(e.debut());
        }
        return authentifie(GERANT, "ADMIN", () -> exercices.exercicePour(boutiqueF.getId(), aujourdhui)).libelle();
    }

    private void verrouille() {
        if (verrouLibelle == null) {
            // L'état « clôturé » reste jusqu'à la fin de la classe : ces tests s'exécutent en dernier.
            fixer(LocalDate.now().plusYears(2));
            verrouLibelle = cloturerJusquAujourdhui();
            fixer(JOUR);
        }
    }

    static String verrouLibelle;

    private void refuse(Callable<?> action) { refuse(verrouLibelle, action); }

    private void refuse(String libelle, Callable<?> action) {
        assertThatThrownBy(() -> authentifie(GERANT, "ADMIN", action)).isInstanceOf(ExerciceClotureException.class).hasMessage(refus(libelle));
    }

    @Test @Order(100)
    void parcoursVente() {
        verrouille();
        refuse(() -> { Vente v = new Vente(); v.setBoutique(boutiqueF); v.setMontantVerse(new BigDecimal("10000")); v.setComptePaiement("ESPECES");
            Produit r = new Produit(); r.setId(produit.getId()); DetailVente l = new DetailVente(); l.setProduit(r); l.setQuantite(1); v.getLignes().add(l); return venteService.effectuerVente(v); });
    }

    @Test @Order(101)
    void parcoursAchat() {
        verrouille();
        refuse(() -> { Achat a = new Achat(); a.setBoutique(boutiqueF); a.setMontantVerse(new BigDecimal("1000")); a.setComptePaiement("ESPECES");
            Produit r = new Produit(); r.setId(produit.getId()); DetailAchat l = new DetailAchat(); l.setProduit(r); l.setQuantite(1); l.setPrixAchatUnitaire(new BigDecimal("5000")); l.setPrixVenteUnitaire(new BigDecimal("10000"));
            a.getLignes().add(l); return achatService.enregistrerAchat(a); });
    }

    @Test @Order(102)
    void parcoursReglementDUneVenteEtDUnAchat() {
        verrouille();
        refuse(() -> venteService.reglerVente(venteAminata.getId(), new BigDecimal("1000"), new BigDecimal("50000")));
        refuse(() -> achatService.reglerAchat(achatSidi.getId(), new BigDecimal("1000"), new BigDecimal("120000")));
    }

    @Test @Order(103)
    void parcoursRetours() {
        verrouille();
        refuse(() -> { RetourVenteForm f = new RetourVenteForm(); RetourVenteForm.Ligne l = new RetourVenteForm.Ligne(); l.setDetailVenteId(venteAminata.getLignes().get(0).getId()); l.setQuantite(1);
            f.setLignes(new ArrayList<>(List.of(l))); f.setRetoursConnus(retours.countByVenteId(venteAminata.getId())); return retourVenteService.enregistrer(venteAminata.getId(), f); });
        refuse(() -> { RetourAchatForm f = new RetourAchatForm(); RetourAchatForm.Ligne l = new RetourAchatForm.Ligne(); l.setDetailAchatId(achatSidi.getLignes().get(0).getId()); l.setQuantite(1);
            f.setLignes(new ArrayList<>(List.of(l))); f.setRetoursConnus(retours.countByAchatId(achatSidi.getId())); return retourAchatService.enregistrer(achatSidi.getId(), f); });
    }

    @Test @Order(104)
    void parcoursDepense() {
        verrouille();
        refuse(() -> caisse.depenser(depense(TypeCompteTresorerie.ESPECES, "1000", "Dépense refusée")));
    }

    @Test @Order(105)
    void parcoursTransfert() {
        verrouille();
        refuse(() -> { TransfertTresorerieForm f = new TransfertTresorerieForm(); f.setCompteSource("ESPECES"); f.setCompteDestination("BANQUE"); f.setMontant(new BigDecimal("1000"));
            f.setMotif("Transfert refusé"); f.setJetonOperation(UUID.randomUUID().toString()); return caisse.transferer(f); });
    }

    @Test @Order(106)
    void parcoursFinancement() {
        verrouille();
        refuse(() -> { var f = new OperationFinanciereForm(); f.setType(TypeOperationTresorerie.APPORT_CAPITAL); f.setPartenaireId(associeId); f.setCompte("ESPECES"); f.setMontant(new BigDecimal("1000"));
            f.setMotif("Apport refusé"); f.setJetonOperation(UUID.randomUUID().toString()); return operationService.enregistrer(f); });
        refuse(() -> { var f = new EmpruntForm(); f.setSens(SensEmprunt.EMPRUNT); f.setPartenaireId(banqueId); f.setCompte("BANQUE"); f.setDateEmprunt(LocalDate.now()); f.setDatePremiereEcheance(LocalDate.now().plusMonths(1));
            f.setCapital(new BigDecimal("10000")); f.setTauxAnnuel(new BigDecimal("12")); f.setNombreEcheances(2); f.setPeriodicite(Periodicite.MENSUELLE); f.setJetonOperation(UUID.randomUUID().toString()); return empruntService.creer(f); });
    }

    @Test @Order(107)
    void parcoursComptage() {
        verrouille();
        refuse(() -> comptages.saisir("ESPECES", new BigDecimal("1"), null, null, UUID.randomUUID().toString()));
    }

    @Test @Order(108)
    void parcoursAjustementDeSoldeDeDepart() {
        verrouille();
        refuse(() -> ajustements.ajusterClient(clientAminata.getId(), new BigDecimal("1000"), SensAjustement.IL_ME_DOIT, "Ajustement refusé", UUID.randomUUID().toString()));
    }

    @Test @Order(109)
    void parcoursRapprochementBancaire() {
        verrouille();
        Long banque = new TransactionTemplate(transactions).execute(s -> comptesRepo.findAll().stream().filter(c -> c.getBoutique().getId().equals(boutiqueF.getId()) && c.getType() == TypeCompteTresorerie.BANQUE).findFirst().orElseThrow().getId());
        // Un relevé dont la fin tombe dans un exercice clôturé, aujourd'hui comme en 2025.
        refuse(() -> releveService.creer(banque, LocalDate.now().minusDays(5), LocalDate.now(), BigDecimal.ZERO, BigDecimal.ZERO, UUID.randomUUID().toString()));
        refuse("2025", () -> releveService.creer(banque, LocalDate.of(2025, 11, 1), LocalDate.of(2025, 11, 30), BigDecimal.ZERO, BigDecimal.ZERO, UUID.randomUUID().toString()));
    }

    @Test @Order(110)
    void parcoursAnnulationDUnMouvementDeCaisse() {
        verrouille();
        MouvementCaisse origine = new TransactionTemplate(transactions).execute(s -> caisseRepo.findAll().stream().filter(m -> m.getMotif() != null && m.getMotif().contains("Loyer d'avril")).findFirst().orElseThrow());
        Utilisateur gerant = utilisateurs.findByEmail(GERANT).orElseThrow();
        refuse(() -> caisse.enregistrerAnnulation(origine, "Annulation refusée", gerant));
    }

    @Test @Order(111)
    void parcoursCorbeilleSuppressionEtRestauration() {
        verrouille();
        refuse(() -> produitService.supprimerProduit(produit.getId()));
        refuse(() -> clientService.supprimerClient(clientAminata.getId()));
    }

    @Test @Order(112)
    void lesEtatsDeLExerciceCloturePersistentEtLesAutresBoutiquesNeSontPasVerrouillees() {
        verrouille();
        assertThat(exerciceRepo.derniereDateCloturee(boutiqueF.getId())).isNotNull();
        assertThat(exerciceRepo.derniereDateCloturee(boutiqueB.getId())).isNull();
        authentifie(GERANT_B, "ADMIN", () -> { caisse.depenser(depense(TypeCompteTresorerie.ESPECES, "500", "Dépense B, boutique non verrouillée")); return null; });
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
