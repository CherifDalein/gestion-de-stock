package org.example.stock.migration;

import org.example.stock.repository.SoldePartenaireRepository;
import org.example.stock.service.SoldePartenaireService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validator;
import org.example.stock.config.TrialProperties;
import org.example.stock.enums.Role;
import org.example.stock.form.StockInitialForm;
import org.example.stock.model.*;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Coût moyen pondéré avec les vrais services d'achat, de vente et d'inventaire sur MariaDB
 * (REPEATABLE READ), exclusivement dans une base jetable, sans serveur web ni emails.
 */
public final class CoutMoyenServiceMariaDbTest {
    private static final String PREFIXE = "stock_cout_moyen_service_test_";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 7, 10, 15);
    private final AchatService achats;
    private final VenteService ventes;
    private final InventaireService inventaire;
    private final ProduitService catalogue;
    private final BoutiqueRepository boutiques;
    private final UtilisateurRepository utilisateurs;
    private final CategorieRepository categories;
    private final FournisseurRepository fournisseurs;
    private final ProduitRepository produits;
    private final DetailVenteRepository lignesVente;
    private final PlatformTransactionManager transactions;
    private int scenarios;

    private CoutMoyenServiceMariaDbTest(AnnotationConfigApplicationContext contexte) {
        achats = contexte.getBean(AchatService.class);
        ventes = contexte.getBean(VenteService.class);
        inventaire = contexte.getBean(InventaireService.class);
        catalogue = contexte.getBean(ProduitService.class);
        boutiques = contexte.getBean(BoutiqueRepository.class);
        utilisateurs = contexte.getBean(UtilisateurRepository.class);
        categories = contexte.getBean(CategorieRepository.class);
        fournisseurs = contexte.getBean(FournisseurRepository.class);
        produits = contexte.getBean(ProduitRepository.class);
        lignesVente = contexte.getBean(DetailVenteRepository.class);
        transactions = contexte.getBean(PlatformTransactionManager.class);
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
                    System.getenv("COUT_MOYEN_TEST_DB_URL"));
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
                        contexte.getEnvironment().setActiveProfiles("cout-moyen-mariadb-service-test");
                        contexte.registerBean(DataSource.class, () -> source);
                        contexte.register(ConfigurationJpa.class);
                        contexte.refresh();
                        CoutMoyenServiceMariaDbTest test = new CoutMoyenServiceMariaDbTest(contexte);
                        test.verifier();
                        System.out.println(test.scenarios + " scénarios du coût moyen pondéré MariaDB réussis.");
                    } finally { SecurityContextHolder.clearContext(); }
                } finally {
                    administration.setCatalog(null); verifierBase(base);
                    try (var s = administration.createStatement()) { s.execute("DROP DATABASE " + base); }
                    System.out.println("Base temporaire du coût moyen supprimée.");
                }
            }
        } catch (Exception e) {
            System.err.println("Test du coût moyen MariaDB interrompu : "
                    + (e instanceof IllegalStateException ? e.getMessage() : "opération impossible ; aucune valeur JDBC publiée."));
            System.exit(1);
        }
    }

    private void verifier() throws Exception {
        sequenceEtDecimales();
        modificationDAchat();
        venteQuiAttendUnAchat();
        achatQuiAttendUneVente();
        correctionApresInstantaneAnterieur();
        operationsSimultaneesPuisRejeu();
        isolationBoutiques();
    }

    private void sequenceEtDecimales() {
        Env e = environnement();
        inventaire.initialiser(e.produit().getId(), initial(e, 3, "1000.01"));
        exigerCout(e, "1000.01", "Le stock initial n'a pas fixé le coût.");
        achats.enregistrerAchat(achat(e, 3, "1000.00"));
        exigerCout(e, "1000.01", "1 000,005 n'a pas été arrondi une seule fois vers le haut.");
        var vente = ventes.effectuerVente(vente(e, 6));
        exiger(egal(coutFige(vente), "1000.01"), "La vente n'a pas figé le coût moyen.");
        achats.enregistrerAchat(achat(e, 10, "2000"));
        exigerCout(e, "2000.00", "Un stock épuisé n'est pas reparti au prix du nouvel achat.");
        reussi("stock initial, arrondi unique et stock épuisé");
    }

    private void modificationDAchat() {
        Env e = environnement();
        var premier = achats.enregistrerAchat(achat(e, 10, "1000"));
        var vente = ventes.effectuerVente(vente(e, 4));
        achats.enregistrerAchat(achat(e, 10, "2000"));
        exigerCout(e, "1625.00", "Le coût incrémental est incorrect.");
        achats.modifierAchat(premier.getId(), modification(e, 10, "1200"));
        exigerCout(e, "1700.00", "La correction n'a pas rejoué l'historique.");
        exiger(egal(coutFige(vente), "1000.00"), "La correction a réécrit le coût d'une vente passée.");
        reussi("correction d'un ancien achat sans réécrire les ventes");
    }

    private void venteQuiAttendUnAchat() throws Exception {
        Env e = environnement();
        achats.enregistrerAchat(achat(e, 10, "1000"));
        Object vente = retenu(e, () -> achats.enregistrerAchat(achat(e, 10, "2000")), () -> ventes.effectuerVente(vente(e, 5)));
        exiger(egal(coutFige((Vente) vente), "1500.00"), "La vente n'a pas relu le coût après l'achat concurrent.");
        exigerCout(e, "1500.00", "La vente a modifié le coût moyen.");
        exiger(stock(e) == 15, "Le stock concurrent est incorrect.");
        reussi("vente bloquée par un achat : coût relu après verrou");
    }

    private void achatQuiAttendUneVente() throws Exception {
        Env e = environnement();
        achats.enregistrerAchat(achat(e, 10, "1000"));
        var vente = new Vente[1];
        retenu(e, () -> vente[0] = ventes.effectuerVente(vente(e, 5)), () -> achats.enregistrerAchat(achat(e, 10, "2000")));
        exiger(egal(coutFige(vente[0]), "1000.00"), "La vente n'a pas figé le coût d'avant l'achat.");
        exigerCout(e, "1666.67", "L'achat n'a pas pondéré le stock restant après la vente.");
        reussi("achat bloqué par une vente : stock relu après verrou");
    }

    /** La correction démarre son instantané REPEATABLE READ avant qu'une vente soit validée. */
    private void correctionApresInstantaneAnterieur() throws Exception {
        Env e = environnement();
        var premier = achats.enregistrerAchat(achat(e, 10, "1000"));
        achats.enregistrerAchat(achat(e, 10, "2000"));
        var instantane = new CountDownLatch(1);
        var venteValidee = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> correction = pool.submit(() -> comme(e.gerant(), () -> new TransactionTemplate(transactions).execute(statut -> {
                exiger(produits.findById(e.produit().getId()).orElseThrow().getQuantite() == 20, "Instantané inattendu.");
                instantane.countDown();
                attendre(venteValidee);
                achats.modifierAchat(premier.getId(), modification(e, 10, "1200"));
                return null;
            })));
            exiger(instantane.await(10, TimeUnit.SECONDS), "L'instantané n'a pas été pris.");
            var vente = comme(e.gerant(), () -> ventes.effectuerVente(vente(e, 8)));
            venteValidee.countDown();
            correction.get(20, TimeUnit.SECONDS);
            exiger(egal(coutFige(vente), "1500.00"), "La vente n'a pas figé le coût moyen.");
        }
        connecter(e.gerant());
        exiger(stock(e) == 12, "La correction a perdu la sortie de la vente concurrente.");
        exigerCout(e, "1600.00", "La correction n'a pas rejoué l'historique complet.");
        reussi("correction après un instantané antérieur à une vente concurrente");
    }

    /**
     * Achats et ventes simultanés sur le même produit, puis une correction identique : le rejeu
     * chronologique doit retrouver exactement le coût calculé au fil des verrous.
     */
    private void operationsSimultaneesPuisRejeu() throws Exception {
        Env e = environnement();
        var premier = achats.enregistrerAchat(achat(e, 40, "1000"));
        var depart = new CyclicBarrier(2);
        List<Vente> ventesFaites = new CopyOnWriteArrayList<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> acheteur = pool.submit(() -> comme(e.gerant(), () -> {
                attendre(depart);
                for (int i = 0; i < 8; i++) achats.enregistrerAchat(achat(e, 3 + i, (1000 + 137 * i) + ".37"));
                return null;
            }));
            Future<?> vendeur = pool.submit(() -> comme(e.gerant(), () -> {
                attendre(depart);
                for (int i = 0; i < 8; i++) ventesFaites.add(ventes.effectuerVente(vente(e, 2 + i % 3)));
                return null;
            }));
            acheteur.get(60, TimeUnit.SECONDS); vendeur.get(60, TimeUnit.SECONDS);
        }
        connecter(e.gerant());
        BigDecimal incremental = produits.findById(e.produit().getId()).orElseThrow().getCoutMoyen();
        for (Vente vente : ventesFaites) {
            BigDecimal cout = coutFige(vente);
            exiger(cout != null && cout.compareTo(new BigDecimal("1000")) >= 0 && cout.compareTo(new BigDecimal("1960.37")) <= 0,
                    "Une vente concurrente a figé un coût incohérent.");
        }
        List<BigDecimal> avant = new ArrayList<>(ventesFaites.stream().map(this::coutFige).toList());
        achats.modifierAchat(premier.getId(), modification(e, 40, "1000"));
        exigerCout(e, incremental.toPlainString(), "Le rejeu ne retrouve pas le coût calculé sous verrou ("
                + incremental + " / " + cout(e) + ").");
        exiger(avant.equals(ventesFaites.stream().map(this::coutFige).toList()), "Le rejeu a modifié les ventes.");
        reussi("achats et ventes simultanés : rejeu identique au calcul sous verrou");
    }

    private void isolationBoutiques() {
        Env a = environnement();
        var premierA = achats.enregistrerAchat(achat(a, 10, "1000"));
        Env b = environnement();
        achats.enregistrerAchat(achat(b, 10, "3000"));
        connecter(a.gerant());
        achats.enregistrerAchat(achat(a, 10, "2000"));
        achats.modifierAchat(premierA.getId(), modification(a, 10, "1600"));
        exigerCout(a, "1800.00", "Le coût de la première boutique est incorrect.");
        connecter(b.gerant());
        exigerCout(b, "3000.00", "Une autre boutique a modifié ce coût moyen.");
        reussi("coûts moyens isolés entre boutiques");
    }

    // --- Données

    private Env environnement() {
        var boutique = new Boutique(); boutique.setNom("Boutique CMP temporaire"); boutique = boutiques.saveAndFlush(boutique);
        var gerant = new Utilisateur(); gerant.setBoutique(boutique); gerant.setNom("Gérant temporaire"); gerant.setRole(Role.ADMIN);
        gerant.setEmail(UUID.randomUUID() + "@cout-moyen-mariadb.test"); gerant = utilisateurs.saveAndFlush(gerant);
        var categorie = new Categorie(); categorie.setBoutique(boutique); categorie.setNom("Catalogue"); categorie = categories.saveAndFlush(categorie);
        var fournisseur = new Fournisseur(); fournisseur.setBoutique(boutique); fournisseur.setNom("Fournisseur temporaire");
        fournisseur = fournisseurs.saveAndFlush(fournisseur);
        connecter(gerant);
        var fiche = new Produit(); fiche.setNom("Sac de riz"); fiche.setReference("CMP-" + UUID.randomUUID()); fiche.setCategorie(categorie);
        return new Env(boutique, gerant, fournisseur, catalogue.ajouterProduit(fiche));
    }

    private record Env(Boutique boutique, Utilisateur gerant, Fournisseur fournisseur, Produit produit) {}

    private static Produit reference(Env e) { var p = new Produit(); p.setId(e.produit().getId()); return p; }

    private static Achat achat(Env e, int quantite, String prix) {
        var ligne = new DetailAchat(); ligne.setProduit(reference(e)); ligne.setQuantite(quantite);
        ligne.setPrixAchatUnitaire(new BigDecimal(prix)); ligne.setPrixVenteUnitaire(new BigDecimal("5000"));
        var achat = new Achat(); achat.setFournisseur(e.fournisseur()); achat.setMontantVerse(Montants.ZERO); achat.getLignes().add(ligne);
        return achat;
    }

    private static Achat modification(Env e, int quantite, String prix) {
        var achat = achat(e, quantite, prix); achat.setMontantVerse(null); return achat;
    }

    private static Vente vente(Env e, int quantite) {
        var ligne = new DetailVente(); ligne.setProduit(reference(e)); ligne.setQuantite(quantite);
        var vente = new Vente(); vente.setMontantVerse(Montants.ZERO); vente.getLignes().add(ligne); return vente;
    }

    private StockInitialForm initial(Env e, long quantite, String cout) {
        var f = new StockInitialForm(); f.setVersion(produits.findById(e.produit().getId()).orElseThrow().getVersion());
        f.setQuantite(quantite); f.setCoutUnitaire(new BigDecimal(cout)); f.setPrixVente(new BigDecimal("5000"));
        f.setMotif("Stock présent à l’ouverture"); return f;
    }

    private BigDecimal coutFige(Vente vente) {
        return lignesVente.findById(vente.getLignes().getFirst().getId()).orElseThrow().getCoutUnitaire();
    }

    private BigDecimal cout(Env e) { return produits.findById(e.produit().getId()).orElseThrow().getCoutMoyen(); }
    private long stock(Env e) { return produits.findById(e.produit().getId()).orElseThrow().getQuantite(); }
    private void exigerCout(Env e, String attendu, String message) { exiger(egal(cout(e), attendu), message + " Obtenu : " + cout(e)); }
    private static boolean egal(BigDecimal valeur, String attendu) { return valeur != null && valeur.compareTo(new BigDecimal(attendu)) == 0; }

    // --- Concurrence

    /** La première opération garde ses verrous ; la seconde doit attendre puis relire. Renvoie la seconde. */
    private Object retenu(Env e, Callable<?> premiere, Callable<?> seconde) throws Exception {
        var verrouPris = new CountDownLatch(1); var liberer = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> a = pool.submit(() -> comme(e.gerant(), () -> new TransactionTemplate(transactions).execute(statut -> {
                try { premiere.call(); } catch (Exception erreur) { throw new IllegalStateException(erreur); }
                verrouPris.countDown(); attendre(liberer); return null;
            })));
            try {
                exiger(verrouPris.await(10, TimeUnit.SECONDS), "La première opération n'a pas pris ses verrous.");
                Future<?> b = pool.submit(() -> comme(e.gerant(), seconde));
                try { b.get(500, TimeUnit.MILLISECONDS); throw new IllegalStateException("La seconde opération n'a pas attendu le produit."); }
                catch (TimeoutException attendu) { }
                liberer.countDown();
                a.get(15, TimeUnit.SECONDS);
                return b.get(15, TimeUnit.SECONDS);
            } finally { liberer.countDown(); }
        } finally { connecter(e.gerant()); }
    }

    private static <T> T comme(Utilisateur compte, Callable<T> action) {
        var precedent = SecurityContextHolder.getContext();
        connecter(compte);
        try { return action.call(); }
        catch (RuntimeException erreur) { throw erreur; }
        catch (Exception erreur) { throw new IllegalStateException(erreur); }
        finally { SecurityContextHolder.setContext(precedent); }
    }

    private static void connecter(Utilisateur compte) {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(compte.getEmail(), "",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))); SecurityContextHolder.setContext(contexte);
    }

    private static void attendre(CountDownLatch latch) {
        try { exiger(latch.await(10, TimeUnit.SECONDS), "Synchronisation expirée."); }
        catch (InterruptedException erreur) { Thread.currentThread().interrupt(); throw new IllegalStateException(erreur); }
    }

    private static void attendre(CyclicBarrier barriere) {
        try { barriere.await(10, TimeUnit.SECONDS); }
        catch (Exception erreur) { throw new IllegalStateException("Départ concurrent absent."); }
    }

    // --- Base temporaire

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
        exiger(base != null && base.startsWith(PREFIXE) && base.matches("stock_cout_moyen_service_test_[a-z0-9]+")
                && base.length() <= 64, "Nom temporaire inattendu ; suppression refusée.");
    }

    private void reussi(String nom) { scenarios++; System.out.println("OK coût moyen MariaDB : " + nom); }
    private static void exiger(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    @Configuration(proxyBeanMethods = false)
    @Profile("cout-moyen-mariadb-service-test")
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = BoutiqueRepository.class)
    @Import({AchatService.class, VenteService.class, InventaireService.class, ProduitService.class, CoutMoyenService.class,
            CaisseService.class, BoutiqueService.class, EssaiBoutiqueService.class, InitialisationBoutiqueService.class,
            StockLockRepository.class, NumerotationFactureService.class, EcheanceService.class, SoldePartenaireService.class, SoldePartenaireRepository.class})
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
