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
 * Justificatifs de solde (lot 4, tâche D). Le jeu de données est celui des états du gérant (EtatsGerantTests), construit avec les vrais
 * services : chaque solde du justificatif est comparé à la fiche, au relevé, au journal et au bilan, et à un calcul fait à la main.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:justificatifs-solde;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class JustificatifsSoldeTests {
    /** Jour de l'édition : après la période (juin 2026) et après les opérations de juillet. */
    static final LocalDate JOUR = LocalDate.of(2026, 7, 15);
    static final LocalDate DU = LocalDate.of(2026, 6, 1), AU = LocalDate.of(2026, 6, 30);
    static final String GERANT = "just-gerant@example.test", CAISSIER = "just-caissier@example.test", GERANT_B = "just-gerant-b@example.test";

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
    @Autowired JustificatifSoldeService justificatifs;
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
        boutiqueA = boutique("Boutique des justificatifs A"); boutiqueB = boutique("Boutique des justificatifs B");
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

    // ==== Partenaires : le solde du justificatif est celui de la fiche et du relevé ===========================================

    /** Solde attendu, calculé à la main (voir le calendrier du jeu de données), au 15/07/2026. Positif : le partenaire doit / la boutique doit. */
    private record Attendu(String nom, BigDecimal solde, BigDecimal resteDepart, int factures, BigDecimal resteFactures, BigDecimal avance) { }

    private static Attendu attendu(String nom, String solde, String resteDepart, int factures, String resteFactures, String avance) {
        return new Attendu(nom, new BigDecimal(solde), new BigDecimal(resteDepart), factures, new BigDecimal(resteFactures), new BigDecimal(avance));
    }

    @Test
    void leSoldeDuJustificatifClientEstCeluiDeLaFicheEtDuReleve() {
        authentifier(GERANT);
        List<Attendu> clientsAttendus = List.of(
                // Aminata : V3 100 000 − versé 30 000 − règlement R1 20 000 (R2 de 10 000 annulé) − retour imputé 20 000 = 30 000 (annulation de règlement + retour).
                attendu("Aminata Camara", "30000", "0", 1, "30000", "0"),
                // Boubacar : solde de départ 200 000 − 50 000 − 30 000 = 120 000 ; V6 à crédit 20 000 (solde de départ + facture).
                attendu("Boubacar Bah", "140000", "120000", 1, "20000", "0"),
                // Mariama : départ −20 000 consommés par V4, surplus de V5 50 000 + avoir du retour RV2 10 000 = avance 60 000 (avoir + avance).
                attendu("Mariama Sow", "-60000", "0", 0, "0", "60000"));
        List<Client> fiches = List.of(aminata, boubacar, mariama);
        for (int i = 0; i < fiches.size(); i++) {
            Client c = fiches.get(i); Attendu a = clientsAttendus.get(i);
            var j = justificatifs.client(c.getId());
            assertThat(j.nom()).isEqualTo(a.nom());
            assertThat(j.solde()).as(a.nom()).isEqualByComparingTo(a.solde());
            assertThat(j.resteSoldeDepart()).as(a.nom() + " solde de départ restant").isEqualByComparingTo(a.resteDepart());
            assertThat(j.factures()).as(a.nom() + " factures").hasSize(a.factures());
            assertThat(j.resteFactures()).as(a.nom() + " restes").isEqualByComparingTo(a.resteFactures());
            assertThat(j.avance()).as(a.nom() + " avance").isEqualByComparingTo(a.avance());
            // La fiche (SoldePartenaireService), le relevé de toute la vie du compte et le justificatif donnent le même solde.
            assertThat(j.solde()).isEqualByComparingTo(soldes.soldeClient(c.getId()).soldeNet());
            assertThat(j.avance()).isEqualByComparingTo(soldes.soldeClient(c.getId()).avanceDisponible());
            assertThat(j.resteSoldeDepart()).isEqualByComparingTo(soldes.soldeClient(c.getId()).resteSoldeDepart());
            assertThat(soldeDuReleve(Cote.CLIENT, c.getId())).as("relevé de " + a.nom()).isEqualByComparingTo(j.solde());
            assertThat(j.solde()).isEqualByComparingTo(j.resteSoldeDepart().add(j.resteFactures()).subtract(j.avance()));
            assertThat(j.arrete()).isEqualTo(JOUR);
            assertThat(j.numero()).isEqualTo("JS-20260715-C" + c.getId());
        }
        // Le détail d'Aminata : la facture V3, avec son échéance du 15/06/2026.
        var aminataJ = justificatifs.client(aminata.getId());
        assertThat(aminataJ.factures().get(0).reste()).isEqualByComparingTo("30000");
        assertThat(aminataJ.factures().get(0).echeance()).isEqualTo(LocalDate.of(2026, 6, 15));
        assertThat(aminataJ.factures().get(0).date()).isEqualTo(LocalDate.of(2026, 6, 5));
        assertThat(justificatifs.client(boubacar.getId()).echeanceSoldeDepart()).isEqualTo(LocalDate.of(2026, 6, 10));
        // Les sens : dette, avance.
        assertThat(aminataJ.estDette()).isTrue();
        assertThat(aminataJ.attestation()).isEqualTo("Le client doit à la boutique la somme de");
        assertThat(justificatifs.client(mariama.getId()).attestation()).startsWith("La boutique détient une avance au nom du client");
        assertThat(justificatifs.client(mariama.getId()).montant()).isEqualByComparingTo("60000");
    }

    @Test
    void leSoldeDuJustificatifFournisseurEstCeluiDeLaFicheEtDuReleve() {
        authentifier(GERANT);
        List<Attendu> attendus = List.of(
                // Sidi : A1 100 000 − 40 000 − retour imputé 20 000 = 40 000 ; A2 80 000 − 30 000 − 10 000 = 40 000 → 80 000.
                attendu("Sidi Import", "80000", "0", 2, "80000", "0"),
                // Kaba : solde de départ « je lui dois » 90 000 − 30 000 − 20 000 = 40 000.
                attendu("Kaba Grossiste", "40000", "40000", 0, "0", "0"),
                // Diallo : avance de départ 30 000 + trop-payé de A3 15 000 + avoir 5 000 = 50 000.
                attendu("Diallo Distribution", "-50000", "0", 0, "0", "50000"));
        List<Fournisseur> fiches = List.of(sidi, kaba, diallo);
        for (int i = 0; i < fiches.size(); i++) {
            Fournisseur f = fiches.get(i); Attendu a = attendus.get(i);
            var j = justificatifs.fournisseur(f.getId());
            assertThat(j.nom()).isEqualTo(a.nom());
            assertThat(j.estClient()).isFalse();
            assertThat(j.solde()).as(a.nom()).isEqualByComparingTo(a.solde());
            assertThat(j.resteSoldeDepart()).as(a.nom() + " solde de départ restant").isEqualByComparingTo(a.resteDepart());
            assertThat(j.factures()).as(a.nom() + " factures").hasSize(a.factures());
            assertThat(j.resteFactures()).isEqualByComparingTo(a.resteFactures());
            assertThat(j.avance()).isEqualByComparingTo(a.avance());
            // La fiche compte l'avance d'un fournisseur en positif ; le justificatif suit le sens des relevés (positif = la boutique doit).
            assertThat(j.solde()).isEqualByComparingTo(soldes.soldeFournisseur(f.getId()).soldeNet().negate());
            assertThat(soldeDuReleve(Cote.FOURNISSEUR, f.getId())).as("relevé de " + a.nom()).isEqualByComparingTo(j.solde());
            assertThat(j.numero()).isEqualTo("JS-20260715-F" + f.getId());
        }
        assertThat(justificatifs.fournisseur(sidi.getId()).attestation()).isEqualTo("La boutique doit au fournisseur la somme de");
        assertThat(justificatifs.fournisseur(diallo.getId()).attestation()).startsWith("La boutique a une avance chez le fournisseur");
        assertThat(justificatifs.fournisseur(kaba.getId()).echeanceSoldeDepart()).isEqualTo(LocalDate.of(2026, 6, 20));
    }

    @Test
    void lesTotauxDesJustificatifsRetrouventLEtatDesCreancesEtDesDettes() {
        authentifier(GERANT);
        // La somme des soldes positifs (hors « sans client ») est celle de l'état des créances : 30 000 + 140 000 ; les avances : 60 000.
        BigDecimal dettesClients = BigDecimal.ZERO, avancesClients = BigDecimal.ZERO;
        for (Client c : List.of(aminata, boubacar, mariama)) {
            var j = justificatifs.client(c.getId());
            dettesClients = dettesClients.add(j.resteSoldeDepart().add(j.resteFactures())); avancesClients = avancesClients.add(j.avance());
        }
        assertThat(dettesClients).isEqualByComparingTo("170000");   // 185 000 du tableau de bord − 15 000 de la vente ancienne sans client
        assertThat(avancesClients).isEqualByComparingTo(tableau.creancesEtDettes().avancesClients());
        BigDecimal avancesFournisseurs = BigDecimal.ZERO;
        for (Fournisseur f : List.of(sidi, kaba, diallo)) avancesFournisseurs = avancesFournisseurs.add(justificatifs.fournisseur(f.getId()).avance());
        assertThat(avancesFournisseurs).isEqualByComparingTo(tableau.creancesEtDettes().avancesFournisseurs());
    }

    @Test
    void lePdfDuClientContientLesMontantsEnChiffresEtEnLettres() throws Exception {
        MockHttpServletResponse reponse = exporter("/etats/justificatifs/client/pdf?id=" + boubacar.getId(), GERANT);
        byte[] pdf = reponse.getContentAsByteArray();
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        assertThat(reponse.getContentType()).isEqualTo("application/pdf");
        assertThat(reponse.getHeader("Content-Disposition")).contains("attachment").contains("JS-20260715-C" + boubacar.getId() + ".pdf");
        assertThat(reponse.getHeader("Cache-Control")).contains("no-store");
        String texte = texteLineaire(pdf);
        authentifier(GERANT);
        String numeroVente = justificatifs.client(boubacar.getId()).factures().get(0).numero();
        assertThat(texte).contains("Boutique des justificatifs A", "Justificatif de solde", "JS-20260715-C" + boubacar.getId(),
                "Numéro du document", "Date d'édition", "Date d'arrêté", "15/07/2026", "Boubacar Bah",
                "Le client doit à la boutique la somme de", "140 000 GNF", "cent quarante mille francs guinéens",
                "Arrêté à la somme de cent quarante mille francs guinéens.",
                "Solde de départ restant à régler", "120 000", "10/06/2026", "Facture de vente " + numeroVente, "20 000", "20/07/2026",
                "Pour la boutique", "Signature et cachet", "Bon pour accord", "ne conserve pas l’historique des soldes")
                .doesNotContain("SECRET_B", "999 000", "Aminata");
        // Une avance : le sens change et l'avance est déduite.
        String avance = texteLineaire(exporter("/etats/justificatifs/client/pdf?id=" + mariama.getId(), GERANT).getContentAsByteArray());
        assertThat(avance).contains("Mariama Sow", "La boutique détient une avance au nom du client, d'un montant de", "60 000 GNF", "soixante mille francs guinéens",
                "Arrêté à la somme de soixante mille francs guinéens.", "Avance détenue par la boutique", "-60 000", "Aucun solde de départ ni facture non soldée.");
    }

    @Test
    void lePdfDuFournisseurInverseLeSensDeLaDette() throws Exception {
        MockHttpServletResponse reponse = exporter("/etats/justificatifs/fournisseur/pdf?id=" + sidi.getId(), GERANT);
        byte[] pdf = reponse.getContentAsByteArray();
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        String texte = texteLineaire(pdf);
        assertThat(texte).contains("Sidi Import", "JS-20260715-F" + sidi.getId(), "La boutique doit au fournisseur la somme de", "80 000 GNF",
                "quatre-vingt mille francs guinéens", "Arrêté à la somme de quatre-vingt mille francs guinéens.", "Facture d’achat", "Solde dû par la boutique",
                "Pour le fournisseur : Bon pour accord", "Signature et cachet").doesNotContain("SECRET_B", "Mariama");
        String avance = texteLineaire(exporter("/etats/justificatifs/fournisseur/pdf?id=" + diallo.getId(), GERANT).getContentAsByteArray());
        assertThat(avance).contains("La boutique a une avance chez le fournisseur, d'un montant de", "50 000 GNF", "cinquante mille francs guinéens",
                "Avance de la boutique chez le fournisseur");
        String depart = texteLineaire(exporter("/etats/justificatifs/fournisseur/pdf?id=" + kaba.getId(), GERANT).getContentAsByteArray());
        assertThat(depart).contains("Solde de départ restant à régler", "20/06/2026", "40 000 GNF", "quarante mille francs guinéens");
    }

    @Test
    void unCompteSoldeEstAtteste() throws Exception {
        authentifie(GERANT, "ADMIN", () -> { fixer(JOUR); return null; });
        Client neuf = client(boutiqueA, "Client sans histoire", "0", null);
        String texte = texteLineaire(exporter("/etats/justificatifs/client/pdf?id=" + neuf.getId(), GERANT).getContentAsByteArray());
        assertThat(texte).contains("Solde nul", "Le compte du client est soldé", "0 GNF", "zéro franc guinéen", "Arrêté à la somme de zéro franc guinéen.");
    }

    // ==== Trésorerie : le solde à une date est celui du journal et du bilan ====================================================

    @Test
    void leSoldeDeTresorerieDUnJourPasseEstCeluiDuJournalEtDuBilan() {
        authentifier(GERANT);
        // Au 30/06/2026 (calculs à la main du bilan) : caisse 590 000, Orange Money 194 000, banque 255 000, total 1 039 000.
        var juin = justificatifs.tresorerie(AU);
        assertThat(juin.comptes().stream().map(c -> c.compte().name() + "=" + c.solde().toPlainString().replaceAll("\\.00$", "")).toList())
                .containsExactly("CAISSE=590000", "ORANGE_MONEY=194000", "BANQUE=255000");
        assertThat(juin.total()).isEqualByComparingTo("1039000");
        assertThat(juin.aujourdHui()).isFalse();
        assertThat(juin.numero()).isEqualTo("JS-20260630-T" + boutiqueA.getId());
        Bilan b = bilan.calculer(periode());
        for (int i = 0; i < 3; i++) assertThat(juin.comptes().get(i).solde()).isEqualByComparingTo(b.tresorerie().get(i).fin());
        // Chaque jour, du 01/05 au 15/07 : la somme des mouvements du journal jusqu'à la fin de ce jour, calculée ici indépendamment.
        for (LocalDate jour = LocalDate.of(2026, 5, 1); !jour.isAfter(JOUR); jour = jour.plusDays(1)) {
            var j = justificatifs.tresorerie(jour);
            LocalDate fin = jour;
            for (var c : j.comptes()) {
                BigDecimal journal = caisseRepo.findAll().stream().filter(m -> m.getBoutique().getId().equals(boutiqueA.getId()) && m.getCompte() == c.compte())
                        .filter(m -> m.getDateMouvement() == null || m.getDateMouvement().toLocalDate().compareTo(fin) <= 0)
                        .map(MouvementCaisse::getMontant).reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(c.solde()).as(c.compte() + " au " + jour).isEqualByComparingTo(journal);
            }
            assertThat(j.total()).isEqualByComparingTo(j.comptes().stream().map(JustificatifSoldeService.SoldeCompte::solde).reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        // Avant les fonds de départ : zéro. Le jour des fonds (fin de journée comprise) : 1 000 000.
        assertThat(justificatifs.tresorerie(LocalDate.of(2026, 5, 1)).total()).isEqualByComparingTo("0");
        assertThat(justificatifs.tresorerie(LocalDate.of(2026, 5, 2)).total()).isEqualByComparingTo("1000000");
        // Le bilan de n'importe quelle période se termine sur le solde du justificatif de son dernier jour.
        Bilan mai = bilan.calculer(new PeriodeEtat(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)));
        assertThat(justificatifs.tresorerie(LocalDate.of(2026, 5, 31)).total()).isEqualByComparingTo(mai.total(t -> t.fin()));
    }

    @Test
    void leSoldeDeTresorerieDAujourdHuiEstCeluiDuJournal() {
        authentifier(GERANT);
        var j = justificatifs.tresorerie(null);
        assertThat(j.aujourdHui()).isTrue();
        assertThat(j.arrete()).isEqualTo(JOUR);
        assertThat(j.comptes().get(0).solde()).isEqualByComparingTo(caisse.getSoldeCompte(CompteTresorerie.CAISSE)).isEqualByComparingTo("600000");
        assertThat(j.comptes().get(1).solde()).isEqualByComparingTo(caisse.getSoldeCompte(CompteTresorerie.ORANGE_MONEY)).isEqualByComparingTo("189000");
        assertThat(j.comptes().get(2).solde()).isEqualByComparingTo(caisse.getSoldeCompte(CompteTresorerie.BANQUE)).isEqualByComparingTo("255000");
        assertThat(j.total()).isEqualByComparingTo("1044000");
        assertThat(justificatifs.tresorerie(JOUR).total()).isEqualByComparingTo(j.total());
        // Le jour de l'édition par la période du bilan (du premier jour au jour même).
        Bilan jusqueAujourdHui = bilan.calculer(new PeriodeEtat(DU, JOUR));
        assertThat(j.total()).isEqualByComparingTo(jusqueAujourdHui.total(t -> t.fin()));
        // La boutique B a sa propre trésorerie : 900 000 + 100 000 − 77 700.
        authentifier(GERANT_B);
        assertThat(justificatifs.tresorerie(null).total()).isEqualByComparingTo("922300");
        assertThat(justificatifs.tresorerie(LocalDate.of(2026, 6, 30)).total()).isEqualByComparingTo("922300");
    }

    @Test
    void lePdfDeTresorerieContientLesComptesLeTotalEtLaMention() throws Exception {
        MockHttpServletResponse reponse = exporter("/etats/justificatifs/tresorerie/pdf?date=2026-06-30", GERANT);
        byte[] pdf = reponse.getContentAsByteArray();
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        assertThat(reponse.getHeader("Content-Disposition")).contains("JS-20260630-T" + boutiqueA.getId() + ".pdf");
        String texte = texteLineaire(pdf);
        assertThat(texte).contains("Boutique des justificatifs A", "Justificatif de solde de trésorerie", "JS-20260630-T" + boutiqueA.getId(), "Date d'arrêté", "30/06/2026",
                "Caisse (espèces)", "590 000", "Orange Money", "194 000", "Banque", "255 000", "Total de la trésorerie", "1 039 000 GNF",
                "un million trente-neuf mille francs guinéens", "Arrêté à la somme de un million trente-neuf mille francs guinéens.",
                "Solde calculé par l'application ; à rapprocher du comptage physique.", "Signature et cachet")
                .doesNotContain("922 300", "SECRET_B");
        String aujourdHui = texteLineaire(exporter("/etats/justificatifs/tresorerie/pdf", GERANT).getContentAsByteArray());
        assertThat(aujourdHui).contains("JS-20260715-T", "600 000", "189 000", "1 044 000 GNF", "un million quarante-quatre mille francs guinéens");
        String vide = texteLineaire(exporter("/etats/justificatifs/tresorerie/pdf?date=2026-05-01", GERANT).getContentAsByteArray());
        assertThat(vide).contains("0 GNF", "zéro franc guinéen");
        String rond = texteLineaire(exporter("/etats/justificatifs/tresorerie/pdf?date=2026-05-02", GERANT).getContentAsByteArray());
        assertThat(rond).contains("1 000 000 GNF", "un million de francs guinéens");
    }

    // ==== Droits, boutiques, paramètres, accès ================================================================================

    @Test
    void leCaissierReçoitUne403PartoutEtUnVisiteurEstRenvoyeVersLaConnexion() throws Exception {
        List<String> urls = List.of("/etats/justificatifs", "/etats/justificatifs/client/pdf?id=" + aminata.getId(), "/etats/justificatifs/fournisseur/pdf?id=" + sidi.getId(),
                "/etats/justificatifs/tresorerie/pdf", "/etats/justificatifs/tresorerie/pdf?date=2026-06-30");
        for (String url : urls) {
            mvc.perform(get(url).with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
            mvc.perform(get(url)).andExpect(status().is3xxRedirection());
        }
        // Les services refusent aussi un appel direct d'un caissier.
        authentifier(CAISSIER, "CAISSIER");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> justificatifs.client(aminata.getId())).hasMessageContaining("403");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> justificatifs.fournisseur(sidi.getId())).hasMessageContaining("403");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> justificatifs.tresorerie(JOUR)).hasMessageContaining("403");
    }

    @Test
    void uneAutreBoutiqueReçoitUne404EtNeVoitRienDeA() throws Exception {
        for (String url : List.of("/etats/justificatifs/client/pdf?id=" + aminata.getId(), "/etats/justificatifs/fournisseur/pdf?id=" + sidi.getId())) {
            mvc.perform(get(url).with(user(GERANT_B).roles("ADMIN"))).andExpect(status().isNotFound());
        }
        mvc.perform(get("/etats/justificatifs/client/pdf?id=987654").with(user(GERANT).roles("ADMIN"))).andExpect(status().isNotFound());
        // Le client d'un partenaire de B n'est pas non plus lisible depuis A.
        Client deB = clients.findAllByBoutiqueId(boutiqueB.getId()).get(0);
        mvc.perform(get("/etats/justificatifs/client/pdf?id=" + deB.getId()).with(user(GERANT).roles("ADMIN"))).andExpect(status().isNotFound());
        String page = normaliser(mvc.perform(get("/etats/justificatifs").with(user(GERANT_B).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page).contains("CLIENT_SECRET_B", "FOURNISSEUR_SECRET_B").doesNotContain("Aminata", "Sidi Import", "Boubacar");
        String tresorerieB = texteLineaire(exporter("/etats/justificatifs/tresorerie/pdf?date=2026-06-30", GERANT_B).getContentAsByteArray());
        assertThat(tresorerieB).contains("Boutique des justificatifs B", "922 300 GNF", "neuf cent vingt-deux mille trois cents francs guinéens").doesNotContain("1 039 000", "590 000");
    }

    @Test
    void lesParametresInvalidesDonnentUne400() throws Exception {
        String[] invalides = {"/etats/justificatifs/client/pdf", "/etats/justificatifs/client/pdf?id=", "/etats/justificatifs/client/pdf?id=abc", "/etats/justificatifs/client/pdf?id=-1",
                "/etats/justificatifs/client/pdf?id=1&id=2", "/etats/justificatifs/client/pdf?id=1.5", "/etats/justificatifs/fournisseur/pdf", "/etats/justificatifs/fournisseur/pdf?id=x",
                "/etats/justificatifs/tresorerie/pdf?date=pas-une-date", "/etats/justificatifs/tresorerie/pdf?date=", "/etats/justificatifs/tresorerie/pdf?date=2026-02-30",
                "/etats/justificatifs/tresorerie/pdf?date=2026-07-16",   // demain : une date d'arrêté ne peut pas être dans le futur
                "/etats/justificatifs/tresorerie/pdf?date=2999-01-01", "/etats/justificatifs/tresorerie/pdf?date=1500-01-01",
                "/etats/justificatifs/tresorerie/pdf?date=2026-06-30&date=2026-06-29"};
        for (String url : invalides) mvc.perform(get(url).with(user(GERANT).roles("ADMIN"))).andExpect(status().isBadRequest());
        // Un POST ou autre verbe n'existe pas : lecture seule.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/etats/justificatifs/tresorerie/pdf").with(user(GERANT).roles("ADMIN")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void lesJustificatifsSontEnLectureSeuleEtLeNumeroEstStable() throws Exception {
        long mouvements = caisseRepo.count(), ventesAvant = ventes.count(), achatsAvant = achats.count();
        byte[] premier = exporter("/etats/justificatifs/client/pdf?id=" + aminata.getId(), GERANT).getContentAsByteArray();
        exporter("/etats/justificatifs/fournisseur/pdf?id=" + sidi.getId(), GERANT);
        exporter("/etats/justificatifs/tresorerie/pdf", GERANT);
        assertThat(caisseRepo.count()).isEqualTo(mouvements);
        assertThat(ventes.count()).isEqualTo(ventesAvant);
        assertThat(achats.count()).isEqualTo(achatsAvant);
        authentifier(GERANT);
        assertThat(justificatifs.client(aminata.getId()).numero()).isEqualTo(justificatifs.client(aminata.getId()).numero()).isEqualTo("JS-20260715-C" + aminata.getId());
        assertThat(texteLineaire(premier)).contains("JS-20260715-C" + aminata.getId());
        // Un autre jour d'édition change la date du numéro, jamais l'identifiant.
        fixer(LocalDate.of(2026, 7, 16));
        assertThat(justificatifs.client(aminata.getId()).numero()).isEqualTo("JS-20260716-C" + aminata.getId());
        assertThat(justificatifs.tresorerie(JOUR).total()).isEqualByComparingTo("1044000");
        fixer(JOUR);
    }

    @Test
    void lesPagesEtLesBoutonsSontReservesAuGerant() throws Exception {
        String page = normaliser(mvc.perform(get("/etats/justificatifs").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page).contains("Justificatifs de solde", "data-justificatif=\"client\"", "data-justificatif=\"fournisseur\"", "data-justificatif=\"tresorerie\"",
                "Aminata Camara", "Sidi Import", "/etats/justificatifs/client/pdf", "/etats/justificatifs/fournisseur/pdf", "/etats/justificatifs/tresorerie/pdf",
                "name=\"date\"", "max=\"2026-07-15\"", "lot 3").doesNotContain("SECRET_B");
        String accueil = mvc.perform(get("/etats").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(accueil).contains("/etats/justificatifs", "Justificatifs de solde");
        String clientsGerant = mvc.perform(get("/clients").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(clientsGerant).contains("/etats/justificatifs/client/pdf?id=" + aminata.getId(), "Justificatif de solde");
        String clientsCaissier = mvc.perform(get("/clients").with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(clientsCaissier).doesNotContain("etats/justificatifs", "Justificatif de solde");
        String fournisseursGerant = mvc.perform(get("/fournisseurs").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(fournisseursGerant).contains("/etats/justificatifs/fournisseur/pdf?id=" + sidi.getId(), "Justificatif de solde");
    }

    /** Revue visuelle : avec JUSTIFICATIFS_SORTIE=<dossier>, écrit les justificatifs du jeu de données pour les convertir en images. */
    @Test
    void ecritLesPdfPourLaRevueVisuelleQuandOnLeDemande() throws Exception {
        String dossier = System.getenv("JUSTIFICATIFS_SORTIE");
        org.junit.jupiter.api.Assumptions.assumeTrue(dossier != null && !dossier.isBlank());
        Path sortie = Path.of(dossier); Files.createDirectories(sortie);
        Files.write(sortie.resolve("client-boubacar-dette.pdf"), exporter("/etats/justificatifs/client/pdf?id=" + boubacar.getId(), GERANT).getContentAsByteArray());
        Files.write(sortie.resolve("client-mariama-avance.pdf"), exporter("/etats/justificatifs/client/pdf?id=" + mariama.getId(), GERANT).getContentAsByteArray());
        Files.write(sortie.resolve("fournisseur-sidi-dette.pdf"), exporter("/etats/justificatifs/fournisseur/pdf?id=" + sidi.getId(), GERANT).getContentAsByteArray());
        Files.write(sortie.resolve("fournisseur-diallo-avance.pdf"), exporter("/etats/justificatifs/fournisseur/pdf?id=" + diallo.getId(), GERANT).getContentAsByteArray());
        Files.write(sortie.resolve("tresorerie-2026-06-30.pdf"), exporter("/etats/justificatifs/tresorerie/pdf?date=2026-06-30", GERANT).getContentAsByteArray());
        Files.write(sortie.resolve("tresorerie-aujourdhui.pdf"), exporter("/etats/justificatifs/tresorerie/pdf", GERANT).getContentAsByteArray());
    }

    // ---- Outils propres aux justificatifs

    /** Solde final du relevé de toute la vie du compte (convention des relevés : positif = le client doit / la boutique doit). */
    private BigDecimal soldeDuReleve(Cote cote, Long id) {
        Etat releve = partenaires.releve(cote, id, new PeriodeEtat(LocalDate.of(2000, 1, 1), LocalDate.of(2026, 12, 31)));
        List<Object> totaux = releve.sections().get(0).getTotaux();
        return (BigDecimal) totaux.get(totaux.size() - 1);
    }

    /** Texte du PDF sur une seule ligne : les phrases longues peuvent passer à la ligne dans le document. */
    static String texteLineaire(byte[] contenu) throws Exception {
        return texte(contenu).replaceAll("\\s+", " ");
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
