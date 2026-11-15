package org.example.stock.migration;

import jakarta.persistence.EntityManager;
import org.example.stock.StockApplication;
import org.example.stock.compta.ClotureService;
import org.example.stock.compta.EtatsFinanciersService;
import org.example.stock.compta.ExerciceClotureException;
import org.example.stock.enums.Role;
import org.example.stock.form.DepenseTresorerieForm;
import org.example.stock.form.OuvertureCaisseForm;
import org.example.stock.model.Boutique;
import org.example.stock.model.MouvementCaisse;
import org.example.stock.model.Utilisateur;
import org.example.stock.service.CaisseService;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Clôture d'exercice (lot 5c) avec les vrais verrous InnoDB, exclusivement dans une base temporaire. Le gérant clôture l'exercice 2024 pendant que d'autres
 * écritures tentent d'annuler des mouvements de 2024 : la clôture prend la boutique, puis l'exercice, en écriture ; aucune écriture ne se glisse pendant
 * qu'elle travaille. Chaque tentative réussit AVANT la fin de la clôture, ou est refusée par le verrou (jamais d'interblocage ni d'autre erreur), et dès
 * que la clôture est validée toutes les tentatives suivantes sont refusées. Après chaque tour, les états recalculés en direct égalent ceux de l'instantané.
 */
public class ClotureMariaDbTest {
    private static final int TOURS = 6, ECRIVAINS = 8;
    private static final String PREFIXE_BASE = "stock_cloture_test_";
    private static final LocalDate DEBUT = LocalDate.of(2024, 1, 1), FIN = LocalDate.of(2024, 12, 31);
    private static final String EMAIL = "cloture-concurrence@example.test";

