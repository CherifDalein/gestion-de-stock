package org.example.stock.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Ajoute uniquement les coordonnées facultatives des boutiques, sans inventer de données. */
public final class ParametresBoutiqueMigration {
    record Champ(String nom, int taille) {}
    record Colonne(int type, int nullable, String defaut, int taille) {}
    static final List<Champ> CHAMPS = List.of(new Champ("adresse", 500), new Champ("telephone", 50), new Champ("email", 254));

    private ParametresBoutiqueMigration() {}

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
                String dossier = System.getenv("PARAMETRES_BOUTIQUE_BACKUP_DIR");
                if (dossier == null || dossier.isBlank()) throw new IllegalStateException(
                        "Une sauvegarde complète est requise : lancer scripts/backup-stock-local.sh puis définir PARAMETRES_BOUTIQUE_BACKUP_DIR sur son répertoire.");
                BoutiquesMigration.verifierSauvegarde(dossier);
            }
            try (Connection c = DriverManager.getConnection(url,
                    AccountEmailMigration.resolve(config.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(config.getProperty("spring.datasource.password")))) {
                migrer(c, appliquer);
            }
        } catch (Exception e) {
            // Ne jamais rendre les valeurs personnelles éventuellement incluses dans une erreur JDBC.
            System.err.println("Migration paramètres boutique interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
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
                if (BoutiquesMigration.compter(c, "SELECT GET_LOCK('stock_parametres_boutique_migration',0)") != 1) {
                    throw new IllegalStateException("Une autre migration des paramètres boutique est en cours.");
                }
                verrou = true;
            }
            executerMigration(c, appliquer);
        } finally {
            if (verrou) BoutiquesMigration.compter(c, "SELECT RELEASE_LOCK('stock_parametres_boutique_migration')");
        }
    }

    private static void executerMigration(Connection c, boolean appliquer) throws SQLException {
        if (!BoutiquesMigration.colonnes(c, "utilisateur").containsKey("actif")) {
            throw new IllegalStateException("La migration des accès utilisateurs doit être terminée avant les paramètres boutique.");
        }
        // Reprendre en lecture seule tous les contrôles des boutiques, de la plateforme et des comptes.
        AccesUtilisateurMigration.migrer(c, false);
        c.setReadOnly(!appliquer);
        Map<String, Colonne> colonnes = colonnes(c);
        long presentes = CHAMPS.stream().filter(champ -> colonnes.containsKey(champ.nom())).count();
        if (presentes != 0 && presentes != CHAMPS.size()) {
            throw new IllegalStateException("Schéma des paramètres boutique partiel ou mixte ; aucune colonne ajoutée ni réparation automatique.");
        }
        long boutiques = BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "boutique"));
        if (presentes == CHAMPS.size()) {
            verifier(colonnes);
            System.out.println("Paramètres boutique déjà conformes ; boutiques et coordonnées conservées : " + boutiques + ".");
            return;
        }
        System.out.println("Coordonnées facultatives absentes ; boutiques existantes à conserver : " + boutiques + ".");
        if (!appliquer) {
            System.out.println("Vérification des paramètres boutique réussie en lecture seule ; aucune donnée modifiée.");
            return;
        }
        var avant = photographie(c);
        boolean maria = mariaDb(c);
        String declarations = String.join(", ", CHAMPS.stream()
                .map(champ -> (maria ? "ADD COLUMN " : "") + champ.nom() + " VARCHAR(" + champ.taille() + ") NULL").toList());
        // MariaDB valide les DDL implicitement : ajouter les trois champs dans une seule instruction.
        String ajout = maria ? declarations : "ADD (" + declarations + ")";
        BoutiquesMigration.executer(c, "ALTER TABLE " + table(c, "boutique") + " " + ajout);
        verifier(colonnes(c));
        String nonNulles = String.join(" OR ", CHAMPS.stream().map(champ -> champ.nom() + " IS NOT NULL").toList());
        if (!avant.equals(photographie(c)) || BoutiquesMigration.compter(c,
                "SELECT COUNT(*) FROM " + table(c, "boutique") + " WHERE " + nonNulles) != 0) {
            throw new IllegalStateException("Les données ont changé pendant l'opération. Garder l'application arrêtée et examiner la sauvegarde.");
        }
        System.out.println("Migration des paramètres boutique terminée ; coordonnées laissées vides et toutes les anciennes données conservées.");
    }

    private static void verifier(Map<String, Colonne> colonnes) {
        for (Champ champ : CHAMPS) {
            Colonne colonne = colonnes.get(champ.nom());
            if (colonne == null || colonne.type() != Types.VARCHAR || colonne.taille() != champ.taille()
                    || colonne.nullable() != DatabaseMetaData.columnNullable || !defautNul(colonne.defaut())) {
                throw new IllegalStateException("Schéma des paramètres boutique non conforme : boutique." + champ.nom()
                        + " doit être VARCHAR(" + champ.taille() + "), facultatif et sans défaut autre que NULL. Aucune réparation automatique.");
            }
        }
    }

    private static boolean defautNul(String defaut) {
        if (defaut == null) return true;
        String valeur = defaut.strip();
        while (valeur.startsWith("(") && valeur.endsWith(")")) valeur = valeur.substring(1, valeur.length() - 1).strip();
        return valeur.equalsIgnoreCase("NULL");
    }

    private static Map<String, Colonne> colonnes(Connection c) throws SQLException {
        String physique = BoutiquesMigration.tables(c).get("boutique");
        Map<String, Colonne> resultat = new LinkedHashMap<>();
        if (physique == null) return resultat;
        try (var r = c.getMetaData().getColumns(c.getCatalog(), c.getSchema(), physique, "%")) {
            while (r.next()) if (physique.equals(r.getString("TABLE_NAME"))) {
                resultat.put(r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), new Colonne(r.getInt("DATA_TYPE"),
                        r.getInt("NULLABLE"), r.getString("COLUMN_DEF"), r.getInt("COLUMN_SIZE")));
            }
        }
        return resultat;
    }

    static Map<String, List<List<Object>>> photographie(Connection c) throws SQLException {
        Map<String, List<List<Object>>> resultat = new LinkedHashMap<>();
        List<String> noms = new ArrayList<>(BoutiquesMigration.TABLES); noms.add("boutique");
        Set<String> nouveaux = new HashSet<>(); CHAMPS.forEach(champ -> nouveaux.add(champ.nom()));
        for (String nom : noms) {
            List<List<Object>> lignes = new ArrayList<>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table(c, nom)
                    + " ORDER BY " + (nom.equals("operation_creation") ? "jeton" : "id"))) {
                while (r.next()) {
                    List<Object> ligne = new ArrayList<>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
                        if (!(nom.equals("boutique") && nouveaux.contains(r.getMetaData().getColumnName(i).toLowerCase(Locale.ROOT)))) {
                            ligne.add(r.getObject(i));
                        }
                    }
                    lignes.add(ligne);
                }
            }
            resultat.put(nom, lignes);
        }
        return resultat;
    }

    private static boolean mariaDb(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static String table(Connection c, String nom) throws SQLException {
        String physique = BoutiquesMigration.tables(c).get(nom);
        if (physique == null || !physique.matches("[a-zA-Z0-9_]+")) throw new IllegalStateException("Identifiant SQL inattendu ; contrôle manuel requis.");
        String guillemet = c.getMetaData().getIdentifierQuoteString().strip();
        return guillemet + physique + guillemet;
    }
}
