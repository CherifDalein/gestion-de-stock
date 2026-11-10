package org.example.stock.recu;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.example.stock.enums.CompteTresorerie;
import org.example.stock.enums.FormatRecu;
import org.example.stock.enums.Role;
import org.example.stock.enums.SensSolde;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.example.stock.service.AchatService;
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

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Lot 4, tâche E : reçus PDF (vente, règlement de vente, d'achat, de solde de départ), formats A4 et ticket 80 mm,
 * mention d'annulation, absence de coût et de marge, droits, isolation entre boutiques et bandeau « Imprimer le reçu ».
 * Si {@code RECU_DEMO_DIR} est défini, les PDF produits y sont aussi écrits (contrôle visuel).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:recus;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class RecuTests {
    static final String GERANT = "recu-gerant@example.test", CAISSIER = "recu-caissier@example.test", AUTRE = "recu-autre@example.test";
    static final String LONGUE = "Riz parfumé de première qualité conditionné en sac de cinquante kilogrammes importé directement d'Asie";
    static final double LARGEUR_TICKET_PT = 80 / 25.4 * 72;

    @Autowired MockMvc mvc;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired CategorieRepository categories;
    @Autowired ProduitRepository produits;
    @Autowired ClientRepository clients;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired VenteRepository ventes;
    @Autowired AchatRepository achats;
    @Autowired RetourRepository retours;
    @Autowired MouvementCaisseRepository caisse;
    @Autowired OperationCreationRepository operations;
    @Autowired VenteService venteService;
    @Autowired AchatService achatService;

    Boutique a, b;
    Client client, clientB;
    Fournisseur fournisseur;
    Produit produit;
    Vente vente;
    Long reglement1, reglement2;

    @BeforeEach
    void preparer() {
        operations.deleteAll(); retours.deleteAll(); caisse.deleteAll(); ventes.deleteAll(); achats.deleteAll(); produits.deleteAll();
        categories.deleteAll(); fournisseurs.deleteAll(); clients.deleteAll(); utilisateurs.deleteAll(); boutiques.deleteAll();
        a = boutique("Boutique Étoile", "12 rue du Commerce, Conakry"); b = boutique("Boutique Autre", null);
        compte(GERANT, "Awa Gérante", Role.ADMIN, a); compte(CAISSIER, "Bakary Caissier", Role.CAISSIER, a); compte(AUTRE, "Zoé Autre", Role.ADMIN, b);
        client = client(a, "Mamadou Barry"); clientB = client(b, "Client Secret B");
        fournisseur = fournisseur(a, "Fournisseur Diallo");
        produit = produit(a, fournisseur, "REF-RIZ", LONGUE);
        // Vente : 3 × 10 000 = 30 000 ; versé 10 000 ; règlements de 5 000 (banque) puis 15 000 (caisse).
        vente = authentifie(GERANT, "ADMIN", () -> {
            Vente v = new Vente(); v.setBoutique(a); Client c = new Client(); c.setId(client.getId()); v.setClient(c); v.setMontantVerse(new BigDecimal("10000"));
            Produit p = new Produit(); p.setId(produit.getId());
            DetailVente l = new DetailVente(); l.setProduit(p); l.setQuantite(3); v.getLignes().add(l);
            return venteService.effectuerVente(v);
        });
        reglement1 = authentifie(GERANT, "ADMIN", () -> venteService.reglerVente(vente.getId(), new BigDecimal("5000"), new BigDecimal("10000"), CompteTresorerie.BANQUE, null));
        reglement2 = authentifie(GERANT, "ADMIN", () -> venteService.reglerVente(vente.getId(), new BigDecimal("15000"), new BigDecimal("15000"), CompteTresorerie.CAISSE, null));
    }

    @AfterEach
    void nettoyer() { SecurityContextHolder.clearContext(); }

    // ---- Vente ------------------------------------------------------------------------------------------------

    @Test
    void recuDeVenteA4ContientLesInformationsDeLaFacture() throws Exception {
        String texte = texte(pdf(gerant(get("/ventes/recu/" + vente.getId()))));
        assertThat(texte).contains("Boutique Étoile", "12 rue du Commerce", "Reçu de vente", vente.getNumero(), "Awa Gérante", "Mamadou Barry",
                "Quantité", "Prix unitaire", "10 000", "30 000", "Total de la vente", "Reste à payer", "Merci de votre visite");
        assertThat(sansEspaces(texte)).contains(sansEspaces(LONGUE));
        // Versé = 10 000 + 5 000 + 15 000 = 30 000, reste 0.
        assertThat(texte).containsPattern("Montant versé\\s+30 000 GNF").containsPattern("Reste à payer\\s+0 GNF");
        aucunCoutNiMarge(texte);
        exporter("vente-a4", pdf(gerant(get("/ventes/recu/" + vente.getId()))));
    }

    @Test
    void recuDeVenteEnTicketTientDansQuatreVingtsMillimetres() throws Exception {
        choisir(FormatRecu.TICKET_80);
        byte[] octets = pdf(gerant(get("/ventes/recu/" + vente.getId())));
        verifierTicket(octets, LONGUE);
        String texte = texte(octets);
        assertThat(texte).contains("REÇU DE VENTE", vente.getNumero(), "Awa Gérante", "Mamadou Barry", "3 × 10 000", "30 000 GNF", "Reste à payer");
        aucunCoutNiMarge(texte);
        exporter("vente-ticket", octets);
    }

    @Test
    void unTicketAvecUnMotSansEspaceTresLongNeDebordePas() throws Exception {
        String sansEspace = "REFERENCE-INTERMINABLE-" + "0123456789".repeat(8);
        Produit p = produit(a, fournisseur, "REF-LONG", sansEspace);
        Vente v = authentifie(GERANT, "ADMIN", () -> {
            Vente n = new Vente(); n.setBoutique(a); n.setMontantVerse(new BigDecimal("10000"));
            Produit q = new Produit(); q.setId(p.getId());
            DetailVente l = new DetailVente(); l.setProduit(q); l.setQuantite(1); n.getLignes().add(l);
            return venteService.effectuerVente(n);
        });
        choisir(FormatRecu.TICKET_80);
        byte[] octets = pdf(gerant(get("/ventes/recu/" + v.getId())));
        verifierTicket(octets, sansEspace);
        assertThat(texte(octets)).contains("Client de passage");
        exporter("vente-ticket-mot-long", octets);
    }

    @Test
    void recuDeVenteAvecRetourMontreLesRetoursEtLeNet() throws Exception {
        Vente v = ventes.findById(vente.getId()).orElseThrow();
        v.setMontantRetours(new BigDecimal("10000")); ventes.saveAndFlush(v);
        for (FormatRecu format : FormatRecu.values()) {
            choisir(format);
            String texte = texte(pdf(gerant(get("/ventes/recu/" + vente.getId()))));
            assertThat(texte).contains("Retours", "Total net").containsPattern("Total net\\s+20 000 GNF");
        }
    }

    @Test
    void leCaissierPeutImprimerUnRecuDeVente() throws Exception {
        assertThat(texte(pdf(caissier(get("/ventes/recu/" + vente.getId()))))).contains(vente.getNumero());
        assertThat(texte(pdf(caissier(get("/ventes/regler/" + vente.getId() + "/recu/" + reglement1))))).contains("Reçu de règlement");
    }

    // ---- Règlement de vente ------------------------------------------------------------------------------------

    @Test
    void recuDeReglementMontreLeResteApresCeReglementEtNonLeResteActuel() throws Exception {
        String premier = texte(pdf(gerant(get("/ventes/regler/" + vente.getId() + "/recu/" + reglement1))));
        assertThat(premier).contains("Reçu de règlement", "Règlement d'une vente", vente.getNumero(), "Mamadou Barry", "Awa Gérante", "Banque", "Merci pour votre règlement");
        assertThat(premier).containsPattern("Montant réglé\\s+5 000 GNF").containsPattern("Reste à régler après ce règlement\\s+15 000 GNF");
        assertThat(premier).doesNotContain("ANNULÉ");
        String second = texte(pdf(gerant(get("/ventes/regler/" + vente.getId() + "/recu/" + reglement2))));
        assertThat(second).containsPattern("Montant réglé\\s+15 000 GNF").containsPattern("\\(soldé\\)\\s+0 GNF");
        aucunCoutNiMarge(premier + second);
        exporter("reglement-vente-a4", pdf(gerant(get("/ventes/regler/" + vente.getId() + "/recu/" + reglement1))));
    }

    @Test
    void recuDeReglementEnTicket() throws Exception {
        choisir(FormatRecu.TICKET_80);
        byte[] octets = pdf(gerant(get("/ventes/regler/" + vente.getId() + "/recu/" + reglement1)));
        verifierTicket(octets, "Banque");
        assertThat(texte(octets)).contains("REÇU DE RÈGLEMENT", "5 000 GNF", "15 000 GNF", "Awa Gérante");
        exporter("reglement-vente-ticket", octets);
    }

    @Test
    void unReglementAnnuleEnsuiteLePorteSurLeRecu() throws Exception {
        mvc.perform(gerant(post("/ventes/regler/" + vente.getId() + "/annuler/" + reglement2).param("motif", "Erreur de saisie").with(csrf())))
                .andExpect(status().is3xxRedirection());
        for (FormatRecu format : FormatRecu.values()) {
            choisir(format);
            byte[] octets = pdf(gerant(get("/ventes/regler/" + vente.getId() + "/recu/" + reglement2)));
            String texte = texte(octets);
            assertThat(texte).containsPattern("ANNULÉ le \\d{2}/\\d{2}/\\d{4}").contains("Erreur de saisie", "Awa Gérante", "ne vaut plus justificatif");
            if (format == FormatRecu.TICKET_80) verifierTicket(octets, "Erreur de saisie");
            exporter("reglement-annule-" + (format == FormatRecu.A4 ? "a4" : "ticket"), octets);
        }
        // Le règlement non annulé n'est pas marqué.
        assertThat(texte(pdf(gerant(get("/ventes/regler/" + vente.getId() + "/recu/" + reglement1))))).doesNotContain("ANNULÉ");
    }

    @Test
    void uneAnnulationNEstPasUnReglementAImprimer() throws Exception {
        mvc.perform(gerant(post("/ventes/regler/" + vente.getId() + "/annuler/" + reglement2).param("motif", "Erreur").with(csrf()))).andExpect(status().is3xxRedirection());
        Long annulation = caisse.findAll().stream().filter(m -> "ANNULATION".equals(m.getSource())).findFirst().orElseThrow().getId();
        mvc.perform(gerant(get("/ventes/regler/" + vente.getId() + "/recu/" + annulation))).andExpect(status().isNotFound());
    }

    // ---- Achat -------------------------------------------------------------------------------------------------

    @Test
    void recuDePaiementDAchatReserveAuGerant() throws Exception {
        Achat achat = authentifie(GERANT, "ADMIN", () -> {
            Achat n = new Achat(); n.setBoutique(a); Fournisseur f = new Fournisseur(); f.setId(fournisseur.getId()); n.setFournisseur(f); n.setMontantVerse(new BigDecimal("5000"));
            Produit p = new Produit(); p.setId(produit.getId());
            DetailAchat l = new DetailAchat(); l.setProduit(p); l.setQuantite(2);
            l.setPrixAchatUnitaire(new BigDecimal("10000.00")); l.setPrixVenteUnitaire(new BigDecimal("12000.00")); n.getLignes().add(l);
            return achatService.enregistrerAchat(n);
        });
        Long mouvement = authentifie(GERANT, "ADMIN", () -> achatService.reglerAchat(achat.getId(), new BigDecimal("5000"), new BigDecimal("5000"), CompteTresorerie.CAISSE, null));
        String url = "/achats/regler/" + achat.getId() + "/recu/" + mouvement;
        for (FormatRecu format : FormatRecu.values()) {
            choisir(format);
            String texte = texte(pdf(gerant(get(url))));
            assertThat(texte).contains("Fournisseur Diallo", "Compte débité", "Awa Gérante").containsPattern("5 000 GNF").containsPattern("10 000 GNF");
            aucunCoutNiMarge(texte);
        }
        mvc.perform(caissier(get(url))).andExpect(status().isForbidden());
        mvc.perform(autre(get(url))).andExpect(status().isNotFound());
    }

    // ---- Solde de départ ---------------------------------------------------------------------------------------

    @Test
    void recusDeReglementDeSoldeDeDepart() throws Exception {
        Client c = clients.findById(client.getId()).orElseThrow();
        c.setSoldeInitial(new BigDecimal("100000.00")); c.setEcheanceSoldeInitial(LocalDate.now().plusDays(30)); clients.saveAndFlush(c);
        Fournisseur f = fournisseurs.findById(fournisseur.getId()).orElseThrow();
        f.setSoldeInitial(new BigDecimal("-80000.00")); f.setEcheanceSoldeInitial(LocalDate.now().plusDays(30)); fournisseurs.saveAndFlush(f);
        mvc.perform(gerant(post("/clients/solde/regler/" + client.getId()).param("montant", "30000").param("reglementAttendu", "0").param("comptePaiement", "CAISSE").with(csrf())))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("recuUrl"));
        mvc.perform(gerant(post("/fournisseurs/solde/regler/" + fournisseur.getId()).param("montant", "20000").param("reglementAttendu", "0").param("comptePaiement", "BANQUE").with(csrf())))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("recuUrl"));
        Long mc = mouvement("SOLDE_CLIENT"), mf = mouvement("SOLDE_FOURNISSEUR");
        String urlC = "/clients/solde/regler/" + client.getId() + "/recu/" + mc, urlF = "/fournisseurs/solde/regler/" + fournisseur.getId() + "/recu/" + mf;
        for (FormatRecu format : FormatRecu.values()) {
            choisir(format);
            String tc = texte(pdf(gerant(get(urlC))));
            assertThat(tc).contains("Mamadou Barry", "solde de départ").containsPattern("30 000 GNF").containsPattern("70 000 GNF");
            String tf = texte(pdf(gerant(get(urlF))));
            assertThat(tf).contains("Fournisseur Diallo").containsPattern("20 000 GNF").containsPattern("60 000 GNF");
        }
        // Le caissier suit les droits de ses pages : clients oui, fournisseurs non.
        mvc.perform(caissier(get(urlC))).andExpect(status().isOk());
        mvc.perform(caissier(get(urlF))).andExpect(status().isForbidden());
        mvc.perform(autre(get(urlC))).andExpect(status().isNotFound());
        mvc.perform(autre(get(urlF))).andExpect(status().isNotFound());
    }

    // ---- Isolation, droits, erreurs ----------------------------------------------------------------------------

    @Test
    void uneAutreBoutiqueNeVoitPasLesRecus() throws Exception {
        mvc.perform(autre(get("/ventes/recu/" + vente.getId()))).andExpect(status().isNotFound());
        mvc.perform(autre(get("/ventes/regler/" + vente.getId() + "/recu/" + reglement1))).andExpect(status().isNotFound());
        // Un mouvement d'une autre vente ne s'imprime pas sous cette vente.
        Vente v2 = authentifie(GERANT, "ADMIN", () -> {
            Vente n = new Vente(); n.setBoutique(a); n.setMontantVerse(BigDecimal.ZERO);
            Produit p = new Produit(); p.setId(produit.getId());
            DetailVente l = new DetailVente(); l.setProduit(p); l.setQuantite(1); n.getLignes().add(l);
            return venteService.effectuerVente(n);
        });
        mvc.perform(gerant(get("/ventes/regler/" + v2.getId() + "/recu/" + reglement1))).andExpect(status().isNotFound());
        mvc.perform(gerant(get("/ventes/recu/999999"))).andExpect(status().isNotFound());
    }

    @Test
    void sansConnexionLesRecusRedirigentVersLaConnexion() throws Exception {
        mvc.perform(get("/ventes/recu/" + vente.getId())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/achats/regler/1/recu/1")).andExpect(status().is3xxRedirection());
    }

    @Test
    void laReponseEstUnPdfEnLigneJamaisMisEnCache() throws Exception {
        MockHttpServletResponse r = mvc.perform(gerant(get("/ventes/recu/" + vente.getId()))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(r.getContentType()).isEqualTo("application/pdf");
        assertThat(r.getHeader("Content-Disposition")).startsWith("inline");
        assertThat(r.getHeader("Cache-Control")).contains("no-store");
        assertThat(new String(r.getContentAsByteArray(), 0, 4)).isEqualTo("%PDF");
    }

    // ---- Bandeau « Opération enregistrée » ---------------------------------------------------------------------

    @Test
    void apresUnReglementLeBandeauEstProposeSeulementSiLeReglageLeVeut() throws Exception {
        Vente v = ventes.findById(vente.getId()).orElseThrow();
        // Rouvre une créance : annule le règlement 2 (15 000).
        mvc.perform(gerant(post("/ventes/regler/" + v.getId() + "/annuler/" + reglement2).param("motif", "Test").with(csrf()))).andExpect(status().is3xxRedirection());
        Vente ouverte = ventes.findById(vente.getId()).orElseThrow();
        mvc.perform(gerant(post("/ventes/regler/" + v.getId()).param("montant", "1000").param("montantVerseAttendu", ouverte.getMontantVerse().toPlainString())
                .param("comptePaiement", "CAISSE").with(csrf()))).andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("recuUrl", org.hamcrest.Matchers.startsWith("/ventes/regler/" + v.getId() + "/recu/")));
        boutique(false);
        Vente ouverte2 = ventes.findById(vente.getId()).orElseThrow();
        mvc.perform(gerant(post("/ventes/regler/" + v.getId()).param("montant", "1000").param("montantVerseAttendu", ouverte2.getMontantVerse().toPlainString())
                .param("comptePaiement", "CAISSE").with(csrf()))).andExpect(status().is3xxRedirection()).andExpect(flash().attributeCount(1));
    }

    @Test
    void apresUneVenteLeBandeauPointeVersLeRecuSiLeReglageLeVeut() throws Exception {
        String url = enregistrerUneVente();
        assertThat(url).startsWith("/ventes/recu/");
        mvc.perform(gerant(get(url))).andExpect(status().isOk());
        boutique(false);
        assertThat(enregistrerUneVente()).isNull();
    }

    @Test
    void laPageAfficheLeBandeauEtLeBoutonRecuDeLaListe() throws Exception {
        mvc.perform(gerant(get("/ventes").flashAttr("recuUrl", "/ventes/recu/" + vente.getId()))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Opération enregistrée")))
                .andExpect(content().string(containsString("Imprimer le reçu")))
                .andExpect(content().string(containsString("href=\"/ventes/recu/" + vente.getId() + "\"")))
                .andExpect(content().string(containsString("target=\"_blank\"")));
        // Sans opération récente : pas de bandeau, mais le bouton « Reçu » de chaque ligne reste là.
        mvc.perform(gerant(get("/ventes"))).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Opération enregistrée"))))
                .andExpect(content().string(containsString("href=\"/ventes/recu/" + vente.getId() + "\"")));
        mvc.perform(caissier(get("/ventes"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/ventes/recu/" + vente.getId() + "\"")));
    }

    @Test
    void lHistoriqueDesReglementsOffreUnLienVersChaqueRecu() throws Exception {
        mvc.perform(gerant(get("/ventes/regler/" + vente.getId()))).andExpect(status().isOk())
                .andExpect(content().string(containsString("/ventes/regler/" + vente.getId() + "/recu/" + reglement1)))
                .andExpect(content().string(containsString("/ventes/regler/" + vente.getId() + "/recu/" + reglement2)));
    }

    // ---- Outils ------------------------------------------------------------------------------------------------

    private String enregistrerUneVente() throws Exception {
        var ecran = mvc.perform(gerant(get("/ventes/nouveau"))).andExpect(status().isOk()).andReturn();
        String jeton = (String) ecran.getModelAndView().getModel().get("jetonCreation");
        var resultat = mvc.perform(gerant(post("/ventes/enregistrer").param("lignes[0].produit.id", "" + produit.getId()).param("lignes[0].quantite", "1")
                .param("montantVerse", "10000").param("jetonCreation", jeton).with(csrf()))).andExpect(status().is3xxRedirection()).andReturn();
        assertThat(resultat.getFlashMap().containsKey("success")).isTrue();
        return (String) resultat.getFlashMap().get("recuUrl");
    }

    private void aucunCoutNiMarge(String texte) {
        assertThat(texte).doesNotContainIgnoringCase("coût").doesNotContainIgnoringCase("marge").doesNotContainIgnoringCase("bénéfice")
                .doesNotContainIgnoringCase("prix d'achat").doesNotContain("3 333");
    }

    /** Largeur exacte de 80 mm, une seule page, aucun caractère hors de la zone imprimable, désignation complète. */
    private void verifierTicket(byte[] octets, String attendu) throws Exception {
        try (PDDocument document = Loader.loadPDF(octets)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            var page = document.getPage(0).getMediaBox();
            assertThat((double) page.getWidth()).isBetween(LARGEUR_TICKET_PT - 0.5, LARGEUR_TICKET_PT + 0.5);
            assertThat((double) page.getHeight()).isLessThan(400 / 25.4 * 72);
            StringBuilder petits = new StringBuilder(); List<String> debordements = new ArrayList<>(); List<Float> tailles = new ArrayList<>();
            new PDFTextStripper() {
                @Override protected void processTextPosition(TextPosition t) {
                    super.processTextPosition(t);
                    float taille = t.getFontSize() * t.getTextMatrix().getScalingFactorY(); tailles.add(taille); if (taille < 7.9) petits.append(t.getUnicode());
                    if (t.getXDirAdj() < 0 || t.getXDirAdj() + t.getWidthDirAdj() > page.getWidth() + 0.01) debordements.add(t.getUnicode());
                    if (t.getYDirAdj() > page.getHeight()) debordements.add("bas:" + t.getUnicode());
                }
            }.getText(document);
            assertThat(debordements).as("caractères hors de la page").isEmpty();
            assertThat(tailles.stream().mapToDouble(Float::doubleValue).min().orElse(0)).as("taille de police minimale : " + petits).isGreaterThanOrEqualTo(7.9);
        }
        assertThat(sansEspaces(texte(octets))).contains(sansEspaces(attendu));
    }

    private static byte[] pdf(MockHttpServletRequestBuilder requete, MockMvc mvc) throws Exception {
        MockHttpServletResponse r = mvc.perform(requete).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(r.getContentType()).isEqualTo("application/pdf");
        return r.getContentAsByteArray();
    }

    private byte[] pdf(MockHttpServletRequestBuilder requete) throws Exception { return pdf(requete, mvc); }

    private static String texte(byte[] octets) throws Exception {
        try (PDDocument document = Loader.loadPDF(octets)) {
            return new PDFTextStripper().getText(document).replace('\u00A0', ' ').replace('\u202F', ' ').replaceAll("\\s+", " ");
        }
    }

    private static String sansEspaces(String s) { return s.replaceAll("[\\s\\u00A0\\u202F\\u200B]+", ""); }

    private void exporter(String nom, byte[] octets) throws Exception {
        String dossier = System.getenv("RECU_DEMO_DIR");
        if (dossier == null || dossier.isBlank()) return;
        Files.createDirectories(Path.of(dossier));
        Files.write(Path.of(dossier, nom + ".pdf"), octets);
    }

    private Long mouvement(String source) {
        return caisse.findAll().stream().filter(m -> source.equals(m.getSource())).map(MouvementCaisse::getId).findFirst().orElseThrow();
    }

    private void choisir(FormatRecu format) {
        Boutique x = boutiques.findById(a.getId()).orElseThrow(); x.setFormatRecu(format); boutiques.saveAndFlush(x);
    }

    private void boutique(boolean proposer) {
        Boutique x = boutiques.findById(a.getId()).orElseThrow(); x.setProposerImpressionRecu(proposer); boutiques.saveAndFlush(x);
    }

    private MockHttpServletRequestBuilder gerant(MockHttpServletRequestBuilder r) { return r.with(user(GERANT).roles("ADMIN")); }
    private MockHttpServletRequestBuilder caissier(MockHttpServletRequestBuilder r) { return r.with(user(CAISSIER).roles("CAISSIER")); }
    private MockHttpServletRequestBuilder autre(MockHttpServletRequestBuilder r) { return r.with(user(AUTRE).roles("ADMIN")); }

    private Boutique boutique(String nom, String adresse) { Boutique x = new Boutique(); x.setNom(nom); x.setAdresse(adresse); return boutiques.saveAndFlush(x); }

    private void compte(String email, String nom, Role role, Boutique boutique) {
        Utilisateur u = new Utilisateur(); u.setBoutique(boutique); u.setEmail(email); u.setNom(nom); u.setRole(role); u.setActif(true); utilisateurs.saveAndFlush(u);
    }

    private Client client(Boutique boutique, String nom) { Client c = new Client(); c.setBoutique(boutique); c.setNom(nom); return clients.saveAndFlush(c); }

    private Fournisseur fournisseur(Boutique boutique, String nom) { Fournisseur f = new Fournisseur(); f.setBoutique(boutique); f.setNom(nom); return fournisseurs.saveAndFlush(f); }

    private Produit produit(Boutique boutique, Fournisseur f, String reference, String nom) {
        Categorie categorie = new Categorie(); categorie.setBoutique(boutique); categorie.setNom("Rayon " + reference); categorie = categories.save(categorie);
        Produit p = new Produit(); p.setBoutique(boutique); p.setNom(nom); p.setReference(reference); p.setCategorie(categorie); p.setFournisseur(f);
        p.setQuantite(100L); p.setPrixAchat(new BigDecimal("3333.00")); p.setCoutMoyen(new BigDecimal("3333.00")); p.setPrixVente(new BigDecimal("10000.00")); p.setPrixDefinis(true);
        return produits.saveAndFlush(p);
    }

    private <T> T authentifie(String email, String role, Callable<T> action) {
        var precedent = SecurityContextHolder.getContext(); var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(email, "", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        SecurityContextHolder.setContext(contexte);
        try { return action.call(); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException(e); }
        finally { SecurityContextHolder.setContext(precedent); }
    }
}
