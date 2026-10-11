package org.example.stock.migration;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Vérification des accès individuels dans des bases locales jetables ; jamais stock_pro. */
public final class AccesUtilisateurMariaDbTest {
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
                for (String scenario : List.of("ancien", "relance", "conforme", "vide", "nullable", "defaut", "sans_defaut",
                        "type", "valeur", "plateforme", "relation")) {
                    c.setReadOnly(false);
                    String base = "stock_acces_utilisateur_test_" + scenario + "_" + Long.toUnsignedString(System.nanoTime(), 36);
                    sql(c, "CREATE DATABASE " + base + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                    try {
                        c.setCatalog(base);
                        boolean vide = scenario.equals("vide");
                        BoutiquesMariaDbTest.schema(c, !vide);
                        BoutiquesMigration.migrer(c, true);
                        if (!vide) {
                            sql(c, "INSERT INTO boutique(nom,date_creation) VALUES('Deuxième',CURRENT_TIMESTAMP)");
                            sql(c, "INSERT INTO utilisateur(id,email,mot_de_passe,role,boutique_id) VALUES(2,'gerant@example.test','empreinte-gerant','ADMIN',2),(3,'caissier@example.test','empreinte-caissier','CAISSIER',1)");
                        }
                        PlateformeMigration.migrer(c, true, vide ? null : "admin@example.test");
                        if (!vide) sql(c, "UPDATE boutique SET actif=FALSE,acces_offert=TRUE WHERE id=2");
                        if (scenario.equals("nullable")) {
                            sql(c, "ALTER TABLE utilisateur ADD COLUMN actif BOOLEAN DEFAULT TRUE");
                            sql(c, "UPDATE utilisateur SET actif=NULL WHERE id=3");
                        }
                        if (scenario.equals("defaut")) sql(c, "ALTER TABLE utilisateur ADD COLUMN actif BOOLEAN NOT NULL DEFAULT FALSE");
                        if (scenario.equals("sans_defaut")) {
                            sql(c, "ALTER TABLE utilisateur ADD COLUMN actif BOOLEAN NOT NULL DEFAULT TRUE");
                            sql(c, "ALTER TABLE utilisateur ALTER COLUMN actif DROP DEFAULT");
                        }
                        if (scenario.equals("type")) sql(c, "ALTER TABLE utilisateur ADD COLUMN actif VARCHAR(5) NOT NULL DEFAULT 'true'");
                        if (scenario.equals("valeur")) {
                            sql(c, "ALTER TABLE utilisateur ADD COLUMN actif BOOLEAN NOT NULL DEFAULT TRUE");
                            sql(c, "UPDATE utilisateur SET actif=2 WHERE id=3");
                        }
                        if (scenario.equals("plateforme")) sql(c, "UPDATE utilisateur SET administrateur_plateforme=TRUE WHERE id=3");
                        if (scenario.equals("relation")) sql(c, "UPDATE produit SET boutique_id=2 WHERE id=1");
                        if (scenario.equals("conforme")) {
                            sql(c, "ALTER TABLE utilisateur ADD COLUMN actif BOOLEAN NOT NULL DEFAULT TRUE");
                            sql(c, "UPDATE utilisateur SET actif=FALSE WHERE id=1");
                        }
                        boolean valide = Set.of("ancien", "relance", "conforme", "vide").contains(scenario);
                        boolean avant = colonnePresente(c);
                        try {
                            AccesUtilisateurMigration.migrer(c, false);
                            if (!valide) throw new AssertionError("Contrôle accepté : " + scenario);
                        } catch (IllegalStateException e) { if (valide) throw e; }
                        if (colonnePresente(c) != avant) throw new AssertionError("Écriture pendant le contrôle.");
                        try {
                            AccesUtilisateurMigration.migrer(c, true);
                            if (!valide) throw new AssertionError("Migration acceptée : " + scenario);
                        } catch (IllegalStateException e) { if (valide) throw e; }
                        if (!valide && colonnePresente(c) != avant) throw new AssertionError("Schéma modifié avant refus.");
                        if (!vide) {
                            if (!valeur(c, "SELECT mot_de_passe FROM utilisateur WHERE id=1").equals("empreinte-inchangee")
                                    || !valeur(c, "SELECT role FROM utilisateur WHERE id=1").equals("ADMIN")
                                    || BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM utilisateur WHERE id=1 AND administrateur_plateforme=TRUE") != 1
                                    || !valeur(c, "SELECT boutique_id FROM utilisateur WHERE id=2").equals("2")
                                    || BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE id=2 AND actif=FALSE AND acces_offert=TRUE") != 1
                                    || !valeur(c, "SELECT montant FROM mouvement_caisse").equals("-30000.00")) throw new AssertionError("Comptes, droits, boutiques ou historique modifiés.");
                        }
                        if (valide) {
                            if (scenario.equals("relance")) sql(c, "UPDATE utilisateur SET actif=FALSE WHERE id IN(1,3)");
                            AccesUtilisateurMigration.migrer(c, false);
                            AccesUtilisateurMigration.migrer(c, true);
                            long attendu = scenario.equals("relance") ? 2 : scenario.equals("conforme") ? 1 : 0;
                            if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM utilisateur WHERE actif=FALSE") != attendu) throw new AssertionError("Comptes réactivés.");
                            if (vide && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM utilisateur") != 0) throw new AssertionError("Compte inventé.");
                        }
                        reussis++; System.out.println("OK accès utilisateurs MariaDB : " + scenario);
                    } finally {
                        c.setReadOnly(false); c.setCatalog(null);
                        if (!base.startsWith("stock_acces_utilisateur_test_")) throw new IllegalStateException("Base temporaire requise.");
                        sql(c, "DROP DATABASE " + base);
                    }
                }
            }
            System.out.println(reussis + " scénarios d'accès utilisateurs MariaDB réussis ; bases temporaires supprimées.");
        } catch (Exception e) {
            System.err.println("Vérification des accès utilisateurs MariaDB interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "connexion ou opération SQL impossible ; aucun détail JDBC publié."));
            System.exit(1);
        }
    }

    private static boolean colonnePresente(Connection c) throws SQLException { return BoutiquesMigration.colonnes(c, "utilisateur").containsKey("actif"); }
    private static void sql(Connection c, String sql) throws SQLException { BoutiquesMigration.executer(c, sql); }
    private static String valeur(Connection c, String sql) throws SQLException { return BoutiquesMariaDbTest.valeur(c, sql); }
}
