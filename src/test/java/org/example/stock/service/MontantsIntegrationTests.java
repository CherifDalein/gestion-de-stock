package org.example.stock.service;

import org.example.stock.enums.Role;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:montants-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@WithMockUser(username = "montants@example.test", roles = "ADMIN")
class MontantsIntegrationTests {
    @Autowired ProduitRepository produits;
    @Autowired AchatRepository achats;
    @Autowired VenteRepository ventes;
    @Autowired MouvementCaisseRepository mouvements;
    @Autowired CategorieRepository categories;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired ClientRepository clients;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired AchatService achatService;
    @Autowired VenteService venteService;
    @Autowired CaisseService caisseService;
    @Autowired MockMvc mvc;
    Produit produit;
    Fournisseur fournisseur;
    Utilisateur utilisateur;

    @BeforeEach
    void preparer() {
        mouvements.deleteAll(); ventes.deleteAll(); achats.deleteAll(); produits.deleteAll();
        categories.deleteAll(); fournisseurs.deleteAll(); clients.deleteAll(); utilisateurs.deleteAll();
        utilisateur = new Utilisateur(); utilisateur.setEmail("montants@example.test");
        utilisateur.setNom("Montants"); utilisateur.setRole(Role.ADMIN); utilisateur = utilisateurs.save(utilisateur);
        Categorie categorie = new Categorie(); categorie.setNom("Décimales"); categorie = categories.save(categorie);
        fournisseur = new Fournisseur(); fournisseur.setNom("Décimales"); fournisseur = fournisseurs.save(fournisseur);
        produit = new Produit(); produit.setNom("Produit décimal"); produit.setReference("DECIMAL");
        produit.setQuantite(10L); produit.setPrixAchat(new BigDecimal("0.10")); produit.setPrixVente(new BigDecimal("0.10"));
        produit.setCategorie(categorie); produit.setFournisseur(fournisseur); produit = produits.saveAndFlush(produit);
    }

    @Test
    void achatEtReglementConserventLesCentimesJusquaLaFacture() throws Exception {
        Achat achat = achat("0.10", 3); achat.setMontantVerse(new BigDecimal("0.10"));
        DetailAchat seconde = new DetailAchat(); seconde.setProduit(produit); seconde.setQuantite(1);
        seconde.setPrixAchatUnitaire(new BigDecimal("0.20")); achat.getLignes().add(seconde);
        Long id = achatService.enregistrerAchat(achat).getId();
        assertThat(achats.findById(id).orElseThrow().getMontantTotal()).isEqualByComparingTo("0.50");
        assertThat(achats.findById(id).orElseThrow().getResteAPayer()).isEqualByComparingTo("0.40");
        achatService.reglerAchat(id, new BigDecimal("0.40"), new BigDecimal("0.10"));
        assertThat(achats.findById(id).orElseThrow().getResteAPayer()).isZero();
        assertThat(caisseService.getSoldeActuel()).isEqualByComparingTo("-0.50");
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(14L);
        mvc.perform(get("/factures/achat/" + id)).andExpect(status().isOk())
                .andExpect(content().string(containsString("0.30 GNF")))
                .andExpect(content().string(containsString("0.50 GNF")));
    }

    @Test
    void venteEtDetteUtilisentLePrixExactDuServeur() throws Exception {
        Vente vente = vente(3); vente.setMontantVerse(new BigDecimal("0.10"));
        Long id = venteService.effectuerVente(vente).getId();
        Vente relue = ventes.findById(id).orElseThrow();
        assertThat(relue.getMontantTotal()).isEqualByComparingTo("0.30");
        assertThat(relue.getResteAPayer()).isEqualByComparingTo("0.20");
        assertThat(ventes.calculerTotalVentesDepuis(LocalDate.now().atStartOfDay())).isEqualByComparingTo("0.10");
        mvc.perform(get("/factures/vente/" + id)).andExpect(status().isOk())
                .andExpect(content().string(containsString("0.30 GNF")))
                .andExpect(content().string(containsString("0.20 GNF")));
    }

