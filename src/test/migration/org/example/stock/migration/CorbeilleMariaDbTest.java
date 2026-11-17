package org.example.stock.migration;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validator;
import org.example.stock.config.CorbeilleProperties;
import org.example.stock.config.TrialProperties;
import org.example.stock.enums.Role;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.example.stock.compta.VerrouExercice;
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
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
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

/**
 * Corbeille (lot 6) avec les vrais services et le SQL natif de {@code CorbeilleService} sur MariaDB (REPEATABLE READ), dans une base jetable
 * créée depuis {@code docs/database-schema.sql} : lister, filtrer, restaurer, conflit de nom de catégorie ({@code nom_cle}), effacement
 * définitif, vidage, effacement automatique au-delà de la durée de conservation, fiche encore référencée gardée et comptée, isolation des boutiques.
 */
public final class CorbeilleMariaDbTest {
    private static final String PREFIXE = "stock_corbeille_service_test_";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 7, 10, 15);
    private final DataSource source;
    private final CorbeilleService corbeille;
    private final ClientService clients;
    private final FournisseurService fournisseurs;
    private final CategorieService categories;
    private final ProduitService produits;
    private final BoutiqueRepository boutiques;
    private final UtilisateurRepository utilisateurs;
    private int scenarios;

    private CorbeilleMariaDbTest(AnnotationConfigApplicationContext contexte, DataSource source) {
        this.source = source;
        corbeille = contexte.getBean(CorbeilleService.class);
        clients = contexte.getBean(ClientService.class);
        fournisseurs = contexte.getBean(FournisseurService.class);
        categories = contexte.getBean(CategorieService.class);
        produits = contexte.getBean(ProduitService.class);
        boutiques = contexte.getBean(BoutiqueRepository.class);
        utilisateurs = contexte.getBean(UtilisateurRepository.class);
    }

    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        try {
            Properties config = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { config.load(lecteur); }
            String serveur = InscriptionEssaiMariaDbTest.urlServeurTest(AccountEmailMigration.resolve(config.getProperty("spring.datasource.url")),
                    System.getenv("CORBEILLE_TEST_DB_URL"));
            String utilisateur = AccountEmailMigration.resolve(config.getProperty("spring.datasource.username"));
            String secret = AccountEmailMigration.resolve(config.getProperty("spring.datasource.password"));
            String base = PREFIXE + Long.toUnsignedString(System.nanoTime(), 36);
            verifierBase(base);
            try (Connection administration = DriverManager.getConnection(serveur, utilisateur, secret)) {
                try (var s = administration.createStatement()) { s.execute("CREATE DATABASE " + base + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"); }
                try {
                    administration.setCatalog(base);
                    creerSchema(administration);
                    DriverManagerDataSource source = new DriverManagerDataSource(urlCatalogue(serveur, base), utilisateur, secret);
                    source.setDriverClassName("org.mariadb.jdbc.Driver");
                    try (var contexte = new AnnotationConfigApplicationContext()) {
                        contexte.getEnvironment().setActiveProfiles("corbeille-mariadb-service-test");
                        contexte.registerBean(DataSource.class, () -> source);
                        contexte.register(ConfigurationJpa.class);
                        contexte.refresh();
                        CorbeilleMariaDbTest test = new CorbeilleMariaDbTest(contexte, source);
                        test.verifier();
                        System.out.println(test.scenarios + " scénarios de la corbeille MariaDB réussis.");
                    } finally { SecurityContextHolder.clearContext(); }
                } finally {
                    administration.setCatalog(null); verifierBase(base);
                    try (var s = administration.createStatement()) { s.execute("DROP DATABASE " + base); }
                    System.out.println("Base temporaire de la corbeille supprimée.");
                }
            }
        } catch (Exception e) {
            System.err.println("Test de la corbeille MariaDB interrompu : "
                    + (e instanceof IllegalStateException ? e.getMessage() : e.getClass().getSimpleName() + " " + e.getMessage()));
            System.exit(1);
        }
    }

    private void verifier() throws Exception {
        listerEtFiltrer();
        restaurer();
        conflitDeNomDeCategorie();
        suppressionDefinitive();
        viderLaCorbeille();
        ficheEncoreReferenceeConservee();
        purgeAuDelaDeLaDuree();
        isolationDesBoutiques();
    }

    // --- Scénarios

    private void listerEtFiltrer() throws Exception {
        Env e = environnement("Liste");
        var c1 = client("Awa Dupont"); var c2 = client("Moussa Diallo");
        var f = fournisseur("Grossiste Sahel"); var cat = categorie("Épicerie");
        var p = produit("Jus de bissap", categorie("Boissons liste"));
        for (Long id : List.of(c1.getId(), c2.getId())) clients.supprimerClient(id);
        fournisseurs.supprimerFournisseur(f.getId()); categories.supprimer(cat.getId()); produits.supprimerProduit(p.getId());
        var tout = corbeille.lister(null, "");
        exiger(tout.size() == 5, "Le contenu de la corbeille est incomplet : " + tout.size());
        exiger(corbeille.lister(TypeCorbeille.CLIENT, "").size() == 2, "Le filtre par type est faux.");
        exiger(corbeille.lister(null, "dupont").size() == 1, "La recherche par nom est fausse.");
        exiger(corbeille.lister(null, "epicerie").size() == 1, "La recherche ne tient pas compte des accents.");
        var ligne = corbeille.lister(TypeCorbeille.PRODUIT, "").get(0);
        exiger(ligne.supprimePar().equals(e.gerant.getId()) && "Gérant Liste".equals(ligne.supprimeParNom()), "L'auteur de la suppression est faux.");
        exiger(ligne.joursRestants() == 30 && ligne.supprimeLe().toLocalDate().equals(NOW.toLocalDate()), "La date ou le délai est faux : " + ligne);
        exiger(corbeille.compter().get(TypeCorbeille.CLIENT) == 2, "Le compteur par type est faux.");
        exiger(clients.listerTous().stream().noneMatch(x -> x.getId().equals(c1.getId())), "Un client de la corbeille figure encore dans la liste.");
        exiger(categories.listerToutes().stream().noneMatch(x -> x.getNom().equals("Épicerie")), "Une catégorie de la corbeille figure encore dans la liste.");
        exiger(produits.listerTous().isEmpty(), "Un produit de la corbeille figure encore dans la liste.");
        corbeille.vider();
        reussi("lister_filtrer");
    }

    private void restaurer() throws Exception {
        environnement("Restauration");
        var c = client("Client restauré");
        clients.supprimerClient(c.getId());
        exiger(clients.listerTous().isEmpty(), "Le client est encore listé.");
        var r = corbeille.restaurer(TypeCorbeille.CLIENT, c.getId());
        exiger(r.nom().equals("Client restauré"), "Le nom restauré est faux.");
        exiger(clients.listerTous().size() == 1 && corbeille.lister(null, "").isEmpty(), "La restauration n'a pas remis le client.");
        exiger(compte("select count(*) from client where id = " + c.getId() + " and supprime_le is null and supprime_par is null") == 1, "Les colonnes de la corbeille ne sont pas vidées.");
        exiger(refuse(() -> corbeille.restaurer(TypeCorbeille.CLIENT, c.getId())), "Une fiche active a été « restaurée ».");
        reussi("restaurer");
    }

    private void conflitDeNomDeCategorie() throws Exception {
        environnement("Conflit");
        var ancienne = categorie("Boissons");
        categories.supprimer(ancienne.getId());
        exiger(compte("select count(*) from categorie where id = " + ancienne.getId() + " and nom_cle is null") == 1, "nom_cle doit être vide dans la corbeille.");
        var nouvelle = categorie("Boissons");
        exiger(!nouvelle.getId().equals(ancienne.getId()), "La catégorie du même nom n'a pas pu être recréée.");
        try { categorie("Boissons"); exiger(false, "Deux catégories actives du même nom ont été créées."); } catch (IllegalArgumentException | org.springframework.dao.DataIntegrityViolationException attendu) { /* refusé */ }
        try { categorie("BOISSONS"); exiger(false, "La casse ne doit pas contourner l'unicité."); } catch (IllegalArgumentException | org.springframework.dao.DataIntegrityViolationException attendu) { /* refusé */ }
        try { corbeille.restaurer(TypeCorbeille.CATEGORIE, ancienne.getId()); exiger(false, "La restauration d'un nom repris a été acceptée."); }
        catch (IllegalArgumentException refus) {
            exiger(refus.getMessage().contains("Renommez d’abord la catégorie active"), "Le message de conflit est incomplet : " + refus.getMessage());
        }
        var renommee = new Categorie(); renommee.setNom("Boissons fraîches");
        categories.modifierCategorie(nouvelle.getId(), renommee);
        corbeille.restaurer(TypeCorbeille.CATEGORIE, ancienne.getId());
        exiger(compte("select count(*) from categorie where nom_cle = 'Boissons' and supprime_le is null") == 1, "nom_cle n'a pas été rétabli à la restauration.");
        exiger(categories.listerToutes().size() == 2, "Les deux catégories doivent être actives.");
        reussi("conflit_nom_categorie");
    }

    private void suppressionDefinitive() throws Exception {
        environnement("Definitive");
        var c = client("À effacer");
        exiger(refuse(() -> corbeille.supprimerDefinitivement(TypeCorbeille.CLIENT, c.getId())), "Une fiche active a été effacée par la corbeille.");
        clients.supprimerClient(c.getId());
        corbeille.supprimerDefinitivement(TypeCorbeille.CLIENT, c.getId());
        exiger(compte("select count(*) from client where id = " + c.getId()) == 0, "La ligne n'a pas été effacée.");
        exiger(corbeille.lister(null, "").isEmpty(), "La corbeille n'est pas vide.");
        reussi("suppression_definitive");
    }

    private void viderLaCorbeille() throws Exception {
        environnement("Vider");
        var cat = categorie("Vide");
        var p = produit("Produit à vider", cat);
        var f = fournisseur("Fournisseur à vider"); var c = client("Client à vider");
        produits.supprimerProduit(p.getId()); categories.supprimer(cat.getId()); fournisseurs.supprimerFournisseur(f.getId()); clients.supprimerClient(c.getId());
        var bilan = corbeille.vider();
        exiger(bilan.effaces() == 4 && bilan.conserves() == 0, "Bilan du vidage inattendu : " + bilan);
        exiger(compte("select count(*) from produit where supprime_le is not null") == 0
                && compte("select count(*) from categorie where supprime_le is not null") == 0, "La corbeille contient encore des lignes.");
        reussi("vider");
    }

    private void ficheEncoreReferenceeConservee() throws Exception {
        Env e = environnement("Reference");
        var cat = categorie("Encore utilisée");
        var p = produit("Produit actif", cat);
        // Une catégorie mise à la corbeille « de force » alors qu'un produit actif s'y réfère : jamais effacée, et comptée.
        sql("update categorie set supprime_le = '" + NOW + "', supprime_par = " + e.gerant.getId() + ", nom_cle = null where id = " + cat.getId());
        exiger(refuse(() -> corbeille.supprimerDefinitivement(TypeCorbeille.CATEGORIE, cat.getId())), "Une catégorie référencée a été effacée.");
        var bilan = corbeille.vider();
        exiger(bilan.effaces() == 0 && bilan.conserves() == 1, "La fiche référencée n'a pas été gardée et comptée : " + bilan);
        sql("update categorie set supprime_le = supprime_le - interval 40 day where id = " + cat.getId());
        var purge = corbeille.purgerExpires();
        exiger(purge.effaces() == 0 && purge.conserves() == 1, "La purge a effacé une fiche référencée : " + purge);
        exiger(compte("select count(*) from categorie where id = " + cat.getId()) == 1 && compte("select count(*) from produit where id = " + p.getId()) == 1, "Une ligne a disparu.");
        // La purge touche toutes les boutiques : on remet la fiche en service pour les scénarios suivants.
        sql("update categorie set supprime_le = null, supprime_par = null, nom_cle = nom where id = " + cat.getId());
        reussi("fiche_referencee_conservee");
    }

    private void purgeAuDelaDeLaDuree() throws Exception {
        environnement("Purge");
        var vieux = client("Vieux client"); var recent = client("Client récent"); var fournisseur = fournisseur("Vieux fournisseur");
        clients.supprimerClient(vieux.getId()); clients.supprimerClient(recent.getId()); fournisseurs.supprimerFournisseur(fournisseur.getId());
        sql("update client set supprime_le = supprime_le - interval 31 day where id = " + vieux.getId());
        sql("update client set supprime_le = supprime_le - interval 29 day where id = " + recent.getId());
        sql("update fournisseur set supprime_le = supprime_le - interval 31 day where id = " + fournisseur.getId());
        var bilan = corbeille.purgerExpires();
        exiger(bilan.effaces() == 2 && bilan.conserves() == 0, "La purge a un bilan inattendu : " + bilan);
        exiger(compte("select count(*) from client where id = " + vieux.getId()) == 0 && compte("select count(*) from client where id = " + recent.getId()) == 1,
                "La purge n'a pas respecté la durée de conservation.");
        exiger(corbeille.purgerExpires(NOW).effaces() == 0, "Une fiche de 29 jours a été purgée.");
        exiger(corbeille.purgerExpires(NOW.plusDays(2)).effaces() == 1, "La date forcée n'a pas purgé la fiche de 29 jours.");
        exiger(corbeille.lister(null, "").isEmpty(), "La corbeille n'est pas vide après la purge.");
        reussi("purge_automatique");
    }

    private void isolationDesBoutiques() throws Exception {
        Env a = environnement("Isolation A");
        var c = client("Client de A");
        clients.supprimerClient(c.getId());
        Env b = environnement("Isolation B");
        exiger(corbeille.lister(null, "").isEmpty(), "La corbeille d'une autre boutique est visible.");
        exiger(refuse(() -> corbeille.restaurer(TypeCorbeille.CLIENT, c.getId())), "Une fiche d'une autre boutique a été restaurée.");
        exiger(refuse(() -> corbeille.supprimerDefinitivement(TypeCorbeille.CLIENT, c.getId())), "Une fiche d'une autre boutique a été effacée.");
        exiger(corbeille.vider().effaces() == 0, "Le vidage a touché une autre boutique.");
        exiger(compte("select count(*) from client where id = " + c.getId() + " and supprime_le is not null") == 1, "La fiche de A a changé.");
        connecter(a.gerant);
        exiger(corbeille.lister(null, "").size() == 1, "La fiche de A a disparu de sa corbeille.");
        exiger(b.boutique != null, "Boutique B absente.");
        reussi("isolation_boutiques");
    }

    // --- Données

    private record Env(Boutique boutique, Utilisateur gerant) {}

    private Env environnement(String nom) {
        var boutique = new Boutique(); boutique.setNom("Boutique " + nom); boutique = boutiques.saveAndFlush(boutique);
        var gerant = new Utilisateur(); gerant.setBoutique(boutique); gerant.setNom("Gérant " + nom.replace("Isolation ", "")); gerant.setRole(Role.ADMIN);
        gerant.setEmail(UUID.randomUUID() + "@corbeille-mariadb.test"); gerant = utilisateurs.saveAndFlush(gerant);
        connecter(gerant);
        return new Env(boutique, gerant);
    }

    private Client client(String nom) { var c = new Client(); c.setNom(nom); return clients.enregistrerClient(c); }

    private Fournisseur fournisseur(String nom) { var f = new Fournisseur(); f.setNom(nom); return fournisseurs.enregistrer(f); }

    private Categorie categorie(String nom) {
        var c = new Categorie(); c.setNom(nom); categories.ajouterCategorie(c);
        return categories.listerToutes().stream().filter(x -> x.getNom().equals(nom)).findFirst().orElseThrow();
    }

    private Produit produit(String nom, Categorie categorie) {
        var p = new Produit(); p.setNom(nom); p.setReference("REF-" + UUID.randomUUID()); p.setCategorie(categorie); return produits.ajouterProduit(p);
    }

    // --- Aides

    private long compte(String requete) throws Exception {
        try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery(requete)) { r.next(); return r.getLong(1); }
    }

    private void sql(String requete) throws Exception {
        try (var c = source.getConnection(); var s = c.createStatement()) { s.executeUpdate(requete); }
    }

    private static boolean refuse(Runnable action) {
        try { action.run(); return false; } catch (IllegalArgumentException | ResponseStatusException refus) { return true; }
    }

    private static void connecter(Utilisateur compte) {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(compte.getEmail(), "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(contexte);
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
            if (instruction.isEmpty() || instruction.startsWith("CREATE DATABASE IF NOT EXISTS stock_pro") || instruction.equals("USE stock_pro")) continue;
            exiger(instruction.startsWith("CREATE TABLE IF NOT EXISTS "), "La fixture de schéma doit uniquement créer des tables.");
            try (var s = c.createStatement()) { s.execute(instruction); }
        }
    }

    private static void verifierBase(String base) {
        exiger(base != null && base.matches("stock_corbeille_service_test_[a-z0-9]+") && base.length() <= 64, "Nom temporaire inattendu ; suppression refusée.");
    }

    private void reussi(String nom) { scenarios++; System.out.println("OK corbeille MariaDB : " + nom); }
    private static void exiger(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    @Configuration(proxyBeanMethods = false)
    @Profile("corbeille-mariadb-service-test")
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = BoutiqueRepository.class)
    @Import({VerrouExercice.class, org.example.stock.pays.FormatMontants.class, org.example.stock.pays.PaysCourant.class, CorbeilleService.class, ClientService.class, FournisseurService.class, CategorieService.class, ProduitService.class,
            BoutiqueService.class, EssaiBoutiqueService.class, StockLockRepository.class, EcheanceService.class, SoldeDepartService.class,
            SoldePartenaireService.class, SoldePartenaireRepository.class, CaisseService.class, InitialisationBoutiqueService.class,
            ComptesTresorerieService.class, NumerotationFactureService.class, DevisService.class})
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
        @Bean
        CorbeilleProperties corbeilleProperties() { return new CorbeilleProperties(); }
        @Bean("recoveryClock")
        Clock clock() { return Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC); }
    }
}
