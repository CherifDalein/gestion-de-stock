package org.example.stock.controller;

import org.example.stock.enums.NatureProduit;
import org.example.stock.enums.Role;
import org.example.stock.export.TableauExport;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.example.stock.service.EtatsPartenairesService;
import org.example.stock.service.EtatsPartenairesService.Cote;
import org.example.stock.service.SoldePartenaireService;
import org.example.stock.service.TableauDeBordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ergonomie des listes (novembre 2026) : menu « Plus », fiches sans champs vides, compteurs de tête.
 * Les droits ne changent pas : une action qu'un rôle ne voyait pas reste invisible, et les compteurs de tête sont les montants
 * des états de créances et de dettes (même source : les soldes déjà calculés pour la liste).
 *
 * <p>Jeu de données (boutique A, montants en GNF, écrits directement en base) :
 * clients Aminata (vente 100 000, versé 30 000 : reste 70 000), Boubacar (solde de départ 200 000 + vente à crédit 20 000),
 * Mariama (avance de départ 20 000), Fatou (soldée, sans aucun contact) et une vente ancienne sans client de 15 000 ;
 * fournisseurs Sidi (achat 80 000, versé 30 000 : dette 50 000), Kaba (solde de départ « je lui dois » 90 000), Diallo (avance chez lui 30 000)
 * et un achat ancien sans fournisseur de 9 000.</p>
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ergonomie-listes;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class ErgonomieListesTests {
    static final String GERANT = "ergo-gerant@example.test", CAISSIER = "ergo-caissier@example.test", GERANT_B = "ergo-gerant-b@example.test";

    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ClientRepository clients;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired VenteRepository ventes;
    @Autowired ProduitRepository produits;
    @Autowired CategorieRepository categories;
    @Autowired AchatRepository achats;
    @Autowired SoldePartenaireService soldes;
    @Autowired TableauDeBordService tableau;
    @Autowired EtatsPartenairesService partenaires;
    @Autowired MockMvc mvc;

    static boolean construit;
    static Client aminata, boubacar, mariama, fatou;
    static Fournisseur sidi, kaba, diallo;
    static Vente venteAminata, venteBoubacar;
    static Achat achatSidi;

    @BeforeEach
    void preparer() {
        if (!construit) { construire(); construit = true; }
    }

    @AfterEach
    void nettoyer() { SecurityContextHolder.clearContext(); }

    private void construire() {
        Boutique a = boutique("Boutique ergonomie A"), b = boutique("Boutique ergonomie B");
        utilisateur(GERANT, "Awa Gérante", Role.ADMIN, a); utilisateur(CAISSIER, "Moussa Caissier", Role.CAISSIER, a);
        utilisateur(GERANT_B, "Gérant de B", Role.ADMIN, b);
        aminata = client(a, "Aminata Camara", "0"); aminata.setTelephone("+224 622 12 34 56"); aminata.setAdresse("Madina, Conakry"); clients.saveAndFlush(aminata);
        boubacar = client(a, "Boubacar Bah", "200000"); boubacar.setEmail("boubacar.bah@example.test"); clients.saveAndFlush(boubacar);
        mariama = client(a, "Mariama Sow", "-20000");
        fatou = client(a, "Fatou Sylla", "0");
        client(b, "CLIENT_SECRET_B", "999000");
        sidi = fournisseur(a, "Sidi Import", "0"); sidi.setTelephone("620 00 11 22"); fournisseurs.saveAndFlush(sidi);
        kaba = fournisseur(a, "Kaba Grossiste", "-90000");
        diallo = fournisseur(a, "Diallo Distribution", "30000");
        Categorie rayon = new Categorie(); rayon.setBoutique(a); rayon.setNom("Rayon ergonomie"); categories.saveAndFlush(rayon);
        Produit p = new Produit(); p.setBoutique(a); p.setNom("Savon ergonomie"); p.setReference("ERGO-1"); p.setQuantite(0L); p.setNature(NatureProduit.STOCK);
        p.setCategorie(rayon); p.setFournisseur(sidi);
        p.setPrixAchat(new BigDecimal("1000")); p.setPrixVente(new BigDecimal("1500")); p.setPrixDefinis(true); p.setCoutMoyen(new BigDecimal("1000")); produits.saveAndFlush(p);
        venteAminata = vente(a, aminata, "100000", "30000");
        venteBoubacar = vente(a, boubacar, "20000", "0");
        vente(a, null, "15000", "0");
        achatSidi = achat(a, sidi, "80000", "30000");
        achat(a, null, "9000", "0");
    }

    // ==== Compteurs de tête : mêmes montants que les états de créances et de dettes =====================================

    private record Compteurs(int nombre, BigDecimal total, BigDecimal avances, int tous) { }

    private static Compteurs compteurs(String html) {
        Matcher n = Pattern.compile("data-synthese=\"nombre\">(\\d+)<").matcher(html);
        Matcher t = Pattern.compile("data-synthese=\"total\" data-valeur=\"([^\"]+)\"").matcher(html);
        Matcher av = Pattern.compile("data-synthese=\"avances\" data-valeur=\"([^\"]+)\"").matcher(html);
        Matcher tous = Pattern.compile("data-synthese=\"tous\">(\\d+)<").matcher(html);
        assertThat(n.find() && t.find() && av.find() && tous.find()).as("les quatre compteurs sont présents").isTrue();
        return new Compteurs(Integer.parseInt(n.group(1)), new BigDecimal(t.group(1)), new BigDecimal(av.group(1)), Integer.parseInt(tous.group(1)));
    }

    private static List<Object> ligne(TableauExport t, String debut) {
        return t.getLignes().stream().filter(l -> String.valueOf(l.get(0)).startsWith(debut)).findFirst().orElseThrow(() -> new AssertionError(debut));
    }

    /** Partenaires qui ont au moins une ligne « reste à payer » positive dans le détail de l'état (hors lignes de total et d'avance). */
    private static Set<String> partenairesEnDette(TableauExport detail) {
        Set<String> noms = new LinkedHashSet<>();
        for (List<Object> l : detail.getLignes()) {
            String nom = String.valueOf(l.get(0));
            if (nom.startsWith("Total ") || nom.startsWith("Ventes sans") || nom.startsWith("Achats sans")) continue;
            if (l.get(7) instanceof BigDecimal reste && reste.signum() > 0) noms.add(nom);
        }
        return noms;
    }

    @Test
    void lesCompteursDesClientsSontCeuxDeLEtatDesCreances() throws Exception {
        Compteurs c = compteurs(page("/clients", GERANT, "ADMIN"));
        assertThat(c.tous()).isEqualTo(4);
        assertThat(c.nombre()).as("clients qui me doivent").isEqualTo(2);
        assertThat(c.total()).as("total à encaisser").isEqualByComparingTo("290000");   // 70 000 + 200 000 + 20 000
        assertThat(c.avances()).as("avances détenues").isEqualByComparingTo("20000");

        authentifier(GERANT);
        var etat = partenaires.creancesOuDettes(Cote.CLIENT, LocalDate.now());
        TableauExport detail = etat.sections().get(0), recap = etat.sections().get(1);
        BigDecimal sansClient = (BigDecimal) ligne(detail, "Ventes sans client").get(7);
        assertThat(sansClient).isEqualByComparingTo("15000");
        // Le total de l'état des créances, moins les ventes sans client (qui n'ont pas de fiche), est le compteur de la liste.
        assertThat(((BigDecimal) ligne(recap, "Total des créances").get(1)).subtract(sansClient)).isEqualByComparingTo(c.total());
        assertThat((BigDecimal) ligne(recap, "Avances disponibles").get(1)).isEqualByComparingTo(c.avances());
        assertThat(partenairesEnDette(detail)).hasSize(c.nombre()).containsExactlyInAnyOrder("Aminata Camara", "Boubacar Bah");
        // Même source que le tableau de bord.
        var cd = tableau.creancesEtDettes();
        assertThat(c.total().add(sansClient)).isEqualByComparingTo(cd.creances());
        assertThat(c.avances()).isEqualByComparingTo(cd.avancesClients());
        assertThat(soldes.creancesClients().total()).isEqualByComparingTo("305000");
    }

    @Test
    void lesCompteursDesFournisseursSontCeuxDeLEtatDesDettes() throws Exception {
        Compteurs c = compteurs(page("/fournisseurs", GERANT, "ADMIN"));
        assertThat(c.tous()).isEqualTo(3);
        assertThat(c.nombre()).as("fournisseurs que je dois payer").isEqualTo(2);
        assertThat(c.total()).as("total à payer").isEqualByComparingTo("140000");       // 50 000 + 90 000
        assertThat(c.avances()).as("avances versées").isEqualByComparingTo("30000");

        authentifier(GERANT);
        var etat = partenaires.creancesOuDettes(Cote.FOURNISSEUR, LocalDate.now());
        TableauExport detail = etat.sections().get(0), recap = etat.sections().get(1);
        BigDecimal sansFournisseur = (BigDecimal) ligne(detail, "Achats sans fournisseur").get(7);
        assertThat(sansFournisseur).isEqualByComparingTo("9000");
        assertThat(((BigDecimal) ligne(recap, "Total des dettes").get(1)).subtract(sansFournisseur)).isEqualByComparingTo(c.total());
        assertThat((BigDecimal) ligne(recap, "Avances versées").get(1)).isEqualByComparingTo(c.avances());
        assertThat(partenairesEnDette(detail)).hasSize(c.nombre()).containsExactlyInAnyOrder("Sidi Import", "Kaba Grossiste");
        var cd = tableau.creancesEtDettes();
        assertThat(c.total().add(sansFournisseur)).isEqualByComparingTo(cd.dettes());
        assertThat(c.avances()).isEqualByComparingTo(cd.avancesFournisseurs());
    }

    @Test
    void lesCompteursFinanciersRestentReservesAuGerant() throws Exception {
        for (String url : List.of("/clients")) {
            String caissier = page(url, CAISSIER, "CAISSIER");
            assertThat(caissier).contains("Aminata Camara").doesNotContain("data-synthese=\"total\"", "data-synthese=\"avances\"", "Total à encaisser", "Avances détenues");
            String gerant = page(url, GERANT, "ADMIN");
            assertThat(gerant).contains("Me doivent", "Total à encaisser", "Avances détenues")
                    .doesNotContain("Avec téléphone</span>", "Sans contact", "Avec email</span>");
        }
        String fournisseurs = page("/fournisseurs", GERANT, "ADMIN");
        assertThat(fournisseurs).contains("Je leur dois", "Total à payer", "Avances versées").doesNotContain("Sans contact");
    }

    @Test
    void lesCompteursNeMontrentJamaisLesPartenairesDUneAutreBoutique() throws Exception {
        String html = page("/clients", GERANT_B, "ADMIN");
        assertThat(html).contains("CLIENT_SECRET_B").doesNotContain("Aminata Camara");
        Compteurs c = compteurs(html);
        assertThat(c.tous()).isEqualTo(1);
        assertThat(c.total()).isEqualByComparingTo("999000");
        assertThat(c.nombre()).isEqualTo(1);
    }

    // ==== Fiches : plus de champs vides, une ligne de contact =============================================================

    @Test
    void uneFicheSansContactNAffichePasNonRenseigne() throws Exception {
        for (String role : List.of("ADMIN", "CAISSIER")) {
            String html = page("/clients", role.equals("ADMIN") ? GERANT : CAISSIER, role);
            assertThat(html).as("liste des clients, rôle " + role).doesNotContain("Non renseigné");
            String fiche = ligne(html, "Fatou Sylla");
            assertThat(fiche).doesNotContain("Non renseigné", "tel:", "mailto:", "partners-contact-mobile");
        }
        assertThat(page("/fournisseurs", GERANT, "ADMIN")).doesNotContain("Non renseigné");
    }

    @Test
    void laFicheNeMontreQueLaLigneDeContactRenseignee() throws Exception {
        String html = page("/clients", GERANT, "ADMIN");
        // Téléphone : lien tel: sans espaces ; l'email est ignoré tant qu'il y a un téléphone.
        String avecTelephone = ligne(html, "Aminata Camara");
        assertThat(avecTelephone).contains("href=\"tel:+224622123456\"", "+224 622 12 34 56").doesNotContain("mailto:");
        // Sans téléphone : l'email.
        String avecEmail = ligne(html, "Boubacar Bah");
        assertThat(avecEmail).contains("href=\"mailto:boubacar.bah@example.test\"").doesNotContain("tel:");
        // Badges de solde : Me doit / Avance / Soldé, avec leur couleur (classe).
        assertThat(avecTelephone).contains("solde-badge--doit", ">Me doit<", "70 000,00 GNF");
        assertThat(avecEmail).contains("solde-badge--doit", "220 000,00 GNF");
        assertThat(ligne(html, "Mariama Sow")).contains("solde-badge--avance", ">Avance<", "20 000,00 GNF");
        assertThat(ligne(html, "Fatou Sylla")).contains("solde-badge--solde", ">Soldé<");
        String fournisseurs = page("/fournisseurs", GERANT, "ADMIN");
        assertThat(ligne(fournisseurs, "Sidi Import")).contains("href=\"tel:620001122\"", "solde-badge--dois", ">Je lui dois<", "50 000,00 GNF");
        assertThat(ligne(fournisseurs, "Kaba Grossiste")).contains("solde-badge--dois", "90 000,00 GNF").doesNotContain("tel:", "mailto:");
        assertThat(ligne(fournisseurs, "Diallo Distribution")).contains("solde-badge--avance", ">Avance<", "Il me doit 30 000,00 GNF");
    }

    // ==== Menu « Plus » : actions déplacées, droits inchangés ============================================================

    @Test
    void clients_lesActionsPrincipalesRestentVisiblesEtLeResteVaDansLeMenu() throws Exception {
        String html = page("/clients", GERANT, "ADMIN");
        String fiche = ligne(html, "Boubacar Bah");
        String menu = menu(fiche), dehors = fiche.replace(menu, "");
        // Visibles : Consulter, Modifier, Régler le solde (il reste un solde de départ à régler).
        assertThat(dehors).contains("data-partner-details", "/clients/modifier/" + boubacar.getId(), "/clients/solde/regler/" + boubacar.getId(), "Régler le solde de départ de Boubacar Bah");
        assertThat(dehors).doesNotContain("/factures/client/", "/etats/", "/clients/supprimer/");
        // Dans le menu : factures, relevés, justificatif, et la suppression en rouge, séparée, derrière la confirmation.
        assertThat(menu).contains("/factures/client/" + boubacar.getId(), "/factures/client/" + boubacar.getId() + "/cumule",
                "/etats/releve/client/" + boubacar.getId(), "/etats/justificatifs/client/pdf?id=" + boubacar.getId(),
                "plus-menu-separateur", "plus-menu-item--danger", "/clients/supprimer/" + boubacar.getId(), "data-confirmer",
                "data-confirmer-titre=\"Supprimer ce client ?\"", "data-confirmer-sujet=\"Boubacar Bah\"", "data-confirmer-oui=\"Supprimer le client\"", "data-confirmer-non=\"Garder\"");
        // Un client sans solde de départ à régler n'a pas le bouton « Régler le solde ».
        assertThat(ligne(html, "Aminata Camara").replace(menu(ligne(html, "Aminata Camara")), "")).doesNotContain("/clients/solde/regler/");
        // Plus de confirm() natif ni de défilement des actions.
        assertThat(html).doesNotContain("confirm(", "onsubmit=", "Faites défiler la ligne des actions", "partenaireDeleteDialog");
    }

    @Test
    void clients_leCaissierGardeSesDroits() throws Exception {
        String html = page("/clients", CAISSIER, "CAISSIER");
        String fiche = ligne(html, "Boubacar Bah");
        String menu = menu(fiche);
        assertThat(menu).contains("/factures/client/" + boubacar.getId()).contains("/factures/client/" + boubacar.getId() + "/cumule");
        assertThat(fiche).doesNotContain("/etats/releve/", "/etats/justificatifs/", "/clients/supprimer/", "plus-menu-item--danger", "data-confirmer");
        assertThat(fiche).contains("/clients/modifier/" + boubacar.getId());
    }

    @Test
    void fournisseurs_memeDecoupageEtMemesDroits() throws Exception {
        String html = page("/fournisseurs", GERANT, "ADMIN");
        String fiche = ligne(html, "Kaba Grossiste");
        String menu = menu(fiche), dehors = fiche.replace(menu, "");
        assertThat(dehors).contains("data-partner-details", "/fournisseurs/modifier/" + kaba.getId(), "/fournisseurs/solde/regler/" + kaba.getId())
                .doesNotContain("/factures/fournisseur/", "/etats/", "/fournisseurs/supprimer/");
        assertThat(menu).contains("/factures/fournisseur/" + kaba.getId(), "/factures/fournisseur/" + kaba.getId() + "/cumule",
                "/etats/releve/fournisseur/" + kaba.getId(), "/etats/justificatifs/fournisseur/pdf?id=" + kaba.getId(),
                "/fournisseurs/supprimer/" + kaba.getId(), "plus-menu-item--danger", "data-confirmer-titre=\"Supprimer ce fournisseur ?\"");
        assertThat(html).doesNotContain("confirm(", "onsubmit=");
    }

    @Test
    void ventes_reglerRecuEtEnvoyerRestentVisiblesLeResteVaDansLeMenu() throws Exception {
        String html = page("/ventes", GERANT, "ADMIN");
        String ligne = ligneDe(html, "/ventes/regler/" + venteBoubacar.getId());
        String menu = menu(ligne), dehors = ligne.replace(menu, "");
        assertThat(dehors).contains("/ventes/regler/" + venteBoubacar.getId(), "data-recu-vente", "data-recu-partage", ">Régler<");
        assertThat(dehors).doesNotContain("/factures/vente/", "/retour", "data-bs-toggle=\"modal\"");
        assertThat(menu).contains("data-bs-toggle=\"modal\"", "#modalVente" + venteBoubacar.getId(), "/factures/vente/" + venteBoubacar.getId(),
                "/ventes/" + venteBoubacar.getId() + "/retour");
        assertThat(html).doesNotContain("Sur écran étroit, faites défiler", "Faites défiler la ligne des actions");
        // Le caissier n'a toujours pas le retour.
        String caissier = page("/ventes", CAISSIER, "CAISSIER");
        String ligneCaissier = ligneDe(caissier, "/ventes/regler/" + venteBoubacar.getId());
        assertThat(menu(ligneCaissier)).contains("/factures/vente/" + venteBoubacar.getId()).doesNotContain("/retour");
        assertThat(caissier).doesNotContain("/ventes/" + venteBoubacar.getId() + "/retour\" class=\"plus-menu-item");
        assertThat(ligneCaissier).contains("data-recu-vente");
    }

    @Test
    void achats_reglerEtConsulterRestentVisiblesLeResteVaDansLeMenu() throws Exception {
        String html = page("/achats", GERANT, "ADMIN");
        String ligne = ligneDe(html, "/achats/regler/" + achatSidi.getId());
        String menu = menu(ligne), dehors = ligne.replace(menu, "");
        assertThat(dehors).contains("/achats/regler/" + achatSidi.getId(), "/factures/achat/" + achatSidi.getId(), "Consulter");
        assertThat(dehors).doesNotContain("/achats/modifier/", "/retour");
        assertThat(menu).contains("/achats/modifier/" + achatSidi.getId(), "/achats/" + achatSidi.getId() + "/retour", "Retour au fournisseur");
        assertThat(html).doesNotContain("Sur écran étroit, faites défiler");
    }

    // ==== Filtres repliables et confirmations ============================================================================

    @Test
    void lesFiltresDuJournalEtDesProduitsSontReplisablesSansChangerLesChamps() throws Exception {
        String journal = page("/caisse/journal", GERANT, "ADMIN");
        assertThat(journal).contains("id=\"caisseToolbar\" class=\"caisse-toolbar\" data-filtres", "caisse-filtres-champs\" data-filtres-contenu", "id=\"caisseReset\"", "data-filtres-effacer");
        for (String champ : List.of("caisseSearch", "caisseCompte", "caisseAuteur", "caisseSource", "caisseDirection", "caisseDateFrom", "caisseDateTo"))
            assertThat(journal).contains("id=\"" + champ + "\"");
        String produits = page("/produits", GERANT, "ADMIN");
        assertThat(produits).contains("id=\"produitsToolbar\" class=\"products-toolbar\" data-filtres", "data-filtres-contenu", "id=\"produitsCategory\"", "id=\"produitsStock\"");
    }

    @Test
    void plusAucuneConfirmationNativeDansLesModelesNiLesScripts() throws Exception {
        java.nio.file.Path racine = java.nio.file.Path.of("src/main/resources");
        try (var fichiers = java.nio.file.Files.walk(racine)) {
            List<String> fautifs = fichiers.filter(p -> p.toString().matches(".*/(templates/.*\\.html|static/js/(?!confirmation\\.js).*\\.js)"))
                    .filter(p -> !p.toString().contains("/static/js/vendor/") && !p.toString().endsWith(".min.js"))
                    .filter(p -> { try { return java.nio.file.Files.readString(p).matches("(?s).*(?<![A-Za-z.])confirm\\(.*|(?s).*window\\.confirm\\(.*"); } catch (java.io.IOException e) { throw new RuntimeException(e); } })
                    .map(java.nio.file.Path::toString).toList();
            // Les replis sans <dialog> des écrans qui ont leur propre fenêtre (catégories, produits) restent tolérés dans leur script.
            assertThat(fautifs).allMatch(p -> p.endsWith("/static/js/categories.js") || p.endsWith("/static/js/produits.js"));
        }
    }

    @Test
    void produits_leGerantAUnMenuPlusSansLaSuppressionVisibleEtLeCaissierNenAPas() throws Exception {
        String gerant = page("/produits", GERANT, "ADMIN");
        assertThat(gerant).contains("plus-menu-details", "plus-menu-item--danger", "/produits/supprimer/").doesNotContain("onsubmit=");
        String caissier = page("/produits", CAISSIER, "CAISSIER");
        assertThat(caissier).doesNotContain("plus-menu-details", "/produits/supprimer/", "/produits/modifier/");
    }

    // ---- Outils -------------------------------------------------------------------------------------------------------

    private String page(String url, String compte, String role) throws Exception {
        return mvc.perform(get(url).with(user(compte).roles(role))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** La ligne de tableau (tr) d'une fiche, retrouvée par le nom du partenaire. */
    private static String ligne(String html, String nom) {
        for (String tr : lignes(html, "data-partner-row")) if (tr.contains("data-name=\"" + nom + "\"")) return tr;
        throw new AssertionError("Fiche introuvable : " + nom);
    }

    private static String ligneDe(String html, String marque) {
        for (String tr : lignes(html, "<td")) if (tr.contains(marque)) return tr;
        throw new AssertionError("Ligne introuvable : " + marque);
    }

    private static List<String> lignes(String html, String marque) {
        List<String> resultat = new ArrayList<>();
        Matcher m = Pattern.compile("(?s)<tr\\b.*?</tr>").matcher(html);
        while (m.find()) if (m.group().contains(marque)) resultat.add(m.group());
        return resultat;
    }

    /** Le menu « Plus » d'une ligne : le bloc details. */
    private static String menu(String ligne) {
        Matcher m = Pattern.compile("(?s)<details class=\"plus-menu-details\".*?</details>").matcher(ligne);
        assertThat(m.find()).as("la ligne a un menu « Plus »").isTrue();
        return m.group();
    }

    private void authentifier(String email) {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(email, "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(contexte);
    }

    private Boutique boutique(String nom) { Boutique b = new Boutique(); b.setNom(nom); return boutiques.saveAndFlush(b); }

    private void utilisateur(String email, String nom, Role role, Boutique b) {
        Utilisateur u = new Utilisateur(); u.setBoutique(b); u.setEmail(email); u.setNom(nom); u.setRole(role); utilisateurs.saveAndFlush(u);
    }

    private Client client(Boutique b, String nom, String soldeInitial) {
        Client c = new Client(); c.setBoutique(b); c.setNom(nom); c.setSoldeInitial(new BigDecimal(soldeInitial)); return clients.saveAndFlush(c);
    }

    private Fournisseur fournisseur(Boutique b, String nom, String soldeInitial) {
        Fournisseur f = new Fournisseur(); f.setBoutique(b); f.setNom(nom); f.setSoldeInitial(new BigDecimal(soldeInitial)); return fournisseurs.saveAndFlush(f);
    }

    private Vente vente(Boutique b, Client acheteur, String total, String verse) {
        Vente v = new Vente(); v.setBoutique(b); v.setClient(acheteur); v.setDateVente(LocalDateTime.of(2026, 6, 5, 9, 0));
        v.setMontantTotal(new BigDecimal(total)); v.setMontantVerse(new BigDecimal(verse)); v.setAvanceUtilisee(BigDecimal.ZERO);
        return ventes.saveAndFlush(v);
    }

    private Achat achat(Boutique b, Fournisseur vendeur, String total, String verse) {
        Achat a = new Achat(); a.setBoutique(b); a.setFournisseur(vendeur); a.setDateAchat(LocalDateTime.of(2026, 6, 6, 9, 0));
        a.setMontantTotal(new BigDecimal(total)); a.setMontantVerse(new BigDecimal(verse)); return achats.saveAndFlush(a);
    }
}
