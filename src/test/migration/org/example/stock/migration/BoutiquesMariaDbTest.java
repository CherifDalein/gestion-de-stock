package org.example.stock.migration;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Parcours uniquement dans des bases MariaDB temporaires ; ne lit ni ne modifie stock_pro. */
public final class BoutiquesMariaDbTest {
    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        try {
            Properties config = new Properties();
            try (var r = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { config.load(r); }
            String url = AccountEmailMigration.resolve(config.getProperty("spring.datasource.url"));
            URI cible = URI.create(url.substring(5));
            if (!Set.of("localhost", "127.0.0.1").contains(cible.getHost()) || cible.getPort() != 3306 || !"/stock_pro".equals(cible.getPath())) {
                throw new IllegalStateException("Serveur MariaDB local et configuration stock_pro requis ; seules des bases de test seront utilisées.");
            }
            String serveur = url.replace("/stock_pro?", "/?").replace("/stock_pro", "/")
                    .replace("createDatabaseIfNotExist=true", "createDatabaseIfNotExist=false");
            int reussis = 0;
            try (var c = DriverManager.getConnection(serveur,
                    AccountEmailMigration.resolve(config.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(config.getProperty("spring.datasource.password")))) {
                for (String scenario : List.of("ancien", "vide", "multiboutique", "orphelin", "mixte", "relation", "detail", "collation", "index")) {
                    c.setReadOnly(false);
                    String base = "stock_boutiques_test_" + scenario + "_" + Long.toUnsignedString(System.nanoTime(), 36);
                    sql(c, "CREATE DATABASE " + base + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                    try {
                        c.setCatalog(base);
                        schema(c, !scenario.equals("vide"));
                        if (scenario.equals("orphelin")) sql(c, "UPDATE detail_vente SET produit_id=999");
                        if (scenario.equals("mixte")) sql(c, "ALTER TABLE client ADD COLUMN boutique_id BIGINT");
                        if (scenario.equals("collation")) {
                            sql(c, "ALTER TABLE categorie DROP INDEX uk_ancien_nom");
                            sql(c, "INSERT INTO categorie(id,nom) VALUES(2,'Epicerie')");
                        }
                        if (scenario.equals("index")) {
                            sql(c, "CREATE UNIQUE INDEX uk_deuxieme_nom ON categorie(nom)");
                            sql(c, "CREATE UNIQUE INDEX uk_nom_id ON categorie(nom,id)");
                        }
                        boolean valide = !Set.of("orphelin", "mixte", "collation").contains(scenario);
                        try {
                            BoutiquesMigration.migrer(c, false);
                            if (!valide) throw new AssertionError("Contrôle accepté : " + scenario);
                        } catch (IllegalStateException e) { if (valide) throw e; }
                        if (BoutiquesMigration.tables(c).containsKey("boutique")) throw new AssertionError("Écriture pendant le contrôle.");
                        try {
                            BoutiquesMigration.migrer(c, true);
                            if (!valide) throw new AssertionError("Migration acceptée : " + scenario);
                        } catch (IllegalStateException e) { if (valide) throw e; }
                        if (!valide) {
                            if (BoutiquesMigration.tables(c).containsKey("boutique")) throw new AssertionError("Schéma modifié avant refus.");
                        } else {
                            if (!scenario.equals("vide") && (!valeur(c, "SELECT mot_de_passe FROM utilisateur").equals("empreinte-inchangee")
                                    || !valeur(c, "SELECT role FROM utilisateur").equals("ADMIN")
                                    || !valeur(c, "SELECT montant FROM mouvement_caisse").equals("-30000.00"))) {
                                throw new AssertionError("Données historiques modifiées.");
                            }
                            if (scenario.equals("multiboutique") || scenario.equals("relation") || scenario.equals("detail")) {
                                sql(c, "INSERT INTO boutique(nom,date_creation) VALUES('Deuxième',CURRENT_TIMESTAMP)");
                                sql(c, "INSERT INTO categorie(id,nom,boutique_id) VALUES(2,'Épicerie',2)");
                                if (scenario.equals("relation")) sql(c, "UPDATE produit SET categorie_id=2");
                                if (scenario.equals("detail")) {
                                    sql(c, "INSERT INTO produit(id,categorie_id,boutique_id) VALUES(2,2,2)");
                                    sql(c, "UPDATE detail_vente SET produit_id=2");
                                }
                            }
                            boolean coherent = !Set.of("relation", "detail").contains(scenario);
                            try {
                                BoutiquesMigration.migrer(c, true);
                                if (!coherent) throw new AssertionError("Relation entre boutiques acceptée.");
                            } catch (IllegalStateException e) { if (coherent) throw e; }
                            if (scenario.equals("multiboutique")) {
                                if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique") != 2
                                        || !valeur(c, "SELECT boutique_id FROM categorie WHERE id=2").equals("2")) throw new AssertionError("Boutiques fusionnées.");
                                try { sql(c, "INSERT INTO categorie(id,nom,boutique_id) VALUES(3,'Epicerie',1)"); throw new AssertionError("Doublon collation accepté."); }
                                catch (SQLException attendu) { /* L'unicité SQL tient compte de la collation. */ }
                            }
                            if (scenario.equals("index") && !BoutiquesMigration.indexesUniques(c, "categorie").values().contains(List.of("nom", "id"))) {
                                throw new AssertionError("Index distinct supprimé.");
                            }
                        }
                        reussis++; System.out.println("OK boutiques MariaDB : " + scenario);
                    } finally {
                        c.setReadOnly(false); c.setCatalog(null);
                        if (!base.startsWith("stock_boutiques_test_")) throw new IllegalStateException("Base temporaire requise.");
                        sql(c, "DROP DATABASE " + base);
                    }
                }
            }
            System.out.println(reussis + " scénarios MariaDB réussis ; bases temporaires supprimées.");
        } catch (Exception e) {
            System.err.println("Vérification boutiques MariaDB interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "connexion ou opération SQL impossible ; aucun détail JDBC publié."));
            System.exit(1);
        }
    }

    static void schema(Connection c, boolean remplir) throws SQLException {
        sql(c, "CREATE TABLE utilisateur(id BIGINT PRIMARY KEY,nom VARCHAR(255),email VARCHAR(255),mot_de_passe VARCHAR(255),role VARCHAR(255))");
        sql(c, "CREATE TABLE categorie(id BIGINT PRIMARY KEY,nom VARCHAR(255),CONSTRAINT uk_ancien_nom UNIQUE(nom))");
        sql(c, "CREATE TABLE client(id BIGINT PRIMARY KEY)");
        sql(c, "CREATE TABLE fournisseur(id BIGINT PRIMARY KEY)");
        sql(c, "CREATE TABLE produit(id BIGINT PRIMARY KEY,categorie_id BIGINT,fournisseur_id BIGINT)");
        sql(c, "CREATE TABLE achat(id BIGINT PRIMARY KEY,fournisseur_id BIGINT)");
        sql(c, "CREATE TABLE vente(id BIGINT PRIMARY KEY,client_id BIGINT)");
        sql(c, "CREATE TABLE mouvement_caisse(id BIGINT PRIMARY KEY,utilisateur_id BIGINT,achat_id BIGINT,vente_id BIGINT,montant DECIMAL(17,2))");
        sql(c, "CREATE TABLE operation_creation(jeton VARCHAR(36) PRIMARY KEY,utilisateur_id BIGINT,achat_id BIGINT,vente_id BIGINT)");
        sql(c, "CREATE TABLE detail_achat(id BIGINT PRIMARY KEY,achat_id BIGINT,produit_id BIGINT)");
        sql(c, "CREATE TABLE detail_vente(id BIGINT PRIMARY KEY,vente_id BIGINT,produit_id BIGINT)");
        if (remplir) {
            sql(c, "INSERT INTO utilisateur VALUES(1,'Admin','admin@example.test','empreinte-inchangee','ADMIN')");
            sql(c, "INSERT INTO categorie VALUES(1,'Épicerie')");
            sql(c, "INSERT INTO client VALUES(1)"); sql(c, "INSERT INTO fournisseur VALUES(1)");
            sql(c, "INSERT INTO produit VALUES(1,1,1)"); sql(c, "INSERT INTO achat VALUES(1,1)"); sql(c, "INSERT INTO vente VALUES(1,1)");
            sql(c, "INSERT INTO mouvement_caisse VALUES(1,1,1,1,-30000.00)");
            sql(c, "INSERT INTO operation_creation VALUES('jeton-historique',1,1,1)");
            sql(c, "INSERT INTO detail_achat VALUES(1,1,1)"); sql(c, "INSERT INTO detail_vente VALUES(1,1,1)");
        }
    }
    static void sql(Connection c, String sql) throws SQLException { BoutiquesMigration.executer(c, sql); }
    static String valeur(Connection c, String sql) throws SQLException { try (var s = c.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getString(1); } }
}
