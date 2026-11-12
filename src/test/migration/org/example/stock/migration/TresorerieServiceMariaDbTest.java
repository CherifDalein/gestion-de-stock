package org.example.stock.migration;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validator;
import org.example.stock.config.TrialProperties;
import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.enums.Role;
import org.example.stock.form.OuvertureCaisseForm;
import org.example.stock.form.TransfertTresorerieForm;
import org.example.stock.form.DepenseTresorerieForm;
import org.example.stock.model.Boutique;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.*;
import org.example.stock.service.*;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

import static org.example.stock.enums.TypeCompteTresorerie.*;

/** Services réels et transactions MariaDB, exclusivement dans une base jetable, sans serveur web ni emails. */
public final class TresorerieServiceMariaDbTest {
    private static final String PREFIXE = "stock_tresorerie_service_test_";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 10, 15);
    private final CaisseService caisse;
    private final BoutiqueRepository boutiques;
    private final UtilisateurRepository utilisateurs;
    private final OperationTresorerieRepository operations;
    private final PlatformTransactionManager transactions;
    private final CompteTresorerieBoutiqueRepository comptes;
    private final org.example.stock.repository.StockLockRepository verrous;
    private int scenarios;

    private TresorerieServiceMariaDbTest(AnnotationConfigApplicationContext contexte) {
        caisse = contexte.getBean(CaisseService.class);
        boutiques = contexte.getBean(BoutiqueRepository.class);
        utilisateurs = contexte.getBean(UtilisateurRepository.class);
        operations = contexte.getBean(OperationTresorerieRepository.class);
        transactions = contexte.getBean(PlatformTransactionManager.class);
        comptes = contexte.getBean(CompteTresorerieBoutiqueRepository.class);
        verrous = contexte.getBean(org.example.stock.repository.StockLockRepository.class);
    }

    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        try {
            Properties config = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) {
                config.load(lecteur);
            }
            String serveur = InscriptionEssaiMariaDbTest.urlServeurTest(
                    AccountEmailMigration.resolve(config.getProperty("spring.datasource.url")),
                    System.getenv("TRESORERIE_TEST_DB_URL"));
            String utilisateur = AccountEmailMigration.resolve(config.getProperty("spring.datasource.username"));
            String secret = AccountEmailMigration.resolve(config.getProperty("spring.datasource.password"));
            String base = PREFIXE + Long.toUnsignedString(System.nanoTime(), 36);
            verifierBase(base);
            try (Connection administration = DriverManager.getConnection(serveur, utilisateur, secret)) {
                try (var s = administration.createStatement()) {
                    s.execute("CREATE DATABASE " + base + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                }
                try {
                    administration.setCatalog(base);
                    creerSchema(administration);
                    DriverManagerDataSource source = new DriverManagerDataSource(urlCatalogue(serveur, base), utilisateur, secret);
                    source.setDriverClassName("org.mariadb.jdbc.Driver");
                    try (Connection verification = source.getConnection()) {
                        exiger(verification.getTransactionIsolation() == Connection.TRANSACTION_REPEATABLE_READ,
                                "Le scénario doit démarrer avec l’isolation MariaDB REPEATABLE READ.");
                    }
                    try (var contexte = new AnnotationConfigApplicationContext()) {
                        contexte.getEnvironment().setActiveProfiles("tresorerie-mariadb-service-test");
                        contexte.registerBean(DataSource.class, () -> source);
                        contexte.register(ConfigurationJpa.class);
                        contexte.refresh();
                        TresorerieServiceMariaDbTest test = new TresorerieServiceMariaDbTest(contexte);
                        test.verifier();
                        System.out.println(test.scenarios + " scénarios du service trésorerie MariaDB réussis.");
                    } finally { SecurityContextHolder.clearContext(); }
                } finally {
                    administration.setCatalog(null); verifierBase(base);
                    try (var s = administration.createStatement()) { s.execute("DROP DATABASE " + base); }
                    System.out.println("Base temporaire du service trésorerie supprimée.");
                }
            }
        } catch (Exception e) {
            System.err.println("Test du service trésorerie MariaDB interrompu : "
                    + (e instanceof IllegalStateException ? e.getMessage() : "opération impossible ; aucune valeur JDBC publiée."));
            System.exit(1);
        }
    }

    private void verifier() throws Exception {
        tousLesSens();
        depenseEtRepetition();
        concurrenceSolde();
        concurrenceInverse();
        encaissementsSimultanes();
        paiementEtTransfertMemeActeur();
        repetitionConcurrente();
        isolationBoutiques();
        rollbackComplet();
    }

    private void tousLesSens() {
        for (TypeCompteTresorerie source : TypeCompteTresorerie.values()) for (TypeCompteTresorerie destination : TypeCompteTresorerie.values()) {
            if (source == destination) continue;
            Env e = environnement();
            caisse.transferer(transfert(source, destination, "80"));
            exiger(caisse.getSoldesParType().get(source).compareTo(new BigDecimal("20")) == 0, "Le compte source n'a pas été débité.");
            exiger(caisse.getSoldesParType().get(destination).compareTo(new BigDecimal("180")) == 0, "Le compte destination n'a pas été crédité.");
            exiger(caisse.getSoldeActuel().compareTo(new BigDecimal("300")) == 0, "Un transfert a modifié le total.");
            exiger(caisse.listerTous().size() == 5, "Les deux écritures liées sont absentes ou dupliquées.");
            reussi("transfert " + source + " vers " + destination);
        }
    }

    private void depenseEtRepetition() {
        Env e = environnement();
        var f = depense(BANQUE, "40");
        exiger(!caisse.depenser(f) && caisse.depenser(f), "La répétition exacte de dépense n'est pas idempotente.");
        exiger(caisse.getSoldesParType().get(BANQUE).compareTo(new BigDecimal("60")) == 0, "La dépense a été comptée plusieurs fois.");
        exiger(caisse.getSoldeActuel().compareTo(new BigDecimal("260")) == 0 && caisse.listerTous().size() == 4,
                "La dépense affecte plusieurs comptes ou son historique est incorrect.");
        f.setMontant(new BigDecimal("41"));
        try { caisse.depenser(f); throw new IllegalStateException("Le jeton altéré a été accepté."); }
        catch (OperationTresorerieConflitException attendu) { }
        reussi("dépense, renvoi exact et altération du jeton");
    }

    private void concurrenceSolde() throws Exception {
        Env e = environnement();
        var resultats = concurrent(() -> {
            connecter(e.premier());
            return resultat(() -> caisse.transferer(transfert(BANQUE, ESPECES, "80")));
        }, () -> {
            connecter(e.second());
            return resultat(() -> caisse.depenser(depense(BANQUE, "80")));
        });
        connecter(e.premier());
        exiger(resultats.contains("ok") && resultats.contains("insuffisant"), "Deux demandes ont consommé le même solde.");
        exiger(caisse.getSoldesParType().get(BANQUE).compareTo(new BigDecimal("20")) == 0, "Le solde après attente n'a pas été relu.");
        exiger(caisse.getSoldesParType().values().stream().noneMatch(v -> v.signum() < 0), "Un compte est devenu négatif.");
        reussi("transfert et dépense concurrents : relecture après verrou avec serveur REPEATABLE READ");
    }

    private void concurrenceInverse() throws Exception {
        Env e = environnement();
        concurrent(() -> { connecter(e.premier()); caisse.transferer(transfert(ESPECES, ORANGE_MONEY, "80")); return "ok"; },
                () -> { connecter(e.second()); caisse.transferer(transfert(ORANGE_MONEY, ESPECES, "80")); return "ok"; });
        connecter(e.premier());
        exiger(caisse.getSoldesParType().get(ESPECES).compareTo(new BigDecimal("100")) == 0
                && caisse.getSoldesParType().get(ORANGE_MONEY).compareTo(new BigDecimal("100")) == 0,
                "Les transferts inverses ont perdu une écriture.");
        exiger(caisse.getSoldeActuel().compareTo(new BigDecimal("300")) == 0 && caisse.listerTous().size() == 7,
                "Le total ou les quatre jambes sont incorrects.");
        reussi("transferts inverses concurrents sans interblocage");
    }

    private void encaissementsSimultanes() throws Exception {
        Env e = environnement();
        var premiereEcriture = new CountDownLatch(1); var terminerPremiere = new CountDownLatch(1);
        try (var executeur = Executors.newFixedThreadPool(2)) {
            var premier = executeur.submit(() -> {
                connecter(e.premier());
                try {
                    new TransactionTemplate(transactions).execute(status -> {
                        caisse.enregistrerEntree(new BigDecimal("10"), "Premier encaissement", "VENTE", e.premier());
                        premiereEcriture.countDown();
                        try { exiger(terminerPremiere.await(10, TimeUnit.SECONDS), "L'encaissement n'a pas été libéré."); }
                        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                        return null;
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            exiger(premiereEcriture.await(5, TimeUnit.SECONDS), "Le premier encaissement n'a pas écrit.");
            var second = executeur.submit(() -> {
                connecter(e.premier());
                try { caisse.enregistrerEntree(new BigDecimal("10"), "Second encaissement", "VENTE", e.premier()); }
                finally { SecurityContextHolder.clearContext(); }
            });
            // Ordre global des verrous : toute écriture de caisse verrouille d'abord la boutique ; le second encaissement
            // attend donc la fin du premier (écritures d'une même boutique sérialisées), puis réussit.
            try {
                boolean attendu = false;
                try { second.get(500, TimeUnit.MILLISECONDS); } catch (TimeoutException attente) { attendu = true; }
                exiger(attendu, "Le second encaissement aurait dû attendre le verrou de la boutique.");
            } finally { terminerPremiere.countDown(); }
            premier.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        connecter(e.premier());
        exiger(caisse.getSoldesParType().get(ESPECES).compareTo(new BigDecimal("120")) == 0
                && caisse.getSoldeActuel().compareTo(new BigDecimal("320")) == 0 && caisse.listerTous().size() == 5,
                "Les deux encaissements du même acteur n'ont pas conservé leurs écritures.");
        reussi("deux encaissements d'une même boutique s'exécutent l'un après l'autre");
    }

    private void paiementEtTransfertMemeActeur() throws Exception {
        Env e = environnement();
        var comptePris = new CountDownLatch(1); var terminerPaiement = new CountDownLatch(1);
        var transfertDemarre = new CountDownLatch(1);
        try (var executeur = Executors.newFixedThreadPool(2)) {
            var paiement = executeur.submit(() -> {
                connecter(e.premier());
                try {
                    new TransactionTemplate(transactions).execute(status -> {
                        // Ordre global des verrous : la boutique, puis le compte.
                        verrous.verrouillerBoutiqueCourante();
                        comptes.verrouillerLecture(e.boutique().getId(), idCompte(e, ESPECES)).orElseThrow();
                        comptePris.countDown();
                        try { exiger(terminerPaiement.await(10, TimeUnit.SECONDS), "L'paiement n'a pas été libéré."); }
                        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                        caisse.enregistrerSortieAchat(new BigDecimal("10"), "Paiement fournisseur concurrent", null, e.premier(), idCompte(e, ESPECES));
                        return null;
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            exiger(comptePris.await(5, TimeUnit.SECONDS), "Le verrou du compte n'a pas été acquis.");
            var transfert = executeur.submit(() -> {
                connecter(e.premier());
                try { transfertDemarre.countDown(); caisse.transferer(transfert(ESPECES, ORANGE_MONEY, "80")); }
                finally { SecurityContextHolder.clearContext(); }
            });
            try {
                exiger(transfertDemarre.await(5, TimeUnit.SECONDS), "Le transfert n'a pas démarré.");
                // MariaDB 10.4 ne publie pas systématiquement ses transactions de lecture
                // dans INNODB_LOCK_WAITS. Vérifier l'appel bloqué, puis les deux commits,
                // garde le scénario indépendant de ces statistiques de diagnostic.
                try {
                    transfert.get(500, TimeUnit.MILLISECONDS);
                    throw new IllegalStateException("Le transfert n'a pas attendu le compte partagé du paiement.");
                } catch (TimeoutException attendu) {
                    // Le paiement détient toujours le vrai verrou S acquis par le repository.
                } catch (java.util.concurrent.ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    throw new IllegalStateException("Le transfert a échoué avant le verrou : "
                            + cause.getClass().getSimpleName() + " : " + cause.getMessage(), cause);
                }
            } finally { terminerPaiement.countDown(); }
            paiement.get(15, TimeUnit.SECONDS); transfert.get(15, TimeUnit.SECONDS);
        }
        connecter(e.premier());
        exiger(caisse.getSoldesParType().get(ESPECES).compareTo(new BigDecimal("10")) == 0
                && caisse.getSoldesParType().get(ORANGE_MONEY).compareTo(new BigDecimal("180")) == 0
                && caisse.getSoldeActuel().compareTo(new BigDecimal("290")) == 0,
                "L'paiement et le transfert du même acteur n'ont pas conservé les écritures.");
        reussi("paiement et transfert du même acteur : aucune inversion des verrous auteur/compte");
    }

    private void repetitionConcurrente() throws Exception {
        Env e = environnement(); var f = transfert(ESPECES, BANQUE, "70");
        var resultats = concurrent(() -> { connecter(e.premier()); return String.valueOf(caisse.transferer(f)); },
                () -> { connecter(e.premier()); return String.valueOf(caisse.transferer(f)); });
        connecter(e.premier());
        exiger(resultats.contains("true") && resultats.contains("false"), "Les renvois n'ont pas été reconnus.");
        exiger(caisse.listerTous().size() == 5 && caisse.getSoldesParType().get(ESPECES).compareTo(new BigDecimal("30")) == 0,
                "Un formulaire concurrent a débité deux fois.");
        reussi("même transfert concurrent appliqué une seule fois");
    }

    private void isolationBoutiques() {
        Env a = environnement(); var f = transfert(ESPECES, BANQUE, "70"); caisse.transferer(f);
        Env b = environnement();
        try { caisse.transferer(f); throw new IllegalStateException("Un jeton étranger a été accepté."); }
        catch (OperationTresorerieConflitException attendu) { }
        exiger(caisse.getSoldeActuel().compareTo(new BigDecimal("300")) == 0 && caisse.listerTous().size() == 3,
                "Une autre boutique a hérité d'une opération.");
        connecter(a.premier()); exiger(caisse.getSoldeActuel().compareTo(new BigDecimal("300")) == 0, "L'isolation a modifié la première boutique.");
        reussi("soldes et jetons isolés entre boutiques");
    }

    private void rollbackComplet() {
        Env e = environnement(); var f = transfert(ESPECES, BANQUE, "70");
        var tx = new TransactionTemplate(transactions); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try {
            tx.execute(status -> { caisse.transferer(f); throw new IllegalArgumentException("annulation de test"); });
            throw new IllegalStateException("L'annulation a été ignorée.");
        } catch (IllegalArgumentException attendu) { }
        exiger(operations.findByJeton(f.getJetonOperation()).isEmpty() && caisse.listerTous().size() == 3,
                "Une annulation a conservé une opération ou une seule jambe.");
        exiger(caisse.getSoldesParType().get(ESPECES).compareTo(new BigDecimal("100")) == 0
                && caisse.getSoldesParType().get(BANQUE).compareTo(new BigDecimal("100")) == 0,
                "Une annulation a modifié les comptes.");
        reussi("annulation atomique du transfert et de ses deux écritures");
    }

    private Env environnement() {
        Boutique b = new Boutique(); b.setNom("Boutique trésorerie temporaire"); b = boutiques.saveAndFlush(b);
        Utilisateur premier = compte(b), second = compte(b); connecter(premier);
        for (var compte : TypeCompteTresorerie.values()) {
            var f = new OuvertureCaisseForm(); f.setComptePaiement(compte.name()); f.setMontant(new BigDecimal("100")); caisse.initialiser(f);
        }
        b.setInitialisationTerminee(true); boutiques.saveAndFlush(b);
        return new Env(b, premier, second);
    }

    private static Long idCompte(Env e, TypeCompteTresorerie type) {
        return e.boutique().getComptesTresorerie().stream().filter(x -> x.getType() == type).findFirst().orElseThrow().getId();
    }

    private Utilisateur compte(Boutique b) {
        var u = new Utilisateur(); u.setBoutique(b); u.setNom("Gérant temporaire"); u.setRole(Role.ADMIN);
        u.setEmail(UUID.randomUUID() + "@tresorerie-mariadb.test"); return utilisateurs.saveAndFlush(u);
    }

    private static void connecter(Utilisateur compte) {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(compte.getEmail(), "",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))); SecurityContextHolder.setContext(contexte);
    }

    private static TransfertTresorerieForm transfert(TypeCompteTresorerie source, TypeCompteTresorerie destination, String montant) {
        var f = new TransfertTresorerieForm(); f.setCompteSource(source.name()); f.setCompteDestination(destination.name());
        f.setMontant(new BigDecimal(montant)); f.setMotif("Transfert MariaDB temporaire"); f.setJetonOperation(UUID.randomUUID().toString()); return f;
    }

    private static DepenseTresorerieForm depense(TypeCompteTresorerie source, String montant) {
        var f = new DepenseTresorerieForm(); f.setCompteSource(source.name()); f.setMontant(new BigDecimal(montant));
        f.setMotif("Dépense MariaDB temporaire"); f.setJetonOperation(UUID.randomUUID().toString()); return f;
    }

    private static String resultat(Runnable action) {
        try { action.run(); return "ok"; }
        catch (IllegalArgumentException exception) {
            if (exception.getMessage().contains("insuffisant")) return "insuffisant";
            throw exception;
        }
    }

    private static List<String> concurrent(Callable<String> a, Callable<String> b) throws Exception {
        var prets = new CountDownLatch(2); var depart = new CountDownLatch(1);
        try (var executeur = Executors.newFixedThreadPool(2)) {
            var taches = List.of(a, b).stream().map(action -> executeur.submit(() -> {
                prets.countDown();
                try { exiger(depart.await(5, TimeUnit.SECONDS), "Départ concurrent absent."); return action.call(); }
                finally { SecurityContextHolder.clearContext(); }
            })).toList();
            exiger(prets.await(5, TimeUnit.SECONDS), "Les opérations ne sont pas prêtes."); depart.countDown();
            return List.of(taches.get(0).get(20, TimeUnit.SECONDS), taches.get(1).get(20, TimeUnit.SECONDS));
        }
    }

    private record Env(Boutique boutique, Utilisateur premier, Utilisateur second) {}

    private static String urlCatalogue(String serveur, String base) {
        verifierBase(base);
        int query = serveur.indexOf('?');
        return query < 0 ? serveur + base : serveur.substring(0, query) + base + serveur.substring(query);
    }

    private static void creerSchema(Connection c) throws Exception {
        String script = Files.readString(Path.of("docs/database-schema.sql"));
        script = script.replaceAll("(?m)^\\s*--[^\\r\\n]*", "");
        for (String partie : script.split(";")) {
            String instruction = partie.strip();
            if (instruction.isEmpty() || instruction.startsWith("CREATE DATABASE IF NOT EXISTS stock_pro")
                    || instruction.equals("USE stock_pro")) continue;
            exiger(instruction.startsWith("CREATE TABLE IF NOT EXISTS "), "La fixture de schéma doit uniquement créer des tables.");
            try (var s = c.createStatement()) { s.execute(instruction); }
        }
    }

    private static void verifierBase(String base) {
        exiger(base != null && base.startsWith(PREFIXE) && base.matches("stock_tresorerie_service_test_[a-z0-9]+")
                && base.length() <= 64, "Nom temporaire inattendu ; suppression refusée.");
    }

    private void reussi(String nom) { scenarios++; System.out.println("OK service trésorerie MariaDB : " + nom); }
    private static void exiger(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    @Configuration(proxyBeanMethods = false)
    @Profile("tresorerie-mariadb-service-test")
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = BoutiqueRepository.class)
    @Import({CaisseService.class, BoutiqueService.class, EssaiBoutiqueService.class, InitialisationBoutiqueService.class, org.example.stock.repository.StockLockRepository.class})
    static class ConfigurationJpa {
        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source); factory.setPackagesToScan("org.example.stock.model");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(java.util.Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.show_sql", "false",
                    "hibernate.jdbc.time_zone", "UTC", "hibernate.connection.handling_mode", "DELAYED_ACQUISITION_AND_HOLD",
                    "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                    "hibernate.implicit_naming_strategy", "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy"));
            return factory;
        }

        @Bean @Primary
        EntityManager entityManager(EntityManagerFactory factory) { return SharedEntityManagerCreator.createSharedEntityManager(factory); }
        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean
        Validator validator() { return new LocalValidatorFactoryBean(); }
        @Bean
        TrialProperties trialProperties() { return new TrialProperties(); }
        @Bean("recoveryClock")
        Clock clock() { return Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC); }
    }
}
