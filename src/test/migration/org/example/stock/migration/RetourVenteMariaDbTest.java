package org.example.stock.migration;

import jakarta.persistence.EntityManager;
import org.example.stock.StockApplication;
import org.example.stock.enums.CompteTresorerie;
import org.example.stock.enums.Role;
import org.example.stock.enums.TypeOperationCreation;
import org.example.stock.model.*;
import org.example.stock.service.OperationCreationService;
import org.example.stock.enums.ModeRemboursement;
import org.example.stock.form.RetourVenteForm;
import org.example.stock.service.RetourVenteService;
import org.example.stock.service.VenteService;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
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

/** Retours de vente avec les vrais verrous InnoDB (REPEATABLE READ), exclusivement dans une base temporaire. */
public class RetourVenteMariaDbTest {
    private final OperationCreationService creations;
    private final RetourVenteService retourService;
    private final VenteService venteService;
    private final TransactionTemplate transaction;
    private final JdbcTemplate jdbc;
    private Long produitId, fournisseurId, clientId;

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
        String database = "stock_retours_test_" + Long.toUnsignedString(System.nanoTime(), 36);
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
                new RetourVenteMariaDbTest(contexte).verifier(contexte.getBean(EntityManager.class));
            } finally {
                SecurityContextHolder.clearContext();
                if (!database.matches("stock_retours_test_[a-z0-9]+")) throw new IllegalStateException("Base de test requise");
                AccountEmailMigration.executer(c, "DROP DATABASE " + database);
            }
        }
        System.out.println("Scénarios de retours de vente MariaDB réussis ; base temporaire supprimée.");
    }

    private RetourVenteMariaDbTest(ConfigurableApplicationContext contexte) {
        creations = contexte.getBean(OperationCreationService.class);
        retourService = contexte.getBean(RetourVenteService.class);
        venteService = contexte.getBean(VenteService.class);
        transaction = new TransactionTemplate(contexte.getBean(PlatformTransactionManager.class));
        transaction.setTimeout(30);
        jdbc = contexte.getBean(JdbcTemplate.class);
    }

    private void verifier(EntityManager em) throws Exception {
        transaction.executeWithoutResult(status -> {
            Boutique boutique = new Boutique(); boutique.setNom("Boutique retours MariaDB"); em.persist(boutique);
            Utilisateur u = new Utilisateur(); u.setBoutique(boutique); u.setNom("Test"); u.setEmail("retours-admin@example.test");
            u.setRole(Role.ADMIN); u.setMotDePasse("empreinte-fictive"); em.persist(u);
            Categorie categorie = new Categorie(); categorie.setBoutique(boutique); categorie.setNom("Tests"); em.persist(categorie);
            Fournisseur fournisseur = new Fournisseur(); fournisseur.setBoutique(boutique); fournisseur.setNom("Fournisseur"); em.persist(fournisseur);
            Client client = new Client(); client.setBoutique(boutique); client.setNom("Client"); em.persist(client);
            clientId = client.getId();
            Produit produit = new Produit(); produit.setBoutique(boutique); produit.setNom("Test"); produit.setReference("TEST-RETOUR");
            produit.setCategorie(categorie); produit.setQuantite(1000L);
            produit.setPrixAchat(new BigDecimal("5.00")); produit.setPrixVente(new BigDecimal("10000.00"));
            em.persist(produit); produitId = produit.getId();
        });
        connecter();
        // 1. Vente payée 100 000 ; deux retours simultanés de 7 unités sur 10 : un seul passe, une seule sortie de caisse.
        vente(10, "100000");
        long vente1 = decimal("SELECT MAX(id) FROM vente").longValue();
        List<Throwable> erreurs = simultanes(() -> retourner(vente1, 7, ModeRemboursement.ARGENT), () -> retourner(vente1, 7, ModeRemboursement.ARGENT));
        exiger(erreurs.size() == 1, "Un seul des deux retours simultanés doit passer : " + erreurs);
        exiger(nombre("retour") == 1, "Un seul retour enregistré");
        exiger(decimal("SELECT montant_retours FROM vente WHERE id=" + vente1).compareTo(new BigDecimal("70000")) == 0, "Total des retours de la vente");
        exiger(decimal("SELECT COALESCE(SUM(montant),0) FROM mouvement_caisse WHERE source='RETOUR_VENTE'").compareTo(new BigDecimal("-70000")) == 0, "Une seule sortie de caisse");
        exiger(decimal("SELECT quantite FROM produit WHERE id=" + produitId).compareTo(new BigDecimal("997")) == 0, "Stock après un seul retour");
        // 2. Vente à crédit ; un retour imputé et un règlement simultanés ne se bloquent pas et se cumulent.
        vente(10, "0");
        long vente2 = decimal("SELECT MAX(id) FROM vente").longValue();
        simultanesSansErreur(() -> retourner(vente2, 3, null), () -> regler(vente2, "50000"));
        exiger(decimal("SELECT montant_retours FROM vente WHERE id=" + vente2).compareTo(new BigDecimal("30000")) == 0, "Retour imputé");
        exiger(decimal("SELECT montant_verse FROM vente WHERE id=" + vente2).compareTo(new BigDecimal("50000")) == 0, "Règlement simultané");
        exiger(decimal("SELECT COALESCE(SUM(montant),0) FROM mouvement_caisse WHERE vente_id=" + vente2 + " AND source='RETOUR_VENTE'").signum() == 0,
                "Un retour imputé sur la dette ne crée aucun mouvement");
        // 3. Deux retours simultanés sur deux ventes différentes du même produit : tous deux passent, le stock est exact.
        vente(5, "50000"); long vente3 = decimal("SELECT MAX(id) FROM vente").longValue();
        vente(5, "50000"); long vente4 = decimal("SELECT MAX(id) FROM vente").longValue();
        BigDecimal stockAvant = decimal("SELECT quantite FROM produit WHERE id=" + produitId);
        simultanesSansErreur(() -> retourner(vente3, 2, ModeRemboursement.AVOIR), () -> retourner(vente4, 3, ModeRemboursement.ARGENT));
        exiger(decimal("SELECT quantite FROM produit WHERE id=" + produitId).compareTo(stockAvant.add(new BigDecimal("5"))) == 0, "Stock après deux retours simultanés");
        exiger(nombre("retour") == 4, "Retours enregistrés");
        exiger(decimal("SELECT COUNT(DISTINCT numero) FROM retour").intValue() == 4, "Numéros RV distincts");
        exiger(decimal("SELECT COALESCE(SUM(montant_avoir),0) FROM retour").compareTo(new BigDecimal("20000")) == 0, "Avoir enregistré");
        // 4. Un retour en avoir et une vente à crédit du même client simultanés : pas de deadlock, l'avance n'est jamais consommée deux fois.
        vente(4, "40000"); long vente5 = decimal("SELECT MAX(id) FROM vente").longValue();
        simultanesSansErreur(() -> retourner(vente5, 1, ModeRemboursement.AVOIR), () -> vente(1, "0"));
        BigDecimal avoirs = decimal("SELECT COALESCE(SUM(montant_avoir),0) FROM retour");
        BigDecimal utilisee = decimal("SELECT COALESCE(SUM(avance_utilisee),0) FROM vente");
        exiger(utilisee.compareTo(avoirs) <= 0, "Avance consommée " + utilisee + " supérieure aux avoirs " + avoirs);
    }

    private void retourner(long venteId, int quantite, ModeRemboursement mode) {
        connecter();
        try {
            long ligne = decimal("SELECT MIN(id) FROM detail_vente WHERE vente_id=" + venteId).longValue();
            RetourVenteForm form = new RetourVenteForm();
            RetourVenteForm.Ligne l = new RetourVenteForm.Ligne(); l.setDetailVenteId(ligne); l.setQuantite(quantite);
            form.setLignes(new ArrayList<>(List.of(l))); form.setModeRemboursement(mode);
            if (mode == ModeRemboursement.ARGENT) form.setComptePaiement(CompteTresorerie.CAISSE);
            form.setRetoursConnus(0L);
            retourService.enregistrer(venteId, form);
        } finally { SecurityContextHolder.clearContext(); }
    }

    private void regler(long venteId, String montant) {
        connecter();
        try { venteService.reglerVente(venteId, new BigDecimal(montant), BigDecimal.ZERO, CompteTresorerie.CAISSE); }
        finally { SecurityContextHolder.clearContext(); }
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

    private void vente(int quantite, String verse) { creations.creerVente(creations.ouvrir(TypeOperationCreation.VENTE), venteForm(quantite, verse)); }

    private Vente venteForm(int quantite, String verse) {
        Vente v = new Vente(); Client c = new Client(); c.setId(clientId); v.setClient(c); v.setMontantVerse(new BigDecimal(verse));
        DetailVente ligne = new DetailVente(); ligne.setProduit(produit()); ligne.setQuantite(quantite); v.getLignes().add(ligne); return v;
    }

    private BigDecimal decimal(String sql) { return jdbc.queryForObject(sql, BigDecimal.class); }
    private long nombre(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private Produit produit() { Produit p = new Produit(); p.setId(produitId); return p; }
    private static void connecter() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("retours-admin@example.test", null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
    private static void exiger(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
