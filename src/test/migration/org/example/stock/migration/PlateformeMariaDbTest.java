package org.example.stock.migration;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Scénarios d'accès plateforme uniquement dans des bases locales jetables. */
public final class PlateformeMariaDbTest {
    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        try {
            Properties config = new Properties();
            try (var r = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { config.load(r); }
            String url = AccountEmailMigration.resolve(config.getProperty("spring.datasource.url"));
            URI cible = URI.create(url.substring(5));
            if (!"mariadb".equals(cible.getScheme()) || !Set.of("localhost", "127.0.0.1").contains(cible.getHost())
                    || !Set.of(-1, 3306).contains(cible.getPort()) || !"/stock_pro".equals(cible.getPath()) || cible.getUserInfo() != null) {
                throw new IllegalStateException("Serveur MariaDB local requis ; seules des bases de test seront utilisées.");
            }
            String serveur = url.replace("/stock_pro?", "/?").replace("/stock_pro", "/")
                    .replace("createDatabaseIfNotExist=true", "createDatabaseIfNotExist=false");
            int reussis = 0;
            try (var c = DriverManager.getConnection(serveur,
                    AccountEmailMigration.resolve(config.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(config.getProperty("spring.datasource.password")))) {
                for (String scenario : List.of("defauts", "attribution", "relance", "partiel", "caissier", "absent",
                        "collation", "ambigu", "valeur", "role")) {
                    c.setReadOnly(false);
                    String base = "stock_plateforme_test_" + scenario + "_" + Long.toUnsignedString(System.nanoTime(), 36);
                    sql(c, "CREATE DATABASE " + base + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                    try {
                        c.setCatalog(base);
                        BoutiquesMariaDbTest.schema(c, true);
                        BoutiquesMigration.migrer(c, true);
                        sql(c, "INSERT INTO boutique(nom,date_creation) VALUES('Deuxième',CURRENT_TIMESTAMP)");
                        sql(c, "INSERT INTO utilisateur(id,email,mot_de_passe,role,boutique_id) VALUES(2,'gerant@example.test','empreinte-gerant','ADMIN',2),(3,'caissier@example.test','empreinte-caissier','CAISSIER',1)");
                        String email = scenario.equals("defauts") ? null : scenario.equals("caissier") ? "caissier@example.test"
                                : scenario.equals("absent") ? "absent@example.test" : "admin@example.test";
                        if (scenario.equals("partiel")) sql(c, "ALTER TABLE boutique ADD COLUMN actif BOOLEAN NOT NULL DEFAULT TRUE");
                        if (scenario.equals("collation")) sql(c, "UPDATE utilisateur SET email='ádmin@example.test' WHERE id=1");
                        if (scenario.equals("ambigu")) sql(c, "INSERT INTO utilisateur(id,email,role,boutique_id) VALUES(4,'admin@example.test','ADMIN',2)");
                        if (scenario.equals("valeur") || scenario.equals("role")) {
                            PlateformeMigration.migrer(c, true, null);
                            if (scenario.equals("valeur")) sql(c, "UPDATE boutique SET actif=2 WHERE id=2");
                            else sql(c, "UPDATE utilisateur SET administrateur_plateforme=TRUE WHERE id=3");
                        }
                        boolean valide = Set.of("defauts", "attribution", "relance").contains(scenario);
                        long avant = champs(c);
                        try {
                            PlateformeMigration.migrer(c, false, email);
                            if (!valide) throw new AssertionError("Contrôle accepté : " + scenario);
                        } catch (IllegalStateException e) { if (valide) throw e; }
                        if (champs(c) != avant) throw new AssertionError("Écriture pendant le contrôle.");
                        try {
                            PlateformeMigration.migrer(c, true, email);
                            if (!valide) throw new AssertionError("Migration acceptée : " + scenario);
                        } catch (IllegalStateException e) { if (valide) throw e; }
                        if (!valide && champs(c) != avant) throw new AssertionError("Schéma modifié avant refus.");
                        if (!scenario.equals("collation") && !valeur(c, "SELECT email FROM utilisateur WHERE id=1").equals("admin@example.test")) throw new AssertionError("Email modifié.");
                        if (!valeur(c, "SELECT mot_de_passe FROM utilisateur WHERE id=1").equals("empreinte-inchangee")
                                || !valeur(c, "SELECT role FROM utilisateur WHERE id=1").equals("ADMIN")
                                || !valeur(c, "SELECT boutique_id FROM utilisateur WHERE id=2").equals("2")
                                || !valeur(c, "SELECT montant FROM mouvement_caisse").equals("-30000.00")) throw new AssertionError("Compte ou historique modifié.");
                        if (valide) {
                            long attendu = email == null ? 0 : 1;
                            if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM utilisateur WHERE administrateur_plateforme=TRUE") != attendu) throw new AssertionError("Attribution incorrecte.");
                            if (scenario.equals("relance")) {
                                sql(c, "UPDATE boutique SET actif=FALSE,acces_offert=TRUE WHERE id=2");
                                PlateformeMigration.migrer(c, true, email);
                                if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE id=2 AND actif=FALSE AND acces_offert=TRUE") != 1) throw new AssertionError("États de boutique réinitialisés.");
                            }
                            PlateformeMigration.migrer(c, true, email);
                        }
                        reussis++; System.out.println("OK plateforme MariaDB : " + scenario);
                    } finally {
                        c.setReadOnly(false); c.setCatalog(null);
                        if (!base.startsWith("stock_plateforme_test_")) throw new IllegalStateException("Base temporaire requise.");
                        sql(c, "DROP DATABASE " + base);
                    }
                }
            }
            System.out.println(reussis + " scénarios plateforme MariaDB réussis ; bases temporaires supprimées.");
        } catch (Exception e) {
            System.err.println("Vérification plateforme MariaDB interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "connexion ou opération SQL impossible ; aucun détail JDBC publié."));
            System.exit(1);
        }
    }

    private static long champs(Connection c) throws SQLException {
        return PlateformeMigration.CHAMPS.stream().filter(champ -> {
            try { return BoutiquesMigration.colonnes(c, champ.table()).containsKey(champ.nom()); }
            catch (SQLException e) { throw new IllegalStateException("Métadonnées indisponibles."); }
        }).count();
    }
    private static void sql(Connection c, String sql) throws SQLException { BoutiquesMigration.executer(c, sql); }
    private static String valeur(Connection c, String sql) throws SQLException { return BoutiquesMariaDbTest.valeur(c, sql); }
}
