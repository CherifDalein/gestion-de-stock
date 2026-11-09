package org.example.stock.controller;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.stock.enums.CompteTresorerie;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * États du gérant (lot 4, tâche C). Le jeu de données est FIXE et construit avec les vrais services (ventes, achats, règlements,
 * annulation, retours, dépenses, transfert) puis daté ; chaque total est comparé à un calcul fait à la main (voir les commentaires
 * du jeu de données). Les chiffres communs avec le tableau de bord doivent lui être identiques.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:etats-gerant;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class EtatsGerantTests {
    /** Jour de l'édition : après la période (juin 2026) et après les opérations de juillet. */
    static final LocalDate JOUR = LocalDate.of(2026, 7, 15);
    static final LocalDate DU = LocalDate.of(2026, 6, 1), AU = LocalDate.of(2026, 6, 30);
    static final String GERANT = "etats-gerant@example.test", CAISSIER = "etats-caissier@example.test", GERANT_B = "etats-gerant-b@example.test";

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
        boutiqueA = boutique("Boutique des états A"); boutiqueB = boutique("Boutique des états B");
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
        fonds(CompteTresorerie.CAISSE, "500000"); fonds(CompteTresorerie.ORANGE_MONEY, "200000"); fonds(CompteTresorerie.BANQUE, "300000");
        dater("2026-05-02");
        stocker(pA, 100); stocker(pB, 50); stocker(pC, 3);

        // --- Mai : avant la période
        Vente v1 = vente(GERANT, aminata, pA, 10, "100000", CompteTresorerie.CAISSE); dater("2026-05-10");          // V1 100 000 payés en caisse
        Achat a1 = achat(sidi, pA, 20, "5000", "40000", CompteTresorerie.BANQUE); echeanceAchat(a1, "2026-06-12"); dater("2026-05-12"); // A1 100 000, versé 40 000
        reglerClient(boubacar, "50000", "0", CompteTresorerie.CAISSE); dater("2026-05-20");                        // reste 150 000
        reglerFournisseur(kaba, "30000", "0", CompteTresorerie.ORANGE_MONEY); dater("2026-05-22");                 // reste 60 000
        venteAncienne(); achatAncien();                                                                             // 15 000 et 9 000 sans partenaire

        // --- Juin : dans la période
        vente(CAISSIER, null, pA, 4, "40000", CompteTresorerie.CAISSE); dater("2026-06-03");                       // V2 40 000 comptant
        vente(CAISSIER, null, pService, 2, "6000", CompteTresorerie.ORANGE_MONEY); dater("2026-06-04");            // V2b service 6 000
        Vente v3 = vente(GERANT, aminata, pB, 5, "30000", CompteTresorerie.ORANGE_MONEY); echeanceVente(v3, "2026-06-15"); dater("2026-06-05"); // 100 000, versé 30 000
        vente(GERANT, mariama, pA, 2, "0", CompteTresorerie.CAISSE); dater("2026-06-08");                          // V4 20 000, avance utilisée 20 000
        Vente v5 = vente(GERANT, mariama, pA, 3, "80000", CompteTresorerie.CAISSE); dater("2026-06-09");          // V5 30 000, versé 80 000 : avance 50 000
        Vente v6 = vente(GERANT, boubacar, pB, 1, "0", CompteTresorerie.CAISSE); echeanceVente(v6, "2026-07-20"); dater("2026-06-10"); // V6 20 000 à crédit
        reglerVente(v3, "20000", "30000", CompteTresorerie.BANQUE); dater("2026-06-12");                           // R1
        reglerVente(v3, "10000", "50000", CompteTresorerie.CAISSE); dater("2026-06-13");                           // R2, annulé le lendemain
        MouvementCaisse r2 = dernierMouvement("REGLEMENT_VENTE");
        authentifie(GERANT, "ADMIN", () -> { annulation.annulerReglementVente(v3.getId(), r2.getId(), "Erreur de saisie"); return null; }); dater("2026-06-14");
        retourVente(v1, 2, ModeRemboursement.ARGENT, CompteTresorerie.CAISSE); dater("2026-06-15");                // RV1 20 000 rendus en caisse
        retourVente(v5, 1, ModeRemboursement.AVOIR, null); dater("2026-06-16");                                    // RV2 10 000 en avoir
        retourVente(v3, 1, null, null); dater("2026-06-16");                                                      // RV3 20 000 imputés sur le reste
        Achat a2 = achat(sidi, pB, 10, "8000", "30000", CompteTresorerie.ORANGE_MONEY); echeanceAchat(a2, "2026-07-17"); dater("2026-06-17"); // A2 80 000, versé 30 000
        Achat a3 = achat(diallo, pA, 4, "5000", "35000", CompteTresorerie.BANQUE); dater("2026-06-18");            // A3 20 000, versé 35 000 : avance 15 000
        retourAchat(a3, 2, ModeRemboursement.ARGENT, CompteTresorerie.BANQUE); dater("2026-06-20");                // RA1 10 000 remboursés en banque
        retourAchat(a3, 1, ModeRemboursement.AVOIR, null); dater("2026-06-20");                                    // RA2 5 000 en avoir
        retourAchat(a1, 4, null, null); dater("2026-06-21");                                                      // RA3 20 000 imputés sur la dette de Sidi
        depenser(CompteTresorerie.CAISSE, "50000", "Loyer de juin"); depenser(CompteTresorerie.ORANGE_MONEY, "12000", "Transport");
        depenser(CompteTresorerie.BANQUE, "80000", "Salaires"); dater("2026-06-22");
        transfert(CompteTresorerie.CAISSE, CompteTresorerie.BANQUE, "100000"); dater("2026-06-24");
        reglerClient(boubacar, "30000", "50000", CompteTresorerie.ORANGE_MONEY); reglerFournisseur(kaba, "20000", "30000", CompteTresorerie.BANQUE); dater("2026-06-26");
        reglerAchat(a2, "10000", "30000", CompteTresorerie.CAISSE); dater("2026-06-27");

        // --- Juillet : après la période
        vente(GERANT, null, pA, 1, "10000", CompteTresorerie.CAISSE); dater("2026-07-02");
        depenser(CompteTresorerie.ORANGE_MONEY, "5000", "Électricité de juillet"); dater("2026-07-05");

        // --- Boutique B : ne doit jamais apparaître dans les états de A
        Categorie catB = categorie(boutiqueB, "Catégorie secrète B");
        Fournisseur fb = fournisseurSimple(boutiqueB, "FOURNISSEUR_SECRET_B");
        Produit pb = produit(boutiqueB, catB, fb, "REF-B-SECRET", "PRODUIT_SECRET_B", NatureProduit.STOCK, 100, "5000", "10000");
        Client clientB = client(boutiqueB, "CLIENT_SECRET_B", "999000", "2026-01-01");
        authentifie(GERANT_B, "ADMIN", () -> { caisse.initialiser(ouverture(CompteTresorerie.CAISSE, "900000")); return null; });
        stocker(pb, 100);
        authentifie(GERANT_B, "ADMIN", () -> venteService.effectuerVente(nouvelleVente(boutiqueB, clientB, pb, 10, "100000", CompteTresorerie.CAISSE)));
        authentifie(GERANT_B, "ADMIN", () -> { caisse.depenser(formDepense(CompteTresorerie.CAISSE, "77700", "Dépense secrète B")); return null; });
        dater("2026-06-11");
    }

    // ==== Bilan : chiffres à la main ====================================================================================

    @Test
    void leBilanDonneLesChiffresCalculesALaMain() {
        authentifier(GERANT);
        Bilan b = bilan.calculer(periode());
        // Ventes de juin : 40 000 + 6 000 + 100 000 + 20 000 + 30 000 + 20 000 = 216 000 ; retours : 20 000 + 10 000 + 20 000 = 50 000.
        assertThat(b.ventes()).isEqualByComparingTo("216000");
        assertThat(b.retoursDeVente()).isEqualByComparingTo("50000");
        assertThat(b.indicateurs().chiffreAffaires()).isEqualByComparingTo("166000");
        // Coût des ventes : 20 000 + 0 + 40 000 + 10 000 + 15 000 + 8 000 = 93 000 ; coût des retours : 10 000 + 5 000 + 8 000 = 23 000.
        assertThat(b.coutDesVentes()).isEqualByComparingTo("70000");
        assertThat(b.indicateurs().beneficeBrut()).isEqualByComparingTo("96000");
        assertThat(b.indicateurs().depenses()).isEqualByComparingTo("142000");     // 50 000 + 12 000 + 80 000 (la dépense de juillet est hors période)
        assertThat(b.indicateurs().beneficeNet()).isEqualByComparingTo("-46000");

        // Trésorerie, compte par compte.
        var caisseT = b.tresorerie().get(0); var orange = b.tresorerie().get(1); var banque = b.tresorerie().get(2);
        assertThat(caisseT.compte()).isEqualTo(CompteTresorerie.CAISSE);
        assertThat(caisseT.debut()).isEqualByComparingTo("650000");         // 500 000 + 100 000 + 50 000
        assertThat(caisseT.encaissements()).isEqualByComparingTo("130000");  // 40 000 + 80 000 + 10 000
        assertThat(caisseT.decaissements()).isEqualByComparingTo("-80000");  // retour 20 000, loyer 50 000, règlement achat 10 000
        assertThat(caisseT.annulations()).isEqualByComparingTo("-10000");
        assertThat(caisseT.transferts()).isEqualByComparingTo("-100000");
        assertThat(caisseT.fin()).isEqualByComparingTo("590000");
        assertThat(orange.debut()).isEqualByComparingTo("170000");           // 200 000 - 30 000
        assertThat(orange.encaissements()).isEqualByComparingTo("66000");    // 6 000 + 30 000 + 30 000
        assertThat(orange.decaissements()).isEqualByComparingTo("-42000");   // achat 30 000 + transport 12 000
        assertThat(orange.fin()).isEqualByComparingTo("194000");
        assertThat(banque.debut()).isEqualByComparingTo("260000");           // 300 000 - 40 000
        assertThat(banque.encaissements()).isEqualByComparingTo("30000");    // règlement 20 000 + remboursement fournisseur 10 000
        assertThat(banque.decaissements()).isEqualByComparingTo("-135000");  // achat 35 000, salaires 80 000, solde Kaba 20 000
        assertThat(banque.transferts()).isEqualByComparingTo("100000");
        assertThat(banque.fin()).isEqualByComparingTo("255000");
        assertThat(b.total(t -> t.fin())).isEqualByComparingTo("1039000");
        assertThat(b.total(t -> t.debut())).isEqualByComparingTo("1080000");
        assertThat(b.total(t -> t.transferts())).isEqualByComparingTo("0");
        // Les fonds de départ sont d'avant la période.
        assertThat(b.total(t -> t.fondsDeDepart())).isEqualByComparingTo("0");

        // Par nature.
        assertThat(b.encaissementsParNature().get("Ventes payées à la création").get(CompteTresorerie.CAISSE)).isEqualByComparingTo("120000");
        assertThat(b.encaissementsParNature().get("Ventes payées à la création").get(CompteTresorerie.ORANGE_MONEY)).isEqualByComparingTo("36000");
        assertThat(b.encaissementsParNature().get("Règlements de ventes").get(CompteTresorerie.BANQUE)).isEqualByComparingTo("20000");
        assertThat(b.encaissementsParNature().get("Remboursements reçus des fournisseurs").get(CompteTresorerie.BANQUE)).isEqualByComparingTo("10000");
        assertThat(b.decaissementsParNature().get("Dépenses").get(CompteTresorerie.BANQUE)).isEqualByComparingTo("-80000");
        assertThat(b.decaissementsParNature().get("Remboursements aux clients").get(CompteTresorerie.CAISSE)).isEqualByComparingTo("-20000");
    }

    @Test
    void laTresorerieEstCoherenteEtCorrespondAuSoldeDuJournal() {
        authentifier(GERANT);
        // Fin = début + encaissements + décaissements + annulations + transferts (+ fonds), compte par compte ; le solde de fin d'une
        // période qui s'étend jusqu'à aujourd'hui est le solde actuel du compte.
        Bilan b = bilan.calculer(periode());
        for (var t : b.tresorerie()) {
            assertThat(t.fin()).isEqualByComparingTo(t.debut().add(t.fondsDeDepart()).add(t.encaissements()).add(t.decaissements())
                    .add(t.annulations()).add(t.transferts()));
        }
        Bilan jusqueAujourdHui = bilan.calculer(new PeriodeEtat(DU, JOUR));
        for (var t : jusqueAujourdHui.tresorerie()) assertThat(t.fin()).isEqualByComparingTo(caisse.getSoldeCompte(t.compte()));
        assertThat(caisse.getSoldeCompte(CompteTresorerie.CAISSE)).isEqualByComparingTo("600000");     // 590 000 + vente de juillet
        assertThat(caisse.getSoldeCompte(CompteTresorerie.ORANGE_MONEY)).isEqualByComparingTo("189000"); // 194 000 - électricité
        assertThat(caisse.getSoldeCompte(CompteTresorerie.BANQUE)).isEqualByComparingTo("255000");
        // La période de mai commence avant les fonds : ils y sont des fonds de départ, début = 0.
        Bilan mai = bilan.calculer(new PeriodeEtat(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)));
        assertThat(mai.total(t -> t.debut())).isEqualByComparingTo("0");
        assertThat(mai.total(t -> t.fondsDeDepart())).isEqualByComparingTo("1000000");
        assertThat(mai.total(t -> t.fin())).isEqualByComparingTo("1080000");
        assertThat(mai.indicateurs().chiffreAffaires()).isEqualByComparingTo("115000");   // V1 100 000 + vente ancienne 15 000
        // Le bilan d'une période où rien ne se passe est nul sans erreur.
        Bilan vide = bilan.calculer(new PeriodeEtat(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)));
        assertThat(vide.total(t -> t.fin())).isEqualByComparingTo("0");
        assertThat(vide.indicateurs().chiffreAffaires()).isEqualByComparingTo("0");
    }

    @Test
    void lesChiffresCommunsAvecLeTableauDeBordLuiSontIdentiques() throws Exception {
        authentifier(GERANT);
        Bilan b = bilan.calculer(periode());
        var mois = tableau.indicateursDuMois(LocalDate.of(2026, 6, 15));
        assertThat(b.indicateurs()).isEqualTo(mois);
        CreancesDettes cd = tableau.creancesEtDettes();
        assertThat(b.creancesDettes()).isEqualTo(cd);
        assertThat(b.stock()).isEqualTo(tableau.valeurStock());
        // Créances, dettes et stock calculés à la main (au 15/07/2026).
        assertThat(cd.creances()).isEqualByComparingTo("185000");         // Aminata 30 000 + Boubacar 140 000 + sans client 15 000
        assertThat(cd.creancesEnRetard()).isEqualByComparingTo("165000"); // 30 000 + 120 000 + 15 000
        assertThat(cd.dettes()).isEqualByComparingTo("129000");           // Sidi 80 000 + Kaba 40 000 + sans fournisseur 9 000
        assertThat(cd.dettesEnRetard()).isEqualByComparingTo("89000");    // 40 000 + 40 000 + 9 000
        assertThat(cd.avancesClients()).isEqualByComparingTo("60000");    // Mariama : surplus 50 000 + avoir 10 000
        assertThat(cd.avancesFournisseurs()).isEqualByComparingTo("50000"); // Diallo : 30 000 de départ + surplus 15 000 + avoir 5 000
        assertThat(cd.positionNette()).isEqualByComparingTo("46000");
        // Stock : A 100 x 5 000 + B 55 x 8 000 + C 3 x 2 000 + D 0 = 946 000 ; le service est exclu.
        assertThat(b.stock().valeur()).isEqualByComparingTo("946000");
        assertThat(b.stock().nombreProduits()).isEqualTo(4);

        // La page du tableau de bord porte les mêmes objets que le bilan (le tableau de bord lit le mois du jour réel : novembre 2026, vide).
        var modele = mvc.perform(get("/").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getModelAndView().getModel();
        authentifier(GERANT);
        assertThat(modele.get("indicateursMois")).isEqualTo(tableau.indicateursDuMois(LocalDate.now()));
        assertThat(modele.get("valeurStock")).isEqualTo(b.stock());
        assertThat(modele.get("creancesDettes")).isEqualTo(b.creancesDettes());
    }

    @Test
    void lePdfEtLExcelDuBilanContiennentLesTotaux() throws Exception {
        MockHttpServletResponse pdf = exporter("/etats/bilan/export/pdf?du=2026-06-01&au=2026-06-30", GERANT);
        assertThat(new String(pdf.getContentAsByteArray(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        assertThat(pdf.getContentType()).isEqualTo("application/pdf");
        String texte = texte(pdf.getContentAsByteArray());
        assertThat(texte).contains("Bilan de la période", "01/06/2026", "30/06/2026", "Boutique des états A", "216 000", "166 000", "96 000", "142 000", "-46 000",
                "1 080 000", "1 039 000", "650 000", "590 000", "194 000", "255 000", "946 000", "185 000", "129 000", "46 000",
                "au jour de l'édition", "lot 3").doesNotContain("SECRET_B", "77 700", "999 000");

        MockHttpServletResponse excel = exporter("/etats/bilan/export/excel?du=2026-06-01&au=2026-06-30", GERANT);
        try (XSSFWorkbook classeur = new XSSFWorkbook(new ByteArrayInputStream(excel.getContentAsByteArray()))) {
            assertThat(classeur.getNumberOfSheets()).isEqualTo(5);
            Sheet resultat = classeur.getSheet("Résultat");
            assertThat(nombre(resultat, "Chiffre d'affaires net des retours")).isEqualByComparingTo("166000");
            assertThat(nombre(resultat, "Bénéfice brut")).isEqualByComparingTo("96000");
            assertThat(nombre(resultat, "Bénéfice net")).isEqualByComparingTo("-46000");
            Sheet tresorerie = classeur.getSheet("Trésorerie");
            assertThat(nombre(tresorerie, "Trésorerie à la fin de la période")).isEqualByComparingTo("1039000");
            assertThat(nombre(classeur.getSheet("Stock"), "Valeur du stock au")).isEqualByComparingTo("946000");
        }
    }

    // ==== Créances et dettes =============================================================================================

    @Test
    void lEtatDesCreancesEstCeluiDuTableauDeBordEtTrieParRetard() {
        authentifier(GERANT);
        Etat etat = partenaires.creancesOuDettes(Cote.CLIENT, JOUR);
        TableauExport detail = etat.sections().get(0), recap = etat.sections().get(1);
        // Ordre : montant en retard décroissant (Boubacar 120 000, ventes sans client 15 000 est à part, Aminata 30 000, Mariama 0).
        List<String> clientsDansLOrdre = detail.getLignes().stream().map(l -> String.valueOf(l.get(0))).distinct().toList();
        assertThat(clientsDansLOrdre.indexOf("Boubacar Bah")).isLessThan(clientsDansLOrdre.indexOf("Aminata Camara"));
        assertThat(clientsDansLOrdre.indexOf("Aminata Camara")).isLessThan(clientsDansLOrdre.indexOf("Mariama Sow"));

        // Boubacar : solde de départ 200 000, réglé 80 000, reste 120 000 en retard de 35 jours (échéance 10/06) ; V6 20 000 pas échue.
        List<Object> depart = ligne(detail, "Boubacar Bah", "Solde de départ");
        assertThat((BigDecimal) depart.get(4)).isEqualByComparingTo("200000");
        assertThat((BigDecimal) depart.get(5)).isEqualByComparingTo("80000");
        assertThat((BigDecimal) depart.get(7)).isEqualByComparingTo("120000");
        assertThat(depart.get(8)).isEqualTo(35);
        List<Object> v6 = ligneDe(detail, "Boubacar Bah", l -> !"Solde de départ".equals(l.get(1)) && l.get(7) != null);
        assertThat((BigDecimal) v6.get(4)).isEqualByComparingTo("20000");
        assertThat((BigDecimal) v6.get(5)).isEqualByComparingTo("0");
        assertThat((BigDecimal) v6.get(7)).isEqualByComparingTo("20000");
        assertThat(v6.get(8)).isNull();
        assertThat((BigDecimal) ligneSousTotal(detail, "Total Boubacar Bah").get(7)).isEqualByComparingTo("140000");

        // Aminata : V3 100 000 − retour 20 000 = total effectif 80 000, versé 50 000 (30 000 + 20 000, le règlement annulé ne compte pas), reste 30 000, 30 jours de retard.
        List<Object> v3 = ligneDe(detail, "Aminata Camara", l -> l.get(7) != null);
        assertThat((BigDecimal) v3.get(4)).isEqualByComparingTo("80000");
        assertThat((BigDecimal) v3.get(5)).isEqualByComparingTo("50000");
        assertThat((BigDecimal) v3.get(6)).isEqualByComparingTo("0");
        assertThat((BigDecimal) v3.get(7)).isEqualByComparingTo("30000");
        assertThat(v3.get(8)).isEqualTo(30);

        // Mariama : aucune facture à payer, une avance de 60 000.
        assertThat((BigDecimal) ligne(detail, "Mariama Sow", "Avance du client").get(7)).isEqualByComparingTo("-60000");
        assertThat((BigDecimal) ligneSousTotal(detail, "Total Mariama Sow").get(7)).isEqualByComparingTo("-60000");

        // Ventes sans client : 15 000, échéance 20/06, 25 jours de retard.
        List<Object> sans = ligneSousTotal(detail, "Ventes sans client");
        assertThat((BigDecimal) sans.get(7)).isEqualByComparingTo("15000");
        assertThat(sans.get(8)).isEqualTo(25);
        assertThat((BigDecimal) detail.getTotaux().get(7)).isEqualByComparingTo("185000");

        // Récapitulatif : mêmes totaux que le tableau de bord.
        CreancesDettes cd = tableau.creancesEtDettes();
        assertThat((BigDecimal) ligne(recap, "Total des créances").get(1)).isEqualByComparingTo(cd.creances());
        assertThat((BigDecimal) ligne(recap, "   dont en retard").get(1)).isEqualByComparingTo(cd.creancesEnRetard());
        assertThat((BigDecimal) ligne(recap, "Avances disponibles").get(1)).isEqualByComparingTo(cd.avancesClients());
        assertThat((BigDecimal) recap.getTotaux().get(1)).isEqualByComparingTo("125000");     // 185 000 − 60 000
        // Les chiffres du service de soldes sont les mêmes.
        assertThat(soldes.creancesClients().total()).isEqualByComparingTo("185000");
        assertThat(soldes.avancesClients()).isEqualByComparingTo("60000");
    }

    @Test
    void lEtatDesDettesEstCeluiDuTableauDeBord() {
        authentifier(GERANT);
        Etat etat = partenaires.creancesOuDettes(Cote.FOURNISSEUR, JOUR);
        TableauExport detail = etat.sections().get(0), recap = etat.sections().get(1);
        // Kaba : 90 000 - 30 000 - 20 000 = 40 000, échéance 20/06 : 25 jours. Sidi : A1 40 000 (échue le 12/06 : 33 jours) + A2 40 000 (échéance 17/07).
        List<Object> depart = ligne(detail, "Kaba Grossiste", "Solde de départ");
        assertThat((BigDecimal) depart.get(4)).isEqualByComparingTo("90000");
        assertThat((BigDecimal) depart.get(5)).isEqualByComparingTo("50000");
        assertThat((BigDecimal) depart.get(7)).isEqualByComparingTo("40000");
        assertThat(depart.get(8)).isEqualTo(25);
        List<Object> a1 = ligneDe(detail, "Sidi Import", l -> Integer.valueOf(33).equals(l.get(8)));
        assertThat((BigDecimal) a1.get(4)).isEqualByComparingTo("80000");   // 100 000 − retour 20 000
        assertThat((BigDecimal) a1.get(5)).isEqualByComparingTo("40000");
        assertThat((BigDecimal) a1.get(7)).isEqualByComparingTo("40000");
        List<Object> a2 = ligneDe(detail, "Sidi Import", l -> l.get(8) == null && l.get(7) != null && l.get(3) != null);
        assertThat((BigDecimal) a2.get(4)).isEqualByComparingTo("80000");
        assertThat((BigDecimal) a2.get(5)).isEqualByComparingTo("40000");   // 30 000 + 10 000
        assertThat((BigDecimal) a2.get(7)).isEqualByComparingTo("40000");
        assertThat((BigDecimal) ligneSousTotal(detail, "Total Sidi Import").get(7)).isEqualByComparingTo("80000");
        assertThat((BigDecimal) ligne(detail, "Diallo Distribution", "Avance chez lui").get(7)).isEqualByComparingTo("-50000");
        List<Object> sans = ligneSousTotal(detail, "Achats sans fournisseur");
        assertThat((BigDecimal) sans.get(7)).isEqualByComparingTo("9000");
        assertThat(sans.get(8)).isEqualTo(44);    // échéance 01/06, au 15/07
        assertThat((BigDecimal) detail.getTotaux().get(7)).isEqualByComparingTo("129000");
        CreancesDettes cd = tableau.creancesEtDettes();
        assertThat((BigDecimal) ligne(recap, "Total des dettes").get(1)).isEqualByComparingTo(cd.dettes());
        assertThat((BigDecimal) ligne(recap, "   dont en retard").get(1)).isEqualByComparingTo(cd.dettesEnRetard());
        assertThat((BigDecimal) recap.getTotaux().get(1)).isEqualByComparingTo("79000");     // 129 000 − 50 000
    }

    @Test
    void lesRetardsSeCalculentALaDateChoisieSansChangerLesMontants() {
        authentifier(GERANT);
        // Au 11/06 : l'échéance du 10/06 de Boubacar est dépassée d'un jour ; celle d'Aminata (15/06) n'est pas échue.
        Etat etat = partenaires.creancesOuDettes(Cote.CLIENT, LocalDate.of(2026, 6, 11));
        TableauExport detail = etat.sections().get(0);
        assertThat(ligne(detail, "Boubacar Bah", "Solde de départ").get(8)).isEqualTo(1);
        assertThat(ligneDe(detail, "Aminata Camara", l -> l.get(7) != null).get(8)).isNull();
        assertThat((BigDecimal) detail.getTotaux().get(7)).isEqualByComparingTo("185000");
        assertThat(etat.notes().size() + detail.getNotes().size()).isGreaterThan(0);
        assertThat(String.join(" ", detail.getNotes())).contains("jour de l'édition").contains("n'est pas le jour de l'édition");
    }

    // ==== Relevés =========================================================================================================

    @Test
    void leReleveDUnFournisseurDonneLeSoldeProgressifEtConcordeAvecLaFiche() {
        authentifier(GERANT);
        Etat etat = partenaires.releve(Cote.FOURNISSEUR, sidi.getId(), periode());
        TableauExport t = etat.sections().get(0);
        // Solde de départ 0 ; mai : facture A1 +100 000 et versement −40 000 ; juin : A2 +80 000, versement −30 000, retour −20 000, règlement −10 000.
        List<List<Object>> lignes = t.getLignes();
        assertThat(lignes.get(0).get(2)).isEqualTo("Solde de départ de la fiche");
        assertThat((BigDecimal) lignes.get(0).get(6)).isEqualByComparingTo("0");
        assertThat(String.valueOf(lignes.get(1).get(2))).startsWith("Opérations antérieures au 01/06/2026 (2 opérations)");
        assertThat((BigDecimal) lignes.get(1).get(6)).isEqualByComparingTo("60000");
        List<BigDecimal> soldesProgressifs = lignes.stream().skip(2).map(l -> (BigDecimal) l.get(6)).toList();
        assertThat(soldesProgressifs).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("140000"), new BigDecimal("110000"), new BigDecimal("90000"), new BigDecimal("80000"));
        assertThat((BigDecimal) t.getTotaux().get(4)).isEqualByComparingTo("80000");   // augmente
        assertThat((BigDecimal) t.getTotaux().get(5)).isEqualByComparingTo("60000");   // diminue
        assertThat((BigDecimal) t.getTotaux().get(6)).isEqualByComparingTo("80000");   // solde final
        // Il est celui de la fiche (dettes de l'état des dettes).
        assertThat(soldes.soldeFournisseur(sidi.getId()).soldeNet().negate()).isEqualByComparingTo("80000");
        assertThat(String.join(" ", t.getNotes())).contains("Concordance").doesNotContain("ATTENTION");
    }

    @Test
    void leReleveDUnClientAvecRetourEtAnnulationConcordeAvecLaFiche() {
        authentifier(GERANT);
        Etat etat = partenaires.releve(Cote.CLIENT, aminata.getId(), periode());
        TableauExport t = etat.sections().get(0);
        // Antérieur : V1 +100 000 −100 000 = 0. Juin : V3 +100 000, versement −30 000, règlement −20 000, règlement −10 000, annulation +10 000,
        // retour RV1 −20 000, remboursement +20 000, retour RV3 −20 000 = 30 000.
        assertThat((BigDecimal) t.getTotaux().get(6)).isEqualByComparingTo("30000");
        assertThat((BigDecimal) t.getTotaux().get(4)).isEqualByComparingTo("130000");
        assertThat((BigDecimal) t.getTotaux().get(5)).isEqualByComparingTo("100000");
        assertThat(t.getLignes().stream().map(l -> String.valueOf(l.get(2))).toList())
                .anyMatch(s -> s.startsWith("Annulation d'un règlement"))
                .anyMatch(s -> s.startsWith("Remboursement au client"))
                .anyMatch(s -> s.startsWith("Retour de marchandises"));
        assertThat(soldes.soldeClient(aminata.getId()).soldeNet()).isEqualByComparingTo("30000");
        assertThat(String.join(" ", t.getNotes())).contains("Concordance").doesNotContain("ATTENTION");

        // Mariama : avance de départ, avance utilisée, surplus et avoir : solde final = −60 000.
        TableauExport m = partenaires.releve(Cote.CLIENT, mariama.getId(), periode()).sections().get(0);
        assertThat((BigDecimal) m.getTotaux().get(6)).isEqualByComparingTo("-60000");
        assertThat(m.getLignes().stream().map(l -> String.valueOf(l.get(2))).toList()).anyMatch(s -> s.startsWith("Avoir de 10"));
        assertThat(String.join(" ", m.getNotes())).contains("Concordance").doesNotContain("ATTENTION");
        // Boubacar : solde de départ 200 000, règlements 50 000 + 30 000, vente à crédit 20 000 : solde 140 000.
        TableauExport b = partenaires.releve(Cote.CLIENT, boubacar.getId(), periode()).sections().get(0);
        assertThat((BigDecimal) b.getTotaux().get(6)).isEqualByComparingTo("140000");
        assertThat(String.join(" ", b.getNotes())).contains("Concordance");
        // Fournisseurs Kaba (reste 40 000) et Diallo (avance 50 000, négatif pour la dette).
        assertThat((BigDecimal) partenaires.releve(Cote.FOURNISSEUR, kaba.getId(), periode()).sections().get(0).getTotaux().get(6)).isEqualByComparingTo("40000");
        TableauExport d = partenaires.releve(Cote.FOURNISSEUR, diallo.getId(), periode()).sections().get(0);
        assertThat((BigDecimal) d.getTotaux().get(6)).isEqualByComparingTo("-50000");
        assertThat(String.join(" ", d.getNotes())).contains("Concordance").doesNotContain("ATTENTION");
    }

    @Test
    void leReleveSurUnePeriodeCourteReporteLesOperationsAnterieuresEtSignaleLesPosterieures() {
        authentifier(GERANT);
        TableauExport t = partenaires.releve(Cote.CLIENT, aminata.getId(), new PeriodeEtat(LocalDate.of(2026, 6, 12), LocalDate.of(2026, 6, 13))).sections().get(0);
        // Avant le 12/06 : V1 (0) puis V3 +100 000 −30 000 = 70 000 ; le 12/06 : −20 000 ; le 13/06 : −10 000.
        assertThat((BigDecimal) t.getLignes().get(1).get(6)).isEqualByComparingTo("70000");
        assertThat((BigDecimal) t.getTotaux().get(6)).isEqualByComparingTo("40000");
        assertThat(String.join(" ", t.getNotes())).contains("postérieures au 13/06/2026").contains("Concordance");
    }

    @Test
    void unPartenaireDUneAutreBoutiqueEstIntrouvable() throws Exception {
        authentifier(GERANT);
        Client secret = clients.findAll().stream().filter(c -> "CLIENT_SECRET_B".equals(c.getNom())).findFirst().orElseThrow();
        Fournisseur secretF = fournisseurs.findAll().stream().filter(f -> "FOURNISSEUR_SECRET_B".equals(f.getNom())).findFirst().orElseThrow();
        for (String url : List.of("/etats/releve/client/" + secret.getId(), "/etats/releve/client/" + secret.getId() + "/export/pdf",
                "/etats/releve/client/" + secret.getId() + "/export/excel", "/etats/releve/fournisseur/" + secretF.getId(),
                "/etats/releve/fournisseur/" + secretF.getId() + "/export/pdf", "/etats/releve/client/987654", "/etats/releve/fournisseur/987654")) {
            mvc.perform(get(url).with(user(GERANT).roles("ADMIN"))).andExpect(status().isNotFound());
        }
    }

    // ==== Stock =========================================================================================================

    @Test
    void lEtatDuStockDonneLesTotauxParCategorieEtLeTotalGeneral() {
        authentifier(GERANT);
        TableauExport t = listes.stock().sections().get(0);
        // Colonnes : Catégorie, Produit, Référence, Quantité, Unité, Coût moyen, Valeur, Seuil, État.
        List<Object> a = ligneDe(t, "Boissons", l -> "REF-A".equals(l.get(2)));
        assertThat(a.get(3)).isEqualTo(100L);
        assertThat((BigDecimal) a.get(5)).isEqualByComparingTo("5000");
        assertThat((BigDecimal) a.get(6)).isEqualByComparingTo("500000");
        assertThat(a.get(8)).isEqualTo("Disponible");
        assertThat((BigDecimal) ligneDe(t, "Boissons", l -> "REF-C".equals(l.get(2))).get(6)).isEqualByComparingTo("6000");
        assertThat(ligneDe(t, "Boissons", l -> "REF-C".equals(l.get(2))).get(8)).isEqualTo("Stock faible");
        assertThat(ligneDe(t, "Textile", l -> "REF-D".equals(l.get(2))).get(8)).isEqualTo("Rupture");
        assertThat((BigDecimal) ligneDe(t, "Textile", l -> "REF-B".equals(l.get(2))).get(6)).isEqualByComparingTo("440000");
        assertThat((BigDecimal) ligneSousTotalCommencant(t, "Total Boissons").get(6)).isEqualByComparingTo("506000");
        assertThat((BigDecimal) ligneSousTotalCommencant(t, "Total Textile").get(6)).isEqualByComparingTo("440000");
        assertThat((BigDecimal) t.getTotaux().get(6)).isEqualByComparingTo("946000");
        assertThat(String.valueOf(t.getTotaux().get(0))).contains("4 articles");
        // Les services et les produits d'une autre boutique sont absents.
        assertThat(t.getLignes().stream().map(l -> String.valueOf(l.get(1))).toList()).noneMatch(s -> s.contains("Livraison") || s.contains("SECRET"));
        assertThat((BigDecimal) t.getTotaux().get(6)).isEqualByComparingTo(tableau.valeurStock().valeur());
    }

    // ==== Dépenses et encaissements =========================================================================================

    @Test
    void laListeDesDepensesDonneLesTotauxParCompte() {
        authentifier(GERANT);
        Etat etat = listes.depenses(periode());
        TableauExport t = etat.sections().get(0), parCompte = etat.sections().get(1);
        assertThat(t.getLignes()).hasSize(3);
        assertThat(t.getLignes().stream().map(l -> String.valueOf(l.get(2))).toList()).containsExactly("Loyer de juin", "Transport", "Salaires");
        assertThat(t.getLignes().stream().map(l -> String.valueOf(l.get(3))).distinct().toList()).containsExactly("Awa Gérante");
        assertThat((BigDecimal) t.getTotaux().get(4)).isEqualByComparingTo("142000");
        assertThat((BigDecimal) ligne(parCompte, "Caisse (espèces)").get(2)).isEqualByComparingTo("50000");
        assertThat((BigDecimal) ligne(parCompte, "Orange Money").get(2)).isEqualByComparingTo("12000");
        assertThat((BigDecimal) ligne(parCompte, "Banque").get(2)).isEqualByComparingTo("80000");
        assertThat((BigDecimal) parCompte.getTotaux().get(2)).isEqualByComparingTo("142000");
        assertThat(t.getLignes().stream().map(l -> String.valueOf(l.get(5))).distinct().toList()).containsExactly("");
        // Juillet : la dépense de juillet seule.
        TableauExport juillet = listes.depenses(new PeriodeEtat(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31))).sections().get(0);
        assertThat(juillet.getLignes()).hasSize(1);
        assertThat((BigDecimal) juillet.getTotaux().get(4)).isEqualByComparingTo("5000");
    }

    @Test
    void uneDepenseAnnuleeParMouvementInverseEstSignalee() {
        authentifier(GERANT);
        // Cas défensif : l'application n'annule pas les dépenses, mais une écriture inverse rattachée à la dépense est signalée.
        OperationTresorerie loyer = operations.findAll().stream().filter(o -> "Loyer de juin".equals(o.getMotif())).findFirst().orElseThrow();
        MouvementCaisse origine = caisseRepo.findAll().stream().filter(m -> m.getOperationTresorerie() != null
                && m.getOperationTresorerie().getId().equals(loyer.getId())).findFirst().orElseThrow();
        MouvementCaisse inverse = new MouvementCaisse();
        inverse.setBoutique(boutiqueA); inverse.setCompte(origine.getCompte()); inverse.setMontant(origine.getMontant().negate());
        inverse.setType("ENTREE"); inverse.setSource("ANNULATION"); inverse.setMotif("Dépense saisie deux fois");
        inverse.setDateMouvement(LocalDateTime.of(2026, 6, 23, 10, 0)); inverse.setAnnulationDe(origine);
        inverse = caisseRepo.saveAndFlush(inverse);
        try {
            TableauExport t = listes.depenses(periode()).sections().get(0);
            assertThat(String.valueOf(ligneDe(t, null, l -> "Loyer de juin".equals(l.get(2))).get(5))).startsWith("Annulée le 23/06/2026").contains("Dépense saisie deux fois");
            assertThat((BigDecimal) t.getTotaux().get(4)).isEqualByComparingTo("142000");    // comme au tableau de bord
        } finally {
            caisseRepo.delete(inverse); caisseRepo.flush();
        }
    }

    @Test
    void laListeDesEncaissementsMontreLesSourcesLesDocumentsEtLesAnnulations() {
        authentifier(GERANT);
        Etat etat = listes.encaissements(periode());
        TableauExport t = etat.sections().get(0), annulations = etat.sections().get(1), parCompte = etat.sections().get(2);
        // Colonnes : Date, Compte, Source, Document, Client / fournisseur, Montant, Utilisateur, Statut.
        // Paiements à la création : V2 40 000 et V5 80 000 (caisse), V2b 6 000 et V3 30 000 (Orange Money) ; règlements : 20 000 (banque) et 10 000 (caisse,
        // annulé) ; solde de départ de Boubacar 30 000 ; remboursement du fournisseur 10 000. V4 (avance seule) ne produit aucun encaissement.
        assertThat(t.getLignes()).hasSize(8);
        assertThat(t.getLignes().stream().map(l -> String.valueOf(l.get(2))).toList()).containsExactlyInAnyOrder(
                "Paiement à la vente", "Paiement à la vente", "Paiement à la vente", "Paiement à la vente", "Règlement de vente", "Règlement de vente",
                "Règlement du solde de départ d'un client", "Remboursement d'un fournisseur");
        assertThat((BigDecimal) t.getTotaux().get(5)).isEqualByComparingTo("226000");
        List<Object> annule = ligneDe(t, null, l -> String.valueOf(l.get(7)).startsWith("Annulé le 14/06/2026"));
        assertThat((BigDecimal) annule.get(5)).isEqualByComparingTo("10000");
        assertThat(String.valueOf(annule.get(7))).contains("Erreur de saisie");
        assertThat(annule.get(4)).isEqualTo("Aminata Camara");
        assertThat(String.valueOf(ligneDe(t, null, l -> "Remboursement d'un fournisseur".equals(l.get(2))).get(4))).isEqualTo("Diallo Distribution");
        assertThat(String.valueOf(ligneDe(t, null, l -> "Règlement du solde de départ d'un client".equals(l.get(2))).get(3))).isEqualTo("Solde de départ");
        assertThat(String.valueOf(ligneDe(t, null, l -> "Vente sans client".equals(l.get(4)) && new BigDecimal("6000").compareTo((BigDecimal) l.get(5)) == 0).get(1))).isEqualTo("Orange Money");

        assertThat(annulations.getLignes()).hasSize(1);
        assertThat((BigDecimal) annulations.getLignes().get(0).get(5)).isEqualByComparingTo("-10000");
        assertThat(String.valueOf(annulations.getLignes().get(0).get(2))).startsWith("Annulation : Règlement de vente du 13/06/2026");
        assertThat((BigDecimal) annulations.getTotaux().get(5)).isEqualByComparingTo("-10000");

        assertThat((BigDecimal) ligne(parCompte, "Caisse (espèces)").get(1)).isEqualByComparingTo("130000");
        assertThat((BigDecimal) ligne(parCompte, "Caisse (espèces)").get(2)).isEqualByComparingTo("-10000");
        assertThat((BigDecimal) ligne(parCompte, "Caisse (espèces)").get(3)).isEqualByComparingTo("120000");
        assertThat((BigDecimal) ligne(parCompte, "Orange Money").get(1)).isEqualByComparingTo("66000");
        assertThat((BigDecimal) ligne(parCompte, "Banque").get(1)).isEqualByComparingTo("30000");
        assertThat((BigDecimal) parCompte.getTotaux().get(1)).isEqualByComparingTo("226000");
        assertThat((BigDecimal) parCompte.getTotaux().get(3)).isEqualByComparingTo("216000");
        // Mêmes encaissements que le bilan.
        Bilan b = bilan.calculer(periode());
        assertThat(b.total(x -> x.encaissements())).isEqualByComparingTo("226000");
        assertThat(b.total(x -> x.annulations())).isEqualByComparingTo("-10000");
    }

    @Test
    void lesVentesParVendeurSontCellesDeLEcranEtLeTotalEstLeChiffreDAffaires() {
        authentifier(GERANT);
        TableauExport t = listes.ventesParVendeur(periode()).sections().get(0);
        // Colonnes : Vendeur, Ventes, CA, Bénéfice brut, Panier moyen. Gérante : V3 100 000 + V4 20 000 + V5 30 000 + V6 20 000 − retours 50 000 = 120 000 ; caissier : 46 000.
        assertThat((BigDecimal) ligne(t, "Awa Gérante").get(2)).isEqualByComparingTo("120000");
        assertThat((BigDecimal) ligne(t, "Moussa Caissier").get(2)).isEqualByComparingTo("46000");
        assertThat((BigDecimal) t.getTotaux().get(2)).isEqualByComparingTo("166000");
        assertThat((BigDecimal) t.getTotaux().get(3)).isEqualByComparingTo("96000");
        assertThat((BigDecimal) t.getTotaux().get(2)).isEqualByComparingTo(tableau.indicateursDeLaPeriode(DU, AU).chiffreAffaires());
    }

    // ==== Pages, exports et droits =========================================================================================

    @Test
    void chaquePageSAffichePourLeGerantAvecLesBoutonsPdfEtExcel() throws Exception {
        String[] pages = {"bilan", "depenses", "encaissements", "stock", "creances", "dettes"};
        for (String page : pages) {
            String html = normaliser(mvc.perform(get("/etats/" + page + "?du=2026-06-01&au=2026-06-30&date=2026-07-15").with(user(GERANT).roles("ADMIN")))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(html).as(page).contains("data-etat-export=\"pdf\"", "data-etat-export=\"excel\"", "/etats/" + page + "/export/pdf")
                    .doesNotContain("SECRET_B", "77 700", "999 000");
        }
        String bilanHtml = normaliser(mvc.perform(get("/etats/bilan?du=2026-06-01&au=2026-06-30").with(user(GERANT).roles("ADMIN")))
                .andReturn().getResponse().getContentAsString());
        assertThat(bilanHtml).contains("Bilan de la période", "216 000", "166 000", "96 000", "142 000", "-46 000", "1 039 000", "946 000", "au jour de l'édition", "lot 3");
        // La page d'accueil des états liste les huit états.
        String accueil = mvc.perform(get("/etats").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(accueil).contains("Bilan de la période", "État des créances", "État des dettes", "État du stock", "Liste des dépenses",
                "Liste des encaissements", "Ventes par vendeur");
        // L'entrée du menu est réservée au gérant ; la page des ventes par vendeur porte ses boutons.
        assertThat(accueil).contains("href=\"/etats\"");
        String vendeurs = mvc.perform(get("/etats/ventes-par-vendeur?du=2026-06-01&au=2026-06-30").with(user(GERANT).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(vendeurs).contains("/etats/ventes-par-vendeur/export/pdf?du=2026-06-01&amp;au=2026-06-30", "/etats/ventes-par-vendeur/export/excel?du=2026-06-01&amp;au=2026-06-30");
        // Le relevé s'ouvre depuis la liste des fournisseurs et des clients.
        String fournisseursPage = mvc.perform(get("/fournisseurs").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(fournisseursPage).contains("/etats/releve/fournisseur/" + sidi.getId());
        String clientsPage = mvc.perform(get("/clients").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(clientsPage).contains("/etats/releve/client/" + aminata.getId());
        assertThat(mvc.perform(get("/clients").with(user(CAISSIER).roles("CAISSIER"))).andReturn().getResponse().getContentAsString()).doesNotContain("/etats/releve");
    }

    @Test
    void tousLesExportsDonnentUnPdfEtUnExcelValides() throws Exception {
        String[] chemins = {"bilan", "depenses", "encaissements", "ventes-par-vendeur", "stock", "creances", "dettes",
                "releve/fournisseur/" + sidi.getId(), "releve/client/" + aminata.getId()};
        for (String chemin : chemins) {
            String parametres = chemin.equals("stock") ? "" : (chemin.equals("creances") || chemin.equals("dettes") ? "?date=2026-07-15" : "?du=2026-06-01&au=2026-06-30");
            MockHttpServletResponse pdf = exporter("/etats/" + chemin + "/export/pdf" + parametres, GERANT);
            assertThat(new String(pdf.getContentAsByteArray(), 0, 4, StandardCharsets.US_ASCII)).as(chemin).isEqualTo("%PDF");
            assertThat(pdf.getHeader("Content-Disposition")).contains("attachment").contains(".pdf");
            MockHttpServletResponse excel = exporter("/etats/" + chemin + "/export/excel" + parametres, GERANT);
            try (XSSFWorkbook classeur = new XSSFWorkbook(new ByteArrayInputStream(excel.getContentAsByteArray()))) {
                assertThat(classeur.getNumberOfSheets()).as(chemin).isGreaterThan(0);
            }
            assertThat(excel.getHeader("Content-Disposition")).contains("attachment").contains(".xlsx");
        }
    }

    @Test
    void lesPdfEtExcelDesCreancesDepensesEtEncaissementsPortentLesTotaux() throws Exception {
        String creances = texte(exporter("/etats/creances/export/pdf?date=2026-07-15", GERANT).getContentAsByteArray());
        assertThat(creances).contains("État des créances", "Boubacar Bah", "Aminata Camara", "Mariama Sow", "Ventes sans client", "185 000", "60 000", "125 000", "120 000", "35")
                .doesNotContain("CLIENT_SECRET_B", "999 000");
        assertThat(creances.indexOf("Boubacar Bah")).isLessThan(creances.indexOf("Aminata Camara"));
        String depenses = texte(exporter("/etats/depenses/export/pdf?du=2026-06-01&au=2026-06-30", GERANT).getContentAsByteArray());
        assertThat(depenses).contains("Liste des dépenses", "Loyer de juin", "Salaires", "142 000", "80 000").doesNotContain("Dépense secrète", "77 700", "Électricité");
        String encaissements = texte(exporter("/etats/encaissements/export/pdf?du=2026-06-01&au=2026-06-30", GERANT).getContentAsByteArray());
        assertThat(encaissements).contains("Liste des encaissements", "226 000", "216 000", "Annulé le 14/06/2026", "Erreur de saisie");
        String stock = texte(exporter("/etats/stock/export/pdf", GERANT).getContentAsByteArray());
        assertThat(stock).contains("État du stock", "946 000", "506 000", "440 000", "Rupture", "Stock faible").doesNotContain("Livraison", "PRODUIT_SECRET_B");
        String releve = texte(exporter("/etats/releve/fournisseur/" + sidi.getId() + "/export/pdf?du=2026-06-01&au=2026-06-30", GERANT).getContentAsByteArray());
        assertThat(releve).contains("Relevé des dettes du fournisseur Sidi Import", "80 000", "140 000", "Concordance");

        MockHttpServletResponse excel = exporter("/etats/encaissements/export/excel?du=2026-06-01&au=2026-06-30", GERANT);
        try (XSSFWorkbook classeur = new XSSFWorkbook(new ByteArrayInputStream(excel.getContentAsByteArray()))) {
            assertThat(nombre(classeur.getSheet("Encaissements"), "Total des encaissements")).isEqualByComparingTo("226000");
            assertThat(nombre(classeur.getSheet("Par compte"), "Total général")).isEqualByComparingTo("216000");
            assertThat(nombre(classeur.getSheet("Annulations"), "Total des annulations")).isEqualByComparingTo("-10000");
        }
        MockHttpServletResponse excelStock = exporter("/etats/stock/export/excel", GERANT);
        try (XSSFWorkbook classeur = new XSSFWorkbook(new ByteArrayInputStream(excelStock.getContentAsByteArray()))) {
            assertThat(nombre(classeur.getSheetAt(0), "Total général")).isEqualByComparingTo("946000");
        }
    }

    @Test
    void leCaissierReçoitUne403SurToutesLesPagesEtTousLesExports() throws Exception {
        List<String> urls = new ArrayList<>(List.of("/etats", "/etats/bilan", "/etats/depenses", "/etats/encaissements", "/etats/stock", "/etats/creances",
                "/etats/dettes", "/etats/ventes-par-vendeur", "/etats/releve/client/" + aminata.getId(), "/etats/releve/fournisseur/" + sidi.getId()));
        for (String page : List.of("bilan", "depenses", "encaissements", "stock", "creances", "dettes", "ventes-par-vendeur",
                "releve/client/" + aminata.getId(), "releve/fournisseur/" + sidi.getId())) {
            urls.add("/etats/" + page + "/export/pdf"); urls.add("/etats/" + page + "/export/excel");
        }
        for (String url : urls) {
            mvc.perform(get(url).with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        }
        // Les services refusent aussi un appel direct d'un caissier.
        authentifier(CAISSIER, "CAISSIER");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> bilan.calculer(periode())).hasMessageContaining("403");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> listes.stock()).hasMessageContaining("403");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> partenaires.creancesOuDettes(Cote.CLIENT, JOUR)).hasMessageContaining("403");
        // Un visiteur anonyme est renvoyé vers la connexion.
        mvc.perform(get("/etats/bilan")).andExpect(status().is3xxRedirection());
    }

    @Test
    void uneAutreBoutiqueNeVoitJamaisLesChiffresDeA() throws Exception {
        authentifier(GERANT_B);
        Bilan b = bilan.calculer(periode());
        assertThat(b.indicateurs().chiffreAffaires()).isEqualByComparingTo("100000");
        assertThat(b.indicateurs().depenses()).isEqualByComparingTo("77700");
        assertThat(b.total(t -> t.fin())).isEqualByComparingTo("922300");     // 900 000 + 100 000 − 77 700
        assertThat(b.stock().valeur()).isEqualByComparingTo("450000");        // 90 x 5 000
        String html = normaliser(mvc.perform(get("/etats/bilan?du=2026-06-01&au=2026-06-30").with(user(GERANT_B).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(html).doesNotContain("Aminata", "216 000", "142 000", "946 000", "1 039 000");
        authentifier(GERANT_B);
        Etat creances = partenaires.creancesOuDettes(Cote.CLIENT, JOUR);
        assertThat(creances.sections().get(0).getLignes().stream().map(l -> String.valueOf(l.get(0))).toList()).noneMatch(s -> s.contains("Aminata") || s.contains("Boubacar"));
        Client aDeA = aminata;
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> partenaires.releve(Cote.CLIENT, aDeA.getId(), periode())).hasMessageContaining("404");
    }

    // ==== Période invalide ===================================================================================================

    @Test
    void unePeriodeInvalideDonneUnMessageEtJamaisUneErreur500() throws Exception {
        String[] invalides = {"du=2026-06-30&au=2026-06-01", "du=abc&au=2026-06-30", "du=2026-06-01&au=", "du=1500-01-01&au=2026-06-30",
                "du=2026-02-30&au=2026-03-01", "du=2026-06-01&du=2026-06-02&au=2026-06-30", "du=%00&au=2026-06-30"};
        for (String parametres : invalides) {
            for (String page : List.of("bilan", "depenses", "encaissements")) {
                String html = mvc.perform(get("/etats/" + page + "?" + parametres).with(user(GERANT).roles("ADMIN")))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                assertThat(html).as(page + "?" + parametres).contains("data-etat-avertissement");
                mvc.perform(get("/etats/" + page + "/export/pdf?" + parametres).with(user(GERANT).roles("ADMIN"))).andExpect(status().isBadRequest());
                mvc.perform(get("/etats/" + page + "/export/excel?" + parametres).with(user(GERANT).roles("ADMIN"))).andExpect(status().isBadRequest());
            }
        }
        mvc.perform(get("/etats/creances?date=pas-une-date").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/etats/creances/export/pdf?date=pas-une-date").with(user(GERANT).roles("ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(get("/etats/bilan/export/csv?du=2026-06-01&au=2026-06-30").with(user(GERANT).roles("ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(get("/etats/releve/client/abc").with(user(GERANT).roles("ADMIN"))).andExpect(status().is4xxClientError());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PeriodeEtat(AU, DU)).isInstanceOf(IllegalArgumentException.class);
        // Sans paramètre : le mois courant (juillet 2026, fixé par l'horloge).
        String mois = mvc.perform(get("/etats/depenses").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mois).contains("Électricité de juillet").doesNotContain("Loyer de juin");
    }

    /** Revue visuelle : avec ETATS_SORTIE=<dossier>, écrit les PDF du jeu de données pour les convertir en images. */
    @Test
    void ecritLesPdfPourLaRevueVisuelleQuandOnLeDemande() throws Exception {
        String dossier = System.getenv("ETATS_SORTIE");
        org.junit.jupiter.api.Assumptions.assumeTrue(dossier != null && !dossier.isBlank());
        Path sortie = Path.of(dossier); Files.createDirectories(sortie);
        String periode = "?du=2026-06-01&au=2026-06-30";
        Files.write(sortie.resolve("bilan.pdf"), exporter("/etats/bilan/export/pdf" + periode, GERANT).getContentAsByteArray());
        Files.write(sortie.resolve("creances.pdf"), exporter("/etats/creances/export/pdf?date=2026-07-15", GERANT).getContentAsByteArray());
        Files.write(sortie.resolve("dettes.pdf"), exporter("/etats/dettes/export/pdf?date=2026-07-15", GERANT).getContentAsByteArray());
        Files.write(sortie.resolve("stock.pdf"), exporter("/etats/stock/export/pdf", GERANT).getContentAsByteArray());
        Files.write(sortie.resolve("encaissements.pdf"), exporter("/etats/encaissements/export/pdf" + periode, GERANT).getContentAsByteArray());
        Files.write(sortie.resolve("depenses.pdf"), exporter("/etats/depenses/export/pdf" + periode, GERANT).getContentAsByteArray());
        Files.write(sortie.resolve("releve-client.pdf"), exporter("/etats/releve/client/" + aminata.getId() + "/export/pdf" + periode, GERANT).getContentAsByteArray());
        Files.write(sortie.resolve("releve-fournisseur.pdf"), exporter("/etats/releve/fournisseur/" + sidi.getId() + "/export/pdf" + periode, GERANT).getContentAsByteArray());
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

    private OuvertureCaisseForm ouverture(CompteTresorerie compte, String montant) {
        OuvertureCaisseForm f = new OuvertureCaisseForm(); f.setComptePaiement(compte); f.setMontant(new BigDecimal(montant)); return f;
    }

    private void fonds(CompteTresorerie compte, String montant) {
        authentifie(GERANT, "ADMIN", () -> { caisse.initialiser(ouverture(compte, montant)); return null; });
    }

    private Vente nouvelleVente(Boutique b, Client acheteur, Produit article, int quantite, String verse, CompteTresorerie compte) {
        Vente vente = new Vente(); vente.setBoutique(b);
        if (acheteur != null) { Client reference = new Client(); reference.setId(acheteur.getId()); vente.setClient(reference); }
        vente.setMontantVerse(new BigDecimal(verse)); vente.setComptePaiement(compte);
        Produit reference = new Produit(); reference.setId(article.getId());
        DetailVente ligne = new DetailVente(); ligne.setProduit(reference); ligne.setQuantite(quantite); vente.getLignes().add(ligne);
        return vente;
    }

    private Vente vente(String vendeur, Client acheteur, Produit article, int quantite, String verse, CompteTresorerie compte) {
        String role = CAISSIER.equals(vendeur) ? "CAISSIER" : "ADMIN";
        return authentifie(vendeur, role, () -> venteService.effectuerVente(nouvelleVente(boutiqueA, acheteur, article, quantite, verse, compte)));
    }

    private Achat achat(Fournisseur vendeur, Produit article, int quantite, String prix, String verse, CompteTresorerie compte) {
        return authentifie(GERANT, "ADMIN", () -> {
            Achat achat = new Achat(); achat.setBoutique(boutiqueA);
            Fournisseur ref = new Fournisseur(); ref.setId(vendeur.getId()); achat.setFournisseur(ref);
            achat.setMontantVerse(new BigDecimal(verse)); achat.setComptePaiement(compte);
            Produit r = new Produit(); r.setId(article.getId());
            DetailAchat ligne = new DetailAchat(); ligne.setProduit(r); ligne.setQuantite(quantite);
            ligne.setPrixAchatUnitaire(new BigDecimal(prix)); ligne.setPrixVenteUnitaire(article.getPrixVente()); achat.getLignes().add(ligne);
            return achatService.enregistrerAchat(achat);
        });
    }

    private void reglerVente(Vente v, String montant, String verseAttendu, CompteTresorerie compte) {
        authentifie(GERANT, "ADMIN", () -> { venteService.reglerVente(v.getId(), new BigDecimal(montant), new BigDecimal(verseAttendu), compte); return null; });
    }

    private void reglerAchat(Achat a, String montant, String verseAttendu, CompteTresorerie compte) {
        authentifie(GERANT, "ADMIN", () -> { achatService.reglerAchat(a.getId(), new BigDecimal(montant), new BigDecimal(verseAttendu), compte); return null; });
    }

    private void reglerClient(Client c, String montant, String dejaRegle, CompteTresorerie compte) {
        authentifie(GERANT, "ADMIN", () -> { soldeDepart.reglerClient(c.getId(), new BigDecimal(montant), new BigDecimal(dejaRegle), compte, null); return null; });
    }

    private void reglerFournisseur(Fournisseur f, String montant, String dejaRegle, CompteTresorerie compte) {
        authentifie(GERANT, "ADMIN", () -> { soldeDepart.reglerFournisseur(f.getId(), new BigDecimal(montant), new BigDecimal(dejaRegle), compte, null); return null; });
    }

    private void retourVente(Vente v, int quantite, ModeRemboursement mode, CompteTresorerie compte) {
        authentifie(GERANT, "ADMIN", () -> {
            RetourVenteForm f = new RetourVenteForm();
            RetourVenteForm.Ligne l = new RetourVenteForm.Ligne(); l.setDetailVenteId(v.getLignes().get(0).getId()); l.setQuantite(quantite);
            f.setLignes(new ArrayList<>(List.of(l))); f.setModeRemboursement(mode); f.setComptePaiement(compte); f.setRetoursConnus(retours.countByVenteId(v.getId()));
            return retourVenteService.enregistrer(v.getId(), f);
        });
    }

    private void retourAchat(Achat a, int quantite, ModeRemboursement mode, CompteTresorerie compte) {
        authentifie(GERANT, "ADMIN", () -> {
            RetourAchatForm f = new RetourAchatForm();
            RetourAchatForm.Ligne l = new RetourAchatForm.Ligne(); l.setDetailAchatId(a.getLignes().get(0).getId()); l.setQuantite(quantite);
            f.setLignes(new ArrayList<>(List.of(l))); f.setModeRemboursement(mode); f.setComptePaiement(compte); f.setRetoursConnus(retours.countByAchatId(a.getId()));
            return retourAchatService.enregistrer(a.getId(), f);
        });
    }

    private void depenser(CompteTresorerie compte, String montant, String motif) {
        authentifie(GERANT, "ADMIN", () -> { caisse.depenser(formDepense(compte, montant, motif)); return null; });
    }

    private DepenseTresorerieForm formDepense(CompteTresorerie compte, String montant, String motif) {
        DepenseTresorerieForm f = new DepenseTresorerieForm(); f.setCompteSource(compte); f.setMontant(new BigDecimal(montant));
        f.setMotif(motif); f.setJetonOperation(UUID.randomUUID().toString()); return f;
    }

    private void transfert(CompteTresorerie source, CompteTresorerie destination, String montant) {
        TransfertTresorerieForm f = new TransfertTresorerieForm(); f.setCompteSource(source); f.setCompteDestination(destination);
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
