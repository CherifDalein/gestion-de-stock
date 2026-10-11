package org.example.stock.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Ajoute l'état individuel des comptes, sans réinitialiser leurs droits ni leurs appartenances. */
public final class AccesUtilisateurMigration {
    record Colonne(int type, int nullable, String defaut, int taille) {}

    private AccesUtilisateurMigration() {}

    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        if (args.length != 1 || !Set.of("--check", "--apply").contains(args[0])) {
            throw new IllegalArgumentException("Utiliser --check ou --apply");
        }
        try {
            Properties config = new Properties();
            try (var reader = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { config.load(reader); }
            String url = AccountEmailMigration.resolve(config.getProperty("spring.datasource.url"))
                    .replace("createDatabaseIfNotExist=true", "createDatabaseIfNotExist=false");
            boolean appliquer = args[0].equals("--apply");
            if (appliquer && url.startsWith("jdbc:mariadb:")) {
                BoutiquesMigration.verifierCibleSauvegarde(url);
                String dossier = System.getenv("ACCES_UTILISATEUR_BACKUP_DIR");
                if (dossier == null || dossier.isBlank()) throw new IllegalStateException(
                        "Une sauvegarde complète est requise : lancer scripts/backup-stock-local.sh puis définir ACCES_UTILISATEUR_BACKUP_DIR sur son répertoire.");
                BoutiquesMigration.verifierSauvegarde(dossier);
            }
            try (Connection c = DriverManager.getConnection(url,
                    AccountEmailMigration.resolve(config.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(config.getProperty("spring.datasource.password")))) {
                migrer(c, appliquer);
            }
        } catch (Exception e) {
            // Les erreurs JDBC peuvent contenir des valeurs personnelles : ne publier que des diagnostics contrôlés.
            System.err.println("Migration accès utilisateurs interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Connexion ou opération SQL impossible. Vérifiez les paramètres DB_* et le schéma avant de reprendre."));
            System.exit(1);
        }
    }

    static void migrer(Connection c, boolean appliquer) throws SQLException {
        if (!Set.of("H2", "MariaDB").contains(c.getMetaData().getDatabaseProductName())) {
            throw new IllegalStateException("Cette migration prend en charge uniquement MariaDB et H2.");
        }
        if (!c.getAutoCommit()) throw new IllegalStateException("Utiliser une connexion dédiée sans transaction ouverte.");
        boolean verrou = false;
        try {
            c.setReadOnly(!appliquer);
            if (appliquer && mariaDb(c)) {
                if (BoutiquesMigration.compter(c, "SELECT GET_LOCK('stock_acces_utilisateur_migration',0)") != 1) {
                    throw new IllegalStateException("Une autre migration d'accès utilisateurs est en cours.");
                }
                verrou = true;
            }
            executerMigration(c, appliquer);
        } finally {
            if (verrou) BoutiquesMigration.compter(c, "SELECT RELEASE_LOCK('stock_acces_utilisateur_migration')");
        }
    }

