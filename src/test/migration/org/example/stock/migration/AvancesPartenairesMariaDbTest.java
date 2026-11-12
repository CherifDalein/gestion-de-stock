package org.example.stock.migration;

import jakarta.persistence.EntityManager;
import org.example.stock.StockApplication;
import org.example.stock.enums.Role;
import org.example.stock.enums.TypeOperationCreation;
import org.example.stock.model.*;
import org.example.stock.service.OperationCreationService;
import org.example.stock.service.SoldeDepartService;
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

/** Avances des partenaires avec les vrais verrous InnoDB (REPEATABLE READ), exclusivement dans une base temporaire. */
public class AvancesPartenairesMariaDbTest {
    private final OperationCreationService creations;
    private final SoldeDepartService soldeDepart;
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
        String database = "stock_avances_test_" + Long.toUnsignedString(System.nanoTime(), 36);
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
                new AvancesPartenairesMariaDbTest(contexte).verifier(contexte.getBean(EntityManager.class));
            } finally {
                SecurityContextHolder.clearContext();
                if (!database.matches("stock_avances_test_[a-z0-9]+")) throw new IllegalStateException("Base de test requise");
                AccountEmailMigration.executer(c, "DROP DATABASE " + database);
            }
        }
        System.out.println("Scénarios d'avances MariaDB réussis ; base temporaire supprimée.");
    }

    private AvancesPartenairesMariaDbTest(ConfigurableApplicationContext contexte) {
        creations = contexte.getBean(OperationCreationService.class);
        soldeDepart = contexte.getBean(SoldeDepartService.class);
        transaction = new TransactionTemplate(contexte.getBean(PlatformTransactionManager.class));
        transaction.setTimeout(30);
        jdbc = contexte.getBean(JdbcTemplate.class);
    }

    private void verifier(EntityManager em) throws Exception {
        transaction.executeWithoutResult(status -> {
            Boutique boutique = new Boutique(); boutique.setNom("Boutique avances MariaDB"); em.persist(boutique);
            Utilisateur u = new Utilisateur(); u.setBoutique(boutique); u.setNom("Test"); u.setEmail("avances-admin@example.test");
            u.setRole(Role.ADMIN); u.setMotDePasse("empreinte-fictive"); em.persist(u);
            Categorie categorie = new Categorie(); categorie.setBoutique(boutique); categorie.setNom("Tests"); em.persist(categorie);
            Fournisseur fournisseur = new Fournisseur(); fournisseur.setBoutique(boutique); fournisseur.setNom("Fournisseur"); em.persist(fournisseur);
            fournisseurId = fournisseur.getId();
            Client client = new Client(); client.setBoutique(boutique); client.setNom("Client"); em.persist(client);
            clientId = client.getId();
            Produit produit = new Produit(); produit.setBoutique(boutique); produit.setNom("Test"); produit.setReference("TEST-AVANCE");
            produit.setCategorie(categorie); produit.setQuantite(1000L);
            produit.setPrixAchat(new BigDecimal("5.00")); produit.setPrixVente(new BigDecimal("10000.00"));
            em.persist(produit); produitId = produit.getId();
        });
        connecter();
        // 1. Trop-payé : 120 000 pour 100 000 => 20 000 d'avance, 120 000 en caisse.
        vente(10, "120000");
        exiger(decimal("SELECT COALESCE(SUM(montant),0) FROM mouvement_caisse").compareTo(new BigDecimal("120000")) == 0, "Caisse du trop-payé");
        exiger(avanceClient().compareTo(new BigDecimal("20000")) == 0, "Avance du client " + avanceClient());
        // 2. Deux ventes simultanées sur 20 000 d'avance : exactement 20 000 consommés, sans mouvement de caisse.
        long mouvements = nombre("mouvement_caisse");
        simultanes(() -> vente(2, "0"), () -> vente(2, "0"));
        BigDecimal utilisee = decimal("SELECT COALESCE(SUM(avance_utilisee),0) FROM vente");
        exiger(utilisee.compareTo(new BigDecimal("20000")) == 0, "Avance consommée par les ventes simultanées : " + utilisee);
        exiger(avanceClient().signum() == 0, "Avance restante");
        exiger(nombre("mouvement_caisse") == mouvements, "L'avance ne crée aucun mouvement de caisse");
        // 3. Même chose chez le fournisseur.
        achat(10, "120000");
        simultanes(() -> achat(2, "0"), () -> achat(2, "0"));
        BigDecimal utiliseeAchats = decimal("SELECT COALESCE(SUM(avance_utilisee),0) FROM achat");
        exiger(utiliseeAchats.compareTo(new BigDecimal("20000")) == 0, "Avance consommée par les achats simultanés : " + utiliseeAchats);
        // 4. Vente et règlement de solde de départ simultanés : le reste n'est jamais dépassé.
        jdbc.update("UPDATE client SET solde_initial = 50000 WHERE id = ?", clientId);
        long avant = nombre("mouvement_caisse");
        List<Throwable> erreurs = simultanes(() -> reglerSolde("30000"), () -> reglerSolde("30000"));
        exiger(erreurs.size() == 1, "Un seul des deux règlements identiques doit réussir : " + erreurs);
        exiger(decimal("SELECT COALESCE(SUM(montant),0) FROM mouvement_caisse WHERE client_id IS NOT NULL").compareTo(new BigDecimal("30000")) == 0, "Règlements du solde");
        exiger(nombre("mouvement_caisse") == avant + 1, "Un seul mouvement de règlement");
        // 5. Un double envoi du même jeton ne consomme pas l'avance deux fois.
        vente(10, "120000");   // 20 000 d'avance de plus
        String jeton = creations.ouvrir(TypeOperationCreation.VENTE);
        var premiere = creations.creerVente(jeton, venteForm(5, "0"));
        BigDecimal apresPremiere = decimal("SELECT COALESCE(SUM(avance_utilisee),0) FROM vente");
        var copie = creations.creerVente(jeton, venteForm(5, "0"));
        exiger(!premiere.dejaEnregistre() && copie.dejaEnregistre(), "Rejeu du jeton");
        exiger(apresPremiere.compareTo(decimal("SELECT COALESCE(SUM(avance_utilisee),0) FROM vente")) == 0, "Rejeu : avance consommée deux fois");
    }

    private void reglerSolde(String montant) {
        connecter();
        try { soldeDepart.reglerClient(clientId, new BigDecimal(montant), BigDecimal.ZERO, "ESPECES", null); }
        finally { SecurityContextHolder.clearContext(); }
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

    private void achat(int quantite, String verse) {
        Achat a = new Achat(); Fournisseur f = new Fournisseur(); f.setId(fournisseurId); a.setFournisseur(f); a.setMontantVerse(new BigDecimal(verse));
        DetailAchat ligne = new DetailAchat(); ligne.setProduit(produit()); ligne.setQuantite(quantite);
        ligne.setPrixAchatUnitaire(new BigDecimal("10000.00")); ligne.setPrixVenteUnitaire(new BigDecimal("12000.00")); a.getLignes().add(ligne);
        creations.creerAchat(creations.ouvrir(TypeOperationCreation.ACHAT), a);
    }

    private BigDecimal avanceClient() {
        return decimal("SELECT GREATEST(0, -c.solde_initial) + COALESCE((SELECT SUM(GREATEST(0, montant_verse - montant_total)) FROM vente WHERE client_id = c.id), 0)"
                + " - COALESCE((SELECT SUM(avance_utilisee) FROM vente WHERE client_id = c.id), 0) FROM client c WHERE c.id = " + clientId);
    }

    private BigDecimal decimal(String sql) { return jdbc.queryForObject(sql, BigDecimal.class); }
    private long nombre(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private Produit produit() { Produit p = new Produit(); p.setId(produitId); return p; }
    private static void connecter() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("avances-admin@example.test", null, List.of()));
    }
    private static void exiger(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
