package org.example.stock.controller;

import org.example.stock.enums.Role;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lot 2, tâche C : le vendeur d'une vente (et l'auteur d'un achat) est le compte connecté, lu côté serveur.
 * Deux boutiques, un gérant, un second gérant, deux caissiers dont un désactivé, comptes distincts.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:vendeur-des-ventes;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class VendeurDesVentesTests {
    private static final String GERANT_A = "vendeur-gerant-a@example.test";
    private static final String GERANT_A2 = "vendeur-gerant-a2@example.test";
    private static final String CAISSIER_A = "vendeur-caissier-a@example.test";
    private static final String ANCIEN_A = "vendeur-ancien-a@example.test";
    private static final String GERANT_B = "vendeur-gerant-b@example.test";
    private static final String CAISSIER_B = "vendeur-caissier-b@example.test";
    private static final Pattern CELLULE_VENDEUR = Pattern.compile("<td data-vendeur(?:=\"\")?>([^<]*)</td>");

    @Autowired MockMvc mvc;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired CategorieRepository categories;
    @Autowired ProduitRepository produits;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired AchatRepository achats;
    @Autowired VenteRepository ventes;
    @Autowired MouvementCaisseRepository caisse;
    @Autowired MouvementStockRepository mouvementsStock;
    @Autowired OperationCreationRepository operations;

    Boutique boutiqueA, boutiqueB;
    Utilisateur gerantA, gerantA2, caissierA, ancienA, gerantB, caissierB;
    Fournisseur fournisseurA;
    Produit rizA, rizB;

    @BeforeEach
    void preparer() {
        operations.deleteAll(); mouvementsStock.deleteAll(); caisse.deleteAll(); ventes.deleteAll(); achats.deleteAll();
        produits.deleteAll(); fournisseurs.deleteAll(); categories.deleteAll(); utilisateurs.deleteAll(); boutiques.deleteAll();
        boutiqueA = boutique("Boutique vendeur A"); boutiqueB = boutique("Boutique vendeur B");
        gerantA = utilisateur(GERANT_A, "Awa Gérante", Role.ADMIN, boutiqueA, true);
        gerantA2 = utilisateur(GERANT_A2, "Alpha Adjoint", Role.ADMIN, boutiqueA, true);
        caissierA = utilisateur(CAISSIER_A, "Bakary Caissier", Role.CAISSIER, boutiqueA, true);
        ancienA = utilisateur(ANCIEN_A, "Cellou Ancien", Role.CAISSIER, boutiqueA, false);
        gerantB = utilisateur(GERANT_B, "Zoé Autre Boutique", Role.ADMIN, boutiqueB, true);
        caissierB = utilisateur(CAISSIER_B, "Yaya Confidentiel", Role.CAISSIER, boutiqueB, true);
        Categorie categorieA = categorie(boutiqueA), categorieB = categorie(boutiqueB);
        fournisseurA = new Fournisseur(); fournisseurA.setNom("Grossiste"); fournisseurA.setBoutique(boutiqueA);
        fournisseurA = fournisseurs.saveAndFlush(fournisseurA);
        rizA = article(boutiqueA, categorieA, "Riz"); rizB = article(boutiqueB, categorieB, "Riz B");
    }

    @AfterEach
    void deconnecter() { SecurityContextHolder.clearContext(); }

    // ----- Enregistrement -----

    @Test
    void uneVenteDuCaissierPorteLeCaissierEtUneVenteDuGerantPorteLeGerant() throws Exception {
        vendre(CAISSIER_A, "CAISSIER").andExpect(redirectedUrl("/ventes"));
        vendre(GERANT_A, "ADMIN").andExpect(redirectedUrl("/ventes"));
        vendre(GERANT_B, "ADMIN", rizB).andExpect(redirectedUrl("/ventes"));

        assertThat(vendeurs(boutiqueA)).containsExactlyInAnyOrder(caissierA.getId(), gerantA.getId());
        Vente duCaissier = ventes.findAll().stream().filter(v -> caissierA.getId().equals(idVendeur(v))).findFirst().orElseThrow();
        assertThat(duCaissier.getBoutique().getId()).isEqualTo(boutiqueA.getId());
        assertThat(vendeurs(boutiqueB)).containsExactly(gerantB.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"vendeur", "vendeur.id", "vendeur.nom", "vendeur.email", "vendeur.role", "vendeurId"})
    void unChampVendeurEnvoyeParLeNavigateurEstRejeteSansRienEnregistrer(String champ) throws Exception {
        String jeton = jeton("/ventes/nouveau", CAISSIER_A, "CAISSIER");
        // Même un compte réel de la boutique, ou celui d'une autre boutique, ne peut pas être imposé.
        for (Utilisateur cible : List.of(gerantA, caissierA, gerantB)) {
            mvc.perform(formulaireVente(CAISSIER_A, "CAISSIER", jeton, rizA).param(champ, champ.endsWith("nom") ? cible.getNom()
                            : champ.endsWith("email") ? cible.getEmail() : champ.endsWith("role") ? "ADMIN" : cible.getId().toString()))
                    .andExpect(status().isBadRequest());
        }
        assertThat(ventes.count()).isZero();
        assertThat(stock(rizA)).isEqualTo(100L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"utilisateur", "utilisateur.id", "utilisateur.nom"})
    void unChampUtilisateurEnvoyeParLeNavigateurEstRejetePourUnAchat(String champ) throws Exception {
        String jeton = jeton("/achats/nouveau", GERANT_A, "ADMIN");
        mvc.perform(formulaireAchat(GERANT_A, jeton, rizA, 2).param(champ, champ.endsWith("nom") ? "Bakary Caissier" : caissierA.getId().toString()))
                .andExpect(status().isBadRequest());
        assertThat(achats.count()).isZero();

        acheter(GERANT_A, rizA, 2).andExpect(redirectedUrl("/achats"));
        Achat achat = achats.findAll().getFirst();
        mvc.perform(modificationAchat(gerantA2, achat, 3).param(champ, champ.endsWith("nom") ? "Bakary Caissier" : caissierA.getId().toString()))
                .andExpect(status().isBadRequest());
        assertThat(idAuteur(achats.findById(achat.getId()).orElseThrow())).isEqualTo(gerantA.getId());
    }

    @Test
    void unAchatPorteSonAuteurEtUneModificationLeConserve() throws Exception {
        acheter(GERANT_A, rizA, 2).andExpect(redirectedUrl("/achats"));
        Achat achat = achats.findAll().getFirst();
        assertThat(idAuteur(achat)).isEqualTo(gerantA.getId());

        // Un autre gérant de la boutique modifie l'achat : l'auteur d'origine reste.
        mvc.perform(modificationAchat(gerantA2, achat, 5)).andExpect(redirectedUrl("/achats?success=modifie"));
        Achat apres = achats.findById(achat.getId()).orElseThrow();
        assertThat(stock(rizA)).isEqualTo(105L);
        assertThat(idAuteur(apres)).isEqualTo(gerantA.getId());
    }

    // ----- Affichage -----

    @Test
    void laListeLeDetailEtLaFactureAfficheLeVendeurOuNonRenseigne() throws Exception {
        Vente duCaissier = vente(boutiqueA, "VT-CAISSIER", caissierA);
        Vente duGerant = vente(boutiqueA, "VT-GERANT", gerantA);
        Vente ancienne = vente(boutiqueA, "VT-ANCIENNE", null);
        Vente dunDesactive = vente(boutiqueA, "VT-ANCIEN", ancienA);

        for (var compte : List.of(new String[]{GERANT_A, "ADMIN"}, new String[]{CAISSIER_A, "CAISSIER"})) {
            String liste = mvc.perform(avec(get("/ventes"), compte[0], compte[1])).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(liste).contains("<th scope=\"col\">Vendeur</th>");
            assertThat(cellulesVendeur(liste)).containsExactlyInAnyOrder("Bakary Caissier", "Awa Gérante", "Non renseigné", "Cellou Ancien");
            assertThat(liste).contains("data-vendeur-detail");
            assertThat(liste).doesNotContain("Zoé Autre Boutique", "Yaya Confidentiel");
        }

        String facture = mvc.perform(avec(get("/factures/vente/" + duCaissier.getId()), CAISSIER_A, "CAISSIER"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(facture).contains("<dt>Vendeur</dt>").containsPattern("data-document-vendeur[^>]*>Bakary Caissier<");
        assertThat(mvc.perform(avec(get("/factures/vente/" + ancienne.getId()), GERANT_A, "ADMIN")).andReturn().getResponse().getContentAsString())
                .containsPattern("data-document-vendeur[^>]*>Non renseigné<");
        assertThat(mvc.perform(avec(get("/factures/vente/" + dunDesactive.getId()), GERANT_A, "ADMIN")).andReturn().getResponse().getContentAsString())
                .containsPattern("data-document-vendeur[^>]*>Cellou Ancien<");
        assertThat(mvc.perform(avec(get("/factures/vente/" + duGerant.getId()), GERANT_A, "ADMIN")).andReturn().getResponse().getContentAsString())
                .containsPattern("data-document-vendeur[^>]*>Awa Gérante<");
    }

    @Test
    void laListeDesAchatsAfficheSaisiParOuNonRenseigne() throws Exception {
        achat(boutiqueA, "AC-ALPHA", gerantA2);
        achat(boutiqueA, "AC-ANCIEN", null);
        achat(boutiqueA, "AC-DESACTIVE", ancienA);
        String liste = mvc.perform(avec(get("/achats"), GERANT_A, "ADMIN")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(liste).contains("<th scope=\"col\">Saisi par</th>");
        Matcher cellules = Pattern.compile("<td data-saisi-par(?:=\"\")?>([^<]*)</td>").matcher(liste);
        List<String> noms = new java.util.ArrayList<>();
        while (cellules.find()) noms.add(cellules.group(1));
        assertThat(noms).containsExactlyInAnyOrder("Alpha Adjoint", "Non renseigné", "Cellou Ancien");
        // Le caissier n'a pas accès aux achats.
        mvc.perform(avec(get("/achats"), CAISSIER_A, "CAISSIER")).andExpect(status().isForbidden());
    }

    // ----- Filtre par vendeur -----

    @Test
    void leGerantFiltreParVendeurEtSeulsLesComptesDeSaBoutiqueSontProposes() throws Exception {
        vente(boutiqueA, "VT-CAISSIER", caissierA);
        vente(boutiqueA, "VT-GERANT", gerantA);
        vente(boutiqueA, "VT-ANCIENNE", null);
        vente(boutiqueA, "VT-ANCIEN", ancienA);
        vente(boutiqueB, "VT-B", gerantB);

        MvcResult complet = mvc.perform(avec(get("/ventes"), GERANT_A, "ADMIN")).andExpect(status().isOk()).andReturn();
        String page = complet.getResponse().getContentAsString();
        assertThat(page).contains("id=\"filtreVendeur\"", "Tous les vendeurs", "Awa Gérante", "Alpha Adjoint", "Bakary Caissier",
                "Cellou Ancien (compte désactivé)").doesNotContain("Zoé Autre Boutique", "Yaya Confidentiel");
        assertThat(numeros(page)).containsExactlyInAnyOrder("VT-CAISSIER", "VT-GERANT", "VT-ANCIENNE", "VT-ANCIEN");
        List<Utilisateur> proposes = (List<Utilisateur>) complet.getModelAndView().getModel().get("vendeursFiltre");
        assertThat(proposes).extracting(Utilisateur::getId)
                .containsExactlyInAnyOrder(gerantA.getId(), gerantA2.getId(), caissierA.getId(), ancienA.getId());

        assertThat(numeros(listeFiltree(GERANT_A, "ADMIN", caissierA.getId().toString()))).containsExactly("VT-CAISSIER");
        assertThat(numeros(listeFiltree(GERANT_A, "ADMIN", ancienA.getId().toString()))).containsExactly("VT-ANCIEN");
        assertThat(numeros(listeFiltree(GERANT_A, "ADMIN", gerantA2.getId().toString()))).isEmpty();
        assertThat(numeros(listeFiltree(GERANT_A, "ADMIN", ""))).hasSize(4);
        // Le filtre se combine avec « échues non soldées » sans élargir la liste.
        assertThat(numeros(mvc.perform(avec(get("/ventes").param("filtre", "echues").param("vendeur", caissierA.getId().toString()),
                GERANT_A, "ADMIN")).andReturn().getResponse().getContentAsString())).isEmpty();
    }

    @Test
    void unVendeurInvalideOuDUneAutreBoutiqueEstIgnoreSansFuite() throws Exception {
        vente(boutiqueA, "VT-CAISSIER", caissierA);
        vente(boutiqueA, "VT-GERANT", gerantA);
        vente(boutiqueB, "VT-B-SECRET", gerantB);

        for (String valeur : List.of(gerantB.getId().toString(), caissierB.getId().toString(), "999999", "abc", "-1", "1 OR 1=1",
                "1,2", "12345678901234567890")) {
            String page = listeFiltree(GERANT_A, "ADMIN", valeur);
            assertThat(numeros(page)).as(valeur).containsExactlyInAnyOrder("VT-CAISSIER", "VT-GERANT");
            assertThat(page).as(valeur).contains("Ce vendeur n&#39;existe pas dans votre boutique")
                    .doesNotContain("VT-B-SECRET", "Zoé Autre Boutique", "Yaya Confidentiel");
        }
        // Valeurs multiples.
        String multiple = mvc.perform(avec(get("/ventes").param("vendeur", caissierA.getId().toString(), gerantA.getId().toString()), GERANT_A, "ADMIN"))
                .andReturn().getResponse().getContentAsString();
        assertThat(numeros(multiple)).containsExactlyInAnyOrder("VT-CAISSIER", "VT-GERANT");
        assertThat(multiple).contains("Ce vendeur n&#39;existe pas dans votre boutique");
        // La réponse est la même pour un compte inexistant et pour celui d'une autre boutique.
        assertThat(listeFiltree(GERANT_A, "ADMIN", "999999").replaceAll("value=\"[^\"]*\"", ""))
                .isEqualTo(listeFiltree(GERANT_A, "ADMIN", gerantB.getId().toString()).replaceAll("value=\"[^\"]*\"", ""));
    }

    @Test
    void leCaissierNeVoitNiLeFiltreNiUneListeDifferente() throws Exception {
        vente(boutiqueA, "VT-CAISSIER", caissierA);
        vente(boutiqueA, "VT-GERANT", gerantA);
        vente(boutiqueB, "VT-B", gerantB);

        MvcResult sans = mvc.perform(avec(get("/ventes"), CAISSIER_A, "CAISSIER")).andExpect(status().isOk()).andReturn();
        String page = sans.getResponse().getContentAsString();
        assertThat(page).doesNotContain("filtreVendeur", "Tous les vendeurs", "Alpha Adjoint");
        assertThat(sans.getModelAndView().getModel()).doesNotContainKey("vendeursFiltre");
        assertThat(numeros(page)).containsExactlyInAnyOrder("VT-CAISSIER", "VT-GERANT");

        // Le paramètre est ignoré : même liste, sans avertissement, même pour un compte de l'autre boutique.
        for (String valeur : List.of(gerantA.getId().toString(), caissierA.getId().toString(), gerantB.getId().toString(), "abc")) {
            String filtree = listeFiltree(CAISSIER_A, "CAISSIER", valeur);
            assertThat(numeros(filtree)).as(valeur).containsExactlyInAnyOrder("VT-CAISSIER", "VT-GERANT");
            assertThat(filtree).doesNotContain("Ce vendeur n&#39;existe pas", "VT-B");
        }
    }

    // ----- Outils -----

    private String listeFiltree(String email, String role, String vendeur) throws Exception {
        return mvc.perform(avec(get("/ventes").param("vendeur", vendeur), email, role)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static List<String> numeros(String page) {
        Matcher m = Pattern.compile("<td class=\"text-nowrap\">(VT-[A-Z0-9-]+)</td>").matcher(page);
        List<String> numeros = new java.util.ArrayList<>();
        while (m.find()) numeros.add(m.group(1));
        return numeros;
    }

    private static List<String> cellulesVendeur(String page) {
        Matcher m = CELLULE_VENDEUR.matcher(page);
        List<String> noms = new java.util.ArrayList<>();
        while (m.find()) noms.add(m.group(1));
        return noms;
    }

    private List<Long> vendeurs(Boutique boutique) {
        return ventes.findAll().stream().filter(v -> v.getBoutique().getId().equals(boutique.getId())).map(VendeurDesVentesTests::idVendeur).toList();
    }

    private static Long idVendeur(Vente vente) { return vente.getVendeur() == null ? null : vente.getVendeur().getId(); }
    private static Long idAuteur(Achat achat) { return achat.getUtilisateur() == null ? null : achat.getUtilisateur().getId(); }

    private ResultActions vendre(String email, String role) throws Exception { return vendre(email, role, rizA); }

    private ResultActions vendre(String email, String role, Produit produit) throws Exception {
        return mvc.perform(formulaireVente(email, role, jeton("/ventes/nouveau", email, role), produit));
    }

    private MockHttpServletRequestBuilder formulaireVente(String email, String role, String jeton, Produit produit) {
        return avec(post("/ventes/enregistrer"), email, role).param("jetonCreation", jeton).param("comptePaiement", "CAISSE")
                .param("lignes[0].produit.id", produit.getId().toString()).param("lignes[0].quantite", "1");
    }

    private ResultActions acheter(String email, Produit produit, int quantite) throws Exception {
        return mvc.perform(formulaireAchat(email, jeton("/achats/nouveau", email, "ADMIN"), produit, quantite));
    }

    private MockHttpServletRequestBuilder formulaireAchat(String email, String jeton, Produit produit, int quantite) {
        return avec(post("/achats/enregistrer"), email, "ADMIN").param("jetonCreation", jeton).param("comptePaiement", "CAISSE")
                .param("fournisseur.id", fournisseurA.getId().toString())
                .param("lignes[0].produit.id", produit.getId().toString()).param("lignes[0].quantite", Integer.toString(quantite))
                .param("lignes[0].prixAchatUnitaire", "4000").param("lignes[0].prixVenteUnitaire", "5000");
    }

    private MockHttpServletRequestBuilder modificationAchat(Utilisateur auteur, Achat achat, int quantite) {
        return avec(post("/achats/modifier/" + achat.getId()), auteur.getEmail(), "ADMIN").param("fournisseur", fournisseurA.getId().toString())
                .param("lignes[0].produit.id", rizA.getId().toString()).param("lignes[0].quantite", Integer.toString(quantite))
                .param("lignes[0].prixAchatUnitaire", "4000").param("lignes[0].prixVenteUnitaire", "5000");
    }

    private String jeton(String route, String email, String role) throws Exception {
        return (String) mvc.perform(avec(get(route), email, role)).andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get("jetonCreation");
    }

    private MockHttpServletRequestBuilder avec(MockHttpServletRequestBuilder requete, String email, String role) {
        return requete.with(user(email).roles(role)).with(csrf());
    }

    private long stock(Produit produit) { return produits.findById(produit.getId()).orElseThrow().getQuantite(); }

    private Vente vente(Boutique boutique, String numero, Utilisateur vendeur) {
        Vente vente = new Vente(); vente.setBoutique(boutique); vente.setNumero(numero); vente.setVendeur(vendeur);
        vente.setDateVente(LocalDateTime.now()); vente.setMontantTotal(new BigDecimal("5000")); vente.setMontantVerse(new BigDecimal("5000"));
        DetailVente ligne = new DetailVente(); ligne.setVente(vente); ligne.setQuantite(1); ligne.setPrixUnitaire(new BigDecimal("5000"));
        ligne.setProduit(boutique.getId().equals(boutiqueA.getId()) ? rizA : rizB); vente.getLignes().add(ligne);
        return ventes.saveAndFlush(vente);
    }

    private Achat achat(Boutique boutique, String numero, Utilisateur auteur) {
        Achat achat = new Achat(); achat.setBoutique(boutique); achat.setNumero(numero); achat.setUtilisateur(auteur);
        achat.setFournisseur(fournisseurA); achat.setDateAchat(LocalDateTime.now());
        achat.setMontantTotal(new BigDecimal("4000")); achat.setMontantVerse(new BigDecimal("4000"));
        DetailAchat ligne = new DetailAchat(); ligne.setAchat(achat); ligne.setProduit(rizA); ligne.setQuantite(1);
        ligne.setPrixAchatUnitaire(new BigDecimal("4000")); achat.getLignes().add(ligne);
        return achats.saveAndFlush(achat);
    }

    private Boutique boutique(String nom) { Boutique b = new Boutique(); b.setNom(nom); return boutiques.saveAndFlush(b); }

    private Utilisateur utilisateur(String email, String nom, Role role, Boutique boutique, boolean actif) {
        Utilisateur u = new Utilisateur(); u.setNom(nom); u.setEmail(email); u.setRole(role); u.setBoutique(boutique); u.setActif(actif);
        return utilisateurs.saveAndFlush(u);
    }

    private Categorie categorie(Boutique boutique) {
        Categorie c = new Categorie(); c.setNom("Alimentation"); c.setBoutique(boutique); return categories.saveAndFlush(c);
    }

    private Produit article(Boutique boutique, Categorie categorie, String nom) {
        Produit p = new Produit(); p.setBoutique(boutique); p.setCategorie(categorie); p.setNom(nom); p.setReference(nom.toUpperCase());
        p.setQuantite(100L); p.setUnite("sac"); p.setPrixDefinis(true);
        p.setPrixAchat(new BigDecimal("4000")); p.setPrixVente(new BigDecimal("5000")); p.setCoutMoyen(new BigDecimal("4000"));
        return produits.saveAndFlush(p);
    }
}