    @Test
    void unVersementAbsentPaieLeTotalEtZeroConserveLaDette() {
        Long payee = venteService.effectuerVente(vente(3)).getId();
        assertThat(ventes.findById(payee).orElseThrow().getMontantVerse()).isEqualByComparingTo("0.30");
        Vente credit = vente(2); credit.setMontantVerse(BigDecimal.ZERO);
        Long id = venteService.effectuerVente(credit).getId();
        assertThat(ventes.findById(id).orElseThrow().getResteAPayer()).isEqualByComparingTo("0.20");
        assertThat(mouvements.count()).isEqualTo(1);
    }

    @Test
    void lesAgregatsDeCaisseRestentExactsEtAcceptentUneCaisseVide() throws Exception {
        assertThat(caisseService.getSoldeActuel()).isZero();
        assertThat(caisseService.getSoldeCloture(LocalDate.now())).isZero();
        MouvementCaisse ancien = new MouvementCaisse(); ancien.setMontant(new BigDecimal("0.07"));
        ancien.setDateMouvement(LocalDate.now().minusDays(1).atStartOfDay()); ancien.setType("ENTREE");
        ancien.setUtilisateur(utilisateur); mouvements.saveAndFlush(ancien);
        caisseService.enregistrerEntree(new BigDecimal("0.10"), "Test", "VENTE", utilisateur);
        caisseService.enregistrerEntree(new BigDecimal("0.20"), "Test", "VENTE", utilisateur);
        caisseService.enregistrerSortie(new BigDecimal("0.05"), "Test", "DEPENSE", utilisateur);
        LocalDate jour = LocalDate.now();
        assertThat(caisseService.getSoldeOuverture(jour)).isEqualByComparingTo("0.07");
        assertThat(caisseService.getEntreesDuJour(jour)).isEqualByComparingTo("0.30");
        assertThat(caisseService.getSortiesDuJour(jour)).isEqualByComparingTo("0.05");
        assertThat(caisseService.getNetDuJour(jour)).isEqualByComparingTo("0.25");
        assertThat(caisseService.getSoldeCloture(jour)).isEqualByComparingTo("0.32");
        mvc.perform(get("/caisse/journal")).andExpect(status().isOk())
                .andExpect(content().string(containsString("0.32 GNF")));
    }

    @Test
    void lesAnciennesDettesAvecVersementNullNeDisparaissentPas() {
        Vente vente = new Vente(); vente.setMontantTotal(new BigDecimal("0.30"));
        assertThat(vente.getResteAPayer()).isEqualByComparingTo("0.30");
        Achat achat = new Achat(); achat.setMontantTotal(new BigDecimal("0.30"));
        assertThat(achat.getResteAPayer()).isEqualByComparingTo("0.30");
    }

    @Test
    void lesGrandsMontantsRestentExactsDansLesFacturesEtLeFormulaireDeModification() throws Exception {
        Long id = achatService.enregistrerAchat(achat("999999999999999.99", 1)).getId();
        mvc.perform(get("/factures/achat/" + id)).andExpect(status().isOk())
                .andExpect(content().string(containsString("999 999 999 999 999.99 GNF")));
        mvc.perform(get("/achats/modifier/" + id)).andExpect(status().isOk())
                .andExpect(content().string(containsString("\"999999999999999.99\"")));
    }

    @Test
    void unSoldeAgregePeutDepasserLePlafondDunMouvementSansPerdreSesCentimes() throws Exception {
        caisseService.enregistrerEntree(Montants.MAX, "Test", "VENTE", utilisateur);
        caisseService.enregistrerEntree(Montants.MAX, "Test", "VENTE", utilisateur);
        assertThat(caisseService.getSoldeActuel()).isEqualByComparingTo("1999999999999999.98");
        mvc.perform(get("/caisse/journal")).andExpect(status().isOk())
                .andExpect(content().string(containsString("1 999 999 999 999 999.98 GNF")));
    }

    @Test
    void lesFacturesCumuleesAdditionnentLesMontantsEnDecimal() throws Exception {
        Client client = new Client(); client.setNom("Client décimal"); client = clients.save(client);
        for (int quantite : new int[]{1, 2}) {
            Vente vente = vente(quantite); vente.setClient(client); venteService.effectuerVente(vente);
            achatService.enregistrerAchat(achat("0.10", quantite));
        }
        mvc.perform(get("/factures/client/" + client.getId() + "/cumule")).andExpect(status().isOk())
                .andExpect(content().string(containsString("0.30 GNF")));
        mvc.perform(get("/factures/fournisseur/" + fournisseur.getId() + "/cumule")).andExpect(status().isOk())
                .andExpect(content().string(containsString("0.30 GNF")));
    }

