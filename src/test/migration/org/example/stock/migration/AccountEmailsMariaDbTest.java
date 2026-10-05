package org.example.stock.migration;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Scénarios dans des bases temporaires uniquement ; ne modifie jamais stock_pro. */
public class AccountEmailsMariaDbTest {
    public static void main(String[] args) throws Exception {
        Properties config = new Properties();
        try (var r = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { config.load(r); }
        String url = AccountEmailMigration.resolve(config.getProperty("spring.datasource.url"));
        var cible = java.net.URI.create(url.substring(5));
        if (!Set.of("localhost", "127.0.0.1").contains(cible.getHost()) || cible.getPort() != 3306 || !"/stock_pro".equals(cible.getPath()))
            throw new IllegalStateException("Serveur local requis");
        url = url.replace("/stock_pro?", "/?");
        int reussis = 0;
        try (var c = DriverManager.getConnection(url, AccountEmailMigration.resolve(config.getProperty("spring.datasource.username")),
                AccountEmailMigration.resolve(config.getProperty("spring.datasource.password")))) {
            for (String cas : List.of("valide", "unicode", "collation", "doublon", "null", "invalide", "backup", "conforme", "administrateur")) {
                c.setReadOnly(false);
                String database = "stock_accounts_test_" + cas + "_" + Long.toUnsignedString(System.nanoTime(), 36);
                execute(c, "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                try {
                    c.setCatalog(database);
                    execute(c, "CREATE TABLE utilisateur(id BIGINT AUTO_INCREMENT PRIMARY KEY,nom VARCHAR(255),email VARCHAR(255),mot_de_passe VARCHAR(255),date_inscription DATE,role VARCHAR(255))");
                    if (!cas.equals("administrateur")) {
                        String email = cas.equals("null") ? null : cas.equals("invalide") ? "abc" : cas.equals("unicode") ? "\tIDENTITÉ@EXAMPLE.TEST\t" : " ADMIN@EXAMPLE.TEST ";
                        inserer(c, email);
                    }
                    if (cas.equals("doublon")) inserer(c, "admin@example.test");
                    if (cas.equals("collation")) inserer(c, "ádmin@example.test");
                    if (cas.equals("backup")) execute(c, "CREATE TABLE " + AccountEmailMigration.BACKUP + "(id BIGINT)");
                    if (cas.equals("conforme")) {
                        execute(c, "UPDATE utilisateur SET email='admin@example.test'");
                        execute(c, "ALTER TABLE utilisateur MODIFY email VARCHAR(255) NOT NULL, ADD CONSTRAINT uk_utilisateur_email UNIQUE(email)");
                    }
                    boolean valide = Set.of("valide", "unicode", "conforme", "administrateur").contains(cas);
                    try {
                        AccountEmailMigration.migrer(c, false);
                        if (!valide && !cas.equals("backup")) throw new AssertionError("Contrôle accepté : " + cas);
                    } catch (IllegalStateException e) { if (valide || cas.equals("backup")) throw e; }
                    c.setReadOnly(false);
                    if (!cas.equals("backup") && backup(c)) throw new AssertionError("Écriture pendant le contrôle");
                    try {
                        AccountEmailMigration.migrer(c, true);
                        if (!valide) throw new AssertionError("Migration acceptée : " + cas);
                    } catch (IllegalStateException e) { if (valide) throw e; }
                    if (valide) {
                        if (!AccountEmailMigration.indexUnique(c) || !AccountEmailMigration.emailObligatoire(c)) throw new AssertionError("Contraintes absentes");
                        if (!cas.equals("administrateur")) {
                            String attendu = cas.equals("unicode") ? "identité@example.test" : "admin@example.test";
                            if (!valeur(c, "SELECT email FROM utilisateur WHERE id=1").equals(attendu)) throw new AssertionError("Email incorrect");
                            if (!valeur(c, "SELECT mot_de_passe FROM utilisateur WHERE id=1").equals("empreinte-fictive")) throw new AssertionError("Empreinte modifiée");
                            if (!valeur(c, "SELECT role FROM utilisateur WHERE id=1").equals("ADMIN")) throw new AssertionError("Rôle modifié");
                        } else {
                            InitialAdmin.creer(c, " ADMIN@EXAMPLE.TEST ", "MotDePasseTest42");
                            try { InitialAdmin.creer(c, "autre@example.test", "MotDePasseTest42"); throw new AssertionError("Deuxième admin accepté"); }
                            catch (IllegalStateException attendu) {}
                            if (!valeur(c, "SELECT COUNT(*) FROM utilisateur").equals("1")) throw new AssertionError("Compte supplémentaire");
                        }
                        AccountEmailMigration.migrer(c, true);
                        if (cas.equals("conforme") && backup(c)) throw new AssertionError("Copie créée pour une base conforme");
                    } else {
                        if (!cas.equals("backup") && backup(c)) throw new AssertionError("Copie créée avant validation");
                        if (AccountEmailMigration.indexUnique(c)) throw new AssertionError("Schéma modifié après refus");
                    }
                    reussis++; System.out.println("OK comptes MariaDB : " + cas);
                } finally {
                    c.setReadOnly(false); c.setCatalog(null);
                    if (!database.startsWith("stock_accounts_test_")) throw new IllegalStateException("Base de test requise");
                    execute(c, "DROP DATABASE " + database);
                }
            }
        }
        System.out.println(reussis + " scénarios réussis ; bases temporaires supprimées.");
    }
    static void execute(Connection c, String sql) throws SQLException { AccountEmailMigration.executer(c, sql); }
    static String valeur(Connection c, String sql) throws SQLException { try (var s = c.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getString(1); } }
    static void inserer(Connection c, String email) throws SQLException {
        try (var s = c.prepareStatement("INSERT INTO utilisateur(nom,email,mot_de_passe,role) VALUES('Admin',?,'empreinte-fictive','ADMIN')")) { s.setString(1, email); s.executeUpdate(); }
    }
    static boolean backup(Connection c) throws SQLException { try (var r = c.getMetaData().getTables(c.getCatalog(), null, AccountEmailMigration.BACKUP, new String[]{"TABLE"})) { return r.next(); } }
}