    private final ConfigurableApplicationContext contexte;
    private final CaisseService caisse;
    private final ClotureService cloture;
    private final EtatsFinanciersService financiers;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private EntityManager em;
    private Long boutiqueId;

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
                    "--spring.jpa.hibernate.ddl-auto=create-only", "--spring.jpa.show-sql=false", "--spring.jpa.open-in-view=false",
                    "--logging.level.root=WARN", "--logging.level.org.hibernate=WARN", "--logging.level.org.springframework=WARN", "--debug=false",
                    "--spring.devtools.restart.enabled=false", "--spring.main.banner-mode=off")) {
                new ClotureMariaDbTest(contexte).verifier(contexte.getBean(EntityManager.class));
            } finally {
                SecurityContextHolder.clearContext();
                if (!database.matches(PREFIXE_BASE + "[a-z0-9]+")) throw new IllegalStateException("Base de test requise");
                AccountEmailMigration.executer(c, "DROP DATABASE " + database);
            }
        }
        System.out.println("Scénarios de clôture d'exercice MariaDB réussis ; base temporaire supprimée.");
    }

    private ClotureMariaDbTest(ConfigurableApplicationContext contexte) {
        this.contexte = contexte;
        caisse = contexte.getBean(CaisseService.class);
        cloture = contexte.getBean(ClotureService.class);
        financiers = contexte.getBean(EtatsFinanciersService.class);
        jdbc = contexte.getBean(JdbcTemplate.class);
        transaction = new TransactionTemplate(contexte.getBean(PlatformTransactionManager.class));
        transaction.setTimeout(60);
    }

    private static void connecter() {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(EMAIL, "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(contexte);
    }

    private void verifier(EntityManager em) throws Exception {
        this.em = em;
        transaction.executeWithoutResult(s -> {
            Boutique b = new Boutique(); b.setNom("Boutique clôture concurrente"); em.persist(b);
            Utilisateur u = new Utilisateur(); u.setBoutique(b); u.setNom("Gérant concurrent"); u.setEmail(EMAIL); u.setRole(Role.ADMIN); u.setMotDePasse("empreinte-fictive"); em.persist(u);
            boutiqueId = b.getId();
        });
        connecter();
        OuvertureCaisseForm ouverture = new OuvertureCaisseForm(); ouverture.setComptePaiement("ESPECES"); ouverture.setMontant(new BigDecimal("5000000")); caisse.initialiser(ouverture);
        int depenses = TOURS * ECRIVAINS + 10;
        for (int i = 0; i < depenses; i++) {
            DepenseTresorerieForm f = new DepenseTresorerieForm(); f.setCompteSource("ESPECES"); f.setMontant(new BigDecimal("1000")); f.setMotif("Dépense " + i); f.setJetonOperation(UUID.randomUUID().toString());
            caisse.depenser(f);
        }
        SecurityContextHolder.clearContext();
        // Toute l'activité de la boutique est datée de 2024 : l'exercice 2024 est terminé et il est le premier.
        jdbc.update("UPDATE mouvement_caisse SET date_mouvement = TIMESTAMPADD(MINUTE, id, '2024-03-01 09:00:00') WHERE boutique_id = ?", boutiqueId);
        jdbc.update("UPDATE operation_tresorerie SET date_operation = TIMESTAMPADD(MINUTE, id, '2024-03-01 09:00:00') WHERE boutique_id = ?", boutiqueId);
        List<Long> origines = jdbc.queryForList("SELECT id FROM mouvement_caisse WHERE boutique_id = ? AND montant < 0 AND annulation_de_id IS NULL ORDER BY id", Long.class, boutiqueId);
        if (origines.size() < TOURS * ECRIVAINS + 2) throw new IllegalStateException("Mouvements de 2024 manquants : " + origines.size());

        int reussies = 0, refusees = 0;
        for (int tour = 0; tour < TOURS; tour++) {
            List<Long> ceTour = origines.subList(tour * ECRIVAINS, (tour + 1) * ECRIVAINS);
            int[] resultat = unTour(tour, ceTour);
            reussies += resultat[0]; refusees += resultat[1];
            // Réouverture pour le tour suivant : l'annulation de la clôture supprime le verrou et conserve l'instantané.
            connecter();
            try { cloture.annulerCloture(DEBUT, "Fin du tour " + tour); } finally { SecurityContextHolder.clearContext(); }
        }
        System.out.println("Clôture et écritures simultanées : " + reussies + " écritures réussies avant la clôture, " + refusees + " refusées par le verrou, sur " + TOURS + " tours ; aucun interblocage.");
        deuxClotures();
    }

    /** Un tour : la clôture et {@code ECRIVAINS} annulations partent ensemble ; retourne {réussies, refusées}. */
    private int[] unTour(int tour, List<Long> origines) throws Exception {
        var pool = Executors.newFixedThreadPool(ECRIVAINS + 1);
        var barriere = new CyclicBarrier(ECRIVAINS + 1);
        AtomicLong debutCloture = new AtomicLong(), finCloture = new AtomicLong(), derniereReussie = new AtomicLong();
        AtomicInteger reussies = new AtomicInteger(), refusees = new AtomicInteger();
        List<Future<?>> taches = new ArrayList<>();
        taches.add(pool.submit(() -> {
            connecter();
            try {
                barriere.await();
                if (tour % 2 == 1) Thread.sleep(40);   // un tour sur deux, les écritures partent un peu avant la clôture
                debutCloture.set(System.nanoTime());
                cloture.cloturer(DEBUT, "Tour " + tour);
                finCloture.set(System.nanoTime());
            } finally { SecurityContextHolder.clearContext(); }
            return null;
        }));
        for (Long id : origines) taches.add(pool.submit(() -> {
            connecter();
            try {
                barriere.await();
                if (tour % 2 == 0) Thread.sleep(ThreadLocalRandom.current().nextInt(0, 60));
                MouvementCaisse origine = transaction.execute(s -> em.find(MouvementCaisse.class, id));
                try {
                    caisse.enregistrerAnnulation(origine, "Annulation concurrente", utilisateur());
                    derniereReussie.accumulateAndGet(System.nanoTime(), Math::max);
                    reussies.incrementAndGet();
                } catch (ExerciceClotureException e) {
                    if (!e.getMessage().equals("L'exercice 2024 est clôturé : cette opération ne peut plus être enregistrée à cette date.")) throw e;
                    refusees.incrementAndGet();
                }
            } finally { SecurityContextHolder.clearContext(); }
            return null;
        }));
        try { for (Future<?> t : taches) t.get(120, TimeUnit.SECONDS); }
        catch (ExecutionException e) { throw new IllegalStateException("Tour " + tour + " : erreur inattendue " + e.getCause(), e.getCause()); }
        finally { pool.shutdownNow(); }
        exiger(finCloture.get() > 0, "La clôture n'a pas abouti au tour " + tour);
        exiger(reussies.get() + refusees.get() == ECRIVAINS, "Une écriture a disparu au tour " + tour);
        // Une écriture réussie est validée avant la fin de la clôture : elle a pris la boutique avant elle (jamais pendant ni après).
        exiger(derniereReussie.get() <= finCloture.get(), "Une écriture s'est glissée pendant ou après la clôture au tour " + tour);
        // Une fois la clôture validée, toute écriture datée de 2024 est refusée (mouvement jamais utilisé : le dernier de la liste).
        long reste = jdbc.queryForObject("SELECT MAX(id) FROM mouvement_caisse WHERE boutique_id = ? AND montant < 0 AND annulation_de_id IS NULL", Long.class, boutiqueId);
        connecter();
        try {
            MouvementCaisse origine = transaction.execute(s -> em.find(MouvementCaisse.class, reste));
            boolean refuse = false;
            try { caisse.enregistrerAnnulation(origine, "Annulation après la clôture", utilisateur()); } catch (ExerciceClotureException e) { refuse = true; }
            exiger(refuse, "Une écriture de 2024 a été acceptée après la clôture au tour " + tour);
            // Les états recalculés en direct sont ceux de l'instantané : rien ne s'est glissé dans les montants figés.
            var fige = financiers.calculer(boutiqueId, DEBUT, FIN);
            var direct = financiers.calculerEnDirect(boutiqueId, DEBUT, FIN);
            exiger(fige.fige() != null, "Les états de 2024 ne sont pas ceux de l'instantané");
            exiger(fige.n().bilan().netActif("BZ").compareTo(direct.n().bilan().netActif("BZ")) == 0
                    && fige.n().compteResultat().montant("XI").compareTo(direct.n().compteResultat().montant("XI")) == 0, "Les états figés diffèrent des états recalculés au tour " + tour);
        } finally { SecurityContextHolder.clearContext(); }
        return new int[] {reussies.get(), refusees.get()};
    }

    private Utilisateur utilisateur() {
        return transaction.execute(s -> em.createQuery("select u from Utilisateur u where u.email = :e", Utilisateur.class).setParameter("e", EMAIL).getSingleResult());
    }

    /** Deux clôtures simultanées du même exercice : une seule aboutit, l'autre est refusée proprement (déjà clôturé), sans interblocage. */
    private void deuxClotures() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var barriere = new CyclicBarrier(2);
        List<Future<String>> taches = new ArrayList<>();
        for (int i = 0; i < 2; i++) taches.add(pool.submit(() -> {
            connecter();
            try { barriere.await(); cloture.cloturer(DEBUT, "Double clôture"); return "ok"; }
            catch (IllegalArgumentException e) { return e.getMessage(); }
            finally { SecurityContextHolder.clearContext(); }
        }));
        int ok = 0, refus = 0;
        try { for (var t : taches) { String r = t.get(120, TimeUnit.SECONDS); if (r.equals("ok")) ok++; else { refus++; exiger(r.contains("déjà clôturé"), "Refus inattendu : " + r); } } }
        finally { pool.shutdownNow(); }
        exiger(ok == 1 && refus == 1, "Deux clôtures simultanées : " + ok + " réussie(s), " + refus + " refusée(s)");
        exiger(jdbc.queryForObject("SELECT COUNT(*) FROM instantane_exercice WHERE statut = 'ACTIF'", Long.class) == 1, "Plus d'un instantané actif");
        connecter();
        try { cloture.annulerCloture(DEBUT, "Fin de l'essai"); } finally { SecurityContextHolder.clearContext(); }
        System.out.println("Deux clôtures simultanées : une réussie, une refusée, un seul instantané actif.");
    }

    private static void exiger(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
