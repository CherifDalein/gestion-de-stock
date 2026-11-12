package org.example.stock.migration;

import jakarta.persistence.EntityManager;
import org.example.stock.StockApplication;
import org.example.stock.enums.ModeRemboursement;
import org.example.stock.enums.Role;
import org.example.stock.enums.TypeOperationCreation;
import org.example.stock.form.RetourAchatForm;
import org.example.stock.form.RetourVenteForm;
import org.example.stock.model.*;
import org.example.stock.service.AchatService;
import org.example.stock.service.AnnulationReglementService;
import org.example.stock.service.CaisseService;
import org.example.stock.service.ComptageCaisseService;
import org.example.stock.service.ComptageDejaTraiteException;
import org.example.stock.service.EmpruntObsoleteException;
import org.example.stock.service.EmpruntService;
import org.example.stock.service.OperationsFinancieresService;
import org.example.stock.service.PartenairesFinanciersService;
import org.example.stock.service.ComptesTresorerieService;
import org.example.stock.service.ReglementVenteObsoleteException;
import org.example.stock.service.OperationCreationService;
import org.example.stock.service.RetourAchatService;
import org.example.stock.service.RetourVenteService;
import org.example.stock.service.SoldeDepartService;
import org.example.stock.service.VenteService;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ordre global des verrous, avec les vrais verrous InnoDB, exclusivement dans une base temporaire : toute écriture métier
 * verrouille la ligne boutique en premier (voir « Ordre des verrous » dans docs/lot-2.md). Chaque scénario croise deux
 * opérations qui, avant cet ordre unique, prenaient leurs verrous en sens inverse, et le répète {@value #TOURS} fois.
 * Aucune erreur ne doit apparaître, interblocage compris.
 */
public class OrdreVerrousMariaDbTest {
    private static final int TOURS = 25;
    private static final String PREFIXE_BASE = "stock_ordre_verrous_test_";

    /** Données d'une boutique de test. */
    private static final class Boutiquier {
        final String email;
        Long produitId, fournisseurId, fournisseurAvanceId, clientId, clientAvanceId, clientSoldeDepartId;
        Boutiquier(String email) { this.email = email; }
    }

    private final OperationCreationService creations;
    private final RetourAchatService retourAchatService;
    private final RetourVenteService retourVenteService;
    private final AchatService achatService;
    private final VenteService venteService;
    private final SoldeDepartService soldeDepartService;
    private final AnnulationReglementService annulationService;
    private final CaisseService caisseService;
    private final ComptageCaisseService comptageService;
    private final ComptesTresorerieService comptesService;
    private final ConfigurableApplicationContext contexte;
    private final TransactionTemplate transaction;
    private final JdbcTemplate jdbc;
    private EntityManager em;
    private final Boutiquier a = new Boutiquier("ordre-verrous-a@example.test");
    private final Boutiquier b = new Boutiquier("ordre-verrous-b@example.test");

    public static void main(String[] args) throws Exception {
        Properties config = new Properties();
        try (var r = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { config.load(r); }
        URI cible = URI.create(AccountEmailMigration.resolve(config.getProperty("spring.datasource.url")).substring(5));
        String username = AccountEmailMigration.resolve(config.getProperty("spring.datasource.username"));
        String password = AccountEmailMigration.resolve(config.getProperty("spring.datasource.password"));
        if (!Set.of("localhost", "127.0.0.1").contains(cible.getHost()) || cible.getPort() != 3306
                || !"/stock_pro".equals(cible.getPath()) || !"root".equals(username) || cible.getUserInfo() != null)
            throw new IllegalStateException("Configuration locale root/stock_pro requise");
        String serveur = "jdbc:mariadb://" + cible.getHost() + ":3306/";
        String database = PREFIXE_BASE + Long.toUnsignedString(System.nanoTime(), 36);
        try (var c = DriverManager.getConnection(serveur, username, password)) {
            AccountEmailMigration.executer(c, "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            try (var contexte = new SpringApplicationBuilder(StockApplication.class).run(
                    "--server.port=0", "--spring.datasource.url=" + serveur + database,
                    "--spring.datasource.username=" + username, "--spring.datasource.password=" + password,
                    "--spring.jpa.hibernate.ddl-auto=create-only", "--spring.jpa.show-sql=false",
                    "--spring.jpa.open-in-view=false",
                    "--logging.level.root=WARN", "--logging.level.org.hibernate=WARN",
                    "--logging.level.org.springframework=WARN", "--debug=false",
                    "--spring.devtools.restart.enabled=false", "--spring.main.banner-mode=off")) {
                new OrdreVerrousMariaDbTest(contexte).verifier(contexte.getBean(EntityManager.class));
            } finally {
                SecurityContextHolder.clearContext();
                if (!database.matches(PREFIXE_BASE + "[a-z0-9]+")) throw new IllegalStateException("Base de test requise");
                AccountEmailMigration.executer(c, "DROP DATABASE " + database);
            }
        }
        System.out.println("Scénarios d'ordre des verrous MariaDB réussis ; base temporaire supprimée.");
    }

    private OrdreVerrousMariaDbTest(ConfigurableApplicationContext contexte) {
        this.contexte = contexte;
        creations = contexte.getBean(OperationCreationService.class);
        retourAchatService = contexte.getBean(RetourAchatService.class);
        retourVenteService = contexte.getBean(RetourVenteService.class);
        achatService = contexte.getBean(AchatService.class);
        venteService = contexte.getBean(VenteService.class);
        soldeDepartService = contexte.getBean(SoldeDepartService.class);
        annulationService = contexte.getBean(AnnulationReglementService.class);
        caisseService = contexte.getBean(CaisseService.class);
        comptageService = contexte.getBean(ComptageCaisseService.class);
        comptesService = contexte.getBean(ComptesTresorerieService.class);
        transaction = new TransactionTemplate(contexte.getBean(PlatformTransactionManager.class));
        transaction.setTimeout(30);
        jdbc = contexte.getBean(JdbcTemplate.class);
    }

    private void preparer(EntityManager em, Boutiquier t, String nom) {
        transaction.executeWithoutResult(status -> {
            Boutique boutique = new Boutique(); boutique.setNom(nom); em.persist(boutique);
            Utilisateur u = new Utilisateur(); u.setBoutique(boutique); u.setNom("Test"); u.setEmail(t.email);
            u.setRole(Role.ADMIN); u.setMotDePasse("empreinte-fictive"); em.persist(u);
            Categorie categorie = new Categorie(); categorie.setBoutique(boutique); categorie.setNom("Tests"); em.persist(categorie);
            t.fournisseurId = fournisseur(em, boutique, "Fournisseur", "0");
            // Solde de départ positif chez un fournisseur : avance de la boutique ; négatif chez un client : avance du client.
            t.fournisseurAvanceId = fournisseur(em, boutique, "Fournisseur avec avance", "10000000");
            t.clientId = client(em, boutique, "Client", "0");
            t.clientAvanceId = client(em, boutique, "Client avec avance", "-10000000");
            t.clientSoldeDepartId = client(em, boutique, "Client avec solde de départ", "5000000");
            Produit produit = new Produit(); produit.setBoutique(boutique); produit.setNom("Test"); produit.setReference("ORDRE-" + nom.hashCode());
            produit.setCategorie(categorie); produit.setQuantite(1_000_000L);
            produit.setPrixAchat(new BigDecimal("5.00")); produit.setPrixVente(new BigDecimal("10000.00"));
            em.persist(produit); t.produitId = produit.getId();
        });
        connecter(t);
        // Un premier achat définit les prix du produit (obligatoire avant toute vente).
        achat(t, t.fournisseurId, 1, "10000");
        SecurityContextHolder.clearContext();
    }

    private static Long fournisseur(EntityManager em, Boutique boutique, String nom, String solde) {
        Fournisseur f = new Fournisseur(); f.setBoutique(boutique); f.setNom(nom); f.setSoldeInitial(new BigDecimal(solde)); em.persist(f); return f.getId();
    }

    private static Long client(EntityManager em, Boutique boutique, String nom, String solde) {
        Client c = new Client(); c.setBoutique(boutique); c.setNom(nom); c.setSoldeInitial(new BigDecimal(solde)); em.persist(c); return c.getId();
    }

    private void verifier(EntityManager em) throws Exception {
        this.em = em;
        preparer(em, a, "Boutique ordre des verrous A");
        preparer(em, b, "Boutique ordre des verrous B");

        // 1. Création d'achat pour un fournisseur ayant une avance + retour en avoir d'un achat de ce fournisseur.
        //    Avant : la création tenait la boutique et attendait l'achat (lecture des factures porteuses d'avance), le retour tenait l'achat
        //    et attendait la boutique.
        for (int tour = 0; tour < TOURS; tour++) {
            connecter(a);
            achat(a, a.fournisseurAvanceId, 4, "40000");
            long existant = dernier("achat");
            simultanesSansErreur("achat+retour en avoir #" + tour,
                    () -> retournerAchat(a, existant, 1, ModeRemboursement.AVOIR), () -> achat(a, a.fournisseurAvanceId, 1, "0"));
        }
        // 2. Création de vente pour un client ayant une avance + retour de vente en avoir.
        for (int tour = 0; tour < TOURS; tour++) {
            connecter(a);
            vente(a, a.clientAvanceId, 4, "48000");
            long existante = dernier("vente");
            simultanesSansErreur("vente+retour en avoir #" + tour,
                    () -> retournerVente(a, existante, 1, ModeRemboursement.AVOIR), () -> vente(a, a.clientAvanceId, 1, "0"));
        }
        // 3. Règlement d'un achat + retour du même achat.
        for (int tour = 0; tour < TOURS; tour++) {
            connecter(a);
            achat(a, a.fournisseurId, 5, "0");
            long credit = dernier("achat");
            BigDecimal verseAvant = decimal("SELECT montant_verse FROM achat WHERE id=" + credit);
            simultanesSansErreur("règlement d'achat+retour #" + tour,
                    () -> reglerAchat(a, credit, "10000", verseAvant), () -> retournerAchat(a, credit, 1, null));
            exiger(decimal("SELECT montant_verse FROM achat WHERE id=" + credit).compareTo(new BigDecimal("10000")) == 0, "Règlement d'achat enregistré");
            exiger(decimal("SELECT montant_retours FROM achat WHERE id=" + credit).compareTo(new BigDecimal("10000")) == 0, "Retour d'achat enregistré");
        }
        // 3 bis. Règlement d'une vente + retour de la même vente.
        for (int tour = 0; tour < TOURS; tour++) {
            connecter(a);
            vente(a, a.clientId, 5, "0");
            long credit = dernier("vente");
            simultanesSansErreur("règlement de vente+retour #" + tour,
                    () -> reglerVente(a, credit, "10000", BigDecimal.ZERO), () -> retournerVente(a, credit, 1, null));
            exiger(decimal("SELECT montant_verse FROM vente WHERE id=" + credit).compareTo(new BigDecimal("10000")) == 0, "Règlement de vente enregistré");
        }
        // 4. Règlement du solde de départ d'un client + création de vente pour ce client.
        for (int tour = 0; tour < TOURS; tour++) {
            connecter(a);
            BigDecimal dejaRegle = decimal("SELECT COALESCE(SUM(montant),0) FROM mouvement_caisse WHERE source='SOLDE_CLIENT' AND client_id=" + a.clientSoldeDepartId);
            simultanesSansErreur("solde de départ client+vente #" + tour,
                    () -> reglerSoldeClient(a, "1000", dejaRegle), () -> vente(a, a.clientSoldeDepartId, 1, "0"));
        }
        // 4 bis. Règlement du solde de départ d'un fournisseur + création d'achat chez ce fournisseur.
        for (int tour = 0; tour < TOURS; tour++) {
            connecter(a);
            // Solde négatif = dette envers le fournisseur : on crée un fournisseur frais à chaque tour.
            long fournisseurDu = fournisseurAvecDette(a);
            simultanesSansErreur("solde de départ fournisseur+achat #" + tour,
                    () -> reglerSoldeFournisseur(a, fournisseurDu, "1000", BigDecimal.ZERO), () -> achat(a, fournisseurDu, 1, "0"));
        }
        // 5. Modification d'un achat + création d'achat pour le même fournisseur porteur d'avance.
        for (int tour = 0; tour < TOURS; tour++) {
            connecter(a);
            achat(a, a.fournisseurAvanceId, 4, "40000");
            long existant = dernier("achat");
            simultanesSansErreur("modification d'achat+achat #" + tour,
                    () -> modifierAchat(a, existant), () -> achat(a, a.fournisseurAvanceId, 1, "0"));
        }
        // 6. Deux boutiques différentes : retours et créations croisés, sans erreur (les boutiques ne se bloquent pas entre elles).
        for (int tour = 0; tour < TOURS; tour++) {
            connecter(a); achat(a, a.fournisseurAvanceId, 3, "30000"); long achatA = dernier("achat");
            connecter(b); achat(b, b.fournisseurAvanceId, 3, "30000"); long achatB = dernier("achat");
            connecter(b); vente(b, b.clientAvanceId, 3, "36000"); long venteB = dernier("vente");
            simultanesSansErreur("deux boutiques #" + tour,
                    () -> retournerAchat(a, achatA, 1, ModeRemboursement.AVOIR), () -> retournerAchat(b, achatB, 1, ModeRemboursement.ARGENT),
                    () -> retournerVente(b, venteB, 1, ModeRemboursement.AVOIR), () -> achat(a, a.fournisseurAvanceId, 1, "0"));
        }
        // Fonds de départ de la caisse : les scénarios précédents l'ont vidée, et annuler un encaissement exige le solde du compte.
        fonds(a, "ESPECES", "100000000");
        // 7. Annulation d'un règlement + nouveau règlement de la même vente (le second peut légitimement être périmé si l'annulation passe d'abord).
        for (int tour = 0; tour < TOURS; tour++) {
            connecter(a);
            vente(a, a.clientId, 8, "40000"); // 40 000 encaissés : la caisse peut rendre le règlement annulé
            long credit = dernier("vente");
            reglerVente(a, credit, "10000", new BigDecimal("40000"));
            long reglement = decimal("SELECT MAX(id) FROM mouvement_caisse WHERE source='REGLEMENT_VENTE' AND vente_id=" + credit).longValue();
            simultanesSansErreur("annulation de règlement+nouveau règlement #" + tour,
                    () -> annulerReglementVente(a, credit, reglement, false),
                    () -> { try { reglerVente(a, credit, "5000", new BigDecimal("50000")); } catch (ReglementVenteObsoleteException perime) { /* annulation passée avant */ } });
            BigDecimal verse = decimal("SELECT montant_verse FROM vente WHERE id=" + credit);
            exiger(verse.compareTo(new BigDecimal("45000")) == 0 || verse.compareTo(new BigDecimal("40000")) == 0, "Montant versé cohérent après annulation : " + verse);
            exiger(decimal("SELECT COUNT(*) FROM mouvement_caisse WHERE source='ANNULATION' AND annulation_de_id=" + reglement).compareTo(BigDecimal.ONE) == 0, "Une seule annulation");
        }
        // 8. Annulation d'un règlement + retour de la même facture : le retour postérieur interdit l'annulation, sinon l'annulation passe.
        for (int tour = 0; tour < TOURS; tour++) {
            connecter(a);
            vente(a, a.clientId, 8, "40000"); // 40 000 encaissés : la caisse peut rendre le règlement annulé
            long credit = dernier("vente");
            reglerVente(a, credit, "10000", new BigDecimal("40000"));
            long reglement = decimal("SELECT MAX(id) FROM mouvement_caisse WHERE source='REGLEMENT_VENTE' AND vente_id=" + credit).longValue();
            simultanesSansErreur("annulation de règlement+retour #" + tour,
                    () -> annulerReglementVente(a, credit, reglement, true), () -> retournerVente(a, credit, 1, null));
            boolean annule = decimal("SELECT COUNT(*) FROM mouvement_caisse WHERE annulation_de_id=" + reglement).signum() > 0;
            exiger(decimal("SELECT montant_verse FROM vente WHERE id=" + credit).compareTo(new BigDecimal(annule ? "40000" : "50000")) == 0, "Montant versé cohérent (annulé=" + annule + ")");
            exiger(decimal("SELECT montant_retours FROM vente WHERE id=" + credit).compareTo(new BigDecimal("12000")) == 0, "Retour enregistré");
        }
        // 9. Comptes multiples (lot 3) : deux comptes Orange Money d'une même boutique. Des transferts croisés (A vers B et B vers A) ne se
        //    bloquent pas (les comptes sont verrouillés par identifiant croissant), pas plus qu'un règlement de vente sur un compte pendant
        //    un transfert qui le débite, ou l'archivage d'un compte vide pendant une écriture sur l'autre compte du même type.
        connecter(a);
        long orange1 = comptePrincipal(a, "ORANGE_MONEY");
        long orange2 = creerCompte(a, "Orange Money Labé", "ORANGE_MONEY");
        fonds(a, "ORANGE_MONEY", "50000000");
        jdbc.update("INSERT INTO mouvement_caisse(boutique_id,date_mouvement,montant,type,motif,source,compte_id) SELECT boutique_id,NOW(6),50000000,'OUVERTURE','Fonds de test','SOLDE_INITIAL',id FROM compte_tresorerie_boutique WHERE id=?", orange2);
        BigDecimal totalOrange = totalType(a, "ORANGE_MONEY");
        for (int tour = 0; tour < TOURS; tour++) {
            simultanesSansErreur("transferts croisés entre deux comptes #" + tour,
                    () -> transferer(a, orange1, orange2, "1000"), () -> transferer(a, orange2, orange1, "1000"),
                    () -> transferer(a, orange1, orange2, "500"), () -> transferer(a, orange2, orange1, "500"));
        }
        exiger(totalType(a, "ORANGE_MONEY").compareTo(totalOrange) == 0, "Le total du type Orange Money est inchangé par les transferts");
        for (int tour = 0; tour < TOURS; tour++) {
            connecter(a);
            vente(a, a.clientId, 5, "0");
            long credit = dernier("vente");
            simultanesSansErreur("règlement de vente sur un compte+transfert qui le débite #" + tour,
                    () -> reglerVenteSur(a, credit, "10000", BigDecimal.ZERO, orange1), () -> transferer(a, orange1, orange2, "1000"));
            exiger(decimal("SELECT montant_verse FROM vente WHERE id=" + credit).compareTo(new BigDecimal("10000")) == 0, "Règlement enregistré sur le compte choisi");
        }
        long videId = creerCompte(a, "Orange vide", "ORANGE_MONEY");
        simultanesSansErreur("archivage d'un compte vide+écriture sur un autre compte du même type",
                () -> archiver(a, videId), () -> reglerVenteSur(a, dernier("vente"), "1000", new BigDecimal("10000"), orange2));
        exiger(decimal("SELECT COUNT(*) FROM compte_tresorerie_boutique WHERE id=" + videId + " AND actif=FALSE").signum() > 0, "Le compte vide est archivé");
        // 10. Financements (3b1) : boutique, puis emprunt, puis partenaire, puis comptes. Deux remboursements de la même échéance ne paient jamais
        //     deux fois (le second voit l'état changé et est refusé), et un remboursement, un apport d'associé, un transfert et l'archivage
        //     d'un partenaire se croisent sans interblocage.
        fonds(a, "BANQUE", "50000000");
        connecter(a);
        long banque = comptePrincipal(a, "BANQUE");
        for (int tour = 0; tour < TOURS; tour++) {
            long[] dossier = nouvelEmprunt(a, tour);
            AtomicInteger acceptes = new AtomicInteger(), refuses = new AtomicInteger();
            simultanesSansErreur("deux remboursements de la même échéance #" + tour,
                    () -> rembourserEmprunt(a, dossier[0], "100000", acceptes, refuses), () -> rembourserEmprunt(a, dossier[0], "100000", acceptes, refuses));
            exiger(acceptes.get() == 1 && refuses.get() == 1, "Un seul remboursement doit être accepté (acceptés=" + acceptes + ", refusés=" + refuses + ")");
            exiger(decimal("SELECT COALESCE(SUM(i.capital),0) FROM imputation_echeance i JOIN echeance_emprunt e ON e.id=i.echeance_id WHERE e.emprunt_id=" + dossier[0])
                    .compareTo(new BigDecimal("100000")) == 0, "Le capital de l'échéance n'est payé qu'une fois");
            long associe = dossier[1];
            simultanesSansErreur("remboursement+apport d'associé+transfert+archivage #" + tour,
                    () -> rembourserEmprunt(a, dossier[0], "50000", new AtomicInteger(), new AtomicInteger()), () -> apporter(a, associe, "1000"),
                    () -> transferer(a, banque, comptePrincipal(a, "ESPECES"), "1000"), () -> tenterArchivage(a, associe));
        }
        // 11. Comptage de caisse (3b2) : boutique, puis comptage, puis comptes. Deux validations simultanées du même comptage n'en laissent passer
        //     qu'une (l'autre reçoit « déjà traité », donc 409) et ne postent qu'un seul mouvement d'écart, même croisées avec un transfert.
        fonds(a, "ESPECES", "5000000");
        long especes = comptePrincipal(a, "ESPECES");
        for (int tour = 0; tour < TOURS; tour++) {
            BigDecimal avant = decimal("SELECT COALESCE(SUM(montant),0) FROM mouvement_caisse WHERE compte_id=" + especes);
            long comptage = nouveauComptageEnAttente(a, especes, avant.subtract(new BigDecimal("1000")));
            AtomicInteger acceptes = new AtomicInteger(), refuses = new AtomicInteger();
            simultanesSansErreur("deux validations du même comptage #" + tour,
                    () -> validerComptage(a, comptage, acceptes, refuses), () -> validerComptage(a, comptage, acceptes, refuses),
                    () -> transferer(a, especes, comptePrincipal(a, "BANQUE"), "1000"));
            exiger(acceptes.get() == 1 && refuses.get() == 1, "Une seule validation doit passer (acceptées=" + acceptes + ", refusées=" + refuses + ")");
            exiger(decimal("SELECT COUNT(*) FROM mouvement_caisse WHERE comptage_caisse_id=" + comptage).intValue() == 1, "Un seul mouvement d'écart par comptage");
            exiger(decimal("SELECT COALESCE(SUM(montant),0) FROM mouvement_caisse WHERE comptage_caisse_id=" + comptage).compareTo(new BigDecimal("-1000")) == 0, "Le manque n'est posté qu'une fois");
            exiger("VALIDE".equals(jdbc.queryForObject("SELECT statut FROM comptage_caisse WHERE id=" + comptage, String.class)), "Le comptage est validé");
        }
        // Invariants finaux : numéros distincts par boutique, aucune avance utilisée en excès.
        for (String table : List.of("achat", "vente", "retour")) {
            exiger(decimal("SELECT COUNT(*) - COUNT(DISTINCT boutique_id, numero) FROM " + table).signum() == 0, "Numéros distincts par boutique : " + table);
        }
        BigDecimal avoirsAchat = decimal("SELECT COALESCE(SUM(montant_avoir),0) FROM retour WHERE type='ACHAT'");
        BigDecimal utiliseeAchat = decimal("SELECT COALESCE(SUM(avance_utilisee),0) FROM achat");
        exiger(utiliseeAchat.compareTo(avoirsAchat.add(new BigDecimal("20000000"))) <= 0, "Avance utilisée chez les fournisseurs : " + utiliseeAchat);
    }

    // ----- Opérations -----

    private void retournerAchat(Boutiquier t, long achatId, int quantite, ModeRemboursement mode) {
        connecter(t);
        try {
            long ligne = decimal("SELECT MIN(id) FROM detail_achat WHERE achat_id=" + achatId).longValue();
            RetourAchatForm form = new RetourAchatForm();
            RetourAchatForm.Ligne l = new RetourAchatForm.Ligne(); l.setDetailAchatId(ligne); l.setQuantite(quantite);
            form.setLignes(new ArrayList<>(List.of(l))); form.setModeRemboursement(mode);
            if (mode == ModeRemboursement.ARGENT) form.setComptePaiement("ESPECES");
            form.setRetoursConnus(0L);
            retourAchatService.enregistrer(achatId, form);
        } finally { SecurityContextHolder.clearContext(); }
    }

    private void retournerVente(Boutiquier t, long venteId, int quantite, ModeRemboursement mode) {
        connecter(t);
        try {
            long ligne = decimal("SELECT MIN(id) FROM detail_vente WHERE vente_id=" + venteId).longValue();
            RetourVenteForm form = new RetourVenteForm();
            RetourVenteForm.Ligne l = new RetourVenteForm.Ligne(); l.setDetailVenteId(ligne); l.setQuantite(quantite);
            form.setLignes(new ArrayList<>(List.of(l))); form.setModeRemboursement(mode);
            if (mode == ModeRemboursement.ARGENT) form.setComptePaiement("ESPECES");
            form.setRetoursConnus(0L);
            retourVenteService.enregistrer(venteId, form);
        } finally { SecurityContextHolder.clearContext(); }
    }

    private void reglerAchat(Boutiquier t, long achatId, String montant, BigDecimal verseAttendu) {
        connecter(t);
        try { achatService.reglerAchat(achatId, new BigDecimal(montant), verseAttendu, "ESPECES"); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private void reglerVente(Boutiquier t, long venteId, String montant, BigDecimal verseAttendu) {
        connecter(t);
        try { venteService.reglerVente(venteId, new BigDecimal(montant), verseAttendu, "ESPECES"); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private void reglerSoldeClient(Boutiquier t, String montant, BigDecimal attendu) {
        connecter(t);
        try { soldeDepartService.reglerClient(t.clientSoldeDepartId, new BigDecimal(montant), attendu, "ESPECES", null); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private void reglerSoldeFournisseur(Boutiquier t, long fournisseurId, String montant, BigDecimal attendu) {
        connecter(t);
        try { soldeDepartService.reglerFournisseur(fournisseurId, new BigDecimal(montant), attendu, "ESPECES", null); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private void annulerReglementVente(Boutiquier t, long venteId, long mouvementId, boolean refusAdmis) {
        connecter(t);
        try { annulationService.annulerReglementVente(venteId, mouvementId, "Erreur de saisie"); }
        catch (IllegalArgumentException refus) { if (!refusAdmis) throw refus; }
        finally { SecurityContextHolder.clearContext(); }
    }

    private void modifierAchat(Boutiquier t, long achatId) {
        connecter(t);
        try {
            Achat modifie = new Achat();
            Fournisseur f = new Fournisseur(); f.setId(t.fournisseurAvanceId); modifie.setFournisseur(f);
            DetailAchat ligne = new DetailAchat(); ligne.setProduit(produit(t)); ligne.setQuantite(5);
            ligne.setPrixAchatUnitaire(new BigDecimal("10000")); ligne.setPrixVenteUnitaire(new BigDecimal("12000")); modifie.getLignes().add(ligne);
            achatService.modifierAchat(achatId, modifie);
        } finally { SecurityContextHolder.clearContext(); }
    }

    private long fournisseurAvecDette(Boutiquier t) {
        Long[] id = new Long[1];
        transaction.executeWithoutResult(status -> {
            Boutique boutique = em.find(Boutique.class, jdbc.queryForObject("SELECT boutique_id FROM utilisateur WHERE email='" + t.email + "'", Long.class));
            id[0] = fournisseur(em, boutique, "Fournisseur avec dette", "-50000");
        });
        return id[0];
    }

    private void achat(Boutiquier t, Long fournisseurId, int quantite, String verse) {
        connecter(t);
        Achat a = new Achat(); Fournisseur f = new Fournisseur(); f.setId(fournisseurId); a.setFournisseur(f); a.setMontantVerse(new BigDecimal(verse));
        DetailAchat ligne = new DetailAchat(); ligne.setProduit(produit(t)); ligne.setQuantite(quantite);
        ligne.setPrixAchatUnitaire(new BigDecimal("10000")); ligne.setPrixVenteUnitaire(new BigDecimal("12000")); a.getLignes().add(ligne);
        try { creations.creerAchat(creations.ouvrir(TypeOperationCreation.ACHAT), a); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private void vente(Boutiquier t, Long clientId, int quantite, String verse) {
        connecter(t);
        Vente v = new Vente(); Client c = new Client(); c.setId(clientId); v.setClient(c); v.setMontantVerse(new BigDecimal(verse));
        DetailVente ligne = new DetailVente(); ligne.setProduit(produit(t)); ligne.setQuantite(quantite); v.getLignes().add(ligne);
        try { creations.creerVente(creations.ouvrir(TypeOperationCreation.VENTE), v); }
        finally { SecurityContextHolder.clearContext(); }
    }

    // ----- Financements -----

    /** Crée un associé et un emprunt de 300 000 sur 3 échéances sans intérêt : [identifiant de l'emprunt, identifiant de l'associé]. */
    private long[] nouvelEmprunt(Boutiquier t, int tour) {
        connecter(t);
        try {
            var pf = new org.example.stock.form.PartenaireFinancierForm(); pf.setNom("Banque " + tour + "-" + System.nanoTime()); pf.setType(org.example.stock.enums.TypePartenaireFinancier.BANQUE);
            var banque = contexteBean(PartenairesFinanciersService.class).creer(pf);
            var af = new org.example.stock.form.PartenaireFinancierForm(); af.setNom("Associé " + tour + "-" + System.nanoTime()); af.setType(org.example.stock.enums.TypePartenaireFinancier.ASSOCIE);
            var associe = contexteBean(PartenairesFinanciersService.class).creer(af);
            var f = new org.example.stock.form.EmpruntForm();
            f.setSens(org.example.stock.enums.SensEmprunt.EMPRUNT); f.setPartenaireId(banque.getId()); f.setCompte("BANQUE");
            var aujourdhui = java.time.LocalDate.now();
            f.setDateEmprunt(aujourdhui); f.setDatePremiereEcheance(aujourdhui.plusDays(30)); f.setCapital(new BigDecimal("300000")); f.setTauxAnnuel(BigDecimal.ZERO);
            f.setNombreEcheances(3); f.setPeriodicite(org.example.stock.enums.Periodicite.MENSUELLE); f.setJetonOperation(java.util.UUID.randomUUID().toString());
            long id = contexteBean(EmpruntService.class).creer(f).id();
            return new long[]{id, associe.getId()};
        } finally { SecurityContextHolder.clearContext(); }
    }

    /** Le formulaire est celui qu'affichait la page (prochaine échéance et reste dû identiques pour les deux envois simultanés). */
    private void rembourserEmprunt(Boutiquier t, long empruntId, String montant, AtomicInteger acceptes, AtomicInteger refuses) {
        connecter(t);
        try {
            var service = contexteBean(EmpruntService.class);
            var situation = service.vue(empruntId).situation();
            var f = new org.example.stock.form.RemboursementForm();
            f.setMontant(new BigDecimal(montant)); f.setCompte("BANQUE"); f.setJetonOperation(java.util.UUID.randomUUID().toString());
            f.setRangAttendu(situation.prochaine().rang()); f.setResteAttendu(situation.totalRestantDu());
            service.rembourser(empruntId, f);
            acceptes.incrementAndGet();
        } catch (EmpruntObsoleteException obsolete) { refuses.incrementAndGet(); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private void apporter(Boutiquier t, long associeId, String montant) {
        connecter(t);
        try {
            var f = new org.example.stock.form.OperationFinanciereForm();
            f.setType(org.example.stock.enums.TypeOperationTresorerie.APPORT_COMPTE_COURANT); f.setPartenaireId(associeId); f.setCompte("BANQUE");
            f.setMontant(new BigDecimal(montant)); f.setMotif("Apport de test"); f.setJetonOperation(java.util.UUID.randomUUID().toString());
            contexteBean(OperationsFinancieresService.class).enregistrer(f);
        } catch (IllegalArgumentException archive) {
            // L'archivage du même partenaire est passé avant l'apport : refus légitime, pas un interblocage.
            if (!archive.getMessage().contains("archivé")) throw archive;
        } finally { SecurityContextHolder.clearContext(); }
    }

    /** L'archivage est refusé quand le solde n'est pas nul : le refus fait partie du scénario, pas un interblocage. */
    private void tenterArchivage(Boutiquier t, long partenaireId) {
        connecter(t);
        try { contexteBean(PartenairesFinanciersService.class).definirActif(partenaireId, false); }
        catch (IllegalArgumentException refus) { /* solde non nul */ }
        finally { SecurityContextHolder.clearContext(); }
    }

    private <T> T contexteBean(Class<T> type) { return contexte.getBean(type); }

    // ----- Concurrence -----

    private void simultanesSansErreur(String scenario, Runnable... taches) throws Exception {
        CyclicBarrier depart = new CyclicBarrier(taches.length);
        List<Throwable> erreurs = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(taches.length)) {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable tache : taches) futures.add(executor.submit(() -> {
                try { depart.await(15, TimeUnit.SECONDS); tache.run(); }
                catch (InterruptedException | BrokenBarrierException | TimeoutException e) { throw new IllegalStateException(e); }
                finally { SecurityContextHolder.clearContext(); }
            }));
            for (Future<?> future : futures) {
                try { future.get(40, TimeUnit.SECONDS); } catch (ExecutionException e) { erreurs.add(e.getCause()); }
            }
        }
        exiger(erreurs.isEmpty(), "Aucune erreur attendue (" + scenario + ") : " + erreurs);
    }

    private void fonds(Boutiquier t, String type, String montant) {
        jdbc.update("INSERT INTO mouvement_caisse(boutique_id,date_mouvement,montant,type,motif,source,compte_id) SELECT u.boutique_id,NOW(6),?,'OUVERTURE','Fonds de test','SOLDE_INITIAL',c.id"
                + " FROM utilisateur u JOIN compte_tresorerie_boutique c ON c.boutique_id=u.boutique_id AND c.type=? AND c.principal_type IS NOT NULL WHERE u.email=?", new BigDecimal(montant), type, t.email);
    }

    private long comptePrincipal(Boutiquier t, String type) {
        return jdbc.queryForObject("SELECT c.id FROM compte_tresorerie_boutique c JOIN utilisateur u ON u.boutique_id=c.boutique_id WHERE u.email=? AND c.type=? AND c.principal_type IS NOT NULL",
                Long.class, t.email, type);
    }

    private long creerCompte(Boutiquier t, String nom, String type) {
        connecter(t);
        try {
            var form = new org.example.stock.form.CompteTresorerieForm();
            form.setType(org.example.stock.enums.TypeCompteTresorerie.valueOf(type)); form.setNom(nom);
            return comptesService.creer(form).getId();
        } finally { SecurityContextHolder.clearContext(); }
    }

    private void archiver(Boutiquier t, long compteId) {
        connecter(t);
        try { comptesService.archiver(compteId); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private long nouveauComptageEnAttente(Boutiquier t, long compteId, BigDecimal compte) {
        connecter(t);
        try {
            var c = comptageService.saisir(String.valueOf(compteId), compte, null, "Comptage concurrent", java.util.UUID.randomUUID().toString());
            exiger(c.getStatut() == org.example.stock.enums.StatutComptage.EN_ATTENTE, "Le comptage avec écart attend la validation");
            return c.getId();
        } finally { SecurityContextHolder.clearContext(); }
    }

    private void validerComptage(Boutiquier t, long comptageId, AtomicInteger acceptes, AtomicInteger refuses) {
        connecter(t);
        try { comptageService.valider(comptageId, "Validation concurrente"); acceptes.incrementAndGet(); }
        catch (ComptageDejaTraiteException e) { refuses.incrementAndGet(); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private void transferer(Boutiquier t, long source, long destination, String montant) {
        connecter(t);
        try {
            var f = new org.example.stock.form.TransfertTresorerieForm();
            f.setCompteSource(String.valueOf(source)); f.setCompteDestination(String.valueOf(destination)); f.setMontant(new BigDecimal(montant));
            f.setMotif("Transfert concurrent"); f.setJetonOperation(java.util.UUID.randomUUID().toString());
            caisseService.transferer(f);
        } finally { SecurityContextHolder.clearContext(); }
    }

    private void reglerVenteSur(Boutiquier t, long venteId, String montant, BigDecimal verseAttendu, long compteId) {
        connecter(t);
        try { venteService.reglerVente(venteId, new BigDecimal(montant), verseAttendu, String.valueOf(compteId)); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private BigDecimal totalType(Boutiquier t, String type) {
        return decimal("SELECT COALESCE(SUM(m.montant),0) FROM mouvement_caisse m JOIN compte_tresorerie_boutique c ON c.id=m.compte_id JOIN utilisateur u ON u.boutique_id=c.boutique_id"
                + " WHERE c.type='" + type + "' AND u.email='" + t.email + "'");
    }

    private long dernier(String table) { return decimal("SELECT MAX(id) FROM " + table).longValue(); }
    private BigDecimal decimal(String sql) { return jdbc.queryForObject(sql, BigDecimal.class); }
    private Produit produit(Boutiquier t) { Produit p = new Produit(); p.setId(t.produitId); return p; }
    private static void connecter(Boutiquier t) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(t.email, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
    private static void exiger(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
