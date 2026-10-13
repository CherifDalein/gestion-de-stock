package org.example.stock.service;

import org.example.stock.enums.Role;
import org.example.stock.enums.TypeOperationCreation;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

/** Deux vrais espaces en base : ni les identifiants ni les modèles envoyés ne choisissent le propriétaire. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:deux-boutiques-operations;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class DeuxBoutiquesOperationsTests {
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired CategorieRepository categories;
    @Autowired ProduitRepository produits;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired ClientRepository clients;
    @Autowired AchatRepository achats;
    @Autowired VenteRepository ventes;
    @Autowired MouvementCaisseRepository mouvements;
    @Autowired OperationCreationRepository operations;
    @Autowired AchatService achatService;
    @Autowired VenteService venteService;
    @Autowired CaisseService caisseService;
    @Autowired OperationCreationService creationService;

    Boutique premiere, seconde;
    Utilisateur gerantA, gerantB;
    Produit produitA, produitB;
    Fournisseur fournisseurA, fournisseurB;
    Client clientA, clientB;

    @BeforeEach
    void preparerDeuxBoutiques() {
        operations.deleteAll(); mouvements.deleteAll(); ventes.deleteAll(); achats.deleteAll();
        produits.deleteAll(); clients.deleteAll(); fournisseurs.deleteAll(); categories.deleteAll();
        utilisateurs.deleteAll(); boutiques.deleteAll();
        premiere = boutique("Boutique A"); seconde = boutique("Boutique B");
        gerantA = utilisateur("gerant-a@example.test", premiere);
        gerantB = utilisateur("gerant-b@example.test", seconde);
        fournisseurA = fournisseur(premiere); fournisseurB = fournisseur(seconde);
        clientA = client(premiere); clientB = client(seconde);
        produitA = produit(premiere, fournisseurA); produitB = produit(seconde, fournisseurB);
    }

    @Test
    void lesCreationsIgnorentLaBoutiqueDuModeleEtLesListesRestentSeparees() {
        Achat achatA = achat(produitA, fournisseurA); achatA.setBoutique(seconde);
        Vente venteA = vente(produitA, clientA); venteA.setBoutique(seconde);
        Achat achatEnregistre = comme(gerantA, () -> achatService.enregistrerAchat(achatA));
        Vente venteEnregistree = comme(gerantA, () -> venteService.effectuerVente(venteA));
        Achat achatB = comme(gerantB, () -> achatService.enregistrerAchat(achat(produitB, fournisseurB)));
        Vente venteB = comme(gerantB, () -> venteService.effectuerVente(vente(produitB, clientB)));
        assertThat(achatEnregistre.getBoutique().getId()).isEqualTo(premiere.getId());
        assertThat(venteEnregistree.getBoutique().getId()).isEqualTo(premiere.getId());
        comme(gerantA, () -> {
            assertThat(achatService.listerTous()).extracting(Achat::getId).containsExactly(achatEnregistre.getId());
            assertThat(venteService.listerToutes()).extracting(Vente::getId).containsExactly(venteEnregistree.getId());
            assertThat(achatService.trouverParId(achatB.getId())).isNull();
            assertThat(venteService.trouverParId(venteB.getId())).isNull();
            assertThat(caisseService.listerTous()).allSatisfy(m -> assertThat(m.getBoutique().getId()).isEqualTo(premiere.getId()));
            return null;
        });
    }

    @Test
    void uneVenteNePeutConsommerAucunProduitDuneAutreBoutique() {
        Vente vente = vente(produitA, clientA);
        vente.getLignes().add(vente(produitB, clientB).getLignes().getFirst());
        assertThatThrownBy(() -> comme(gerantA, () -> venteService.effectuerVente(vente)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifierAucunEffet();
    }

    @Test
    void unAchatNePeutAugmenterAucunProduitDuneAutreBoutique() {
        Achat achat = achat(produitA, fournisseurA);
        achat.getLignes().add(achat(produitB, fournisseurB).getLignes().getFirst());
        assertThatThrownBy(() -> comme(gerantA, () -> achatService.enregistrerAchat(achat)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifierAucunEffet();
    }

    @Test
    void leClientEtLeFournisseurDoiventAppartenirALaBoutique() {
        assertThatThrownBy(() -> comme(gerantA, () -> venteService.effectuerVente(vente(produitA, clientB))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Client introuvable");
        assertThatThrownBy(() -> comme(gerantA, () -> achatService.enregistrerAchat(achat(produitA, fournisseurB))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Fournisseur introuvable");
        verifierAucunEffet();
    }

    @Test
    void onNePeutNiModifierNiReglerUnDocumentDuneAutreBoutique() {
        Achat achatB = comme(gerantB, () -> achatService.enregistrerAchat(achat(produitB, fournisseurB)));
        Vente venteB = comme(gerantB, () -> venteService.effectuerVente(vente(produitB, clientB)));
        Achat modification = achat(produitA, fournisseurA); modification.setMontantVerse(null);
        comme(gerantA, () -> {
            introuvable(() -> achatService.modifierAchat(achatB.getId(), modification));
            introuvable(() -> achatService.reglerAchat(achatB.getId(), new BigDecimal("1"), BigDecimal.ZERO));
            introuvable(() -> venteService.reglerVente(venteB.getId(), new BigDecimal("1"), BigDecimal.ZERO));
            assertThat(caisseService.listerReglementsAchat(achatB.getId())).isEmpty();
            assertThat(caisseService.listerReglementsVente(venteB.getId())).isEmpty();
            return null;
        });
        assertThat(achats.findById(achatB.getId()).orElseThrow().getMontantVerse()).isZero();
        assertThat(ventes.findById(venteB.getId()).orElseThrow().getMontantVerse()).isZero();
        assertThat(mouvements.count()).isZero();
    }

    @Test
    void laModificationNePeutImporterNiUnFournisseurNiUnProduitEtranger() {
        Achat achatA = comme(gerantA, () -> achatService.enregistrerAchat(achat(produitA, fournisseurA)));
        Achat mauvaisFournisseur = achat(produitA, fournisseurB); mauvaisFournisseur.setMontantVerse(null);
        Achat mauvaisProduit = achat(produitB, fournisseurA); mauvaisProduit.setMontantVerse(null);
        comme(gerantA, () -> {
            assertThatThrownBy(() -> achatService.modifierAchat(achatA.getId(), mauvaisFournisseur)).isInstanceOf(IllegalArgumentException.class);
            introuvable(() -> achatService.modifierAchat(achatA.getId(), mauvaisProduit));
            return null;
        });
        assertThat(produits.findById(produitA.getId()).orElseThrow().getQuantite()).isEqualTo(12L);
        assertThat(produits.findById(produitB.getId()).orElseThrow().getQuantite()).isEqualTo(10L);
        assertThat(achats.findById(achatA.getId()).orElseThrow().getFournisseur().getId()).isEqualTo(fournisseurA.getId());
    }

    @Test
    void toutesLesSommesEtTousLesJournauxDeCaisseSontIsoles() {
        comme(gerantA, () -> { caisseService.enregistrerEntree(new BigDecimal("100"), "Ancien A", "AJUSTEMENT", gerantA); return null; });
        MouvementCaisse ancien = mouvements.findAll().getFirst();
        ancien.setDateMouvement(LocalDate.now().minusDays(1).atTime(12, 0)); mouvements.saveAndFlush(ancien);
        comme(gerantA, () -> {
            caisseService.enregistrerEntree(new BigDecimal("30.15"), "Entrée A", "AJUSTEMENT", gerantA);
            caisseService.enregistrerSortie(new BigDecimal("10.05"), "Sortie A", "DEPENSE", gerantA); return null;
        });
        comme(gerantB, () -> {
            caisseService.enregistrerEntree(new BigDecimal("900"), "Entrée B", "AJUSTEMENT", gerantB);
            caisseService.enregistrerSortie(new BigDecimal("400"), "Sortie B", "DEPENSE", gerantB); return null;
        });
        comme(gerantA, () -> {
            assertThat(caisseService.getSoldeActuel()).isEqualByComparingTo("120.10");
            assertThat(caisseService.getSoldeOuverture(LocalDate.now())).isEqualByComparingTo("100");
            assertThat(caisseService.getEntreesDuJour(LocalDate.now())).isEqualByComparingTo("30.15");
            assertThat(caisseService.getSortiesDuJour(LocalDate.now())).isEqualByComparingTo("10.05");
            assertThat(caisseService.getNetDuJour(LocalDate.now())).isEqualByComparingTo("20.10");
            assertThat(caisseService.getSoldeCloture(LocalDate.now())).isEqualByComparingTo("120.10");
            assertThat(caisseService.getCaisseDuJour(LocalDate.now())).isEqualByComparingTo("20.10");
            assertThat(caisseService.getMouvementsDuJour(LocalDate.now())).hasSize(2);
            assertThat(caisseService.listerTous()).hasSize(3).allSatisfy(m -> assertThat(m.getBoutique().getId()).isEqualTo(premiere.getId()));
            return null;
        });
        assertThat(comme(gerantB, caisseService::getSoldeActuel)).isEqualByComparingTo("500");
    }

    @Test
    void laCaisseRefuseLesDocumentsEtLUtilisateurEtrangersMemeAvecUneBoutiqueFalsifiee() {
        Achat achatB = comme(gerantB, () -> achatService.enregistrerAchat(achat(produitB, fournisseurB)));
        Vente venteB = comme(gerantB, () -> venteService.effectuerVente(vente(produitB, clientB)));
        Achat fauxAchat = new Achat(); fauxAchat.setId(achatB.getId()); fauxAchat.setBoutique(premiere);
        Vente fausseVente = new Vente(); fausseVente.setId(venteB.getId()); fausseVente.setBoutique(premiere);
        Utilisateur fauxUtilisateur = new Utilisateur(); fauxUtilisateur.setId(gerantB.getId()); fauxUtilisateur.setBoutique(premiere);
        comme(gerantA, () -> {
            assertThatThrownBy(() -> caisseService.enregistrerSortieAchat(BigDecimal.ONE, "Faux", fauxAchat, gerantA))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> caisseService.enregistrerEntreeVente(BigDecimal.ONE, "Faux", fausseVente, gerantA))
                    .isInstanceOf(IllegalArgumentException.class);
            introuvable(() -> caisseService.enregistrerEntree(BigDecimal.ONE, "Faux", "AJUSTEMENT", fauxUtilisateur));
            return null;
        });
        assertThat(mouvements.count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(TypeOperationCreation.class)
    void unJetonEtrangerNePeutEtreUtiliseNiRejoue(TypeOperationCreation type) {
        String jeton = comme(gerantB, () -> creationService.ouvrir(type));
        comme(gerantA, () -> { conflit(() -> creer(type, jeton, produitA, fournisseurA, clientA)); return null; });
        assertThat(achats.count() + ventes.count()).isZero();
        var resultat = comme(gerantB, () -> creer(type, jeton, produitB, fournisseurB, clientB));
        comme(gerantA, () -> { conflit(() -> creer(type, jeton, produitB, fournisseurB, clientB)); return null; });
        var rejoue = comme(gerantB, () -> creer(type, jeton, produitB, fournisseurB, clientB));
        assertThat(rejoue.dejaEnregistre()).isTrue();
        assertThat(rejoue.documentId()).isEqualTo(resultat.documentId());
        assertThat(achats.count() + ventes.count()).isEqualTo(1);
        assertThat(operations.findById(jeton).orElseThrow().getBoutique().getId()).isEqualTo(seconde.getId());
    }

    @ParameterizedTest
    @EnumSource(TypeOperationCreation.class)
    void unResultatDeJetonIncoherentNeRevelePasUnDocumentEtranger(TypeOperationCreation type) {
        String jeton = comme(gerantA, () -> creationService.ouvrir(type));
        var operation = operations.findById(jeton).orElseThrow();
        if (type == TypeOperationCreation.ACHAT)
            operation.setAchat(comme(gerantB, () -> achatService.enregistrerAchat(achat(produitB, fournisseurB))));
        else operation.setVente(comme(gerantB, () -> venteService.effectuerVente(vente(produitB, clientB))));
        operations.saveAndFlush(operation);
        comme(gerantA, () -> { introuvable(() -> creer(type, jeton, produitA, fournisseurA, clientA)); return null; });
        assertThat(achats.count() + ventes.count()).isEqualTo(1);
        assertThat(produits.findById(produitA.getId()).orElseThrow().getQuantite()).isEqualTo(10L);
    }

    @Test
    void lesReglementsLocauxRestentFonctionnelsEtNaffectentPasLAutreCaisse() {
        Achat achatA = comme(gerantA, () -> achatService.enregistrerAchat(achat(produitA, fournisseurA)));
        Vente venteB = comme(gerantB, () -> venteService.effectuerVente(vente(produitB, clientB)));
        comme(gerantA, () -> { achatService.reglerAchat(achatA.getId(), new BigDecimal("4.25"), BigDecimal.ZERO); return null; });
        comme(gerantB, () -> { venteService.reglerVente(venteB.getId(), new BigDecimal("7.10"), BigDecimal.ZERO); return null; });
        assertThat(comme(gerantA, caisseService::getSoldeActuel)).isEqualByComparingTo("-4.25");
        assertThat(comme(gerantB, caisseService::getSoldeActuel)).isEqualByComparingTo("7.10");
        assertThat(comme(gerantA, () -> caisseService.listerReglementsAchat(achatA.getId()))).hasSize(1);
        assertThat(comme(gerantB, () -> caisseService.listerReglementsVente(venteB.getId()))).hasSize(1);
    }

    @Test
    void lesAgregatsVentesEtLesHistoriquesDeFacturesFiltrentParBoutique() {
        Vente venteA = vente(produitA, clientA); venteA.setMontantVerse(new BigDecimal("4.10"));
        Vente venteB = vente(produitB, clientB); venteB.setMontantVerse(new BigDecimal("20.90"));
        Vente enregistreeA = comme(gerantA, () -> venteService.effectuerVente(venteA));
        comme(gerantB, () -> venteService.effectuerVente(venteB));
        Achat achatA = comme(gerantA, () -> achatService.enregistrerAchat(achat(produitA, fournisseurA)));
        comme(gerantB, () -> achatService.enregistrerAchat(achat(produitB, fournisseurB)));
        LocalDateTime debut = LocalDate.now().atStartOfDay();
        assertThat(ventes.calculerTotalVentesDepuis(debut, premiere.getId())).isEqualByComparingTo("4.10");
        assertThat(ventes.findByDateVenteAfterAndBoutiqueId(debut, premiere.getId())).extracting(Vente::getId).containsExactly(enregistreeA.getId());
        assertThat(ventes.findByClientIdAndBoutiqueIdOrderByDateVenteDesc(clientB.getId(), premiere.getId())).isEmpty();
        assertThat(ventes.findByClientIdAndDateVenteAfterAndBoutiqueIdOrderByDateVenteDesc(clientB.getId(), debut, premiere.getId())).isEmpty();
        assertThat(achats.findByDateAchatAfterAndBoutiqueId(debut, premiere.getId())).extracting(Achat::getId).containsExactly(achatA.getId());
        assertThat(achats.findByFournisseurIdAndBoutiqueIdOrderByDateAchatDesc(fournisseurB.getId(), premiere.getId())).isEmpty();
        assertThat(achats.findByFournisseurIdAndDateAchatAfterAndBoutiqueIdOrderByDateAchatDesc(fournisseurB.getId(), debut, premiere.getId())).isEmpty();
    }

    private Boutique boutique(String nom) { Boutique boutique = new Boutique(); boutique.setNom(nom); return boutiques.saveAndFlush(boutique); }
    private Utilisateur utilisateur(String email, Boutique boutique) {
        Utilisateur utilisateur = new Utilisateur(); utilisateur.setNom("Gérant"); utilisateur.setEmail(email);
        utilisateur.setRole(Role.ADMIN); utilisateur.setBoutique(boutique); return utilisateurs.saveAndFlush(utilisateur);
    }
    private Fournisseur fournisseur(Boutique boutique) {
        Fournisseur fournisseur = new Fournisseur(); fournisseur.setNom("Fournisseur"); fournisseur.setBoutique(boutique); return fournisseurs.saveAndFlush(fournisseur);
    }
    private Client client(Boutique boutique) {
        Client client = new Client(); client.setNom("Client"); client.setBoutique(boutique); return clients.saveAndFlush(client);
    }
    private Produit produit(Boutique boutique, Fournisseur fournisseur) {
        Categorie categorie = new Categorie(); categorie.setNom("Même catégorie"); categorie.setBoutique(boutique); categorie = categories.saveAndFlush(categorie);
        Produit produit = new Produit(); produit.setBoutique(boutique); produit.setNom("Produit"); produit.setReference("MEME-REFERENCE");
        produit.setQuantite(10L); produit.setPrixAchat(new BigDecimal("5")); produit.setPrixVente(new BigDecimal("20"));
        produit.setCategorie(categorie); produit.setFournisseur(fournisseur); return produits.saveAndFlush(produit);
    }
    private Achat achat(Produit produit, Fournisseur fournisseur) {
        Achat achat = new Achat(); Fournisseur reference = new Fournisseur(); reference.setId(fournisseur.getId()); achat.setFournisseur(reference);
        achat.setMontantVerse(BigDecimal.ZERO); DetailAchat ligne = new DetailAchat(); ligne.setProduit(referenceProduit(produit));
        ligne.setQuantite(2); ligne.setPrixAchatUnitaire(new BigDecimal("5")); ligne.setPrixVenteUnitaire(produit.getPrixVente()); achat.getLignes().add(ligne); return achat;
    }
    private Vente vente(Produit produit, Client client) {
        Vente vente = new Vente(); Client reference = new Client(); reference.setId(client.getId()); vente.setClient(reference);
        vente.setMontantVerse(BigDecimal.ZERO); DetailVente ligne = new DetailVente(); ligne.setProduit(referenceProduit(produit)); ligne.setQuantite(2);
        vente.getLignes().add(ligne); return vente;
    }
    private Produit referenceProduit(Produit produit) { Produit reference = new Produit(); reference.setId(produit.getId()); return reference; }
    private OperationCreationService.ResultatCreation creer(TypeOperationCreation type, String jeton, Produit produit, Fournisseur fournisseur, Client client) {
        return type == TypeOperationCreation.ACHAT ? creationService.creerAchat(jeton, achat(produit, fournisseur))
                : creationService.creerVente(jeton, vente(produit, client));
    }
    private void verifierAucunEffet() {
        assertThat(produits.findById(produitA.getId()).orElseThrow().getQuantite()).isEqualTo(10L);
        assertThat(produits.findById(produitB.getId()).orElseThrow().getQuantite()).isEqualTo(10L);
        assertThat(achats.count()).isZero(); assertThat(ventes.count()).isZero(); assertThat(mouvements.count()).isZero();
    }
    private void introuvable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
    private void conflit(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CreationOperationException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }
    private <T> T comme(Utilisateur utilisateur, Supplier<T> action) {
        var precedent = SecurityContextHolder.getContext(); var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(utilisateur.getEmail(), "",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(contexte);
        try { return action.get(); } finally { SecurityContextHolder.setContext(precedent); }
    }
}
