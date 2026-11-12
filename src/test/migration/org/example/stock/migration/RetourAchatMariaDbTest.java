package org.example.stock.migration;

import jakarta.persistence.EntityManager;
import org.example.stock.StockApplication;
import org.example.stock.enums.ModeRemboursement;
import org.example.stock.enums.Role;
import org.example.stock.enums.TypeOperationCreation;
import org.example.stock.form.RetourAchatForm;
import org.example.stock.model.*;
import org.example.stock.service.AchatService;
import org.example.stock.service.OperationCreationService;
import org.example.stock.service.RetourAchatService;
import org.example.stock.service.VenteService;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
 * Retours d'achat avec les vrais verrous InnoDB (REPEATABLE READ par défaut, READ COMMITTED pour le retour et la
 * modification d'achat), exclusivement dans une base temporaire.
 */
public class RetourAchatMariaDbTest {
    private final OperationCreationService creations;
    private final RetourAchatService retourService;
    private final AchatService achatService;
    private final VenteService venteService;
    private final TransactionTemplate transaction;
    private final JdbcTemplate jdbc;
    private Long produitId, fournisseurId;

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
        String database = "stock_retours_achat_test_" + Long.toUnsignedString(System.nanoTime(), 36);
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
                new RetourAchatMariaDbTest(contexte).verifier(contexte.getBean(EntityManager.class));
            } finally {
                SecurityContextHolder.clearContext();
                if (!database.matches("stock_retours_achat_test_[a-z0-9]+")) throw new IllegalStateException("Base de test requise");
                AccountEmailMigration.executer(c, "DROP DATABASE " + database);
            }
        }
        System.out.println("Scénarios de retours d'achat MariaDB réussis ; base temporaire supprimée.");
    }

    private RetourAchatMariaDbTest(ConfigurableApplicationContext contexte) {
        creations = contexte.getBean(OperationCreationService.class);
        retourService = contexte.getBean(RetourAchatService.class);
        achatService = contexte.getBean(AchatService.class);
        venteService = contexte.getBean(VenteService.class);
        transaction = new TransactionTemplate(contexte.getBean(PlatformTransactionManager.class));
        transaction.setTimeout(30);
        jdbc = contexte.getBean(JdbcTemplate.class);
    }

    private void verifier(EntityManager em) throws Exception {
        transaction.executeWithoutResult(status -> {
            Boutique boutique = new Boutique(); boutique.setNom("Boutique retours d'achat MariaDB"); em.persist(boutique);
            Utilisateur u = new Utilisateur(); u.setBoutique(boutique); u.setNom("Test"); u.setEmail("retours-achat-admin@example.test");
            u.setRole(Role.ADMIN); u.setMotDePasse("empreinte-fictive"); em.persist(u);
            Categorie categorie = new Categorie(); categorie.setBoutique(boutique); categorie.setNom("Tests"); em.persist(categorie);
            Fournisseur fournisseur = new Fournisseur(); fournisseur.setBoutique(boutique); fournisseur.setNom("Fournisseur"); em.persist(fournisseur);
            fournisseurId = fournisseur.getId();
            Produit produit = new Produit(); produit.setBoutique(boutique); produit.setNom("Test"); produit.setReference("TEST-RETOUR-ACHAT");
            produit.setCategorie(categorie); produit.setQuantite(1000L);
            produit.setPrixAchat(new BigDecimal("5.00")); produit.setPrixVente(new BigDecimal("10000.00"));
            em.persist(produit); produitId = produit.getId();
        });
        connecter();
        // 1. Achat payé 100 000 ; deux retours simultanés de 7 unités sur 10 : un seul passe, une seule entrée de caisse.
        achat(10, "100000");
        long achat1 = dernier("achat");
        List<Throwable> erreurs = simultanes(() -> retourner(achat1, 7, ModeRemboursement.ARGENT), () -> retourner(achat1, 7, ModeRemboursement.ARGENT));
        exiger(erreurs.size() == 1, "Un seul des deux retours simultanés doit passer : " + erreurs);
        exiger(nombre("retour") == 1, "Un seul retour enregistré");
        exiger(decimal("SELECT montant_retours FROM achat WHERE id=" + achat1).compareTo(new BigDecimal("70000")) == 0, "Total des retours de l'achat");
        exiger(decimal("SELECT COALESCE(SUM(montant),0) FROM mouvement_caisse WHERE source='RETOUR_ACHAT'").compareTo(new BigDecimal("70000")) == 0, "Une seule entrée de caisse");
        exiger(decimal("SELECT quantite FROM produit WHERE id=" + produitId).compareTo(new BigDecimal("1003")) == 0, "Stock après un seul retour (1000 + 10 − 7)");
        // 2. Achat à crédit ; un retour imputé et un règlement simultanés ne se bloquent pas et se cumulent.
        achat(10, "0");
        long achat2 = dernier("achat");
        simultanesSansErreur(() -> retourner(achat2, 3, null), () -> regler(achat2, "50000"));
        exiger(decimal("SELECT montant_retours FROM achat WHERE id=" + achat2).compareTo(new BigDecimal("30000")) == 0, "Retour imputé");
        exiger(decimal("SELECT montant_verse FROM achat WHERE id=" + achat2).compareTo(new BigDecimal("50000")) == 0, "Règlement simultané");
        exiger(decimal("SELECT COUNT(*) FROM mouvement_caisse m JOIN retour r ON r.id = m.retour_id WHERE r.achat_id=" + achat2).signum() == 0,
                "Un retour imputé sur la dette ne crée aucun mouvement");
        // 3. Deux retours simultanés sur deux achats différents du même fournisseur et du même produit : tous deux passent.
        achat(5, "50000"); long achat3 = dernier("achat");
        achat(5, "50000"); long achat4 = dernier("achat");
        BigDecimal stockAvant = decimal("SELECT quantite FROM produit WHERE id=" + produitId);
        simultanesSansErreur(() -> retourner(achat3, 2, ModeRemboursement.AVOIR), () -> retourner(achat4, 3, ModeRemboursement.ARGENT));
        exiger(decimal("SELECT quantite FROM produit WHERE id=" + produitId).compareTo(stockAvant.subtract(new BigDecimal("5"))) == 0, "Stock après deux retours simultanés");
        exiger(nombre("retour") == 4, "Retours enregistrés");
        exiger(decimal("SELECT COUNT(DISTINCT numero) FROM retour").intValue() == 4, "Numéros RA distincts");
        exiger(decimal("SELECT COALESCE(SUM(montant_avoir),0) FROM retour").compareTo(new BigDecimal("20000")) == 0, "Avoir enregistré");
        // 4. Un retour en avoir et un achat à crédit du même fournisseur simultanés : pas de deadlock, l'avance n'est jamais consommée deux fois.
        achat(4, "40000"); long achat5 = dernier("achat");
        simultanesSansErreur(() -> retourner(achat5, 1, ModeRemboursement.AVOIR), () -> achat(1, "0"));
        BigDecimal avoirs = decimal("SELECT COALESCE(SUM(montant_avoir),0) FROM retour");
        BigDecimal utilisee = decimal("SELECT COALESCE(SUM(avance_utilisee),0) FROM achat");
        exiger(utilisee.compareTo(avoirs) <= 0, "Avance consommée " + utilisee + " supérieure aux avoirs " + avoirs);
        // 5. Un retour et une vente du même produit simultanés, pour tout ce qui reste en stock : un seul des deux passe,
        //    le stock ne devient jamais négatif (plafond du stock actuel relu sous le verrou du produit).
        achat(5, "50000"); long achat6 = dernier("achat");
        jdbc.update("UPDATE produit SET quantite = 5 WHERE id = " + produitId);
        List<Throwable> concurrents = simultanes(() -> retourner(achat6, 5, ModeRemboursement.ARGENT), () -> vente(5));
        exiger(concurrents.size() == 1, "Le retour et la vente de tout le stock ne passent pas tous les deux : " + concurrents);
        exiger(decimal("SELECT quantite FROM produit WHERE id=" + produitId).signum() == 0, "Stock à zéro, jamais négatif");
        // 6. Un retour et une modification du même achat simultanés, plusieurs fois : pas de deadlock, et un achat avec retour
        //    reste figé (l'une des deux opérations aboutit, jamais les deux).
        for (int tour = 0; tour < 5; tour++) {
            achat(5, "50000"); long achat7 = dernier("achat");
            BigDecimal totalAvant = decimal("SELECT montant_total FROM achat WHERE id=" + achat7);
            List<Throwable> course = simultanes(() -> retourner(achat7, 1, ModeRemboursement.ARGENT), () -> modifier(achat7));
            for (Throwable erreur : course) {
                exiger(!(erreur instanceof ConcurrencyFailureException), "Aucun deadlock attendu : " + erreur);
            }
            long retoursAchat7 = jdbc.queryForObject("SELECT COUNT(*) FROM retour WHERE achat_id=" + achat7, Long.class);
            boolean modifie = decimal("SELECT montant_total FROM achat WHERE id=" + achat7).compareTo(totalAvant) != 0;
            exiger(retoursAchat7 + (modifie ? 1 : 0) == 1, "Exactement une des deux opérations doit aboutir (retours " + retoursAchat7 + ", modifié " + modifie + ") : " + course);
        }
        // 7. Plusieurs paires de retours simultanés sur des achats différents du même fournisseur : jamais de deadlock.
        for (int tour = 0; tour < 5; tour++) {
            achat(3, "0"); long x = dernier("achat");
            achat(3, "30000"); long y = dernier("achat");
            simultanesSansErreur(() -> retourner(x, 1, null), () -> retourner(y, 1, ModeRemboursement.ARGENT));
        }
    }

    private void retourner(long achatId, int quantite, ModeRemboursement mode) {
        connecter();
        try {
            long ligne = decimal("SELECT MIN(id) FROM detail_achat WHERE achat_id=" + achatId).longValue();
            RetourAchatForm form = new RetourAchatForm();
            RetourAchatForm.Ligne l = new RetourAchatForm.Ligne(); l.setDetailAchatId(ligne); l.setQuantite(quantite);
            form.setLignes(new ArrayList<>(List.of(l))); form.setModeRemboursement(mode);
            if (mode == ModeRemboursement.ARGENT) form.setComptePaiement("ESPECES");
            form.setRetoursConnus(0L);
            retourService.enregistrer(achatId, form);
        } finally { SecurityContextHolder.clearContext(); }
    }

    private void regler(long achatId, String montant) {
        connecter();
        try { achatService.reglerAchat(achatId, new BigDecimal(montant), BigDecimal.ZERO, "ESPECES"); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private void modifier(long achatId) {
        connecter();
        try {
            Achat modifie = new Achat();
            Fournisseur f = new Fournisseur(); f.setId(fournisseurId); modifie.setFournisseur(f);
            DetailAchat ligne = new DetailAchat(); ligne.setProduit(produit()); ligne.setQuantite(6);
            ligne.setPrixAchatUnitaire(new BigDecimal("10000")); ligne.setPrixVenteUnitaire(new BigDecimal("12000")); modifie.getLignes().add(ligne);
            achatService.modifierAchat(achatId, modifie);
        } finally { SecurityContextHolder.clearContext(); }
    }

    private void vente(int quantite) {
        connecter();
        try {
            Vente v = new Vente(); v.setMontantVerse(new BigDecimal(quantite * 10000));
            DetailVente ligne = new DetailVente(); ligne.setProduit(produit()); ligne.setQuantite(quantite); v.getLignes().add(ligne);
            creations.creerVente(creations.ouvrir(TypeOperationCreation.VENTE), v);
        } finally { SecurityContextHolder.clearContext(); }
    }

    private void simultanesSansErreur(Runnable a, Runnable b) throws Exception {
        List<Throwable> erreurs = simultanes(a, b);
        exiger(erreurs.isEmpty(), "Aucune erreur attendue : " + erreurs);
    }

    /** Lance deux tâches au même instant ; renvoie les erreurs rencontrées. */
    private List<Throwable> simultanes(Runnable a, Runnable b) throws Exception {
        CyclicBarrier depart = new CyclicBarrier(2);
        List<Throwable> erreurs = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> taches = new ArrayList<>();
            for (Runnable tache : List.of(a, b)) taches.add(executor.submit(() -> {
                connecter();
                try { depart.await(15, TimeUnit.SECONDS); tache.run(); } catch (InterruptedException | BrokenBarrierException | TimeoutException e) { throw new IllegalStateException(e); }
                finally { SecurityContextHolder.clearContext(); }
            }));
            for (Future<?> tache : taches) {
                try { tache.get(40, TimeUnit.SECONDS); } catch (ExecutionException e) { erreurs.add(e.getCause()); }
            }
        }
        return erreurs;
    }

    private void achat(int quantite, String verse) { creations.creerAchat(creations.ouvrir(TypeOperationCreation.ACHAT), achatForm(quantite, verse)); }

    private Achat achatForm(int quantite, String verse) {
        Achat a = new Achat(); Fournisseur f = new Fournisseur(); f.setId(fournisseurId); a.setFournisseur(f); a.setMontantVerse(new BigDecimal(verse));
        DetailAchat ligne = new DetailAchat(); ligne.setProduit(produit()); ligne.setQuantite(quantite);
        ligne.setPrixAchatUnitaire(new BigDecimal("10000")); ligne.setPrixVenteUnitaire(new BigDecimal("12000")); a.getLignes().add(ligne); return a;
    }

    private long dernier(String table) { return decimal("SELECT MAX(id) FROM " + table).longValue(); }
    private BigDecimal decimal(String sql) { return jdbc.queryForObject(sql, BigDecimal.class); }
    private long nombre(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private Produit produit() { Produit p = new Produit(); p.setId(produitId); return p; }
    private static void connecter() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("retours-achat-admin@example.test", null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
    private static void exiger(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
