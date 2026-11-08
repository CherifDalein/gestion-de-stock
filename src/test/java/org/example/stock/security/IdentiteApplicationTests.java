package org.example.stock.security;

import org.example.stock.enums.Role;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** L'identité RFC-COMPTA est celle de l'application ; les documents gardent l'identité de leur boutique. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class IdentiteApplicationTests {
    @Autowired MockMvc mvc;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired LogoBoutiqueRepository logos;
    @Autowired ClientRepository clients;
    @Autowired CategorieRepository categories;
    @Autowired ProduitRepository produits;
    @Autowired VenteRepository ventes;

    @Test
    void laPageDeConnexionPorteLeNomLeLogoEtLesIcones() throws Exception {
        String page = mvc.perform(get("/login")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("<title>Connexion · RFC-COMPTA</title>")
                .contains("alt=\"RFC-COMPTA\"").contains("/img/marque/rfc-compta-logo.svg")
                .contains("rel=\"icon\" type=\"image/svg+xml\" href=\"/img/marque/rfc-compta-icone.svg\"")
                .contains("rel=\"icon\" type=\"image/png\" sizes=\"32x32\" href=\"/img/marque/favicon-32.png\"")
                .contains("rel=\"apple-touch-icon\" href=\"/img/marque/apple-touch-icon.png\"")
                .contains("<meta name=\"theme-color\" content=\"#0B2540\">")
                .doesNotContain("Stock Pro");
    }

    @Test
    void lAccueilEtLesPagesPubliquesPortentLeNomEtLesIcones() throws Exception {
        for (String route : new String[]{"/accueil", "/inscription", "/mot-de-passe-oublie"}) {
            String page = mvc.perform(get(route)).andReturn().getResponse().getContentAsString();
            assertThat(page).as(route).contains("RFC-COMPTA").contains("alt=\"RFC-COMPTA\"")
                    .contains("/img/marque/favicon-32.png").doesNotContain("Stock Pro");
        }
    }

    @Test
    void laMarqueEtLeManifesteSontPublics() throws Exception {
        for (String ressource : new String[]{"/img/marque/rfc-compta-logo.svg", "/img/marque/rfc-compta-marque.svg",
                "/img/marque/rfc-compta-icone.svg", "/img/marque/favicon-32.png", "/img/marque/apple-touch-icon.png",
                "/img/marque/icone-192.png", "/img/marque/icone-512.png", "/site.webmanifest"}) {
            mvc.perform(get(ressource)).andExpect(status().isOk());
        }
        String manifeste = mvc.perform(get("/site.webmanifest")).andReturn().getResponse().getContentAsString();
        assertThat(manifeste).contains("\"name\": \"RFC-COMPTA\"").contains("#0B2540").contains("icone-192.png").contains("icone-512.png");
    }

    @Test
    void uneFactureDeVenteAfficheLaBoutiqueEtPasRfcCompta() throws Exception {
        Boutique boutique = new Boutique(); boutique.setNom("Épicerie Soumah"); boutique.setAdresse("Kaloum, Conakry");
        boutique = boutiques.saveAndFlush(boutique);
        LogoBoutique logo = new LogoBoutique(); logo.setBoutique(boutique); logo.setContenu(new byte[]{1, 2, 3}); logo.setRevision("r1");
        logos.saveAndFlush(logo);
        Utilisateur gerant = new Utilisateur(); gerant.setEmail("gerant@identite.test"); gerant.setNom("Gérant"); gerant.setRole(Role.ADMIN);
        gerant.setBoutique(boutique); gerant.setMotDePasse("empreinte-fictive"); utilisateurs.saveAndFlush(gerant);
        Client client = new Client(); client.setNom("Client fictif"); client.setBoutique(boutique); clients.saveAndFlush(client);
        Categorie categorie = new Categorie(); categorie.setNom("Rayon"); categorie.setBoutique(boutique); categories.saveAndFlush(categorie);
        Produit produit = new Produit(); produit.setNom("Riz"); produit.setReference("RIZ"); produit.setBoutique(boutique);
        produit.setCategorie(categorie); produit.setQuantite(10L); produit.setPrixAchat(new BigDecimal("10")); produit.setPrixVente(new BigDecimal("20"));
        produits.saveAndFlush(produit);
        Vente vente = new Vente(); vente.setBoutique(boutique); vente.setClient(client); vente.setDateVente(LocalDateTime.now());
        vente.setMontantTotal(new BigDecimal("20")); vente.setMontantVerse(new BigDecimal("20"));
        DetailVente ligne = new DetailVente(); ligne.setVente(vente); ligne.setProduit(produit); ligne.setQuantite(1); ligne.setPrixUnitaire(new BigDecimal("20"));
        vente.getLignes().add(ligne); vente = ventes.saveAndFlush(vente);

        String facture = mvc.perform(get("/factures/vente/" + vente.getId()).with(user(gerant.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(facture).contains("Épicerie Soumah").contains("Kaloum, Conakry")
                .contains("class=\"document-brand-logo\"").contains("/boutique/logo")
                .doesNotContain("RFC-COMPTA").doesNotContain("rfc-compta-logo").doesNotContain("Stock Pro");
    }
}
