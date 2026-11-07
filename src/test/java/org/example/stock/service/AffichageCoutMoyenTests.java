package org.example.stock.service;

import org.example.stock.enums.NatureProduit;
import org.example.stock.enums.Role;
import org.example.stock.model.*;
import org.example.stock.repository.*;
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
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Coût moyen pondéré et marge : visibles du gérant seulement, dans la liste et les fiches. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:affichage-cout-moyen;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AffichageCoutMoyenTests {
    private static final String GERANT = "cmp-gerant@example.test";
    private static final String CAISSIER = "cmp-caissier@example.test";

    @Autowired MockMvc mvc;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired CategorieRepository categories;
    @Autowired ProduitRepository produits;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired ClientRepository clients;
    @Autowired AchatRepository achats;
    @Autowired VenteRepository ventes;
    @Autowired MouvementCaisseRepository caisse;
    @Autowired MouvementStockRepository mouvementsStock;
    @Autowired OperationCreationRepository operations;
    @Autowired AchatService achatService;

    Boutique boutique;
    Categorie categorie;
    Fournisseur fournisseur;
    Produit riz;

    @BeforeEach
    void preparer() {
        operations.deleteAll(); mouvementsStock.deleteAll(); caisse.deleteAll(); ventes.deleteAll(); achats.deleteAll();
        produits.deleteAll(); clients.deleteAll(); fournisseurs.deleteAll(); categories.deleteAll(); utilisateurs.deleteAll(); boutiques.deleteAll();
        boutique = new Boutique(); boutique.setNom("Boutique CMP"); boutique = boutiques.saveAndFlush(boutique);
        utilisateur(GERANT, Role.ADMIN); utilisateur(CAISSIER, Role.CAISSIER);
        categorie = new Categorie(); categorie.setNom("Vivres"); categorie.setBoutique(boutique); categorie = categories.saveAndFlush(categorie);
        fournisseur = new Fournisseur(); fournisseur.setNom("Grossiste"); fournisseur.setBoutique(boutique);
        fournisseur = fournisseurs.saveAndFlush(fournisseur);
        riz = produit("Riz", "RIZ-50", NatureProduit.STOCK, 0L, "0", "150000", "0");
        // Deux achats : 10 à 100 000 puis 10 à 120 000 → coût moyen 110 000, dernier prix d'achat 120 000.
        comme(GERANT, "ADMIN", () -> achatService.enregistrerAchat(achat(riz, "100000")));
        comme(GERANT, "ADMIN", () -> achatService.enregistrerAchat(achat(riz, "120000")));
    }

    @AfterEach
    void deconnecter() { SecurityContextHolder.clearContext(); }

    @Test
    void laListeMontreAuGerantLeCoutMoyenEtLaMargeMaisPasLeDernierPrixDansLaColonne() throws Exception {
        Produit actuel = produits.findById(riz.getId()).orElseThrow();
        assertThat(actuel.getCoutMoyen()).isEqualByComparingTo("110000");
        assertThat(actuel.getPrixAchat()).isEqualByComparingTo("120000");

        String html = pageGerant("/produits");
        assertThat(html).contains(">Coût moyen</button>").contains(">Marge unitaire</button>")
                .doesNotContain("Prix d’achat</button>").doesNotContain("data-sort=\"prixAchat\"");
        String ligne = ligne(html, "RIZ-50");
        assertThat(ligne).contains("110 000,00").contains("40 000,00 GNF (26,7 %)")
                .contains("data-cost=\"110000.00\"").contains("data-margin=\"40000.00\"").contains("data-margin-rate=\"26.7\"")
                .contains("data-last-purchase-price=\"120000.00\"")
                .doesNotContain("120 000,00");
        assertThat(html).contains("Coût moyen pondéré").contains("Dernier prix d’achat");
    }

    @Test
    void uneMargeNegativeSAfficheEnRouge() throws Exception {
        produit("Sucre", "SUCRE", NatureProduit.STOCK, 5L, "4000", "3000", "4000");
        String ligne = ligne(pageGerant("/produits"), "SUCRE");
        assertThat(ligne).contains("-1 000,00 GNF (-33,3 %)");
        assertThat(cellule(ligne, "-1 000,00 GNF")).contains("text-danger");
        assertThat(cellule(ligne(pageGerant("/produits"), "RIZ-50"), "26,7 %")).doesNotContain("text-danger");
    }

    @Test
    void unServiceAfficheSonCoutFixeEtSaMarge() throws Exception {
        produit("Livraison", "SRV-LIV", NatureProduit.SERVICE, 0L, "20000", "50000", "20000");
        String ligne = ligne(pageGerant("/produits"), "SRV-LIV");
        assertThat(ligne).contains("title=\"Coût fixe du service\"").contains("20 000,00")
                .contains("30 000,00 GNF (60,0 %)").contains("data-cost=\"20000.00\"")
                .doesNotContain("data-last-purchase-price");
    }

    @Test
    void sansPrixDeVenteOuAvecPrixDeVenteNulLaMargeSAfficheParUnTiret() throws Exception {
        produit("Offert", "OFFERT", NatureProduit.STOCK, 5L, "1000", "0", "1000");
        Produit sansPrix = produit("Nouveau", "NOUVEAU", NatureProduit.STOCK, 0L, "0", "0", "0");
        sansPrix.setPrixDefinis(false); produits.saveAndFlush(sansPrix);
        String html = pageGerant("/produits");
        for (String reference : List.of("OFFERT", "NOUVEAU")) {
            String ligne = ligne(html, reference);
            assertThat(ligne).doesNotContain("data-margin=").doesNotContain("data-margin-rate=").doesNotContain("%)");
            assertThat(cellule(ligne, "—")).isNotEmpty();
        }
        assertThat(ligne(html, "NOUVEAU")).contains("À définir");
    }

    @Test
    void laFicheEtLaModificationMontrentCoutMoyenDernierPrixEtMarge() throws Exception {
        for (String url : List.of("/produits/" + riz.getId() + "/stock", "/produits/modifier/" + riz.getId())) {
            String html = pageGerant(url);
            assertThat(html).contains("Coût moyen pondéré").contains("110 000,00 GNF")
                    .contains("Dernier prix d’achat").contains("120 000,00 GNF")
                    .contains("Le coût moyen tient compte de tous les achats en stock ; c’est lui qui sert au calcul du bénéfice.")
                    .contains("Marge unitaire").contains("40 000,00 GNF (26,7 %)");
        }
    }

    @Test
    void lesEcransDuCaissierNeContiennentNiCoutNiDernierPrixNiMarge() throws Exception {
        produit("Livraison", "SRV-LIV", NatureProduit.SERVICE, 0L, "20000", "50000", "20000");
        List<String> interdits = List.of("110 000", "110000", "120 000", "120000", "40 000", "40000", "26,7", "26.7",
                "20 000", "20000", "30 000", "30000", "Coût moyen", "Coût fixe", "Dernier prix d", "Marge", "marge",
                "data-cost", "data-last-purchase-price", "data-margin", "data-purchase-price", "prixAchat", "coutMoyen");
        for (String url : List.of("/produits", "/ventes/nouveau", "/")) {
            String html = mvc.perform(get(url).with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            for (String interdit : interdits) assertThat(html).as(url + " ne doit pas contenir « " + interdit + " »").doesNotContain(interdit);
        }
        // Les fiches de gestion restent fermées au caissier.
        for (String url : List.of("/produits/" + riz.getId() + "/stock", "/produits/modifier/" + riz.getId())) {
            mvc.perform(get(url).with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        }
        // Le caissier voit toujours le prix de vente et le stock.
        String liste = mvc.perform(get("/produits").with(user(CAISSIER).roles("CAISSIER"))).andReturn().getResponse().getContentAsString();
        assertThat(liste).contains("data-sale-price=\"150000.00\"").contains("150 000,00");
    }

    // ----- Outils -----

    private String pageGerant(String url) throws Exception {
        return mvc.perform(get(url).with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
    private static String ligne(String html, String reference) {
        var m = Pattern.compile("(?s)<tr[^>]*data-reference=\"" + Pattern.quote(reference) + "\".*?</tr>").matcher(html);
        assertThat(m.find()).as("ligne " + reference).isTrue();
        return m.group();
    }
    /** Ouverture de la cellule {@code <td ...>} qui contient le texte donné, jusqu'à ce texte. */
    private static String cellule(String ligne, String texte) {
        var m = Pattern.compile("(?s)<td[^>]*>(?:(?!</td>).)*" + Pattern.quote(texte)).matcher(ligne);
        return m.find() ? m.group() : "";
    }
    private void utilisateur(String email, Role role) {
        Utilisateur u = new Utilisateur(); u.setNom("Utilisateur"); u.setEmail(email); u.setRole(role); u.setBoutique(boutique);
        utilisateurs.saveAndFlush(u);
    }
    private Produit produit(String nom, String reference, NatureProduit nature, Long quantite, String achat, String vente, String cout) {
        Produit p = new Produit(); p.setBoutique(boutique); p.setCategorie(categorie); p.setNom(nom); p.setReference(reference);
        p.setNature(nature); p.setQuantite(quantite);
        p.setPrixAchat(new BigDecimal(achat)); p.setPrixVente(new BigDecimal(vente)); p.setCoutMoyen(new BigDecimal(cout));
        return produits.saveAndFlush(p);
    }
    private Achat achat(Produit produit, String prix) {
        Achat achat = new Achat(); Fournisseur f = new Fournisseur(); f.setId(fournisseur.getId()); achat.setFournisseur(f);
        achat.setMontantVerse(BigDecimal.ZERO);
        DetailAchat ligne = new DetailAchat(); Produit ref = new Produit(); ref.setId(produit.getId()); ligne.setProduit(ref);
        ligne.setQuantite(10);
        ligne.setPrixAchatUnitaire(new BigDecimal(prix)); ligne.setPrixVenteUnitaire(new BigDecimal("150000"));
        achat.getLignes().add(ligne); return achat;
    }
    private <T> T comme(String email, String role, java.util.function.Supplier<T> action) {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(email, "", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        SecurityContextHolder.setContext(contexte);
        try { return action.get(); } finally { SecurityContextHolder.clearContext(); }
    }
}