    private static void executerMigration(Connection c, boolean appliquer) throws SQLException {
        for (var champ : PlateformeMigration.CHAMPS) {
            if (!BoutiquesMigration.colonnes(c, champ.table()).containsKey(champ.nom())) {
                throw new IllegalStateException("La migration plateforme doit être terminée avant les accès individuels des utilisateurs.");
            }
        }
        // Vérifier tous les droits, états, contraintes et relations du schéma précédent en lecture seule.
        PlateformeMigration.migrer(c, false, null);
        c.setReadOnly(!appliquer);
        Colonne colonne = colonne(c);
        long comptes = BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + tableUtilisateur(c));
        if (colonne != null) {
            verifier(c, colonne);
            System.out.println("Schéma d'accès utilisateurs déjà conforme ; comptes conservés : " + comptes
                    + " ; comptes désactivés : " + BoutiquesMigration.compter(c,
                    "SELECT COUNT(*) FROM " + tableUtilisateur(c) + " WHERE actif=" + faux(colonne)) + ".");
            return;
        }
        System.out.println("État individuel des comptes absent ; comptes existants à conserver actifs : " + comptes + ".");
        if (!appliquer) {
            System.out.println("Vérification des accès utilisateurs réussie en lecture seule ; aucun compte ni droit modifié.");
            return;
        }
        List<List<Object>> avant = photographieComptes(c);
        Map<String, Long> compteursAvant = compteurs(c);
        // Une seule DDL, validée implicitement sur MariaDB : aucun UPDATE des comptes existants.
        BoutiquesMigration.executer(c, "ALTER TABLE " + tableUtilisateur(c) + " ADD COLUMN actif BOOLEAN NOT NULL DEFAULT TRUE");
        Colonne apres = colonne(c);
        verifier(c, apres);
        if (!avant.equals(photographieComptes(c)) || !compteursAvant.equals(compteurs(c))
                || BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + tableUtilisateur(c) + " WHERE actif=" + faux(apres)) != 0) {
            throw new IllegalStateException("Les données ou comptes ont changé pendant l'opération. Garder l'application arrêtée et examiner la sauvegarde.");
        }
        System.out.println("Migration des accès utilisateurs terminée ; tous les comptes existants restent actifs, avec leurs mots de passe, rôles, droits plateforme et boutiques conservés.");
    }

    private static void verifier(Connection c, Colonne colonne) throws SQLException {
        if (colonne == null || colonne.nullable() != DatabaseMetaData.columnNoNulls
                || !Set.of(Types.BOOLEAN, Types.BIT, Types.TINYINT).contains(colonne.type())
                || colonne.type() == Types.BIT && colonne.taille() != 1
                || !PlateformeMigration.defautConforme(colonne.defaut(), true)) {
            throw new IllegalStateException("Schéma d'accès utilisateurs non conforme : utilisateur.actif doit être booléen, obligatoire et avoir TRUE pour défaut. Aucune réparation automatique.");
        }
        String valeurs = colonne.type() == Types.BOOLEAN ? "FALSE,TRUE" : "0,1";
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + tableUtilisateur(c)
                + " WHERE actif IS NULL OR actif NOT IN (" + valeurs + ")") > 0) {
            throw new IllegalStateException("États d'accès utilisateurs incohérents : seules les valeurs booléennes zéro/un sont autorisées. Aucune réactivation automatique.");
        }
    }

    private static Colonne colonne(Connection c) throws SQLException {
        String physique = BoutiquesMigration.tables(c).get("utilisateur");
        if (physique == null) return null;
        try (var r = c.getMetaData().getColumns(c.getCatalog(), c.getSchema(), physique, "%")) {
            while (r.next()) if (physique.equals(r.getString("TABLE_NAME")) && "actif".equalsIgnoreCase(r.getString("COLUMN_NAME"))) {
                return new Colonne(r.getInt("DATA_TYPE"), r.getInt("NULLABLE"), r.getString("COLUMN_DEF"), r.getInt("COLUMN_SIZE"));
            }
        }
        return null;
    }

    private static List<List<Object>> photographieComptes(Connection c) throws SQLException {
        List<List<Object>> comptes = new ArrayList<>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + tableUtilisateur(c) + " ORDER BY id")) {
            while (r.next()) {
                List<Object> compte = new ArrayList<>();
                for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
                    if (!"actif".equalsIgnoreCase(r.getMetaData().getColumnName(i))) compte.add(r.getObject(i));
                }
                comptes.add(compte);
            }
        }
        return comptes;
    }

    private static Map<String, Long> compteurs(Connection c) throws SQLException {
        Map<String, Long> compteurs = new LinkedHashMap<>();
        List<String> noms = new ArrayList<>(BoutiquesMigration.TABLES); noms.add("boutique");
        for (String nom : noms) compteurs.put(nom, BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, nom)));
        return compteurs;
    }
    private static String faux(Colonne colonne) { return colonne.type() == Types.BOOLEAN ? "FALSE" : "0"; }
    private static boolean mariaDb(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static String tableUtilisateur(Connection c) throws SQLException { return table(c, "utilisateur"); }
    private static String table(Connection c, String nom) throws SQLException {
        String physique = BoutiquesMigration.tables(c).get(nom);
        if (physique == null || !physique.matches("[a-zA-Z0-9_]+")) throw new IllegalStateException("Identifiant SQL inattendu ; contrôle manuel requis.");
        String guillemet = c.getMetaData().getIdentifierQuoteString().strip();
        return guillemet + physique + guillemet;
    }
}
