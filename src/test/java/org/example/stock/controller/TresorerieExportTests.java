package org.example.stock.controller;

import org.example.stock.support.ComptesDeTest;
import org.apache.poi.ss.usermodel.*;
import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.enums.Role;
import org.example.stock.enums.TypeOperationTresorerie;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.stock.export.ExportTestOutils.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lot 4, tâche B : export du journal de trésorerie (gérant seulement). Deux boutiques, un gérant et un caissier dans la boutique A.
 * Le caissier ne reçoit jamais aucune donnée de trésorerie : 403, comme sur l'écran du journal.
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:export-tresorerie;DB_CLOSE_DELAY=-1", "stock.export.limite-lignes=8"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class TresorerieExportTests {
    private static final String GERANT_A = "tresor-gerant-a@example.test";
    private static final String CAISSIER_A = "tresor-caissier-a@example.test";
    private static final String GERANT_B = "tresor-gerant-b@example.test";
    private static final String FORMULE = "=SUM(1+1)";
    private static final String ENTETE = "Date et heure";

    @Autowired MockMvc mvc;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired MouvementCaisseRepository caisse;
    @Autowired OperationTresorerieRepository operations;

    Boutique boutiqueA, boutiqueB;
    String idP(TypeCompteTresorerie type) { return String.valueOf(org.example.stock.support.ComptesDeTest.de(boutiqueA, type).getId()); }
    Utilisateur awa, bakary, gerantB;
    MouvementCaisse m1, m2, m3, m4, m5, m6, m7;

    @BeforeEach
    void preparer() {
        caisse.deleteAll(); operations.deleteAll(); utilisateurs.deleteAll(); boutiques.deleteAll();
        boutiqueA = boutique("Boutique Étoile A", "12 rue de l'Œuvre, Conakry"); boutiqueB = boutique("Boutique Autre B", null);
        awa = compte(GERANT_A, "Awa Gérante", Role.ADMIN, boutiqueA); bakary = compte(CAISSIER_A, "Bakary Caissier", Role.CAISSIER, boutiqueA);
        gerantB = compte(GERANT_B, "Zoé Autre Boutique", Role.ADMIN, boutiqueB);
        OperationTresorerie depense = operation(boutiqueA, awa);

        m1 = mouvement(boutiqueA, TypeCompteTresorerie.ESPECES, LocalDateTime.of(2026, 10, 1, 8, 0), "5000000", "OUVERTURE", "SOLDE_INITIAL", "Ouverture caisse", awa, null, null);
        m2 = mouvement(boutiqueA, TypeCompteTresorerie.ESPECES, LocalDateTime.of(2026, 10, 5, 10, 0), "1500000", "ENTREE", "VENTE", "Vente FV-000001", awa, null, null);
        m3 = mouvement(boutiqueA, TypeCompteTresorerie.ORANGE_MONEY, LocalDateTime.of(2026, 10, 6, 11, 0), "300000", "ENTREE", "REGLEMENT_VENTE", "Règlement FV-000002", bakary, null, null);
        m4 = mouvement(boutiqueA, TypeCompteTresorerie.ORANGE_MONEY, LocalDateTime.of(2026, 10, 7, 9, 0), "-300000", "SORTIE", "ANNULATION", "Erreur de compte", awa, m3, null);
        m5 = mouvement(boutiqueA, TypeCompteTresorerie.BANQUE, LocalDateTime.of(2026, 10, 10, 12, 0), "-250000.50", "SORTIE", "ACHAT", "Achat FA-000003", null, null, null);
        m6 = mouvement(boutiqueA, TypeCompteTresorerie.ESPECES, LocalDateTime.of(2026, 10, 12, 12, 0), "-100000", "SORTIE", "DEPENSE", FORMULE, awa, null, depense);
        m7 = mouvement(boutiqueA, TypeCompteTresorerie.BANQUE, LocalDateTime.of(2026, 10, 14, 12, 0), null, null, null, null, awa, null, null);
        mouvement(boutiqueB, TypeCompteTresorerie.ESPECES, LocalDateTime.of(2026, 10, 10, 10, 0), "9999999", "ENTREE", "VENTE", "Vente secrète B", gerantB, null, null);
    }

    @Test
    void lExcelDuJournalADeVraisNombresLesSoldesEtLesTotauxDeLaSelection() throws Exception {
        MockHttpServletResponse reponse = mvc.perform(gerant(get("/caisse/export/excel"))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(reponse.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"tresorerie_" + LocalDate.now() + ".xlsx\"");
        assertThat(reponse.getHeader("Cache-Control")).isEqualTo("no-store");
        try (Workbook classeur = classeur(reponse)) {
            Sheet f = classeur.getSheet("Trésorerie");
            assertThat(f.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Boutique Étoile A");
            assertThat(f.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Journal de trésorerie");
            assertThat(f.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Journal complet");
            assertThat(entetes(f, ENTETE)).containsExactly("Date et heure", "Compte", "Sens", "Montant", "Source", "Motif", "Utilisateur", "Annulation");
            List<Row> lignes = lignes(f, ENTETE);
            assertThat(lignes).hasSize(7);
            // Les plus récents d'abord, comme l'historique de l'écran : m7 (14/10) … m1 (01/10).
            assertThat(lignes.stream().map(r -> r.getCell(0).getLocalDateTimeCellValue()).toList()).isSortedAccordingTo(java.util.Comparator.reverseOrder());
            Row r7 = lignes.get(0), r6 = lignes.get(1), r5 = lignes.get(2), r4 = lignes.get(3), r3 = lignes.get(4), r2 = lignes.get(5), r1 = lignes.get(6);
            assertThat(r1.getCell(0).getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 10, 1, 8, 0));
            assertThat(DateUtil.isCellDateFormatted(r1.getCell(0))).isTrue();
            assertThat(List.of(chaine(r1, 1), chaine(r1, 2), chaine(r1, 4), chaine(r1, 5), chaine(r1, 6)))
                    .containsExactly("Caisse", "Entrée", "Solde initial", "Ouverture caisse", "Awa Gérante");
            assertThat(nombre(r1, 3)).isEqualTo(5_000_000d);
            assertThat(r1.getCell(3).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
            assertThat(nombre(r2, 3)).isEqualTo(1_500_000d);
            // Le mouvement annulé porte la mention d'annulation avec son motif ; l'annulation renvoie au mouvement d'origine.
            assertThat(chaine(r3, 7)).isEqualTo("Annulé le 07/10/2026 09:00 · motif : Erreur de compte");
            assertThat(chaine(r4, 7)).isEqualTo("Annulation du mouvement du 06/10/2026 11:00");
            assertThat(List.of(chaine(r4, 1), chaine(r4, 2), chaine(r4, 4))).containsExactly("Orange Money", "Sortie", "ANNULATION");
            assertThat(nombre(r4, 3)).isEqualTo(-300_000d);
            assertThat(chaine(r2, 7)).isNull();
            // Centimes, utilisateur absent = « Système ».
            assertThat(nombre(r5, 3)).isEqualTo(-250_000.5);
            assertThat(r5.getCell(3).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
            assertThat(chaine(r5, 6)).isEqualTo("Système");
            assertThat(chaine(r5, 2)).isEqualTo("Sortie");
            // Dépense : la référence de l'opération figure dans le motif ; une formule saisie reste du texte.
            assertThat(chaine(r6, 5)).isEqualTo(FORMULE + " · Dépense #" + operations.findAll().getFirst().getId());
            assertThat(r6.getCell(5).getCellType()).isEqualTo(CellType.STRING);
            assertThat(r6.getCell(5).getCellStyle().getQuotePrefixed()).isTrue();
            aucuneFormule(f);
            // Montant et source non renseignés.
            assertThat(nombre(r7, 3)).isNull();
            assertThat(List.of(chaine(r7, 2), chaine(r7, 4), chaine(r7, 5))).containsExactly("Non renseigné", "Non renseignée", "Motif non renseigné");
            // Totaux de la sélection : entrées 6 800 000, sorties 650 000,50, net 6 149 999,50.
            Row totaux = totaux(f, ENTETE);
            assertThat(chaine(totaux, 0)).isEqualTo("7 mouvements");
            assertThat(nombre(totaux, 3)).isEqualTo(6_149_999.5);
            String tout = texteDeLaFeuille(f);
            assertThat(tout).contains("entrées 6 800 000 GNF", "sorties 650 000,50 GNF", "variation nette 6 149 999,50 GNF");
            // Soldes de l'écran : caisse 6 400 000, Orange Money 0, banque −250 000,50, total 6 149 999,50 ; journée sans mouvement.
            assertThat(tout).contains("Caisse 6 400 000 GNF", "Orange Money 0 GNF", "Banque -250 000,50 GNF", "Total 6 149 999,50 GNF",
                    "solde à l’ouverture 6 149 999,50 GNF", "entrées 0 GNF · sorties 0 GNF · variation 0 GNF");
            assertThat(tout).doesNotContain("Vente secrète B", "9999999", "Boutique Autre B");
        }
    }

    @Test
    void lePdfDuJournalEstEnPaysageAvecLaBoutiqueLesLignesEtLesSoldes() throws Exception {
        MockHttpServletResponse reponse = mvc.perform(gerant(get("/caisse/export/pdf"))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(reponse.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"tresorerie_" + LocalDate.now() + ".pdf\"");
        String texte = pdf(reponse);
        assertThat(texte).contains("Boutique Étoile A", "12 rue de l'Œuvre, Conakry", "Journal de trésorerie", "Journal complet", "Vente FV-000001", "Ouverture caisse",
                "Solde initial", "Orange Money", "Annulé le 07/10/2026 09:00", "Erreur de compte", "Système", "5 000 000", "250 000,50", "7 mouvements",
                "6 149 999,50", "Soldes actuels de la boutique", "Caisse 6 400 000 GNF", "par Awa Gérante", "Page 1 / 1");
        assertThat(texte).doesNotContain("RFC-COMPTA", "Vente secrète B", "9 999 999");
        assertThat(estPaysage(reponse.getContentAsByteArray())).isTrue();
    }

    @Test
    void chaqueFiltreDuJournalDonneLesMouvementsDeLEcran() throws Exception {
        record Cas(String[] parametres, List<MouvementCaisse> attendus, String entete) { }
        List<Cas> cas = List.of(
                new Cas(new String[]{}, List.of(m7, m6, m5, m4, m3, m2, m1), "Journal complet"),
                new Cas(new String[]{"compte", idP(TypeCompteTresorerie.ESPECES)}, List.of(m6, m2, m1), "Compte : Caisse"),
                new Cas(new String[]{"compte", idP(TypeCompteTresorerie.ORANGE_MONEY)}, List.of(m4, m3), "Compte : Orange Money"),
                new Cas(new String[]{"compte", idP(TypeCompteTresorerie.BANQUE)}, List.of(m7, m5), "Compte : Banque"),
                new Cas(new String[]{"sens", "entree"}, List.of(m3, m2, m1), "Sens : entrées"),
                new Cas(new String[]{"sens", "sortie"}, List.of(m6, m5, m4), "Sens : sorties"),
                new Cas(new String[]{"sens", "inconnu"}, List.of(m7), "Sens : montant non renseigné"),
                new Cas(new String[]{"sens", "neutre"}, List.of(), "Sens : sans variation"),
                new Cas(new String[]{"source", "Solde initial"}, List.of(m1), "Source : Solde initial"),
                new Cas(new String[]{"source", "VENTE"}, List.of(m2), "Source : VENTE"),
                new Cas(new String[]{"source", "Non renseignée"}, List.of(m7), "Source : Non renseignée"),
                new Cas(new String[]{"du", "2026-10-06", "au", "2026-10-10"}, List.of(m5, m4, m3), "Période du 06/10/2026 au 10/10/2026"),
                new Cas(new String[]{"du", "2026-10-12"}, List.of(m7, m6), "Depuis le 12/10/2026"),
                new Cas(new String[]{"au", "2026-10-01"}, List.of(m1), "Jusqu'au 01/10/2026"),
                new Cas(new String[]{"compte", idP(TypeCompteTresorerie.ESPECES), "sens", "sortie", "du", "2026-10-01", "au", "2026-10-31"}, List.of(m6),
                        "Période du 01/10/2026 au 31/10/2026 · Compte : Caisse · Sens : sorties"),
                // La recherche de l'écran porte sur tout le texte de la ligne, mention d'annulation comprise.
                new Cas(new String[]{"q", "erreur"}, List.of(m4, m3), "Recherche : « erreur »"),
                new Cas(new String[]{"q", "AWA gerante", "compte", idP(TypeCompteTresorerie.ORANGE_MONEY)}, List.of(m4), "Compte : Orange Money · Recherche : « AWA gerante »"),
                new Cas(new String[]{"q", "250000.50"}, List.of(m5), "Recherche : « 250000.50 »"));
        for (Cas c : cas) {
            String nom = String.join("=", c.parametres());
            try (Workbook classeur = classeur(mvc.perform(gerant(avecParametres(get("/caisse/export/excel"), c.parametres()))).andExpect(status().isOk()).andReturn().getResponse())) {
                Sheet f = classeur.getSheetAt(0);
                assertThat(lignes(f, ENTETE).stream().map(r -> r.getCell(0) == null ? null : r.getCell(0).getLocalDateTimeCellValue()).toList()).as("Excel " + nom)
                        .containsExactlyElementsOf(c.attendus().stream().map(MouvementCaisse::getDateMouvement).toList());
                assertThat(f.getRow(2).getCell(0).getStringCellValue()).as("en-tête " + nom).isEqualTo(c.entete());
            }
            String pdf = pdf(mvc.perform(gerant(avecParametres(get("/caisse/export/pdf"), c.parametres()))).andExpect(status().isOk()).andReturn().getResponse());
            assertThat(pdf).as("PDF " + nom).contains(c.entete());
            for (MouvementCaisse m : List.of(m1, m2, m5)) {
                String motif = m.getMotif();
                if (c.attendus().contains(m)) assertThat(pdf).as("PDF " + nom).contains(motif); else assertThat(pdf).as("PDF " + nom).doesNotContain(motif);
            }
        }
    }

    @Test
    void lePeriodeFigureDansLeNomDuFichier() throws Exception {
        assertThat(mvc.perform(gerant(get("/caisse/export/excel").param("du", "2026-10-01").param("au", "2026-10-31"))).andReturn().getResponse()
                .getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"tresorerie_2026-10-01_2026-10-31.xlsx\"");
        assertThat(mvc.perform(gerant(get("/caisse/export/pdf").param("du", "2026-10-01").param("au", "2026-10-31"))).andReturn().getResponse()
                .getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"tresorerie_2026-10-01_2026-10-31.pdf\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sansFiltreLExportContientLesMemesMouvementsQueLEcranDuJournal() throws Exception {
        List<MouvementCaisse> ecran = (List<MouvementCaisse>) mvc.perform(gerant(get("/caisse/journal"))).andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("mouvements");
        try (Workbook classeur = classeur(mvc.perform(gerant(get("/caisse/export/excel"))).andReturn().getResponse())) {
            assertThat(lignes(classeur.getSheetAt(0), ENTETE)).hasSameSizeAs(ecran).hasSize(7);
        }
        assertThat(ecran).extracting(MouvementCaisse::getId).containsExactlyInAnyOrder(m1.getId(), m2.getId(), m3.getId(), m4.getId(), m5.getId(), m6.getId(), m7.getId());
    }

    @Test
    void leCaissierNeRecoitAucuneDonneeDeTresorerie() throws Exception {
        for (String url : List.of("/caisse/export/excel", "/caisse/export/pdf")) {
            MvcResult resultat = mvc.perform(caissier(get(url))).andExpect(status().isForbidden()).andReturn();
            assertThat(resultat.getResponse().getContentType()).isNotIn("application/pdf", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            assertThat(resultat.getResponse().getContentAsString()).doesNotContain("Vente FV-000001", "5000000", "Ouverture caisse");
            mvc.perform(caissier(get(url).param("compte", idP(TypeCompteTresorerie.ESPECES)).param("du", "2026-10-01"))).andExpect(status().isForbidden());
        }
        // Comme le journal lui-même.
        mvc.perform(caissier(get("/caisse/journal"))).andExpect(status().isForbidden());
    }

    @Test
    void laListeSansConnexionRedirigeEtLesAutresVerbesSontRefuses() throws Exception {
        for (String url : List.of("/caisse/export/excel", "/caisse/export/pdf")) {
            assertThat(mvc.perform(get(url)).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl()).endsWith("/login");
            mvc.perform(post(url).with(user(GERANT_A).roles("ADMIN")).with(csrf())).andExpect(status().isMethodNotAllowed());
        }
        long avant = caisse.count();
        mvc.perform(gerant(get("/caisse/export/excel"))).andExpect(status().isOk());
        mvc.perform(gerant(get("/caisse/export/pdf"))).andExpect(status().isOk());
        assertThat(caisse.count()).isEqualTo(avant);
    }

    @Test
    void uneAutreBoutiqueNeVoitQueSesMouvements() throws Exception {
        try (Workbook classeur = classeur(mvc.perform(avec(get("/caisse/export/excel"), GERANT_B, "ADMIN")).andReturn().getResponse())) {
            Sheet f = classeur.getSheetAt(0);
            assertThat(lignes(f, ENTETE)).hasSize(1);
            assertThat(f.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Boutique Autre B");
            String tout = texteDeLaFeuille(f);
            assertThat(tout).contains("Vente secrète B", "Caisse 9 999 999 GNF").doesNotContain("Ouverture caisse", "Étoile", "Awa", "5000000", "Erreur de compte");
        }
        String pdf = pdf(mvc.perform(avec(get("/caisse/export/pdf"), GERANT_B, "ADMIN")).andReturn().getResponse());
        assertThat(pdf).contains("Boutique Autre B", "Vente secrète B").doesNotContain("Ouverture caisse", "Vente FV-000001", "Étoile");
    }

    @Test
    void lesParametresInvalidesDuJournalSontRefuses() throws Exception {
        for (String[] invalide : new String[][]{{"compte", "TIROIR"}, {"sens", "partout"}, {"du", "abc"}, {"du", "2026-02-30"}, {"au", "1999-01-01"},
                {"du", "2026-11-30", "au", "2026-11-01"}, {"q", "x".repeat(101)}, {"source", "x".repeat(101)}}) {
            mvc.perform(gerant(avecParametres(get("/caisse/export/excel"), invalide))).andExpect(status().isBadRequest());
            mvc.perform(gerant(avecParametres(get("/caisse/export/pdf"), invalide))).andExpect(status().isBadRequest());
        }
        mvc.perform(gerant(get("/caisse/export/excel").param("compte", "CAISSE", "BANQUE"))).andExpect(status().isBadRequest());
        mvc.perform(gerant(get("/caisse/export/excel").param("q", "a", "b"))).andExpect(status().isBadRequest());
    }

    @Test
    void auDelaDeLaLimiteLeJournalRevientALaPageAvecLeMessage() throws Exception {
        for (int i = 0; i < 2; i++) mouvement(boutiqueA, TypeCompteTresorerie.ESPECES, LocalDateTime.of(2026, 11, 1 + i, 9, 0), "1000", "ENTREE", "VENTE", "Vente " + i, awa, null, null);
        for (String format : new String[]{"excel", "pdf"}) {
            MvcResult resultat = mvc.perform(gerant(get("/caisse/export/" + format))).andExpect(status().is3xxRedirection()).andReturn();
            assertThat(resultat.getResponse().getRedirectedUrl()).isEqualTo("/caisse/journal");
            assertThat(resultat.getFlashMap().get("error").toString()).contains("dépasse 8 lignes", "Réduisez la période");
        }
        // Un filtre qui ramène sous la limite exporte normalement.
        mvc.perform(gerant(get("/caisse/export/excel").param("compte", idP(TypeCompteTresorerie.BANQUE)))).andExpect(status().isOk());
        assertThat(mvc.perform(gerant(get("/caisse/journal")).flashAttr("error", "Cet export dépasse 8 lignes.")).andReturn().getResponse().getContentAsString()).isNotNull();
    }

    @Test
    void lePageDuJournalPorteLesBoutonsEtLeursChampsDeFiltre() throws Exception {
        String page = mvc.perform(gerant(get("/caisse/journal"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("data-export-liste=\"excel\"", "data-export-liste=\"pdf\"", "href=\"/caisse/export/excel\"", "href=\"/caisse/export/pdf\"", "js/export-liste.js",
                "data-export-champs=\"q=#caisseSearch;compte=#caisseCompte;source=#caisseSource@libelle;sens=#caisseDirection;du=#caisseDateFrom;au=#caisseDateTo\"");
    }

    /** Génère les PDF et Excel de démonstration si EXPORT_DEMO_DIR est défini (inspection visuelle, voir docs/lot-4.md). */
    @Test
    void genererLaDemonstration() throws Exception {
        String dossier = System.getenv("EXPORT_DEMO_DIR");
        org.junit.jupiter.api.Assumptions.assumeTrue(dossier != null && !dossier.isBlank());
        java.nio.file.Path cible = java.nio.file.Path.of(dossier);
        java.nio.file.Files.createDirectories(cible);
        for (String format : new String[]{"pdf", "excel"}) {
            byte[] octets = mvc.perform(gerant(get("/caisse/export/" + format))).andReturn().getResponse().getContentAsByteArray();
            java.nio.file.Files.write(cible.resolve("demo-tresorerie" + (format.equals("pdf") ? ".pdf" : ".xlsx")), octets);
        }
    }

    // ----- Outils -----

    private MockHttpServletRequestBuilder gerant(MockHttpServletRequestBuilder requete) { return avec(requete, GERANT_A, "ADMIN"); }
    private MockHttpServletRequestBuilder caissier(MockHttpServletRequestBuilder requete) { return avec(requete, CAISSIER_A, "CAISSIER"); }

    private Boutique boutique(String nom, String adresse) {
        Boutique b = new Boutique(); b.setNom(nom); b.setAdresse(adresse); return boutiques.saveAndFlush(b);
    }

    private Utilisateur compte(String email, String nom, Role role, Boutique boutique) {
        Utilisateur u = new Utilisateur(); u.setNom(nom); u.setEmail(email); u.setRole(role); u.setBoutique(boutique); u.setActif(true);
        return utilisateurs.saveAndFlush(u);
    }

    private OperationTresorerie operation(Boutique boutique, Utilisateur auteur) {
        OperationTresorerie o = new OperationTresorerie(); o.setBoutique(boutique); o.setUtilisateur(auteur); o.setJeton(java.util.UUID.randomUUID().toString());
        o.setType(TypeOperationTresorerie.DEPENSE); o.setCompteSource(ComptesDeTest.de(boutique, TypeCompteTresorerie.ESPECES)); o.setMontant(new BigDecimal("100000")); o.setMotif("Loyer");
        o.setDateOperation(LocalDateTime.of(2026, 10, 12, 12, 0));
        return operations.saveAndFlush(o);
    }

    private MouvementCaisse mouvement(Boutique boutique, TypeCompteTresorerie compte, LocalDateTime date, String montant, String type, String source, String motif,
                                      Utilisateur auteur, MouvementCaisse annule, OperationTresorerie operation) {
        MouvementCaisse m = new MouvementCaisse(); m.setBoutique(boutique); m.setCompte(ComptesDeTest.de(boutique, compte)); m.setDateMouvement(date);
        m.setMontant(montant == null ? null : new BigDecimal(montant)); m.setType(type); m.setSource(source); m.setMotif(motif); m.setUtilisateur(auteur);
        m.setAnnulationDe(annule); m.setOperationTresorerie(operation);
        return caisse.saveAndFlush(m);
    }
}
