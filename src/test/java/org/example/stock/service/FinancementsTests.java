package org.example.stock.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.example.stock.enums.Periodicite;
import org.example.stock.enums.Role;
import org.example.stock.enums.SensEmprunt;
import org.example.stock.enums.TypeOperationTresorerie;
import org.example.stock.enums.TypePartenaireFinancier;
import org.example.stock.form.EcheancierForm;
import org.example.stock.form.EmpruntForm;
import org.example.stock.form.OperationFinanciereForm;
import org.example.stock.form.PartenaireFinancierForm;
import org.example.stock.form.RemboursementForm;
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
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Financements (étape 3b1) : partenaires, apports et retraits, emprunts et prêts, échéancier, remboursements, annulations, retards,
 * bilan, droits et isolation entre boutiques, justificatifs et exports. H2, vraies transactions.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:financements;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class FinancementsTests {
    static final String ADMIN = "gerant-fin@example.test", CAISSIER = "caissier-fin@example.test", ADMIN2 = "gerant2-fin@example.test";

    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired MouvementCaisseRepository mouvements;
    @Autowired OperationTresorerieRepository operations;
    @Autowired ImputationEcheanceRepository imputations;
    @Autowired EcheanceEmpruntRepository echeances;
    @Autowired EmpruntRepository emprunts;
    @Autowired PartenaireFinancierRepository partenaires;
    @Autowired EmpruntService empruntService;
    @Autowired PartenairesFinanciersService partenaireService;
    @Autowired OperationsFinancieresService operationService;
    @Autowired FinancementsExportService exportService;
    @Autowired JustificatifSoldeService justificatifs;
    @Autowired BilanService bilan;
    @Autowired CaisseService caisse;
    @Autowired EcheanceService horloge;
    @Autowired MockMvc mvc;

    Boutique boutique, autre;
    PartenaireFinancier banque, associe;

    @BeforeEach
    void preparer() {
        mouvements.deleteAll(); imputations.deleteAll(); echeances.deleteAll(); operations.deleteAll(); emprunts.deleteAll(); partenaires.deleteAll(); utilisateurs.deleteAll();
        boutique = boutique("Boutique financements"); autre = boutique("Autre boutique financements");
        utilisateur(ADMIN, Role.ADMIN, boutique); utilisateur(CAISSIER, Role.CAISSIER, boutique); utilisateur(ADMIN2, Role.ADMIN, autre);
        connecter(ADMIN, "ADMIN");
        banque = partenaire("BICIGUI", TypePartenaireFinancier.BANQUE);
        associe = partenaire("Mamadou Diallo", TypePartenaireFinancier.ASSOCIE);
    }

    @AfterEach void deconnecter() { SecurityContextHolder.clearContext(); }

    // ---- Emprunt : création et échéancier -----------------------------------------------------------------------------------

    @Test
    void unEmpruntEntreSurLeCompteEtGenereSonEcheancier() {
        Long id = emprunter("1200000", "12", 12, null).id();
        Emprunt e = emprunts.findById(id).orElseThrow();
        assertThat(e.getNumero()).isEqualTo("EM-000001");
        assertThat(e.estEmprunt()).isTrue();
        assertThat(solde("BANQUE")).isEqualByComparingTo("1200000");
        var detail = empruntService.detail(id);
        assertThat(detail.vue().situation().lignes()).hasSize(12);
        assertThat(detail.vue().situation().interetsPrevus()).isEqualByComparingTo("78000");
        assertThat(detail.deblocage()).isNotNull();
        assertThat(detail.remboursements()).isEmpty();
        assertThat(mouvements.findByOperationTresorerieIdOrderByIdAsc(detail.deblocage().getId())).singleElement()
                .satisfies(m -> { assertThat(m.getSource()).isEqualTo(SourceMouvement.DEBLOCAGE_EMPRUNT); assertThat(m.getMontant()).isEqualByComparingTo("1200000"); });
        // Le prochain emprunt prend le numéro suivant ; un prêt a sa propre numérotation.
        assertThat(emprunts.findById(emprunter("100000", "0", 2, null).id()).orElseThrow().getNumero()).isEqualTo("EM-000002");
    }

    @Test
    void lesFraisDeDossierSortentDuCompteEtSontUneChargeFinanciere() {
        emprunter("1000000", "0", 10, "25000");
        assertThat(solde("BANQUE")).isEqualByComparingTo("975000");
        assertThat(mouvements.findAll()).extracting(MouvementCaisse::getSource).contains(SourceMouvement.FRAIS_DOSSIER);
        assertThatThrownBy(() -> empruntService.creer(forme(SensEmprunt.EMPRUNT, "1000", "0", 2, "1001"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> empruntService.creer(forme(SensEmprunt.PRET, "1000", "0", 2, "10"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lesFormulairesInvalidesOuRepetesNeCreentRien() {
        var f = forme(SensEmprunt.EMPRUNT, "500000", "10", 5, null);
        var premier = empruntService.creer(f);
        var repete = empruntService.creer(f);
        assertThat(premier.repetition()).isFalse();
        assertThat(repete.repetition()).isTrue();
        assertThat(repete.id()).isEqualTo(premier.id());
        assertThat(emprunts.count()).isEqualTo(1);
        assertThat(solde("BANQUE")).isEqualByComparingTo("500000");
        var autreMontant = forme(SensEmprunt.EMPRUNT, "600000", "10", 5, null); autreMontant.setJetonOperation(f.getJetonOperation());
        assertThatThrownBy(() -> empruntService.creer(autreMontant)).isInstanceOf(OperationTresorerieConflitException.class);
        assertThatThrownBy(() -> empruntService.creer(forme(SensEmprunt.EMPRUNT, "0", "10", 5, null))).isInstanceOf(IllegalArgumentException.class);
        var futur = forme(SensEmprunt.EMPRUNT, "1000", "10", 5, null); futur.setDateEmprunt(horloge.aujourdHui().plusDays(2));
        assertThatThrownBy(() -> empruntService.creer(futur)).isInstanceOf(IllegalArgumentException.class);
        assertThat(emprunts.count()).isEqualTo(1);
    }

    @Test
    void unPretSortDuCompteSeulementSiLeSoldeSuffit() {
        associeApporte("ESPECES", "300000");
        assertThatThrownBy(() -> empruntService.creer(forme(SensEmprunt.PRET, "300000.01", "0", 3, null))).hasMessageContaining("insuffisant");
        var id = empruntService.creer(forme(SensEmprunt.PRET, "300000", "0", 3, null)).id();
        assertThat(emprunts.findById(id).orElseThrow().getNumero()).isEqualTo("PR-000001");
        assertThat(solde("ESPECES")).isEqualByComparingTo("0");
    }

    @Test
    void leTotalDuCapitalDeLEcheancierModifieRestePositionEtEgalAuCapital() {
        Long id = emprunter("1000", "12", 2, null).id();
        var f = new EcheancierForm();
        f.getDates().add(horloge.aujourdHui().plusDays(10)); f.getDates().add(horloge.aujourdHui().plusDays(40));
        f.getCapitaux().add(new BigDecimal("400")); f.getCapitaux().add(new BigDecimal("600"));
        empruntService.modifierEcheancier(id, f);
        var lignes = empruntService.vue(id).situation().lignes();
        assertThat(lignes.get(0).echeance().getCapital()).isEqualByComparingTo("400");
        assertThat(lignes.get(0).echeance().getInteret()).isEqualByComparingTo("10.00");
        assertThat(lignes.get(1).echeance().getInteret()).isEqualByComparingTo("6.00");
        f.getCapitaux().set(1, new BigDecimal("599"));
        assertThatThrownBy(() -> empruntService.modifierEcheancier(id, f)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Remboursements ------------------------------------------------------------------------------------------------------

    @Test
    void unRemboursementPartielEstImputeInteretsPuisCapital() {
        Long id = emprunter("1200000", "12", 12, null).id();
        assertThat(rembourser(id, "20000")).isFalse();
        var s = empruntService.vue(id).situation();
        assertThat(s.interetsPayes()).isEqualByComparingTo("12000");
        assertThat(s.capitalPaye()).isEqualByComparingTo("8000");
        assertThat(s.capitalRestantDu()).isEqualByComparingTo("1192000");
        assertThat(s.prochaine().rang()).isEqualTo(1);
        assertThat(solde("BANQUE")).isEqualByComparingTo("1180000");
        var op = empruntService.detail(id).remboursements().getFirst().operation();
        var ms = mouvements.findByOperationTresorerieIdOrderByIdAsc(op.getId());
        assertThat(ms).extracting(MouvementCaisse::getSource).containsExactly(SourceMouvement.REMBOURSEMENT_EMPRUNT, SourceMouvement.INTERETS_EMPRUNT);
        assertThat(ms).extracting(MouvementCaisse::getMontant).usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("-8000"), new BigDecimal("-12000"));
    }

    @Test
    void unPaiementCouvrePlusieursEcheancesEtLeDossierSeClotAutomatiquement() {
        Long id = emprunter("300000", "12", 3, null).id();
        operationService.enregistrer(operation(TypeOperationTresorerie.PRODUIT_FINANCIER, null, "BANQUE", "50000", "Provision pour les intérêts"));
        rembourser(id, "150000");
        var s = empruntService.vue(id).situation();
        assertThat(s.lignes().get(0).soldee()).isTrue();
        assertThat(s.lignes().get(1).partielle()).isTrue();
        assertThat(s.prochaine().rang()).isEqualTo(2);
        rembourser(id, s.totalRestantDu().toPlainString());
        assertThat(empruntService.vue(id).situation().clos()).isTrue();
        assertThat(empruntService.vue(id).situation().capitalRestantDu()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> rembourser(id, "1")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("soldé");
    }

    @Test
    void unRemboursementEnAvanceNeChangePasLesInteretsPrevus() {
        Long id = emprunter("300000", "12", 3, null).id();
        BigDecimal prevus = empruntService.vue(id).situation().interetsPrevus();
        rembourser(id, "206000"); // les deux premières échéances en entier
        var s = empruntService.vue(id).situation();
        assertThat(s.interetsPrevus()).isEqualByComparingTo(prevus);
        assertThat(s.lignes().get(0).soldee() && s.lignes().get(1).soldee()).isTrue();
        assertThat(s.enRetardMaintenant()).isFalse();
    }

    @Test
    void leMontantNeDepassePasLeRestantNiLeSoldeDuCompte() {
        Long id = emprunter("100000", "0", 2, null).id();
        assertThatThrownBy(() -> rembourser(id, "100000.01")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rembourser(id, "0")).isInstanceOf(IllegalArgumentException.class);
        // On vide la banque, puis le remboursement manque de fonds.
        var retrait = operation(TypeOperationTresorerie.AUTRE_CHARGE, null, "BANQUE", "100000", "Vidage du compte");
        operationService.enregistrer(retrait);
        assertThatThrownBy(() -> rembourser(id, "50000")).hasMessageContaining("insuffisant");
        assertThat(imputations.activesDeLEmprunt(id)).isEmpty();
    }

    @Test
    void leFormulaireObsoleteEstRefuseEtLaRepetitionNePayePasDeuxFois() {
        Long id = emprunter("300000", "0", 3, null).id();
        var vue = empruntService.vue(id);
        var a = remboursement("100000", "BANQUE", vue.situation());
        var b = remboursement("100000", "BANQUE", vue.situation());
        assertThat(empruntService.rembourser(id, a)).isFalse();
        // Même formulaire renvoyé : répétition exacte, rien de plus.
        assertThat(empruntService.rembourser(id, a)).isTrue();
        // Second formulaire ouvert avant le premier paiement : l'état affiché a changé.
        assertThatThrownBy(() -> empruntService.rembourser(id, b)).isInstanceOf(EmpruntObsoleteException.class);
        assertThat(empruntService.vue(id).situation().capitalPaye()).isEqualByComparingTo("100000");
        assertThat(solde("BANQUE")).isEqualByComparingTo("200000");
    }

    @Test
    void leRemboursementAnticipeExigeLeSoldeExactEtRemetLesInteretsFuturs() {
        Long id = emprunter("300000", "12", 3, null).id();
        var s = empruntService.vue(id).situation();
        BigDecimal anticipe = s.soldeAnticipe();
        assertThat(anticipe).isEqualByComparingTo("300000"); // aucune échéance échue : le capital seul
        var faux = remboursement("299999", "BANQUE", s); faux.setAnticipe(true);
        assertThatThrownBy(() -> empruntService.rembourser(id, faux)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("anticipé");
        var bon = remboursement(anticipe.toPlainString(), "BANQUE", s); bon.setAnticipe(true);
        empruntService.rembourser(id, bon);
        var apres = empruntService.vue(id).situation();
        assertThat(apres.clos()).isTrue();
        assertThat(apres.interetsPayes()).isEqualByComparingTo("0");
        assertThat(apres.interetsRemis()).isEqualByComparingTo("6000");
        assertThat(solde("BANQUE")).isEqualByComparingTo("0");
    }

    @Test
    void annulerUnRemboursementRemetLesSoldesEtSeFaitDuDernierAuPremier() {
        Long id = emprunter("300000", "0", 3, null).id();
        rembourser(id, "100000");
        rembourser(id, "50000");
        var ops = empruntService.detail(id).remboursements();
        Long recent = ops.get(0).operation().getId(), ancien = ops.get(1).operation().getId();
        assertThatThrownBy(() -> empruntService.annulerRemboursement(id, ancien, "Erreur de saisie")).hasMessageContaining("plus récent");
        assertThatThrownBy(() -> empruntService.annulerRemboursement(id, recent, "x")).hasMessageContaining("motif");
        empruntService.annulerRemboursement(id, recent, "Erreur de saisie");
        assertThat(empruntService.vue(id).situation().capitalPaye()).isEqualByComparingTo("100000");
        assertThat(solde("BANQUE")).isEqualByComparingTo("200000");
        assertThatThrownBy(() -> empruntService.annulerRemboursement(id, recent, "Encore une fois")).isInstanceOf(AnnulationDejaFaiteException.class);
        empruntService.annulerRemboursement(id, ancien, "Erreur de saisie");
        assertThat(empruntService.vue(id).situation().capitalPaye()).isEqualByComparingTo("0");
        assertThat(solde("BANQUE")).isEqualByComparingTo("300000");
        // Plus aucun remboursement actif : l'échéancier est de nouveau modifiable.
        assertThat(empruntService.vue(id).situation().aUnRemboursement()).isFalse();
        assertThat(empruntService.detail(id).remboursements()).allSatisfy(r -> assertThat(r.annule()).isTrue());
    }

    @Test
    void lAnnulationReouvreLeDossierClos() {
        Long id = emprunter("100000", "0", 1, null).id();
        rembourser(id, "100000");
        assertThat(empruntService.vue(id).situation().clos()).isTrue();
        empruntService.annulerRemboursement(id, empruntService.detail(id).remboursements().getFirst().operation().getId(), "Paiement non reçu");
        assertThat(empruntService.vue(id).situation().clos()).isFalse();
    }

    @Test
    void lesEcheancesEnRetardSontDetecteesEtSignaleesAuTableauDeBord() {
        LocalDate jour = horloge.aujourdHui();
        var f = forme(SensEmprunt.EMPRUNT, "300000", "12", 3, null);
        f.setDateEmprunt(jour.minusDays(100)); f.setDatePremiereEcheance(jour.minusDays(70));
        Long id = empruntService.creer(f).id();
        operationService.enregistrer(operation(TypeOperationTresorerie.PRODUIT_FINANCIER, null, "BANQUE", "50000", "Provision pour les intérêts"));
        var s = empruntService.vue(id).situation();
        assertThat(s.enRetardMaintenant()).isTrue();
        assertThat(s.nombreRetards()).isEqualTo(3);
        assertThat(s.statut()).isEqualTo("En retard");
        assertThat(s.plusGrandRetardEnJours()).isEqualTo(70);
        var retards = empruntService.retards(5);
        assertThat(retards.emprunts()).isEqualTo(1);
        assertThat(retards.echeances()).isEqualTo(3);
        assertThat(retards.montant()).isEqualByComparingTo(s.montantEnRetard());
        rembourser(id, s.totalRestantDu().toPlainString());
        assertThat(empruntService.retards(5).aucun()).isTrue();
    }

    @Test
    void lEcheancierNeSeModifiePlusApresUnRemboursement() {
        Long id = emprunter("1000", "0", 2, null).id();
        rembourser(id, "100");
        var f = new EcheancierForm();
        f.getDates().add(horloge.aujourdHui().plusDays(10)); f.getDates().add(horloge.aujourdHui().plusDays(40));
        f.getCapitaux().add(new BigDecimal("500")); f.getCapitaux().add(new BigDecimal("500"));
        assertThatThrownBy(() -> empruntService.modifierEcheancier(id, f)).hasMessageContaining("figé");
    }

    @Test
    void lesInteretsDUnPretSontUnProduitFinancier() {
        associeApporte("ESPECES", "500000");
        Long id = empruntService.creer(forme(SensEmprunt.PRET, "300000", "12", 3, null)).id();
        rembourser(id, "103000", "ESPECES");
        var sources = mouvements.findAll().stream().map(MouvementCaisse::getSource).toList();
        assertThat(sources).contains(SourceMouvement.REMBOURSEMENT_PRET, SourceMouvement.INTERETS_PRET);
        var b = bilan.calculer(periode());
        assertThat(b.indicateurs().horsExploitation().produitsFinanciers()).isEqualByComparingTo("3000");
        assertThat(b.indicateurs().horsExploitation().chargesFinancieres()).isEqualByComparingTo("0");
    }

    // ---- Apports, retraits, partenaires ----------------------------------------------------------------------------------

    @Test
    void lesApportsEtRetraitsDUnAssocieModifientSonSoldeEtSAnnulent() {
        associeApporte("ESPECES", "1000000");
        operationService.enregistrer(operation(TypeOperationTresorerie.RETRAIT_COMPTE_COURANT, associe.getId(), "ESPECES", "250000", "Retrait personnel"));
        operationService.enregistrer(operation(TypeOperationTresorerie.APPORT_CAPITAL, associe.getId(), "BANQUE", "500000", "Capital"));
        var vue = partenaireService.vue(associe.getId());
        assertThat(vue.solde().compteCourant()).isEqualByComparingTo("750000");
        assertThat(vue.solde().capitalApporte()).isEqualByComparingTo("500000");
        assertThat(vue.solde().total()).isEqualByComparingTo("750000");
        assertThat(vue.solde().laBoutiqueDoit()).isTrue();
        assertThat(solde("ESPECES")).isEqualByComparingTo("750000");
        var retrait = operations.deTypes(boutique.getId(), List.of(TypeOperationTresorerie.RETRAIT_COMPTE_COURANT)).getFirst();
        operationService.annuler(retrait.getId(), "Saisi par erreur");
        assertThat(partenaireService.vue(associe.getId()).solde().compteCourant()).isEqualByComparingTo("1000000");
        assertThat(solde("ESPECES")).isEqualByComparingTo("1000000");
        assertThatThrownBy(() -> operationService.annuler(retrait.getId(), "Deuxième tentative")).isInstanceOf(AnnulationDejaFaiteException.class);
    }

    @Test
    void unApportExigeUnAssocieActifEtUnRetraitUnSoldeSuffisant() {
        assertThatThrownBy(() -> operationService.enregistrer(operation(TypeOperationTresorerie.APPORT_CAPITAL, banque.getId(), "ESPECES", "1000", "Apport"))).hasMessageContaining("Associé");
        assertThatThrownBy(() -> operationService.enregistrer(operation(TypeOperationTresorerie.APPORT_CAPITAL, null, "ESPECES", "1000", "Apport"))).hasMessageContaining("associé");
        assertThatThrownBy(() -> operationService.enregistrer(operation(TypeOperationTresorerie.RETRAIT_CAPITAL, associe.getId(), "ESPECES", "1000", "Retrait"))).hasMessageContaining("insuffisant");
        assertThatThrownBy(() -> operationService.enregistrer(operation(TypeOperationTresorerie.APPORT_CAPITAL, associe.getId(), "ESPECES", "0", "Apport"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operationService.enregistrer(operation(TypeOperationTresorerie.TRANSFERT, associe.getId(), "ESPECES", "10", "Autre"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(operations.count()).isZero();
    }

    @Test
    void leSoldeDUnPartenaireAddittionneCompteCourantEtDossiers() {
        associeApporte("ESPECES", "100000");
        Long emprunt = empruntService.creer(formePour(associe, SensEmprunt.EMPRUNT, "200000", "0", 2, null)).id();
        var s = partenaireService.vue(associe.getId()).solde();
        assertThat(s.compteCourant()).isEqualByComparingTo("100000");
        assertThat(s.empruntsNets()).isEqualByComparingTo("200000");
        assertThat(s.total()).isEqualByComparingTo("300000");
        rembourser(emprunt, "100000", "ESPECES");
        assertThat(partenaireService.vue(associe.getId()).solde().total()).isEqualByComparingTo("200000");
        assertThat(partenaireService.detail(associe.getId()).emprunts()).hasSize(1);
        assertThat(partenaireService.detail(associe.getId()).operations()).isNotEmpty();
    }

    @Test
    void unPartenaireAvecSoldeOuDossierNeSArchivePasEtSonTypeNeChangePlus() {
        associeApporte("ESPECES", "1000");
        assertThatThrownBy(() -> partenaireService.definirActif(associe.getId(), false)).hasMessageContaining("solde");
        var f = new PartenaireFinancierForm(); f.setNom("Mamadou Diallo"); f.setType(TypePartenaireFinancier.DIVERS);
        assertThatThrownBy(() -> partenaireService.modifier(associe.getId(), f)).hasMessageContaining("type");
        partenaireService.definirActif(banque.getId(), false);
        assertThat(partenaireService.lister(false)).extracting(v -> v.partenaire().getNom()).doesNotContain("BICIGUI");
        assertThat(partenaireService.lister(true)).extracting(v -> v.partenaire().getNom()).contains("BICIGUI");
        assertThatThrownBy(() -> empruntService.creer(forme(SensEmprunt.EMPRUNT, "1000", "0", 2, null))).hasMessageContaining("archivé");
        partenaireService.definirActif(banque.getId(), true);
        var dossier = emprunter("1000", "0", 2, null).id();
        assertThat(dossier).isNotNull();
        assertThatThrownBy(() -> partenaireService.definirActif(banque.getId(), false)).hasMessageContaining("solde");
    }

    @Test
    void leNomDUnPartenaireEstUniqueParBoutiqueSansTenirCompteDeLaCasse() {
        var f = new PartenaireFinancierForm(); f.setNom("  bicigui "); f.setType(TypePartenaireFinancier.BANQUE);
        assertThatThrownBy(() -> partenaireService.creer(f)).hasMessageContaining("existe déjà");
        connecter(ADMIN2, "ADMIN");
        assertThat(partenaireService.creer(f).getNom()).isEqualTo("bicigui");
    }

    // ---- Bilan -----------------------------------------------------------------------------------------------------------

    @Test
    void leBeneficeNetIncluraLesInteretsMaisPasLeCapitalNiLesApports() {
        associeApporte("ESPECES", "500000");
        Long id = emprunter("1200000", "12", 12, "10000").id();
        rembourser(id, "112000"); // 100 000 de capital et 12 000 d'intérêts
        operationService.enregistrer(operation(TypeOperationTresorerie.PRODUIT_FINANCIER, null, "BANQUE", "7000", "Intérêts créditeurs"));
        operationService.enregistrer(operation(TypeOperationTresorerie.AUTRE_CHARGE, null, "BANQUE", "3000", "Amende"));
        var b = bilan.calculer(periode());
        var h = b.indicateurs().horsExploitation();
        assertThat(h.chargesFinancieres()).isEqualByComparingTo("22000");      // intérêts 12 000 + frais 10 000
        assertThat(h.produitsFinanciers()).isEqualByComparingTo("7000");
        assertThat(h.autresCharges()).isEqualByComparingTo("3000");
        assertThat(h.autresProduits()).isEqualByComparingTo("0");
        assertThat(b.indicateurs().beneficeNet()).isEqualByComparingTo("-18000"); // 7 000 − 22 000 − 3 000
        // Variation de trésorerie : apport 500 000 + emprunt 1 200 000 − frais 10 000 − remboursement 112 000 + 7 000 − 3 000.
        assertThat(b.variationTresorerie()).isEqualByComparingTo("1582000");
        assertThat(b.financements().get(SourceMouvement.APPORT_COMPTE_COURANT)).isEqualByComparingTo("500000");
        assertThat(b.financements().get(SourceMouvement.DEBLOCAGE_EMPRUNT)).isEqualByComparingTo("1200000");
        assertThat(b.financements().get(SourceMouvement.REMBOURSEMENT_EMPRUNT)).isEqualByComparingTo("-100000");
        assertThat(b.financements().get(SourceMouvement.INTERETS_EMPRUNT)).isEqualByComparingTo("-12000");
    }

    @Test
    void lesAnnulationsSontNettesDansLeBilan() {
        Long id = emprunter("300000", "12", 3, null).id();
        rembourser(id, "103000");
        empruntService.annulerRemboursement(id, empruntService.detail(id).remboursements().getFirst().operation().getId(), "Erreur de saisie");
        var b = bilan.calculer(periode());
        assertThat(b.indicateurs().horsExploitation().chargesFinancieres()).isEqualByComparingTo("0");
        assertThat(b.financements().get(SourceMouvement.REMBOURSEMENT_EMPRUNT)).isEqualByComparingTo("0");
        assertThat(b.variationTresorerie()).isEqualByComparingTo("300000");
        assertThat(b.autresFlux()).isEqualByComparingTo("0");
    }

    // ---- Documents et exports ----------------------------------------------------------------------------------------------

    @Test
    void leJustificatifDUnPartenaireIndiqueSonSoldeEnLettres() throws Exception {
        associeApporte("ESPECES", "2000000");
        var j = justificatifs.partenaireFinancier(associe.getId());
        assertThat(j.numero()).isEqualTo("JS-" + horloge.aujourdHui().toString().replace("-", "") + "-P" + associe.getId());
        assertThat(j.laBoutiqueDoit()).isTrue();
        assertThat(j.montantEnLettres()).contains("deux millions");
        byte[] pdf = mvc.perform(get("/etats/justificatifs/partenaire/pdf").param("id", String.valueOf(associe.getId())).with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf")).andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String texte = new PDFTextStripper().getText(doc);
            assertThat(texte.replaceAll("[\\s\\u00a0\\u202f]+", " ")).contains("Mamadou Diallo").contains("deux millions").contains("2 000 000");
        }
    }

    @Test
    void leRecuDeRemboursementEtLEcheancierSontDisponiblesEnPdfEtExcel() throws Exception {
        Long id = emprunter("300000", "12", 3, null).id();
        rembourser(id, "103000");
        Long op = empruntService.detail(id).remboursements().getFirst().operation().getId();
        byte[] recu = mvc.perform(get("/financements/emprunts/" + id + "/remboursements/" + op + "/recu").with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf")).andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(recu)) {
            String texte = new PDFTextStripper().getText(doc);
            assertThat(texte).contains("BICIGUI").contains("103").contains("EM-000001");
        }
        connecter(ADMIN, "ADMIN");
        empruntService.annulerRemboursement(id, op, "Paiement refusé");
        byte[] annule = mvc.perform(get("/financements/emprunts/" + id + "/remboursements/" + op + "/recu").with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(annule)) { assertThat(new PDFTextStripper().getText(doc)).contains("ANNUL"); }
        mvc.perform(get("/financements/emprunts/" + id + "/echeancier/pdf").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
        mvc.perform(get("/financements/emprunts/" + id + "/echeancier/excel").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/financements/partenaires/export/excel").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/financements/partenaires/export/pdf").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
        mvc.perform(get("/financements/partenaires/" + banque.getId() + "/releve/pdf").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/financements/partenaires/" + banque.getId() + "/releve/excel").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/etats/emprunts-prets/export/pdf").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/etats/emprunts-prets/export/excel").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk());
        connecter(ADMIN, "ADMIN");
        assertThat(exportService.echeancier(id).getLignes()).isNotEmpty();
    }

    @Test
    void lesOperationsFinancieresPortentLeNomDeCeluiQuiLesSaisitSurEcranEtDansLExport() throws Exception {
        Long id = emprunter("500000", "0", 2, null).id();
        connecter(ADMIN, "ADMIN");
        var tableau = exportService.releve(banque.getId());
        assertThat(tableau.getColonnes().get(tableau.getColonnes().size() - 1).titre()).isEqualTo("Saisi par");
        assertThat(tableau.getLignes()).isNotEmpty();
        String detail = mvc.perform(get("/financements/emprunts/" + id).with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(detail).contains("Saisi par ");
    }

    @Test
    void leJournalEtLesExportsAfficheDesLibellesClairs() throws Exception {
        emprunter("300000", "12", 3, "5000");
        String html = mvc.perform(get("/caisse/journal").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Emprunt reçu").contains("Frais de dossier d&#39;emprunt").doesNotContain("DEBLOCAGE_EMPRUNT");
    }

    // ---- Droits, isolation, sécurité ---------------------------------------------------------------------------------------

    @Test
    void leCaissierReçoit403SurToutesLesRoutesDeFinancement() throws Exception {
        Long id = emprunter("1000", "0", 2, null).id();
        for (String url : List.of("/financements", "/financements/emprunts", "/financements/emprunts/nouveau", "/financements/emprunts/" + id,
                "/financements/emprunts/" + id + "/rembourser", "/financements/emprunts/" + id + "/echeancier", "/financements/emprunts/" + id + "/echeancier/pdf",
                "/financements/partenaires", "/financements/partenaires/nouveau", "/financements/partenaires/" + banque.getId(), "/financements/partenaires/export/excel",
                "/financements/operations", "/financements/operations/nouvelle", "/etats/emprunts-prets", "/etats/justificatifs/partenaire/pdf?id=" + banque.getId())) {
            mvc.perform(get(url).with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/financements/partenaires/nouveau").with(user(CAISSIER).roles("CAISSIER")).with(csrf()).param("nom", "X").param("type", "BANQUE"))
                .andExpect(status().isForbidden());
        connecter(CAISSIER, "CAISSIER");
        assertThatThrownBy(() -> empruntService.lister()).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> operationService.enregistrer(operation(TypeOperationTresorerie.AUTRE_PRODUIT, null, "ESPECES", "10", "Test"))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void unDossierOuUnPartenaireDUneAutreBoutiqueEstIntrouvable() throws Exception {
        Long id = emprunter("1000", "0", 2, null).id();
        Long op = empruntService.detail(id).deblocage().getId();
        for (String url : List.of("/financements/emprunts/" + id, "/financements/emprunts/" + id + "/rembourser", "/financements/emprunts/" + id + "/echeancier",
                "/financements/emprunts/" + id + "/echeancier/pdf", "/financements/partenaires/" + banque.getId(), "/financements/partenaires/" + banque.getId() + "/modifier",
                "/financements/partenaires/" + banque.getId() + "/releve/pdf", "/financements/operations/" + op + "/annuler",
                "/etats/justificatifs/partenaire/pdf?id=" + banque.getId())) {
            mvc.perform(get(url).with(user(ADMIN2).roles("ADMIN"))).andExpect(status().isNotFound());
        }
        mvc.perform(post("/financements/partenaires/" + banque.getId() + "/archiver").with(user(ADMIN2).roles("ADMIN")).with(csrf())).andExpect(status().isNotFound());
        assertThat(partenaires.findById(banque.getId()).orElseThrow().isActif()).isTrue();
    }

    @Test
    void lesFormulairesRefusentLesChampsInconnusEtExigentLeJetonCsrf() throws Exception {
        mvc.perform(post("/financements/partenaires/nouveau").with(user(ADMIN).roles("ADMIN")).param("nom", "Sans jeton").param("type", "BANQUE"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/financements/partenaires/nouveau").with(user(ADMIN).roles("ADMIN")).with(csrf()).param("nom", "Inconnu").param("type", "BANQUE").param("actif", "false"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/financements/partenaires/nouveau").with(user(ADMIN).roles("ADMIN")).with(csrf()).param("nom", "Répété").param("nom", "Deux").param("type", "BANQUE"))
                .andExpect(status().isBadRequest());
        assertThat(partenaires.findAll()).extracting(PartenaireFinancier::getNom).doesNotContain("Inconnu", "Répété", "Sans jeton");
        mvc.perform(post("/financements/partenaires/nouveau").with(user(ADMIN).roles("ADMIN")).with(csrf()).param("nom", "Crédit Rural").param("type", "BANQUE"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/financements/emprunts/nouveau").with(user(ADMIN).roles("ADMIN")).with(csrf()).param("capital", "1000").param("compte", "BANQUE").param("utilisateur", "1"))
                .andExpect(status().isBadRequest());
        assertThat(emprunts.count()).isZero();
    }

    @Test
    void lesPagesDeFinancementSAfficheNtPourLeGerant() throws Exception {
        Long id = emprunter("300000", "12", 3, null).id();
        rembourser(id, "50000");
        associeApporte("ESPECES", "10000");
        for (String url : List.of("/financements", "/financements/emprunts?statut=en-cours&sens=EMPRUNT", "/financements/emprunts/nouveau?sens=PRET", "/financements/emprunts/" + id,
                "/financements/emprunts/" + id + "/rembourser", "/financements/emprunts/" + id + "/echeancier", "/financements/partenaires", "/financements/partenaires?archives=1",
                "/financements/partenaires/nouveau", "/financements/partenaires/" + associe.getId(), "/financements/partenaires/" + associe.getId() + "/modifier",
                "/financements/operations", "/financements/operations?groupe=associes", "/financements/operations/nouvelle?type=AUTRE_PRODUIT",
                "/etats/emprunts-prets", "/etats", "/etats/bilan", "/etats/justificatifs", "/")) {
            String html = mvc.perform(get(url).with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(html).as(url).doesNotContain("Whitelabel").doesNotContain("th:text");
        }
        mvc.perform(get("/financements/emprunts?statut=a&statut=b").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(get("/financements/emprunts?statut=inconnu").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isBadRequest());
    }

    // ---- Aides -------------------------------------------------------------------------------------------------------------

    private EmpruntService.Creation emprunter(String capital, String taux, int echeancesNombre, String frais) {
        return empruntService.creer(forme(SensEmprunt.EMPRUNT, capital, taux, echeancesNombre, frais));
    }

    private EmpruntForm forme(SensEmprunt sens, String capital, String taux, int nombre, String frais) { return formePour(banque, sens, capital, taux, nombre, frais); }

    private EmpruntForm formePour(PartenaireFinancier p, SensEmprunt sens, String capital, String taux, int nombre, String frais) {
        var f = new EmpruntForm();
        f.setSens(sens); f.setPartenaireId(p.getId()); f.setCompte(sens == SensEmprunt.EMPRUNT && p == banque ? "BANQUE" : "ESPECES");
        f.setDateEmprunt(horloge.aujourdHui()); f.setDatePremiereEcheance(horloge.aujourdHui().plusDays(30));
        f.setCapital(new BigDecimal(capital)); f.setTauxAnnuel(new BigDecimal(taux)); f.setNombreEcheances(nombre); f.setPeriodicite(Periodicite.MENSUELLE);
        f.setFraisDossier(frais == null ? null : new BigDecimal(frais)); f.setJetonOperation(UUID.randomUUID().toString());
        return f;
    }

    private RemboursementForm remboursement(String montant, String compte, SituationEmprunt s) {
        var f = new RemboursementForm();
        f.setMontant(new BigDecimal(montant)); f.setCompte(compte); f.setJetonOperation(UUID.randomUUID().toString());
        f.setRangAttendu(s.prochaine() == null ? 1 : s.prochaine().rang()); f.setResteAttendu(s.totalRestantDu());
        return f;
    }

    private boolean rembourser(Long id, String montant) { return rembourser(id, montant, "BANQUE"); }

    private boolean rembourser(Long id, String montant, String compte) {
        return empruntService.rembourser(id, remboursement(montant, compte, empruntService.vue(id).situation()));
    }

    private OperationFinanciereForm operation(TypeOperationTresorerie type, Long partenaireId, String compte, String montant, String motif) {
        var f = new OperationFinanciereForm();
        f.setType(type); f.setPartenaireId(partenaireId); f.setCompte(compte); f.setMontant(new BigDecimal(montant)); f.setMotif(motif);
        f.setJetonOperation(UUID.randomUUID().toString());
        return f;
    }

    private void associeApporte(String compte, String montant) {
        operationService.enregistrer(operation(TypeOperationTresorerie.APPORT_COMPTE_COURANT, associe.getId(), compte, montant, "Apport de l'associé"));
    }

    private BigDecimal solde(String type) {
        return caisse.getSoldesParCompte().stream().filter(s -> s.compte().getType().name().equals(type) || s.compte().getType().name().equals(type.replace("BANQUE", "BANQUE")))
                .map(CaisseService.SoldeCompte::solde).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private PeriodeEtat periode() { return new PeriodeEtat(horloge.aujourdHui().minusDays(1), horloge.aujourdHui().plusDays(1)); }

    private PartenaireFinancier partenaire(String nom, TypePartenaireFinancier type) {
        var f = new PartenaireFinancierForm(); f.setNom(nom); f.setType(type);
        return partenaireService.creer(f);
    }

    private Boutique boutique(String nom) { Boutique b = new Boutique(); b.setNom(nom); return boutiques.saveAndFlush(b); }

    private Utilisateur utilisateur(String email, Role role, Boutique b) {
        Utilisateur u = new Utilisateur(); u.setBoutique(b); u.setEmail(email); u.setNom(email); u.setRole(role); return utilisateurs.saveAndFlush(u);
    }

    private static void connecter(String email, String role) {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(email, "", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        SecurityContextHolder.setContext(contexte);
    }
}
