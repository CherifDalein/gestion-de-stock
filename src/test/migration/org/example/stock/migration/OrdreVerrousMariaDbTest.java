package org.example.stock.migration;

import jakarta.persistence.EntityManager;
import org.example.stock.StockApplication;
import org.example.stock.enums.CompteTresorerie;
import org.example.stock.enums.ModeRemboursement;
import org.example.stock.enums.Role;
import org.example.stock.enums.TypeOperationCreation;
import org.example.stock.form.RetourAchatForm;
import org.example.stock.form.RetourVenteForm;
import org.example.stock.model.*;
import org.example.stock.service.AchatService;
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
        creations = contexte.getBean(OperationCreationService.class);
        retourAchatService = contexte.getBean(RetourAchatService.class);
        retourVenteService = contexte.getBean(RetourVenteService.class);
        achatService = contexte.getBean(AchatService.class);
        venteService = contexte.getBean(VenteService.class);
        soldeDepartService = contexte.getBean(SoldeDepartService.class);
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
            if (mode == ModeRemboursement.ARGENT) form.setComptePaiement(CompteTresorerie.CAISSE);
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
            if (mode == ModeRemboursement.ARGENT) form.setComptePaiement(CompteTresorerie.CAISSE);
            form.setRetoursConnus(0L);
            retourVenteService.enregistrer(venteId, form);
        } finally { SecurityContextHolder.clearContext(); }
    }

    private void reglerAchat(Boutiquier t, long achatId, String montant, BigDecimal verseAttendu) {
        connecter(t);
        try { achatService.reglerAchat(achatId, new BigDecimal(montant), verseAttendu, CompteTresorerie.CAISSE); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private void reglerVente(Boutiquier t, long venteId, String montant, BigDecimal verseAttendu) {
        connecter(t);
        try { venteService.reglerVente(venteId, new BigDecimal(montant), verseAttendu, CompteTresorerie.CAISSE); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private void reglerSoldeClient(Boutiquier t, String montant, BigDecimal attendu) {
        connecter(t);
        try { soldeDepartService.reglerClient(t.clientSoldeDepartId, new BigDecimal(montant), attendu, CompteTresorerie.CAISSE, null); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private void reglerSoldeFournisseur(Boutiquier t, long fournisseurId, String montant, BigDecimal attendu) {
        connecter(t);
        try { soldeDepartService.reglerFournisseur(fournisseurId, new BigDecimal(montant), attendu, CompteTresorerie.CAISSE, null); }
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

    private long dernier(String table) { return decimal("SELECT MAX(id) FROM " + table).longValue(); }
    private BigDecimal decimal(String sql) { return jdbc.queryForObject(sql, BigDecimal.class); }
    private Produit produit(Boutiquier t) { Produit p = new Produit(); p.setId(t.produitId); return p; }
    private static void connecter(Boutiquier t) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(t.email, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
    private static void exiger(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