    @Test
    void uneAncienneVenteAvecVersementNullAfficheSaDetteEtUnVersementZero() throws Exception {
        Client client = new Client(); client.setNom("Ancien client"); client = clients.save(client);
        Vente vente = vente(3); vente.setClient(client);
        Long id = venteService.effectuerVente(vente).getId();
        Vente ancienne = ventes.findById(id).orElseThrow(); ancienne.setMontantVerse(null); ventes.saveAndFlush(ancienne);
        mvc.perform(get("/factures/vente/" + id)).andExpect(status().isOk())
                .andExpect(content().string(containsString("0.30 GNF")))
                .andExpect(content().string(containsString("0.00 GNF")));
        mvc.perform(get("/factures/client/" + client.getId() + "/cumule")).andExpect(status().isOk())
                .andExpect(content().string(containsString("0.30 GNF")))
                .andExpect(content().string(containsString("0.00 GNF")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "1000000000000000"})
    void leFormulaireProduitRefuseLaPrecisionOuLaTailleExcessive(String prix) throws Exception {
        mvc.perform(post("/produits/ajouter").with(csrf()).param("nom", "Invalide").param("reference", "INVALIDE")
                        .param("quantite", "1").param("categorie.id", produit.getCategorie().getId().toString())
                        .param("prixAchat", prix).param("prixVente", prix))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("produit", "prixAchat", "prixVente"));
        assertThat(produits.count()).isEqualTo(1);
        verifierAucuneOperation();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "0.001", "1000000000000000"})
    void unPrixAchatInvalideNeChangeNiStockNiCaisse(String prix) {
        assertThatThrownBy(() -> achatService.enregistrerAchat(achat(prix, 1))).isInstanceOf(IllegalArgumentException.class);
        verifierAucuneOperation();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "0.001", "1000000000000000", "0.31"})
    void unVersementVenteInvalideAnnuleLaVenteEtLeStock(String montant) {
        Vente vente = vente(3); vente.setMontantVerse(new BigDecimal(montant));
        assertThatThrownBy(() -> venteService.effectuerVente(vente)).isInstanceOf(RuntimeException.class);
        verifierAucuneOperation();
    }

    @Test
    void leDepassementDeTotalAnnuleLensembleDeLOperation() {
        assertThatThrownBy(() -> achatService.enregistrerAchat(achat("999999999999999.99", 2)))
                .isInstanceOf(IllegalArgumentException.class);
        produit.setPrixVente(Montants.MAX); produits.saveAndFlush(produit);
        assertThatThrownBy(() -> venteService.effectuerVente(vente(2))).isInstanceOf(IllegalArgumentException.class);
        verifierAucuneOperation();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "-Infinity"})
    void lesSaisiesNonFiniesSontRefuseesParLesFormulaires(String valeur) throws Exception {
        mvc.perform(post("/ventes/enregistrer").with(csrf()).param("montantVerse", valeur))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/achats/enregistrer").with(csrf()).param("lignes[0].prixAchatUnitaire", valeur))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/produits/ajouter").with(csrf()).param("prixAchat", valeur))
                .andExpect(status().isBadRequest());
        verifierAucuneOperation();
    }

    private Achat achat(String prix, int quantite) {
        Achat achat = new Achat(); achat.setFournisseur(fournisseur);
        DetailAchat ligne = new DetailAchat(); ligne.setProduit(produit); ligne.setQuantite(quantite);
        ligne.setPrixAchatUnitaire(new BigDecimal(prix)); achat.getLignes().add(ligne); return achat;
    }

    private Vente vente(int quantite) {
        Vente vente = new Vente(); DetailVente ligne = new DetailVente();
        ligne.setProduit(produit); ligne.setQuantite(quantite); vente.getLignes().add(ligne); return vente;
    }

    private void verifierAucuneOperation() {
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(10L);
        assertThat(achats.count()).isZero(); assertThat(ventes.count()).isZero(); assertThat(mouvements.count()).isZero();
    }
}
