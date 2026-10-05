package org.example.stock.migration;

import jakarta.validation.Validation;
import org.example.stock.form.InscriptionForm;
import org.example.stock.model.Emails;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Outil indépendant de Spring : --check ne modifie pas la base. Arrêter les instances avant --apply. */
public class AccountEmailMigration {
    static final String BACKUP = "accounts_backup_20261005_utilisateur";
    record Compte(long id, String email, String normalise) {}

    public static void main(String[] args) {
        if (args.length != 1 || !Set.of("--check", "--apply").contains(args[0])) {
            throw new IllegalArgumentException("Utiliser --check ou --apply");
        }
        try {
            Properties config = new Properties();
            try (var reader = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { config.load(reader); }
            try (Connection c = DriverManager.getConnection(resolve(config.getProperty("spring.datasource.url")),
                    resolve(config.getProperty("spring.datasource.username")), resolve(config.getProperty("spring.datasource.password")))) {
                migrer(c, args[0].equals("--apply"));
            }
        } catch (Exception e) {
            // Ne pas publier les paramètres SQL, emails ou empreintes présents dans certaines exceptions JDBC.
            String message = e instanceof IllegalStateException ? e.getMessage() : "Connexion ou opération SQL impossible. Vérifiez le serveur et les paramètres DB_*";
            System.err.println("Migration interrompue : " + message);
            System.exit(1);
        }
    }

    static void migrer(Connection c, boolean appliquer) throws Exception {
        c.setReadOnly(!appliquer);
        List<Compte> comptes = new ArrayList<>();
        Set<String> emails = new HashSet<>();
        int invalides = 0, doublons = 0, modifications = 0;
        try (var factory = Validation.buildDefaultValidatorFactory(); var s = c.createStatement();
             var r = s.executeQuery("SELECT id,email FROM utilisateur ORDER BY id")) {
            while (r.next()) {
                String email = r.getString(2), normalise = Emails.normaliser(email);
                InscriptionForm formulaire = new InscriptionForm(); formulaire.setEmail(email);
                if (!factory.getValidator().validateProperty(formulaire, "email").isEmpty()) invalides++;
                if (!emails.add(normalise)) doublons++;
                if (!Objects.equals(email, normalise)) modifications++;
                comptes.add(new Compte(r.getLong(1), email, normalise));
            }
        }
        if (invalides > 0 || doublons > 0) {
            throw new IllegalStateException("Emails invalides : " + invalides + " ; doublons normalisés : " + doublons
                    + ". Corrigez ces comptes manuellement sans supprimer leurs historiques.");
        }
        verifierCollation(c, comptes);
        boolean unique = indexUnique(c), obligatoire = emailObligatoire(c);
        System.out.println("Comptes : " + comptes.size() + " ; emails à normaliser : " + modifications
                + " ; index unique : " + unique + " ; email obligatoire : " + obligatoire);
        if (!appliquer) { System.out.println("Vérification réussie en lecture seule."); return; }
        if (modifications == 0 && unique && obligatoire) { System.out.println("Schéma et emails déjà conformes."); return; }

        try (var tables = c.getMetaData().getTables(c.getCatalog(), null, BACKUP, new String[]{"TABLE"})) {
            if (tables.next()) throw new IllegalStateException("La copie " + BACKUP + " existe déjà. Vérifiez l'état de la migration avant de reprendre.");
        }
        executer(c, "CREATE TABLE " + BACKUP + " AS SELECT * FROM utilisateur");
        System.out.println("Copie des lignes conservée dans " + BACKUP + ". Les empreintes et rôles ne sont pas modifiés.");
        // Seules les adresses changent. Les identifiants et les relations de caisse restent identiques.
        c.setAutoCommit(false);
        try (var update = c.prepareStatement("UPDATE utilisateur SET email = ? WHERE id = ?")) {
            for (Compte compte : comptes) {
                if (!Objects.equals(compte.email(), compte.normalise())) {
                    update.setString(1, compte.normalise()); update.setLong(2, compte.id()); update.addBatch();
                }
            }
            update.executeBatch(); c.commit();
        } catch (SQLException e) { c.rollback(); throw e; }
        finally { c.setAutoCommit(true); }
        // DDL à validation implicite sur MariaDB/MySQL : la copie reste disponible en cas d'échec.
        if (!obligatoire) executer(c, "ALTER TABLE utilisateur MODIFY COLUMN email VARCHAR(255) NOT NULL");
        if (!unique) executer(c, "ALTER TABLE utilisateur ADD CONSTRAINT uk_utilisateur_email UNIQUE (email)");
        if (!indexUnique(c) || !emailObligatoire(c)) throw new IllegalStateException("Le schéma final doit être vérifié. La copie des comptes est conservée.");
        System.out.println("Migration terminée ; recharger les sessions après le redémarrage de l'application.");
    }

    static void verifierCollation(Connection c, List<Compte> comptes) throws SQLException {
        if (comptes.isEmpty() || c.getMetaData().getDatabaseProductName().equals("H2")) return;
        String charset, collation;
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT CHARACTER_SET_NAME,COLLATION_NAME FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='utilisateur' AND COLUMN_NAME='email'")) {
            if (!r.next()) throw new IllegalStateException("Colonne email introuvable");
            charset = r.getString(1); collation = r.getString(2);
        }
        if (charset == null || collation == null || !charset.matches("[a-zA-Z0-9_]+") || !collation.matches("[a-zA-Z0-9_]+"))
            throw new IllegalStateException("Collation email inattendue");
        String valeur = "SELECT CONVERT(? USING " + charset + ") COLLATE " + collation + " AS email";
        String candidats = String.join(" UNION ALL ", Collections.nCopies(comptes.size(), valeur));
        try (var s = c.prepareStatement("SELECT COUNT(*) FROM (SELECT email FROM (" + candidats
                + ") candidats GROUP BY email HAVING COUNT(*) > 1) doublons")) {
            for (int i = 0; i < comptes.size(); i++) s.setString(i + 1, comptes.get(i).normalise());
            try (var r = s.executeQuery()) {
                r.next(); if (r.getLong(1) > 0) throw new IllegalStateException("Des emails normalisés sont identiques selon la collation SQL. Aucun compte n'a été modifié.");
            }
        }
    }

    static boolean indexUnique(Connection c) throws SQLException {
        Map<String, List<String>> index = new HashMap<>();
        try (var r = c.getMetaData().getIndexInfo(c.getCatalog(), null, "utilisateur", true, false)) {
            while (r.next()) if (r.getString("COLUMN_NAME") != null)
                index.computeIfAbsent(r.getString("INDEX_NAME"), n -> new ArrayList<>()).add(r.getString("COLUMN_NAME"));
        }
        return index.values().stream().anyMatch(colonnes -> colonnes.size() == 1 && colonnes.getFirst().equalsIgnoreCase("email"));
    }

    static boolean emailObligatoire(Connection c) throws SQLException {
        try (var r = c.getMetaData().getColumns(c.getCatalog(), null, "utilisateur", "email")) {
            return r.next() && r.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls;
        }
    }

    static void executer(Connection c, String sql) throws SQLException { try (var s = c.createStatement()) { s.execute(sql); } }
    static String resolve(String valeur) {
        if (!valeur.startsWith("${")) return valeur;
        String expression = valeur.substring(2, valeur.length() - 1); int sep = expression.indexOf(':');
        return System.getenv().getOrDefault(expression.substring(0, sep), expression.substring(sep + 1));
    }
}
