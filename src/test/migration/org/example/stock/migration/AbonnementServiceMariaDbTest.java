package org.example.stock.migration;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validator;
import org.example.stock.enums.Role;
import org.example.stock.enums.StatutAbonnement;
import org.example.stock.form.AccesBoutiqueForm;
import org.example.stock.form.ActivationAbonnementForm;
import org.example.stock.form.ProlongationEssaiForm;
import org.example.stock.model.Boutique;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.BoutiqueRepository;
import org.example.stock.repository.HistoriqueAbonnementRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.example.stock.service.GestionAbonnementService;
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
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Vrai service transactionnel sur MariaDB ; aucun serveur web, aucun email, uniquement une base jetable. */
public final class AbonnementServiceMariaDbTest {
    private static final String PREFIXE = "stock_abonnement_service_test_";
    private static final LocalDateTime NOW = LocalDateTime.of(2024, 1, 31, 10, 15);
    private final GestionAbonnementService service;
    private final BoutiqueRepository boutiques;
    private final UtilisateurRepository utilisateurs;
    private final HistoriqueAbonnementRepository historique;
    private final Utilisateur operateur, autreOperateur;
    private int scenarios;

    private AbonnementServiceMariaDbTest(AnnotationConfigApplicationContext contexte) {
        service = contexte.getBean(GestionAbonnementService.class);
        boutiques = contexte.getBean(BoutiqueRepository.class);
        utilisateurs = contexte.getBean(UtilisateurRepository.class);
        historique = contexte.getBean(HistoriqueAbonnementRepository.class);
        Boutique plateforme = boutique("Plateforme de test");
        operateur = compte("operateur@abonnement-mariadb.test", plateforme);
        autreOperateur = compte("autre-operateur@abonnement-mariadb.test", plateforme);
        connecter(operateur);
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
                    System.getenv("ABONNEMENT_TEST_DB_URL"));
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
                        contexte.getEnvironment().setActiveProfiles("abonnement-mariadb-service-test");
                        contexte.registerBean(DataSource.class, () -> source);
                        contexte.register(ConfigurationJpa.class);
                        contexte.refresh();
                        AbonnementServiceMariaDbTest test = new AbonnementServiceMariaDbTest(contexte);
                        test.verifier();
                        System.out.println(test.scenarios + " scénarios du service abonnement MariaDB réussis.");
                    } finally { SecurityContextHolder.clearContext(); }
                } finally {
                    administration.setCatalog(null); verifierBase(base);
                    try (var s = administration.createStatement()) { s.execute("DROP DATABASE " + base); }
                    System.out.println("Base temporaire du service abonnement supprimée.");
                }
            }
        } catch (Exception e) {
            System.err.println("Test du service abonnement MariaDB interrompu : "
                    + (e instanceof IllegalStateException ? e.getMessage() : "opération impossible ; aucune valeur JDBC publiée."));
            System.exit(1);
        }
    }

    private void verifier() throws Exception {
        moisCivil();
        essaiAvantAbonnement();
        moisConcurrentsMemeBoutique();
        moisConcurrentsBoutiquesDifferentes();
        repetitionConcurrente();
        contenuJetonDifferent();
        prolongationsConcurrentes();
        suspensionEtOffertIndependants();
    }

    private void moisCivil() {
        Boutique b = boutique("Fin de janvier");
        ActivationAbonnementForm formulaire = activation();
        var premiere = service.activerMois(b.getId(), formulaire);
        var seconde = service.activerMois(b.getId(), formulaire);
        exiger(premiere.abonnementFin().equals(LocalDateTime.of(2024, 2, 29, 10, 15)), "Le mois bissextile est incorrect.");
        exiger(seconde.abonnementFin().equals(premiere.abonnementFin()), "Un formulaire répété a ajouté un mois.");
        exiger(nombre(b) == 1, "Le formulaire répété a créé plusieurs événements.");
        reussi("mois civil bissextile et renvoi idempotent");
    }

    private void essaiAvantAbonnement() {
        Boutique b = boutique("Essai conservé");
        b.setEssaiDebut(NOW.minusDays(20)); b.setEssaiFin(NOW.plusDays(10)); boutiques.saveAndFlush(b);
        var situation = service.activerMois(b.getId(), activation());
        exiger(situation.statut() == StatutAbonnement.ESSAI, "L’essai n’est plus affiché comme actif.");
        exiger(situation.essaiFin().equals(NOW.plusDays(10)), "L’activation a modifié l’essai.");
        exiger(situation.abonnementDebut().equals(situation.essaiFin()), "Le mois doit suivre l’essai.");
        reussi("activation à la fin d’un essai valide");
    }

    private void moisConcurrentsMemeBoutique() throws Exception {
        Boutique b = boutique("Deux mois concurrents");
        concurrent(() -> { connecter(operateur); service.activerMois(b.getId(), activation()); },
                () -> { connecter(autreOperateur); service.activerMois(b.getId(), activation()); });
        Boutique resultat = boutiques.findById(b.getId()).orElseThrow();
        exiger(resultat.getAbonnementDebut().equals(NOW), "Le début d’abonnement a été perdu.");
        exiger(resultat.getAbonnementFin().equals(NOW.plusMonths(1).plusMonths(1)), "Une activation concurrente a été perdue.");
        exiger(nombre(b) == 2, "L’historique concurrent doit contenir les deux actions.");
        reussi("deux administrateurs et deux mois pour la même boutique");
    }

    private void moisConcurrentsBoutiquesDifferentes() throws Exception {
        Boutique a = boutique("Boutique concurrente A"), b = boutique("Boutique concurrente B");
        concurrent(() -> { connecter(operateur); service.activerMois(a.getId(), activation()); },
                () -> { connecter(autreOperateur); service.activerMois(b.getId(), activation()); });
        exiger(boutiques.findById(a.getId()).orElseThrow().getAbonnementFin().equals(NOW.plusMonths(1)), "La première boutique a perdu son mois.");
        exiger(boutiques.findById(b.getId()).orElseThrow().getAbonnementFin().equals(NOW.plusMonths(1)), "La seconde boutique a perdu son mois.");
        exiger(nombre(a) == 1 && nombre(b) == 1, "Les historiques des boutiques doivent rester distincts.");
        reussi("deux boutiques et deux jetons concurrents");
    }

    private void repetitionConcurrente() throws Exception {
        Boutique b = boutique("Même formulaire concurrent");
        ActivationAbonnementForm formulaire = activation();
        concurrent(() -> { connecter(operateur); service.activerMois(b.getId(), formulaire); },
                () -> { connecter(operateur); service.activerMois(b.getId(), formulaire); });
        exiger(boutiques.findById(b.getId()).orElseThrow().getAbonnementFin().equals(NOW.plusMonths(1)), "Le formulaire concurrent a accordé deux mois.");
        exiger(nombre(b) == 1, "Le formulaire concurrent a ajouté deux événements.");
        reussi("un formulaire concurrent n’accorde qu’un mois");
    }

    private void contenuJetonDifferent() {
        Boutique a = boutique("Jeton original"), b = boutique("Jeton détourné");
        ActivationAbonnementForm formulaire = activation(); service.activerMois(a.getId(), formulaire);
        try {
            service.activerMois(b.getId(), formulaire);
            throw new IllegalStateException("Un jeton a été accepté pour une autre boutique.");
        } catch (ResponseStatusException attendu) {
            exiger(attendu.getStatusCode().value() == 409, "Le jeton détourné doit provoquer un conflit.");
        }
        exiger(boutiques.findById(b.getId()).orElseThrow().getAbonnementFin() == null && nombre(b) == 0,
                "Le conflit a laissé une période ou un événement.");
        reussi("un jeton reste lié à son action et à sa boutique");
    }

    private void prolongationsConcurrentes() throws Exception {
        Boutique b = boutique("Essai expiré concurrent");
        b.setEssaiDebut(NOW.minusDays(60)); b.setEssaiFin(NOW.minusDays(30)); boutiques.saveAndFlush(b);
        concurrent(() -> { connecter(operateur); service.prolongerEssai(b.getId(), prolongation(3)); },
                () -> { connecter(autreOperateur); service.prolongerEssai(b.getId(), prolongation(5)); });
        Boutique resultat = boutiques.findById(b.getId()).orElseThrow();
        exiger(resultat.getEssaiDebut().equals(NOW.minusDays(60)), "Le début de l’essai a été réécrit.");
        exiger(resultat.getEssaiFin().equals(NOW.plusDays(8)), "Une prolongation concurrente a été perdue.");
        exiger(nombre(b) == 2, "Les deux prolongations doivent être conservées.");
        reussi("deux prolongations après expiration sont cumulées");
    }

    private void suspensionEtOffertIndependants() {
        Boutique b = boutique("Accès indépendant");
        AccesBoutiqueForm acces = new AccesBoutiqueForm(); acces.setActif(false); acces.setAccesOffert(true);
        service.modifierAcces(b.getId(), acces);
        var situation = service.activerMois(b.getId(), activation());
        exiger(!situation.actif() && situation.accesOffert() && !situation.accesAutorise(), "Le mois a réactivé une boutique suspendue.");
        exiger(situation.statut() == StatutAbonnement.SUSPENDU, "La suspension doit rester prioritaire.");
        service.modifierAcces(b.getId(), acces);
        exiger(nombre(b) == 3, "La répétition des drapeaux a créé un historique fictif.");
        reussi("suspension et accès offert restent indépendants du mois");
    }

    private long nombre(Boutique b) {
        connecter(operateur);
        return service.consulterPourPlateforme(b.getId(), 0).historique().size();
    }

    private Boutique boutique(String nom) {
        Boutique b = new Boutique(); b.setNom(nom); return boutiques.saveAndFlush(b);
    }

    private Utilisateur compte(String email, Boutique b) {
        Utilisateur u = new Utilisateur(); u.setEmail(email); u.setNom("Opérateur de test");
        u.setMotDePasse("empreinte-factice-jamais-utilisee-pour-login"); u.setBoutique(b);
        u.setRole(Role.ADMIN); u.setAdministrateurPlateforme(true); return utilisateurs.saveAndFlush(u);
    }

    private static ActivationAbonnementForm activation() {
        ActivationAbonnementForm f = new ActivationAbonnementForm(); f.setJetonAction(UUID.randomUUID().toString()); return f;
    }

    private static ProlongationEssaiForm prolongation(int jours) {
        ProlongationEssaiForm f = new ProlongationEssaiForm(); f.setJours(jours); f.setJetonAction(UUID.randomUUID().toString()); return f;
    }

    private static void connecter(Utilisateur compte) {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(compte.getEmail(), "",
                List.of(new SimpleGrantedAuthority("ROLE_PLATEFORME_ADMIN"))));
        SecurityContextHolder.setContext(contexte);
    }

    private static void concurrent(Runnable premiere, Runnable seconde) throws Exception {
        CountDownLatch prets = new CountDownLatch(2), depart = new CountDownLatch(1);
        try (var executeur = Executors.newFixedThreadPool(2)) {
            var taches = List.of(premiere, seconde).stream().map(action -> executeur.submit(() -> {
                prets.countDown();
                try {
                    exiger(depart.await(5, TimeUnit.SECONDS), "Une opération concurrente n’a pas démarré.");
                    action.run();
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Test concurrent interrompu."); }
                finally { SecurityContextHolder.clearContext(); }
            })).toList();
            exiger(prets.await(5, TimeUnit.SECONDS), "Les deux opérations ne sont pas prêtes."); depart.countDown();
            for (var tache : taches) tache.get(20, TimeUnit.SECONDS);
        }
    }

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
        exiger(base != null && base.startsWith(PREFIXE) && base.matches("stock_abonnement_service_test_[a-z0-9]+")
                && base.length() <= 64, "Nom temporaire inattendu ; suppression refusée.");
    }

    private void reussi(String nom) { scenarios++; System.out.println("OK service abonnement MariaDB : " + nom); }
    private static void exiger(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    @Configuration(proxyBeanMethods = false)
    @Profile("abonnement-mariadb-service-test")
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = BoutiqueRepository.class)
    @Import(GestionAbonnementService.class)
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
        @Bean("recoveryClock")
        Clock clock() { return Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC); }
    }
}
