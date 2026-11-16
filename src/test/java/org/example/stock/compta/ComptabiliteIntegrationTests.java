package org.example.stock.compta;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.enums.ModeRemboursement;
import org.example.stock.enums.NatureProduit;
import org.example.stock.enums.Role;
import org.example.stock.export.TableauExport;
import org.example.stock.form.DepenseTresorerieForm;
import org.example.stock.form.OuvertureCaisseForm;
import org.example.stock.form.RetourAchatForm;
import org.example.stock.form.RetourVenteForm;
import org.example.stock.form.TransfertTresorerieForm;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.example.stock.service.*;
import org.example.stock.compta.ComptabiliteService.Livres;
import org.example.stock.compta.ComptabiliteService.Statut;
import org.example.stock.service.BilanService.Bilan;
import org.example.stock.service.EtatsPartenairesService.Cote;
import org.example.stock.service.TableauDeBordService.CreancesDettes;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comptabilité (lot 5a) sur un jeu de données FIXE et construit avec les vrais services (ventes, achats, règlements,
 * annulation, retours, dépenses, transfert) puis daté ; chaque total est comparé à un calcul fait à la main (voir les commentaires
 * du jeu de données). Les chiffres communs avec le tableau de bord doivent lui être identiques.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:compta-gerant;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class ComptabiliteIntegrationTests {
    /** Jour de l'édition : après la période (juin 2026) et après les opérations de juillet. */
    static final LocalDate JOUR = LocalDate.of(2026, 7, 15);
    static final LocalDate DU = LocalDate.of(2026, 6, 1), AU = LocalDate.of(2026, 6, 30);
    static final String GERANT = "compta-gerant@example.test", CAISSIER = "compta-caissier@example.test", GERANT_B = "compta-gerant-b@example.test";

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
    @Autowired VenteService venteService;
    @Autowired AchatService achatService;
    @Autowired RetourVenteService retourVenteService;
    @Autowired RetourAchatService retourAchatService;
    @Autowired AnnulationReglementService annulation;
    @Autowired SoldeDepartService soldeDepart;
    @Autowired CaisseService caisse;
    @Autowired TableauDeBordService tableau;
    @Autowired SoldePartenaireService soldes;
    @Autowired BilanService bilan;
    @Autowired EtatsPartenairesService partenaires;
    @Autowired EtatsListesService listes;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @Autowired ComptabiliteService comptabilite;
    @Autowired CorrespondanceService correspondances;
    @Autowired PlanComptesService plan;
    @MockitoBean(name = "recoveryClock") Clock horloge;

    // ---- Jeu de données (construit une seule fois) -----------------------------------------------------------------
    static boolean construit;
    static Boutique boutiqueA, boutiqueB;
    static Produit pA, pB, pC, pD, pService;
    static Client aminata, boubacar, mariama;
    static Fournisseur sidi, kaba, diallo;
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

    /**
     * Calendrier du jeu de données (montants en GNF). Boutique A ; coûts moyens constants (les achats se font au coût moyen) :
     * produit A 5 000 (prix 10 000), produit B 8 000 (prix 20 000), C 2 000 (prix 4 000), D 1 000, service S 0 (prix 3 000).
     * Avant la période : fonds de départ, V1, A1, règlements de soldes de départ, une vente et un achat anciens sans partenaire.
     * Pendant la période (juin) : toutes les autres opérations. Après : une vente et une dépense de juillet.
     */
    private void construire() {
        boutiqueA = boutique("Boutique compta A"); boutiqueB = boutique("Boutique compta B");
        utilisateur(GERANT, "Awa Gérante", Role.ADMIN, boutiqueA); utilisateur(CAISSIER, "Moussa Caissier", Role.CAISSIER, boutiqueA);
        utilisateur(GERANT_B, "Gérant de B", Role.ADMIN, boutiqueB);
        Categorie boissons = categorie(boutiqueA, "Boissons"), textile = categorie(boutiqueA, "Textile"), services = categorie(boutiqueA, "Services");
        Fournisseur parDefaut = fournisseurSimple(boutiqueA, "Fournisseur de référence");
        pA = produit(boutiqueA, boissons, parDefaut, "REF-A", "Jus de bissap", NatureProduit.STOCK, 100, "5000", "10000");
        pB = produit(boutiqueA, textile, parDefaut, "REF-B", "Pagne wax", NatureProduit.STOCK, 50, "8000", "20000");
        pC = produit(boutiqueA, boissons, parDefaut, "REF-C", "Eau minérale", NatureProduit.STOCK, 3, "2000", "4000");
        pD = produit(boutiqueA, textile, parDefaut, "REF-D", "Foulard", NatureProduit.STOCK, 0, "1000", "2500");
        pService = produit(boutiqueA, services, parDefaut, "SERV-S", "Livraison", NatureProduit.SERVICE, 0, "0", "3000");

        aminata = client(boutiqueA, "Aminata Camara", "0", null);
        boubacar = client(boutiqueA, "Boubacar Bah", "200000", "2026-06-10");
        mariama = client(boutiqueA, "Mariama Sow", "-20000", null);
        sidi = fournisseur(boutiqueA, "Sidi Import", "0", null);
        kaba = fournisseur(boutiqueA, "Kaba Grossiste", "-90000", "2026-06-20");
        diallo = fournisseur(boutiqueA, "Diallo Distribution", "30000", null);

        // --- Fonds de départ (02/05)
        fonds(TypeCompteTresorerie.ESPECES, "500000"); fonds(TypeCompteTresorerie.MOBILE_MONEY, "200000"); fonds(TypeCompteTresorerie.BANQUE, "300000");
        dater("2026-05-02");
        stocker(pA, 100); stocker(pB, 50); stocker(pC, 3);

        // --- Mai : avant la période
        Vente v1 = vente(GERANT, aminata, pA, 10, "100000", TypeCompteTresorerie.ESPECES); dater("2026-05-10");          // V1 100 000 payés en caisse
        Achat a1 = achat(sidi, pA, 20, "5000", "40000", TypeCompteTresorerie.BANQUE); echeanceAchat(a1, "2026-06-12"); dater("2026-05-12"); // A1 100 000, versé 40 000
        reglerClient(boubacar, "50000", "0", TypeCompteTresorerie.ESPECES); dater("2026-05-20");                        // reste 150 000
        reglerFournisseur(kaba, "30000", "0", TypeCompteTresorerie.MOBILE_MONEY); dater("2026-05-22");                 // reste 60 000
        venteAncienne(); achatAncien();                                                                             // 15 000 et 9 000 sans partenaire

        // --- Juin : dans la période
        vente(CAISSIER, null, pA, 4, "40000", TypeCompteTresorerie.ESPECES); dater("2026-06-03");                       // V2 40 000 comptant
        vente(CAISSIER, null, pService, 2, "6000", TypeCompteTresorerie.MOBILE_MONEY); dater("2026-06-04");            // V2b service 6 000
        Vente v3 = vente(GERANT, aminata, pB, 5, "30000", TypeCompteTresorerie.MOBILE_MONEY); echeanceVente(v3, "2026-06-15"); dater("2026-06-05"); // 100 000, versé 30 000
        vente(GERANT, mariama, pA, 2, "0", TypeCompteTresorerie.ESPECES); dater("2026-06-08");                          // V4 20 000, avance utilisée 20 000
        Vente v5 = vente(GERANT, mariama, pA, 3, "80000", TypeCompteTresorerie.ESPECES); dater("2026-06-09");          // V5 30 000, versé 80 000 : avance 50 000
        Vente v6 = vente(GERANT, boubacar, pB, 1, "0", TypeCompteTresorerie.ESPECES); echeanceVente(v6, "2026-07-20"); dater("2026-06-10"); // V6 20 000 à crédit
        reglerVente(v3, "20000", "30000", TypeCompteTresorerie.BANQUE); dater("2026-06-12");                           // R1
        reglerVente(v3, "10000", "50000", TypeCompteTresorerie.ESPECES); dater("2026-06-13");                           // R2, annulé le lendemain
        MouvementCaisse r2 = dernierMouvement("REGLEMENT_VENTE");
        authentifie(GERANT, "ADMIN", () -> { annulation.annulerReglementVente(v3.getId(), r2.getId(), "Erreur de saisie"); return null; }); dater("2026-06-14");
        retourVente(v1, 2, ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES); dater("2026-06-15");                // RV1 20 000 rendus en caisse
        retourVente(v5, 1, ModeRemboursement.AVOIR, null); dater("2026-06-16");                                    // RV2 10 000 en avoir
        retourVente(v3, 1, null, null); dater("2026-06-16");                                                      // RV3 20 000 imputés sur le reste
        Achat a2 = achat(sidi, pB, 10, "8000", "30000", TypeCompteTresorerie.MOBILE_MONEY); echeanceAchat(a2, "2026-07-17"); dater("2026-06-17"); // A2 80 000, versé 30 000
        Achat a3 = achat(diallo, pA, 4, "5000", "35000", TypeCompteTresorerie.BANQUE); dater("2026-06-18");            // A3 20 000, versé 35 000 : avance 15 000
        retourAchat(a3, 2, ModeRemboursement.ARGENT, TypeCompteTresorerie.BANQUE); dater("2026-06-20");                // RA1 10 000 remboursés en banque
        retourAchat(a3, 1, ModeRemboursement.AVOIR, null); dater("2026-06-20");                                    // RA2 5 000 en avoir
        retourAchat(a1, 4, null, null); dater("2026-06-21");                                                      // RA3 20 000 imputés sur la dette de Sidi
        depenser(TypeCompteTresorerie.ESPECES, "50000", "Loyer de juin"); depenser(TypeCompteTresorerie.MOBILE_MONEY, "12000", "Transport");
        depenser(TypeCompteTresorerie.BANQUE, "80000", "Salaires"); dater("2026-06-22");
        transfert(TypeCompteTresorerie.ESPECES, TypeCompteTresorerie.BANQUE, "100000"); dater("2026-06-24");
        reglerClient(boubacar, "30000", "50000", TypeCompteTresorerie.MOBILE_MONEY); reglerFournisseur(kaba, "20000", "30000", TypeCompteTresorerie.BANQUE); dater("2026-06-26");
        reglerAchat(a2, "10000", "30000", TypeCompteTresorerie.ESPECES); dater("2026-06-27");

        // --- Juillet : après la période
        vente(GERANT, null, pA, 1, "10000", TypeCompteTresorerie.ESPECES); dater("2026-07-02");
        depenser(TypeCompteTresorerie.MOBILE_MONEY, "5000", "Électricité de juillet"); dater("2026-07-05");

        // --- Boutique B : ne doit jamais apparaître dans les états de A
        Categorie catB = categorie(boutiqueB, "Catégorie secrète B");
        Fournisseur fb = fournisseurSimple(boutiqueB, "FOURNISSEUR_SECRET_B");
        Produit pb = produit(boutiqueB, catB, fb, "REF-B-SECRET", "PRODUIT_SECRET_B", NatureProduit.STOCK, 100, "5000", "10000");
        Client clientB = client(boutiqueB, "CLIENT_SECRET_B", "999000", "2026-01-01");
        authentifie(GERANT_B, "ADMIN", () -> { caisse.initialiser(ouverture(TypeCompteTresorerie.ESPECES, "900000")); return null; });
        stocker(pb, 100);
        authentifie(GERANT_B, "ADMIN", () -> venteService.effectuerVente(nouvelleVente(boutiqueB, clientB, pb, 10, "100000", TypeCompteTresorerie.ESPECES)));
        authentifie(GERANT_B, "ADMIN", () -> { caisse.depenser(formDepense(TypeCompteTresorerie.ESPECES, "77700", "Dépense secrète B")); return null; });
        dater("2026-06-11");
    }

    // ==== Calculs ==============================================================================================

    private Livres livres(String du, String au) {
        authentifier(GERANT);
        return comptabilite.calculer(boutiqueA.getId(), LocalDate.parse(du), LocalDate.parse(au));
    }

    /** Solde (débit moins crédit) d'un compte sur toutes les écritures des livres. */
    private static BigDecimal solde(Livres l, String compte) {
        return l.ecritures().stream().flatMap(e -> e.lignes().stream()).filter(x -> x.compte().equals(compte)).map(Ecriture.Ligne::solde).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void lesCinqControlesSontConformesEtToutesLesEcrituresEquilibrees() {
        Livres l = livres("2026-05-01", "2026-07-15");
        var controles = comptabilite.controles(l);
        assertThat(controles).hasSize(5);
        assertThat(controles).allSatisfy(c -> assertThat(c.statut()).as(c.libelle() + " : " + c.detail()).isEqualTo(Statut.OK));
        assertThat(l.ecritures()).isNotEmpty().allSatisfy(e -> assertThat(e.estEquilibree()).as(e.toString()).isTrue());
        var balance = comptabilite.balance(l);
        assertThat(balance.total().debit()).isEqualByComparingTo(balance.total().credit());
        assertThat(balance.total().finDebit()).isEqualByComparingTo(balance.total().finCredit());
        assertThat(balance.total().ouvertureDebit()).isEqualByComparingTo("0");
    }

    @Test
    void lesComptesDonnentLesMontantsCalculesALaMain() {
        Livres l = livres("2026-05-01", "2026-07-15");
        // Trésorerie : mêmes soldes que la page des comptes (caisse 600 000, Orange Money 189 000, banque 255 000), chacun sur son sous-compte.
        var soldesTres = plan.comptesTresorerie(boutiqueA.getId());
        assertThat(soldesTres).hasSize(3);
        assertThat(soldesTres.values()).allSatisfy(n -> assertThat(n).matches("57\\d+|55\\d+|52\\d+"));
        BigDecimal total = soldesTres.values().stream().map(n -> solde(l, n)).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("1044000");
        // Créances 185 000, avances clients 60 000, dettes 129 000, avances fournisseurs 50 000, stock 946 000 (comme le tableau de bord).
        assertThat(solde(l, "4111")).isEqualByComparingTo("185000");
        assertThat(solde(l, "4191")).isEqualByComparingTo("-60000");
        assertThat(solde(l, "4011")).isEqualByComparingTo("-129000");
        assertThat(solde(l, "4091")).isEqualByComparingTo("50000");
        assertThat(solde(l, "31")).isEqualByComparingTo("946000");
        // Ventes : marchandises 335 000 moins retours 50 000 ; services 6 000. Achats : 209 000 moins retours 35 000.
        assertThat(solde(l, "701")).isEqualByComparingTo("-285000");
        assertThat(solde(l, "706")).isEqualByComparingTo("-6000");
        assertThat(solde(l, "601")).isEqualByComparingTo("174000");
        assertThat(solde(l, "6031")).isEqualByComparingTo("-946000");
        // Dépenses classées par mots-clés du motif.
        assertThat(solde(l, "622")).isEqualByComparingTo("50000");   // Loyer de juin
        assertThat(solde(l, "61")).isEqualByComparingTo("12000");    // Transport
        assertThat(solde(l, "66")).isEqualByComparingTo("80000");    // Salaires
        assertThat(solde(l, "6052")).isEqualByComparingTo("5000");   // Électricité de juillet
        assertThat(solde(l, "585")).isEqualByComparingTo("0");       // le transfert revient à zéro
        // Report à nouveau : fonds de départ 1 000 000 + Boubacar 200 000 + Diallo 30 000 - Mariama 20 000 - Kaba 90 000.
        assertThat(solde(l, "12")).isEqualByComparingTo("-1120000");
    }

    @Test
    void lAnnulationEstUneContrePassationExacte() {
        Livres l = livres("2026-05-01", "2026-07-15");
        var contre = l.ecritures().stream().filter(Ecriture::contrePassation).toList();
        assertThat(contre).hasSize(1);
        var origine = l.ecritures().stream().filter(e -> !e.contrePassation() && e.origine().equals("MOUVEMENT:" + dernierReglementVenteAnnule())).findFirst().orElseThrow();
        assertThat(contre.getFirst().totalDebit()).isEqualByComparingTo(origine.totalDebit());
        for (int i = 0; i < origine.lignes().size(); i++) {
            assertThat(contre.getFirst().lignes().get(i).compte()).isEqualTo(origine.lignes().get(i).compte());
            assertThat(contre.getFirst().lignes().get(i).debit()).isEqualByComparingTo(origine.lignes().get(i).credit());
        }
    }

    private Long dernierReglementVenteAnnule() {
        return caisseRepo.findAll().stream().filter(m -> m.getAnnulationDe() != null).map(m -> m.getAnnulationDe().getId()).findFirst().orElseThrow();
    }

    @Test
    void laPeriodeFiltreLeJournalEtReporteLesSoldesAuGrandLivreEtALaBalance() {
        Livres juin = livres("2026-06-01", "2026-06-30");
        assertThat(comptabilite.journal(juin, null, null, null)).allSatisfy(e -> assertThat(e.date().toLocalDate()).isBetween(DU, AU));
        assertThat(comptabilite.journal(juin, Journal.VE, null, null)).isNotEmpty().allSatisfy(e -> assertThat(e.journal()).isEqualTo(Journal.VE));
        assertThat(comptabilite.journal(juin, null, "622", null)).hasSize(1);
        assertThat(comptabilite.journal(juin, null, null, "loyer")).hasSize(1);
        // Le report d'ouverture du compte de caisse est le solde du 31 mai : fonds de départ moins les mouvements de mai.
        var caisseT = plan.comptesTresorerie(boutiqueA.getId()).values().stream().filter(n -> n.startsWith("57")).findFirst().orElseThrow();
        var gl = comptabilite.grandLivre(juin, caisseT, null, null);
        assertThat(gl).hasSize(1);
        assertThat(gl.getFirst().ouverture()).isEqualByComparingTo("650000");
        assertThat(gl.getFirst().soldeFin()).isEqualByComparingTo("590000");
        assertThat(gl.getFirst().lignes().getLast().solde()).isEqualByComparingTo(gl.getFirst().soldeFin());
        var ligneBalance = comptabilite.balance(juin).lignes().stream().filter(x -> x.numero().equals(caisseT)).findFirst().orElseThrow();
        assertThat(ligneBalance.ouvertureDebit()).isEqualByComparingTo("650000");
        assertThat(ligneBalance.finDebit()).isEqualByComparingTo("590000");
        // Juin finit avant aujourd'hui : l'application ne garde pas ses soldes passés, les contrôles 3 à 5 ne sont pas comparés.
        var controles = comptabilite.controles(juin);
        assertThat(controles.get(0).statut()).isEqualTo(Statut.OK);
        assertThat(controles.get(1).statut()).isEqualTo(Statut.OK);
        assertThat(controles.subList(2, 5)).allSatisfy(c -> assertThat(c.statut()).isEqualTo(Statut.NON_COMPARE));
    }

    @Test
    void leStockDUneDatePasseeRemonteLesOperationsPosterieures() {
        // Au 14/06 : A 101 x 5 000 + B 44 x 8 000 + C 3 x 2 000 = 863 000.
        Livres l = livres("2026-06-01", "2026-06-14");
        assertThat(solde(l, "31")).isEqualByComparingTo("863000");
        assertThat(solde(l, "6031")).isEqualByComparingTo("-863000");
        // Avant toute opération : aucun mouvement, aucune erreur.
        Livres vide = livres("2026-03-01", "2026-03-31");
        assertThat(vide.ecritures()).isEmpty();
        assertThat(comptabilite.balance(vide).lignes()).isEmpty();
    }

    @Test
    void laCorrespondanceModifieeChangeLesEcrituresEtSeRetablit() {
        authentifier(GERANT);
        Long id = boutiqueA.getId();
        correspondances.modifier(id, "VENTES_MARCHANDISES", "702", null);
        correspondances.modifier(id, "DEPENSE_LOYER", "623", "loyer, bail");
        try {
            Livres l = livres("2026-05-01", "2026-07-15");
            assertThat(solde(l, "701")).isEqualByComparingTo("50000");   // 701 ne reçoit plus que les retours de vente (autre clé)
            assertThat(solde(l, "702")).isEqualByComparingTo("-335000");
            assertThat(solde(l, "623")).isEqualByComparingTo("50000");
            assertThat(solde(l, "622")).isEqualByComparingTo("0");
            assertThat(correspondances.lignes(id).stream().filter(x -> x.modifie()).count()).isEqualTo(2);
            assertThat(comptabilite.controles(l)).allSatisfy(c -> assertThat(c.statut()).isEqualTo(Statut.OK));
            // Un compte inconnu est refusé et ne change rien.
            assertThatThrownBy(() -> correspondances.modifier(id, "VENTES_SERVICES", "999999", null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("999999");
            assertThatThrownBy(() -> correspondances.modifier(id, "CLE_INCONNUE", "701", null)).isInstanceOf(IllegalArgumentException.class);
        } finally {
            correspondances.retablirDefauts(id);
        }
        Livres apres = livres("2026-05-01", "2026-07-15");
        assertThat(solde(apres, "701")).isEqualByComparingTo("-285000");
        assertThat(solde(apres, "702")).isEqualByComparingTo("0");
        assertThat(correspondances.lignes(id).stream().noneMatch(x -> x.modifie())).isTrue();
    }

    @Test
    void lesSousComptesDeTresorerieSontCreesEtLesSousComptesManuelsValides() {
        authentifier(GERANT);
        Long id = boutiqueA.getId();
        plan.synchroniserTresorerie(id);
        plan.synchroniserTresorerie(id);
        assertThat(plan.comptesTresorerie(id)).hasSize(3);
        assertThat(plan.plan(id).stream().filter(PlanComptesService.CompteVue::tresorerie)).hasSize(3);
        assertThat(plan.existe(id, "571")).isTrue();
        assertThat(plan.existe(id, "99999")).isFalse();
        var sous = plan.ajouter(id, "658", "6589001", "Pertes de change diverses");
        try {
            assertThat(plan.existe(id, "6589001")).isTrue();
            assertThatThrownBy(() -> plan.ajouter(id, "658", "6589001", "Doublon")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> plan.ajouter(id, "658", "701", "Hors parent")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> plan.ajouter(id, "9999", "99991", "Parent inconnu")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> plan.ajouter(id, "658", "658123456", "Trop long")).isInstanceOf(IllegalArgumentException.class);
            correspondances.modifier(id, "AUTRES_CHARGES", "6589001", null);
            assertThatThrownBy(() -> plan.supprimer(id, sous.getId())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("correspondance");
            correspondances.retablirDefauts(id);
            plan.modifier(id, sous.getId(), "6589002", "Pertes diverses");
            assertThat(plan.existe(id, "6589002")).isTrue();
        } finally {
            plan.supprimer(id, sous.getId());
        }
        assertThat(plan.existe(id, "6589002")).isFalse();
        var tresorerie = plan.plan(id).stream().filter(PlanComptesService.CompteVue::tresorerie).findFirst().orElseThrow();
        assertThatThrownBy(() -> plan.supprimer(id, tresorerie.sousCompteId())).isInstanceOf(IllegalArgumentException.class);
        // La recherche trouve par numéro et par mots, sans tenir compte des accents ni des majuscules.
        assertThat(plan.plan(id).stream().filter(c -> PlanComptesService.correspond(c, "571")).count()).isGreaterThan(1);
        assertThat(plan.plan(id).stream().filter(c -> PlanComptesService.correspond(c, "Ventes DE marchandises")).count()).isGreaterThan(0);
    }

    @Test
    void laBoutiqueBNeVoitPasLesEcrituresDeLaBoutiqueA() {
        authentifier(GERANT_B);
        Livres b = comptabilite.calculer(boutiqueB.getId(), LocalDate.of(2026, 5, 1), JOUR);
        String tout = b.ecritures().toString();
        assertThat(tout).contains("CLIENT_SECRET_B").doesNotContain("Aminata", "Boubacar", "Sidi Import", "Loyer de juin");
        assertThat(solde(b, "701")).isEqualByComparingTo("-100000");
        assertThat(comptabilite.controles(b)).allSatisfy(c -> assertThat(c.statut()).isEqualTo(Statut.OK));
        // Les sous-comptes et la correspondance de B sont les siens.
        assertThat(plan.comptesTresorerie(boutiqueB.getId())).isNotEmpty();
        assertThat(plan.comptesTresorerie(boutiqueB.getId()).keySet()).doesNotContainAnyElementsOf(plan.comptesTresorerie(boutiqueA.getId()).keySet());
    }

    // ==== Pages, exports et droits =============================================================================

    private static final String[] PAGES = {"/comptabilite", "/comptabilite/journal", "/comptabilite/grand-livre", "/comptabilite/balance", "/comptabilite/controles",
            "/comptabilite/plan", "/comptabilite/correspondance"};
    private static final String PERIODE = "?du=2026-06-01&au=2026-06-30";

    @Test
    void lesPagesSontReserveesAuGerantEtPortentLaMention() throws Exception {
        for (String page : PAGES) {
            mvc.perform(get(page).with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
            String html = mvc.perform(get(page).with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(normaliser(html)).as(page).contains("Comptabilité en cours de validation par un expert-comptable", "data-compta-bandeau");
        }
        for (String export : new String[] {"journal", "grand-livre", "balance", "controles"}) {
            for (String format : new String[] {"pdf", "excel"}) {
                mvc.perform(get("/comptabilite/" + export + "/export/" + format + PERIODE).with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
            }
        }
        mvc.perform(get("/comptabilite")).andExpect(status().is3xxRedirection());
        // Le menu de la comptabilité n'est visible que du gérant.
        assertThat(mvc.perform(get("/").with(user(GERANT).roles("ADMIN"))).andReturn().getResponse().getContentAsString()).contains("data-menu-compta");
        assertThat(mvc.perform(get("/").with(user(CAISSIER).roles("CAISSIER"))).andReturn().getResponse().getContentAsString()).doesNotContain("data-menu-compta");
    }

    @Test
    void lesExportsPdfEtExcelPortentLesMemesChiffresEtLaMention() throws Exception {
        for (String export : new String[] {"journal", "grand-livre", "balance", "controles"}) {
            MockHttpServletResponse pdf = exporter("/comptabilite/" + export + "/export/pdf" + PERIODE, GERANT);
            assertThat(new String(pdf.getContentAsByteArray(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
            assertThat(normaliser(texte(pdf.getContentAsByteArray()))).as(export).contains("en cours de validation par un expert-comptable").doesNotContain("SECRET_B", "77 700");
            MockHttpServletResponse excel = exporter("/comptabilite/" + export + "/export/excel" + PERIODE, GERANT);
            try (XSSFWorkbook classeur = new XSSFWorkbook(new ByteArrayInputStream(excel.getContentAsByteArray()))) {
                assertThat(classeur.getNumberOfSheets()).isGreaterThan(0);
                String contenu = texteClasseur(classeur);
                assertThat(contenu).as(export).contains("en cours de validation par un expert-comptable").doesNotContain("SECRET_B");
            }
        }
        String balance = normaliser(texte(exporter("/comptabilite/balance/export/pdf" + PERIODE, GERANT).getContentAsByteArray()));
        assertThat(balance).contains("Balance", "Total", "650 000", "590 000");
        String journal = normaliser(texte(exporter("/comptabilite/journal/export/pdf?du=2026-06-22&au=2026-06-22&compte=622", GERANT).getContentAsByteArray()));
        assertThat(journal).contains("Loyer de juin", "50 000");
        mvc.perform(get("/comptabilite/journal/export/pdf?du=2026-06-30&au=2026-06-01").with(user(GERANT).roles("ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(get("/comptabilite/journal/export/pdf?du=pas-une-date&au=2026-06-01").with(user(GERANT).roles("ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(get("/comptabilite/journal/export/word" + PERIODE).with(user(GERANT).roles("ADMIN"))).andExpect(status().isNotFound());
    }

    private static String texteClasseur(XSSFWorkbook classeur) {
        StringBuilder s = new StringBuilder();
        for (Sheet f : classeur) for (Row r : f) for (Cell c : r) s.append(c.getCellType() == CellType.NUMERIC ? String.valueOf(c.getNumericCellValue()) : c.toString()).append(' ');
        return s.toString();
    }

    @Test
    void unePeriodeInvalideDonneUnAvertissementEtLeMoisEnCours() throws Exception {
        String html = mvc.perform(get("/comptabilite/journal?du=2026-06-30&au=2026-06-01").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(normaliser(html)).contains("La période demandée est invalide");
        String hostile = mvc.perform(get("/comptabilite/journal?compte=%3Cscript%3E&journal=XX&q=" + "a".repeat(200)).with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(hostile).doesNotContain("<script>");
    }

    // ==== Formulaires : champs inconnus ou répétés ==========================================================

    @Test
    void lesFormulairesRefusentLesChampsInconnusEtRepetes() throws Exception {
        var gerant = user(GERANT).roles("ADMIN");
        mvc.perform(post("/comptabilite/correspondance/VENTES_SERVICES").with(csrf()).with(user(CAISSIER).roles("CAISSIER")).param("compte", "706")).andExpect(status().isForbidden());
        mvc.perform(post("/comptabilite/correspondance/VENTES_SERVICES").with(gerant).param("compte", "706")).andExpect(status().isForbidden());   // sans jeton CSRF
        mvc.perform(post("/comptabilite/correspondance/VENTES_SERVICES").with(csrf()).with(gerant).param("compte", "706").param("boutique", "2")).andExpect(status().isBadRequest());
        mvc.perform(post("/comptabilite/correspondance/VENTES_SERVICES").with(csrf()).with(gerant).param("compte", "706").param("compte", "707")).andExpect(status().isBadRequest());
        mvc.perform(post("/comptabilite/plan/sous-comptes").with(csrf()).with(gerant).param("parent", "658").param("numero", "6589").param("intitule", "x").param("boutiqueId", "9")).andExpect(status().isBadRequest());
        mvc.perform(post("/comptabilite/correspondance/defauts").with(csrf()).with(gerant).param("x", "1")).andExpect(status().isBadRequest());
        // Un POST valide enregistre, puis la page le montre, et le rétablissement efface.
        mvc.perform(post("/comptabilite/correspondance/VENTES_SERVICES").with(csrf()).with(gerant).param("compte", "707")).andExpect(status().is3xxRedirection());
        authentifier(GERANT);
        assertThat(correspondances.lignes(boutiqueA.getId()).stream().filter(x -> x.cle().name().equals("VENTES_SERVICES")).findFirst().orElseThrow().compte()).isEqualTo("707");
        String page = mvc.perform(get("/comptabilite/correspondance").with(gerant)).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Modifié");
        mvc.perform(post("/comptabilite/correspondance/defauts").with(csrf()).with(gerant)).andExpect(status().is3xxRedirection());
        assertThat(correspondances.lignes(boutiqueA.getId()).stream().noneMatch(x -> x.modifie())).isTrue();
        // Une correspondance de la boutique A ne touche jamais celle de B.
        assertThat(correspondances.lignes(boutiqueB.getId()).stream().noneMatch(x -> x.modifie())).isTrue();
    }

    // ==== Outils ============================================================================================================

    private PeriodeEtat periode() { return new PeriodeEtat(DU, AU); }

    private MockHttpServletResponse exporter(String url, String compte) throws Exception {
        return mvc.perform(get(url).with(user(compte).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse();
    }

    static String normaliser(String texte) { return org.springframework.web.util.HtmlUtils.htmlUnescape(texte).replace('\u00a0', ' ').replace('\u202f', ' '); }

    static String texte(byte[] contenu) throws Exception {
        try (PDDocument document = Loader.loadPDF(contenu)) {
            return normaliser(new PDFTextStripper().getText(document));
        }
    }

    /** Dernier nombre de la ligne dont la première cellule commence par {@code libelle}. */
    static BigDecimal nombre(Sheet feuille, String libelle) {
        assertThat(feuille).isNotNull();
        for (Row ligne : feuille) {
            Cell premiere = ligne.getCell(0);
            if (premiere == null || premiere.getCellType() != CellType.STRING || !premiere.getStringCellValue().startsWith(libelle)) continue;
            for (int i = ligne.getLastCellNum() - 1; i >= 1; i--) {
                Cell c = ligne.getCell(i);
                if (c != null && c.getCellType() == CellType.NUMERIC) return BigDecimal.valueOf(c.getNumericCellValue());
            }
        }
        throw new AssertionError("Ligne introuvable dans la feuille " + feuille.getSheetName() + " : " + libelle);
    }

    /** Ligne dont la première cellule vaut {@code debut} (et la deuxième {@code deuxieme} quand elle est donnée), parmi les lignes puis les totaux. */
    static List<Object> ligne(TableauExport t, String premiere, String... deuxieme) {
        for (List<Object> l : t.getLignes()) {
            if (String.valueOf(l.get(0)).equals(premiere) && (deuxieme.length == 0 || String.valueOf(l.get(1)).equals(deuxieme[0]))) return l;
        }
        for (List<Object> l : t.getLignes()) {
            if (String.valueOf(l.get(0)).startsWith(premiere) && (deuxieme.length == 0 || String.valueOf(l.get(1)).equals(deuxieme[0]))) return l;
        }
        throw new AssertionError("Ligne introuvable : " + premiere + " " + List.of(deuxieme));
    }

    static List<Object> ligneDe(TableauExport t, String premiere, java.util.function.Predicate<List<Object>> condition) {
        return t.getLignes().stream().filter(l -> premiere == null || String.valueOf(l.get(0)).equals(premiere)).filter(condition).findFirst()
                .orElseThrow(() -> new AssertionError("Ligne introuvable : " + premiere));
    }

    static List<Object> ligneSousTotal(TableauExport t, String libelle) { return ligne(t, libelle); }

    static List<Object> ligneSousTotalCommencant(TableauExport t, String debut) {
        return t.getLignes().stream().filter(l -> String.valueOf(l.get(0)).startsWith(debut)).findFirst().orElseThrow(() -> new AssertionError(debut));
    }

    private void authentifier(String email) { authentifier(email, "ADMIN"); }

    private void authentifier(String email, String role) {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(email, "", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
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

    // ---- Construction du jeu de données

    private Boutique boutique(String nom) { Boutique b = new Boutique(); b.setNom(nom); return boutiques.saveAndFlush(b); }

    private void utilisateur(String email, String nom, Role role, Boutique b) {
        Utilisateur u = new Utilisateur(); u.setBoutique(b); u.setEmail(email); u.setNom(nom); u.setRole(role); utilisateurs.saveAndFlush(u);
    }

    private Categorie categorie(Boutique b, String nom) { Categorie c = new Categorie(); c.setBoutique(b); c.setNom(nom); return categories.saveAndFlush(c); }

    private Fournisseur fournisseurSimple(Boutique b, String nom) { Fournisseur f = new Fournisseur(); f.setBoutique(b); f.setNom(nom); return fournisseurs.saveAndFlush(f); }

    private Produit produit(Boutique b, Categorie c, Fournisseur f, String reference, String nom, NatureProduit nature, int quantite, String cout, String prix) {
        Produit p = new Produit(); p.setBoutique(b); p.setNom(nom); p.setReference(reference); p.setCategorie(c); p.setFournisseur(f); p.setNature(nature);
        p.setQuantite(0L); p.setPrixAchat(new BigDecimal(cout)); p.setPrixVente(new BigDecimal(prix)); p.setPrixDefinis(true);
        p.setCoutMoyen(new BigDecimal(cout));
        return produits.saveAndFlush(p);
    }

    /** Quantité de départ posée après les fonds de départ (une quantité présente avant eux compterait comme une activité). */
    private void stocker(Produit p, long quantite) {
        Produit cible = produits.findById(p.getId()).orElseThrow(); cible.setQuantite(quantite); produits.saveAndFlush(cible);
    }

    private Client client(Boutique b, String nom, String soldeInitial, String echeance) {
        Client c = new Client(); c.setBoutique(b); c.setNom(nom); c.setSoldeInitial(new BigDecimal(soldeInitial));
        c.setEcheanceSoldeInitial(echeance == null ? null : LocalDate.parse(echeance));
        return clients.saveAndFlush(c);
    }

    private Fournisseur fournisseur(Boutique b, String nom, String soldeInitial, String echeance) {
        Fournisseur f = new Fournisseur(); f.setBoutique(b); f.setNom(nom); f.setSoldeInitial(new BigDecimal(soldeInitial));
        f.setEcheanceSoldeInitial(echeance == null ? null : LocalDate.parse(echeance));
        return fournisseurs.saveAndFlush(f);
    }

    private OuvertureCaisseForm ouverture(TypeCompteTresorerie compte, String montant) {
        OuvertureCaisseForm f = new OuvertureCaisseForm(); f.setComptePaiement(compte == null ? null : compte.name()); f.setMontant(new BigDecimal(montant)); return f;
    }

    private void fonds(TypeCompteTresorerie compte, String montant) {
        authentifie(GERANT, "ADMIN", () -> { caisse.initialiser(ouverture(compte, montant)); return null; });
    }

    private Vente nouvelleVente(Boutique b, Client acheteur, Produit article, int quantite, String verse, TypeCompteTresorerie compte) {
        Vente vente = new Vente(); vente.setBoutique(b);
        if (acheteur != null) { Client reference = new Client(); reference.setId(acheteur.getId()); vente.setClient(reference); }
        vente.setMontantVerse(new BigDecimal(verse)); vente.setComptePaiement(compte.name());
        Produit reference = new Produit(); reference.setId(article.getId());
        DetailVente ligne = new DetailVente(); ligne.setProduit(reference); ligne.setQuantite(quantite); vente.getLignes().add(ligne);
        return vente;
    }

    private Vente vente(String vendeur, Client acheteur, Produit article, int quantite, String verse, TypeCompteTresorerie compte) {
        String role = CAISSIER.equals(vendeur) ? "CAISSIER" : "ADMIN";
        return authentifie(vendeur, role, () -> venteService.effectuerVente(nouvelleVente(boutiqueA, acheteur, article, quantite, verse, compte)));
    }

    private Achat achat(Fournisseur vendeur, Produit article, int quantite, String prix, String verse, TypeCompteTresorerie compte) {
        return authentifie(GERANT, "ADMIN", () -> {
            Achat achat = new Achat(); achat.setBoutique(boutiqueA);
            Fournisseur ref = new Fournisseur(); ref.setId(vendeur.getId()); achat.setFournisseur(ref);
            achat.setMontantVerse(new BigDecimal(verse)); achat.setComptePaiement(compte.name());
            Produit r = new Produit(); r.setId(article.getId());
            DetailAchat ligne = new DetailAchat(); ligne.setProduit(r); ligne.setQuantite(quantite);
            ligne.setPrixAchatUnitaire(new BigDecimal(prix)); ligne.setPrixVenteUnitaire(article.getPrixVente()); achat.getLignes().add(ligne);
            return achatService.enregistrerAchat(achat);
        });
    }

    private void reglerVente(Vente v, String montant, String verseAttendu, TypeCompteTresorerie compte) {
        authentifie(GERANT, "ADMIN", () -> { venteService.reglerVente(v.getId(), new BigDecimal(montant), new BigDecimal(verseAttendu), compte.name()); return null; });
    }

    private void reglerAchat(Achat a, String montant, String verseAttendu, TypeCompteTresorerie compte) {
        authentifie(GERANT, "ADMIN", () -> { achatService.reglerAchat(a.getId(), new BigDecimal(montant), new BigDecimal(verseAttendu), compte.name()); return null; });
    }

    private void reglerClient(Client c, String montant, String dejaRegle, TypeCompteTresorerie compte) {
        authentifie(GERANT, "ADMIN", () -> { soldeDepart.reglerClient(c.getId(), new BigDecimal(montant), new BigDecimal(dejaRegle), compte.name(), null); return null; });
    }

    private void reglerFournisseur(Fournisseur f, String montant, String dejaRegle, TypeCompteTresorerie compte) {
        authentifie(GERANT, "ADMIN", () -> { soldeDepart.reglerFournisseur(f.getId(), new BigDecimal(montant), new BigDecimal(dejaRegle), compte.name(), null); return null; });
    }

    private void retourVente(Vente v, int quantite, ModeRemboursement mode, TypeCompteTresorerie compte) {
        authentifie(GERANT, "ADMIN", () -> {
            RetourVenteForm f = new RetourVenteForm();
            RetourVenteForm.Ligne l = new RetourVenteForm.Ligne(); l.setDetailVenteId(v.getLignes().get(0).getId()); l.setQuantite(quantite);
            f.setLignes(new ArrayList<>(List.of(l))); f.setModeRemboursement(mode); f.setComptePaiement(compte == null ? null : compte.name()); f.setRetoursConnus(retours.countByVenteId(v.getId()));
            return retourVenteService.enregistrer(v.getId(), f);
        });
    }

    private void retourAchat(Achat a, int quantite, ModeRemboursement mode, TypeCompteTresorerie compte) {
        authentifie(GERANT, "ADMIN", () -> {
            RetourAchatForm f = new RetourAchatForm();
            RetourAchatForm.Ligne l = new RetourAchatForm.Ligne(); l.setDetailAchatId(a.getLignes().get(0).getId()); l.setQuantite(quantite);
            f.setLignes(new ArrayList<>(List.of(l))); f.setModeRemboursement(mode); f.setComptePaiement(compte == null ? null : compte.name()); f.setRetoursConnus(retours.countByAchatId(a.getId()));
            return retourAchatService.enregistrer(a.getId(), f);
        });
    }

    private void depenser(TypeCompteTresorerie compte, String montant, String motif) {
        authentifie(GERANT, "ADMIN", () -> { caisse.depenser(formDepense(compte, montant, motif)); return null; });
    }

    private DepenseTresorerieForm formDepense(TypeCompteTresorerie compte, String montant, String motif) {
        DepenseTresorerieForm f = new DepenseTresorerieForm(); f.setCompteSource(compte.name()); f.setMontant(new BigDecimal(montant));
        f.setMotif(motif); f.setJetonOperation(UUID.randomUUID().toString()); return f;
    }

    private void transfert(TypeCompteTresorerie source, TypeCompteTresorerie destination, String montant) {
        TransfertTresorerieForm f = new TransfertTresorerieForm(); f.setCompteSource(source.name()); f.setCompteDestination(destination.name());
        f.setMontant(new BigDecimal(montant)); f.setMotif("Transfert de fonds"); f.setJetonOperation(UUID.randomUUID().toString());
        authentifie(GERANT, "ADMIN", () -> { caisse.transferer(f); return null; });
    }

    /** Vente ancienne sans client (créance de 15 000, échéance 20/06) : donnée d'avant les clients, sans mouvement de caisse. */
    private void venteAncienne() {
        Vente v = new Vente(); v.setBoutique(boutiqueA); v.setDateVente(LocalDateTime.of(2026, 5, 30, 9, 0));
        v.setMontantTotal(new BigDecimal("15000")); v.setMontantVerse(BigDecimal.ZERO); v.setAvanceUtilisee(BigDecimal.ZERO);
        v.setDateEcheance(LocalDate.of(2026, 6, 20)); ventes.saveAndFlush(v);
        reperePourVentes = Math.max(reperePourVentes, v.getId());
    }

    /** Achat ancien sans fournisseur (dette de 9 000, échéance 01/06). */
    private void achatAncien() {
        Achat a = new Achat(); a.setBoutique(boutiqueA); a.setDateAchat(LocalDateTime.of(2026, 5, 25, 9, 0));
        a.setMontantTotal(new BigDecimal("9000")); a.setMontantVerse(BigDecimal.ZERO); a.setDateEcheance(LocalDate.of(2026, 6, 1)); achats.saveAndFlush(a);
        reperePourAchats = Math.max(reperePourAchats, a.getId());
    }

    private void echeanceVente(Vente v, String echeance) {
        Vente cible = ventes.findById(v.getId()).orElseThrow(); cible.setDateEcheance(LocalDate.parse(echeance)); ventes.saveAndFlush(cible);
    }

    private void echeanceAchat(Achat a, String echeance) {
        Achat cible = achats.findById(a.getId()).orElseThrow(); cible.setDateEcheance(LocalDate.parse(echeance)); achats.saveAndFlush(cible);
    }

    private MouvementCaisse dernierMouvement(String source) {
        return caisseRepo.findAll().stream().filter(m -> source.equals(m.getSource())).max(java.util.Comparator.comparing(MouvementCaisse::getId)).orElseThrow();
    }

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
