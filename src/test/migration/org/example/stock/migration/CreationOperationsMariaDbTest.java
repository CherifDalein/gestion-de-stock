package org.example.stock.migration;

import jakarta.persistence.EntityManager;
import org.example.stock.StockApplication;
import org.example.stock.enums.Role;
import org.example.stock.enums.TypeOperationCreation;
import org.example.stock.model.*;
import org.example.stock.repository.OperationCreationRepository;
import org.example.stock.service.CreationOperationException;
import org.example.stock.service.OperationCreationService;
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
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Vérifications avec les vrais verrous InnoDB, exclusivement dans une base temporaire. */
public class CreationOperationsMariaDbTest {
    private final OperationCreationService creations;
    private final OperationCreationRepository operations;
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
        String database = "stock_creation_test_" + Long.toUnsignedString(System.nanoTime(), 36);
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
                new CreationOperationsMariaDbTest(contexte).verifier(contexte.getBean(EntityManager.class));
            } finally {
                SecurityContextHolder.clearContext();
                if (!database.matches("stock_creation_test_[a-z0-9]+")) throw new IllegalStateException("Base de test requise");
                AccountEmailMigration.executer(c, "DROP DATABASE " + database);
            }
        }
        System.out.println("10 scénarios de création MariaDB réussis ; base temporaire supprimée.");
    }

    private CreationOperationsMariaDbTest(ConfigurableApplicationContext contexte) {
        creations = contexte.getBean(OperationCreationService.class);
        operations = contexte.getBean(OperationCreationRepository.class);
        transaction = new TransactionTemplate(contexte.getBean(PlatformTransactionManager.class));
        transaction.setTimeout(20);
        jdbc = contexte.getBean(JdbcTemplate.class);
    }

    private void verifier(EntityManager em) throws Exception {
        transaction.executeWithoutResult(status -> {
            Boutique boutique = new Boutique(); boutique.setNom("Boutique tests MariaDB"); em.persist(boutique);
            for (String email : List.of("test-admin@example.test", "autre-admin@example.test")) {
                Utilisateur u = new Utilisateur(); u.setBoutique(boutique); u.setNom("Test"); u.setEmail(email); u.setRole(Role.ADMIN);
                u.setMotDePasse("empreinte-fictive"); em.persist(u);
            }
            Categorie categorie = new Categorie(); categorie.setBoutique(boutique); categorie.setNom("Tests"); em.persist(categorie);
            Fournisseur fournisseur = new Fournisseur(); fournisseur.setBoutique(boutique); fournisseur.setNom("Tests"); em.persist(fournisseur);
            fournisseurId = fournisseur.getId();
            Produit produit = new Produit(); produit.setBoutique(boutique); produit.setNom("Test"); produit.setReference("TEST-CREATION");
            produit.setCategorie(categorie); produit.setQuantite(100L);
            produit.setPrixAchat(new BigDecimal("5.00")); produit.setPrixVente(new BigDecimal("10.00"));
            em.persist(produit); produitId = produit.getId();
        });
        connecter("test-admin@example.test");
        String achat = creations.ouvrir(TypeOperationCreation.ACHAT), vente = creations.ouvrir(TypeOperationCreation.VENTE);
        var a = creations.creerAchat(achat, achat(3)); Etat apresAchat = etat();
        var copieA = creations.creerAchat(achat, achat(3));
        exiger(!a.dejaEnregistre() && copieA.dejaEnregistre() && a.documentId().equals(copieA.documentId()), "Replay achat");
        exiger(apresAchat.equals(etat()), "Replay achat modifie le stock ou la caisse");
        verifierEtat(1, 0, 1, 103, "-15.00");
        var v = creations.creerVente(vente, vente(2)); Etat apresVente = etat();
        var copieV = creations.creerVente(vente, vente(2));
        exiger(!v.dejaEnregistre() && copieV.dejaEnregistre() && v.documentId().equals(copieV.documentId()), "Replay vente");
        exiger(apresVente.equals(etat()), "Replay vente modifie le stock ou la caisse");
        verifierEtat(1, 1, 2, 101, "5.00");
        refuser(() -> creations.creerAchat(achat, achat(4)));
        refuser(() -> creations.creerVente(vente, vente(3)));
        connecter("autre-admin@example.test"); refuser(() -> creations.creerAchat(achat, achat(3)));
        connecter("test-admin@example.test"); refuser(() -> creations.creerVente(achat, vente(2)));
        exiger(apresVente.equals(etat()), "Un refus modifie des opérations");
        String annule = creations.ouvrir(TypeOperationCreation.ACHAT);
        try {
            transaction.executeWithoutResult(status -> { creations.creerAchat(annule, achat(3)); throw new Annulation(); });
            throw new AssertionError("Transaction externe non annulée");
        } catch (Annulation attendu) { }
        exiger(apresVente.equals(etat()), "Rollback incomplet");
        exiger(!creations.creerAchat(annule, achat(3)).dejaEnregistre(), "Jeton consommé par le rollback");
        verifierEtat(2, 1, 3, 104, "-10.00");
        concurrents(TypeOperationCreation.ACHAT); verifierEtat(3, 1, 4, 107, "-25.00");
        concurrents(TypeOperationCreation.VENTE); verifierEtat(3, 2, 5, 105, "-5.00");
        // Le rollback et les renvois n'ont consommé aucun numéro : la numérotation reste continue.
        exiger(numeros("achat").equals(List.of("FA-000001", "FA-000002", "FA-000003"))
                && numeros("vente").equals(List.of("FV-000001", "FV-000002")), "Numérotation discontinue " + numeros("achat") + numeros("vente"));
        numerotationConcurrente();
    }

    /** Deux ventes réellement simultanées : la seconde attend le verrou de la boutique, puis prend le numéro suivant. */
    private void numerotationConcurrente() throws Exception {
        String premierJeton = creations.ouvrir(TypeOperationCreation.VENTE), secondJeton = creations.ouvrir(TypeOperationCreation.VENTE);
        CountDownLatch premiereCreee = new CountDownLatch(1), autoriserCommit = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Long> premiere = executor.submit(() -> {
                connecter("test-admin@example.test");
                try { return transaction.execute(status -> {
                    Long id = creations.creerVente(premierJeton, vente(1)).documentId();
                    premiereCreee.countDown(); attendre(autoriserCommit); return id;
                }); } finally { SecurityContextHolder.clearContext(); }
            });
            attendre(premiereCreee);
            Future<Long> seconde = executor.submit(() -> {
                connecter("test-admin@example.test");
                try { return creations.creerVente(secondJeton, vente(1)).documentId(); }
                finally { SecurityContextHolder.clearContext(); }
            });
            try {
                seconde.get(500, TimeUnit.MILLISECONDS);
                throw new AssertionError("La seconde vente n'a pas attendu le compteur de la boutique");
            } catch (TimeoutException attendu) { }
            autoriserCommit.countDown();
            Long a = premiere.get(25, TimeUnit.SECONDS), b = seconde.get(25, TimeUnit.SECONDS);
            exiger("FV-000003".equals(numero(a)) && "FV-000004".equals(numero(b)), "Numéros concurrents " + numero(a) + " / " + numero(b));
        } finally { autoriserCommit.countDown(); }
        exiger(jdbc.queryForObject("SELECT prochain_numero_vente FROM boutique", Long.class) == 5L, "Compteur de ventes incohérent");
        // Un numéro manuel déjà attribué automatiquement est refusé sans rien enregistrer.
        Etat avantRefus = etat();
        String jetonManuel = creations.ouvrir(TypeOperationCreation.VENTE);
        Vente doublon = vente(1); doublon.setNumero("FV-000004");
        try { creations.creerVente(jetonManuel, doublon); throw new AssertionError("Numéro en double accepté"); }
        catch (IllegalArgumentException attendu) { exiger(attendu.getMessage().contains("déjà utilisé"), "Message du doublon"); }
        exiger(avantRefus.equals(etat()), "Un numéro refusé modifie des opérations");
        Vente manuelle = vente(1); manuelle.setNumero("FV-000005");
        exiger("FV-000005".equals(numero(creations.creerVente(jetonManuel, manuelle).documentId())), "Numéro manuel");
        // Le numéro manuel ne consomme pas le compteur, mais il est sauté par le numéro automatique suivant.
        exiger("FV-000006".equals(numero(creations.creerVente(creations.ouvrir(TypeOperationCreation.VENTE), vente(1)).documentId())),
                "Numéro manuel non sauté");
        exiger(jdbc.queryForObject("SELECT prochain_numero_vente FROM boutique", Long.class) == 7L, "Compteur après saut incohérent");
    }

    private String numero(Long venteId) {
        return jdbc.queryForObject("SELECT numero FROM vente WHERE id=?", String.class, venteId);
    }

    private List<String> numeros(String table) {
        return jdbc.queryForList("SELECT numero FROM " + table + " ORDER BY id", String.class);
    }

    private void concurrents(TypeOperationCreation type) throws Exception {
        String jeton = creations.ouvrir(type);
        CountDownLatch verrouPris = new CountDownLatch(1), secondeDemarree = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<OperationCreationService.ResultatCreation> premiere = executor.submit(() -> {
                connecter("test-admin@example.test");
                try { return transaction.execute(status -> {
                    operations.verrouiller(jeton).orElseThrow(); verrouPris.countDown(); attendre(secondeDemarree);
                    return creer(type, jeton);
                }); } finally { SecurityContextHolder.clearContext(); }
            });
            Future<OperationCreationService.ResultatCreation> seconde = executor.submit(() -> {
                connecter("test-admin@example.test");
                try { attendre(verrouPris); secondeDemarree.countDown(); return creer(type, jeton); }
                finally { SecurityContextHolder.clearContext(); }
            });
            var a = premiere.get(25, TimeUnit.SECONDS); var b = seconde.get(25, TimeUnit.SECONDS);
            exiger(a.documentId().equals(b.documentId()) && a.dejaEnregistre() != b.dejaEnregistre(), "Création concurrente " + type);
        }
    }

    private OperationCreationService.ResultatCreation creer(TypeOperationCreation type, String jeton) {
        return type == TypeOperationCreation.ACHAT ? creations.creerAchat(jeton, achat(3)) : creations.creerVente(jeton, vente(2));
    }
    private Achat achat(int quantite) {
        Achat a = new Achat(); Fournisseur f = new Fournisseur(); f.setId(fournisseurId); a.setFournisseur(f);
        a.setMontantVerse(new BigDecimal("5.00").multiply(BigDecimal.valueOf(quantite)));
        DetailAchat ligne = new DetailAchat(); ligne.setProduit(produit()); ligne.setQuantite(quantite);
        ligne.setPrixAchatUnitaire(new BigDecimal("5.00")); ligne.setPrixVenteUnitaire(new BigDecimal("10.00"));
        a.getLignes().add(ligne); return a;
    }
    private Vente vente(int quantite) {
        Vente v = new Vente(); v.setMontantVerse(new BigDecimal("10.00").multiply(BigDecimal.valueOf(quantite)));
        DetailVente ligne = new DetailVente(); ligne.setProduit(produit()); ligne.setQuantite(quantite);
        v.getLignes().add(ligne); return v;
    }
    private Produit produit() { Produit p = new Produit(); p.setId(produitId); return p; }
    private record Etat(long achats, long ventes, long mouvements, long stock, BigDecimal solde) { }
    private Etat etat() {
        return new Etat(nombre("achat"), nombre("vente"), nombre("mouvement_caisse"),
                jdbc.queryForObject("SELECT quantite FROM produit WHERE id=?", Long.class, produitId),
                jdbc.queryForObject("SELECT COALESCE(SUM(montant),0) FROM mouvement_caisse", BigDecimal.class).setScale(2));
    }
    private long nombre(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private void verifierEtat(long achats, long ventes, long mouvements, long stock, String solde) {
        exiger(etat().equals(new Etat(achats, ventes, mouvements, stock, new BigDecimal(solde))), "Stock ou caisse incohérents");
    }
    private static void refuser(Supplier<?> appel) {
        try { appel.get(); throw new AssertionError("Conflit accepté"); }
        catch (CreationOperationException e) { exiger(e.getStatus().value() == 409, "Statut du refus incorrect"); }
    }
    private static void connecter(String email) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(email, null, List.of()));
    }
    private static void attendre(CountDownLatch latch) {
        try { exiger(latch.await(15, TimeUnit.SECONDS), "Délai du scénario concurrent dépassé"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    private static void exiger(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static class Annulation extends RuntimeException { }
}
