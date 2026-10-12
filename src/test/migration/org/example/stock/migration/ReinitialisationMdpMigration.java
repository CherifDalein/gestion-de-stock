package org.example.stock.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Crée seulement le stockage des empreintes de réinitialisation, sans modifier les comptes. */
public final class ReinitialisationMdpMigration {
    static final String TABLE = "reinitialisation_mot_de_passe";
    static final String UNIQUE_JETON = "uk_reinitialisation_mdp_jeton";
    static final String CLE_UTILISATEUR = "fk_reinitialisation_mdp_utilisateur";
    static final Set<String> CHAMPS = Set.of("utilisateur_id", "empreinte_jeton", "empreinte_mot_de_passe", "cree_le", "expire_le");
    record Colonne(int type, String nomType, int nullable, String defaut, int taille, int decimales, boolean autoIncrement) {}

    private ReinitialisationMdpMigration() {}

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
                String dossier = System.getenv("REINITIALISATION_MDP_BACKUP_DIR");
                if (dossier == null || dossier.isBlank()) throw new IllegalStateException(
                        "Une sauvegarde complète est requise : lancer scripts/backup-stock-local.sh puis définir REINITIALISATION_MDP_BACKUP_DIR sur son répertoire.");
                BoutiquesMigration.verifierSauvegarde(dossier);
            }
            try (Connection c = DriverManager.getConnection(url,
                    AccountEmailMigration.resolve(config.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(config.getProperty("spring.datasource.password")))) {
                migrer(c, appliquer);
            }
        } catch (Exception e) {
            // Ne pas publier les empreintes ou les valeurs personnelles pouvant figurer dans une erreur JDBC.
            System.err.println("Migration réinitialisation du mot de passe interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
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
                if (BoutiquesMigration.compter(c, "SELECT GET_LOCK('stock_reinitialisation_mdp_migration',0)") != 1) {
                    throw new IllegalStateException("Une autre migration de réinitialisation du mot de passe est en cours.");
                }
                verrou = true;
            }
            executerMigration(c, appliquer);
        } finally {
            if (verrou) BoutiquesMigration.compter(c, "SELECT RELEASE_LOCK('stock_reinitialisation_mdp_migration')");
        }
    }

    private static void executerMigration(Connection c, boolean appliquer) throws SQLException {
        Set<String> contacts = new HashSet<>();
        ParametresBoutiqueMigration.CHAMPS.forEach(champ -> contacts.add(champ.nom()));
        if (!BoutiquesMigration.colonnes(c, "boutique").keySet().containsAll(contacts)) {
            throw new IllegalStateException("La migration des paramètres boutique doit être terminée avant la réinitialisation du mot de passe.");
        }
        // Reprendre les contrôles des douze tables existantes sans les modifier.
        ParametresBoutiqueMigration.migrer(c, false);
        c.setReadOnly(!appliquer);
        if (BoutiquesMigration.tables(c).containsKey(TABLE)) {
            verifier(c);
            System.out.println("Stockage des réinitialisations déjà conforme ; comptes et demandes existantes conservés.");
            return;
        }
        if (!appliquer) {
            System.out.println("Vérification de la réinitialisation réussie en lecture seule ; aucune table ni demande créée.");
            return;
        }
        var avant = photographie(c);
        creerTable(c);
        verifier(c);
        if (!avant.equals(photographie(c)) || BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, TABLE)) != 0) {
            throw new IllegalStateException("Les données ont changé pendant l'opération. Garder l'application arrêtée et examiner la sauvegarde.");
        }
        System.out.println("Migration de la réinitialisation terminée ; table vide, comptes et toutes les anciennes données conservés.");
    }

    static void creerTable(Connection c) throws SQLException {
        String date = mariaDb(c) ? "DATETIME(6)" : "TIMESTAMP(6)";
        // Une seule DDL crée les colonnes et les contraintes ; aucune donnée ou demande n'est inventée.
        BoutiquesMigration.executer(c, "CREATE TABLE " + identifiant(c, TABLE) + " ("
                + "utilisateur_id BIGINT NOT NULL PRIMARY KEY, "
                + "empreinte_jeton VARCHAR(64) NOT NULL, empreinte_mot_de_passe VARCHAR(64) NOT NULL, "
                + "cree_le " + date + " NOT NULL, expire_le " + date + " NOT NULL, "
                + "CONSTRAINT " + UNIQUE_JETON + " UNIQUE (empreinte_jeton), "
                + "CONSTRAINT " + CLE_UTILISATEUR + " FOREIGN KEY (utilisateur_id) REFERENCES " + table(c, "utilisateur") + "(id))"
                + (mariaDb(c) ? " ENGINE=InnoDB" : ""));
    }

    private static void verifier(Connection c) throws SQLException {
        Map<String, Colonne> colonnes = colonnes(c);
        if (!colonnes.keySet().equals(CHAMPS)) nonConforme("les cinq colonnes attendues sont requises, sans colonne supplémentaire");
        for (String nom : CHAMPS) {
            Colonne colonne = colonnes.get(nom);
            if (colonne.nullable() != DatabaseMetaData.columnNoNulls || !defautNul(colonne.defaut()) || colonne.autoIncrement()) {
                nonConforme("les colonnes doivent être obligatoires, sans défaut ni auto-incrément");
            }
        }
        if (colonnes.get("utilisateur_id").type() != Types.BIGINT) nonConforme("utilisateur_id doit être BIGINT");
        for (String nom : List.of("empreinte_jeton", "empreinte_mot_de_passe")) {
            Colonne colonne = colonnes.get(nom);
            if (colonne.type() != Types.VARCHAR || colonne.taille() != 64) nonConforme("les empreintes doivent être VARCHAR(64)");
        }
        for (String nom : List.of("cree_le", "expire_le")) {
            Colonne colonne = colonnes.get(nom);
            boolean dateConforme = mariaDb(c)
                    ? "DATETIME".equalsIgnoreCase(colonne.nomType()) && dateMariaDbConforme(c, nom)
                    : colonne.decimales() == 6;
            if (colonne.type() != Types.TIMESTAMP || !dateConforme) {
                nonConforme("les dates doivent conserver six décimales et utiliser DATETIME sur MariaDB");
            }
        }
        SortedMap<Integer, String> primaire = new TreeMap<>();
        try (var r = c.getMetaData().getPrimaryKeys(c.getCatalog(), c.getSchema(), BoutiquesMigration.tables(c).get(TABLE))) {
            while (r.next()) primaire.put(r.getInt("KEY_SEQ"), r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        if (!new ArrayList<>(primaire.values()).equals(List.of("utilisateur_id"))) nonConforme("utilisateur_id doit être l'unique colonne de la clé primaire");
        Collection<List<String>> uniques = BoutiquesMigration.indexesUniques(c, TABLE).values();
        if (!uniques.contains(List.of("empreinte_jeton"))
                || uniques.stream().anyMatch(index -> !Set.of(List.of("utilisateur_id"), List.of("empreinte_jeton")).contains(index))) {
            nonConforme("l'empreinte du jeton doit être unique et aucune autre unicité ne doit restreindre les demandes");
        }
        verifierCleUtilisateur(c);
        if (mariaDb(c)) {
            try (var s = c.prepareStatement("SELECT engine FROM information_schema.tables WHERE table_schema=? AND table_name=?")) {
                s.setString(1, c.getCatalog()); s.setString(2, BoutiquesMigration.tables(c).get(TABLE));
                try (var r = s.executeQuery()) {
                    if (!r.next() || !"InnoDB".equalsIgnoreCase(r.getString(1))) nonConforme("le moteur doit être InnoDB pour garantir les contraintes");
                }
            }
        }
        String t = table(c, TABLE);
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + t + " r LEFT JOIN " + table(c, "utilisateur")
                + " u ON u.id=r.utilisateur_id WHERE u.id IS NULL OR r.expire_le<=r.cree_le"
                + " OR CHAR_LENGTH(r.empreinte_jeton)<>64 OR r.empreinte_jeton NOT REGEXP '^[0-9a-fA-F]{64}$'"
                + " OR CHAR_LENGTH(r.empreinte_mot_de_passe)<>64 OR r.empreinte_mot_de_passe NOT REGEXP '^[0-9a-fA-F]{64}$'") != 0) {
            throw new IllegalStateException("Demandes de réinitialisation incohérentes ; aucune demande ni aucun compte corrigé automatiquement.");
        }
    }

    private static void verifierCleUtilisateur(Connection c) throws SQLException {
        int relations = 0;
        try (var r = c.getMetaData().getImportedKeys(c.getCatalog(), c.getSchema(), BoutiquesMigration.tables(c).get(TABLE))) {
            while (r.next()) {
                relations++;
                if (r.getInt("KEY_SEQ") != 1 || !"utilisateur_id".equalsIgnoreCase(r.getString("FKCOLUMN_NAME"))
                        || !"utilisateur".equalsIgnoreCase(r.getString("PKTABLE_NAME"))
                        || !"id".equalsIgnoreCase(r.getString("PKCOLUMN_NAME"))
                        || !Set.of(DatabaseMetaData.importedKeyNoAction, DatabaseMetaData.importedKeyRestrict).contains(r.getInt("DELETE_RULE"))
                        || !Set.of(DatabaseMetaData.importedKeyNoAction, DatabaseMetaData.importedKeyRestrict).contains(r.getInt("UPDATE_RULE"))) {
                    nonConforme("une seule relation sans cascade doit relier utilisateur_id à utilisateur(id)");
                }
            }
        }
        if (relations != 1) nonConforme("la clé étrangère vers utilisateur(id) est requise");
    }

    private static boolean dateMariaDbConforme(Connection c, String nom) throws SQLException {
        // Le pilote MariaDB retourne NULL pour DECIMAL_DIGITS de DATETIME(6).
        // La précision des fractions de seconde est exposée dans information_schema.columns.
        try (var s = c.prepareStatement("SELECT data_type,datetime_precision FROM information_schema.columns"
                + " WHERE table_schema=? AND table_name=? AND LOWER(column_name)=?")) {
            s.setString(1, c.getCatalog()); s.setString(2, BoutiquesMigration.tables(c).get(TABLE)); s.setString(3, nom);
            try (var r = s.executeQuery()) {
                if (!r.next() || !"datetime".equalsIgnoreCase(r.getString("data_type"))) return false;
                int precision = r.getInt("datetime_precision");
                return !r.wasNull() && precision == 6;
            }
        }
    }

    private static void nonConforme(String raison) {
        throw new IllegalStateException("Schéma de réinitialisation non conforme : " + raison + ". Aucune réparation automatique.");
    }

    private static boolean defautNul(String defaut) {
        if (defaut == null) return true;
        String valeur = defaut.strip();
        while (valeur.startsWith("(") && valeur.endsWith(")")) valeur = valeur.substring(1, valeur.length() - 1).strip();
        return valeur.equalsIgnoreCase("NULL");
    }

    static Map<String, Colonne> colonnes(Connection c) throws SQLException {
        String physique = BoutiquesMigration.tables(c).get(TABLE);
        Map<String, Colonne> resultat = new LinkedHashMap<>();
        if (physique == null) return resultat;
        try (var r = c.getMetaData().getColumns(c.getCatalog(), c.getSchema(), physique, "%")) {
            while (r.next()) if (physique.equals(r.getString("TABLE_NAME"))) {
                resultat.put(r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), new Colonne(r.getInt("DATA_TYPE"),
                        r.getString("TYPE_NAME"), r.getInt("NULLABLE"), r.getString("COLUMN_DEF"), r.getInt("COLUMN_SIZE"),
                        r.getInt("DECIMAL_DIGITS"), "YES".equalsIgnoreCase(r.getString("IS_AUTOINCREMENT"))));
            }
        }
        return resultat;
    }

    /** Inclut toutes les anciennes colonnes, y compris les coordonnées et les comptes désactivés. */
    static Map<String, List<List<Object>>> photographie(Connection c) throws SQLException {
        Map<String, List<List<Object>>> resultat = new LinkedHashMap<>();
        List<String> noms = new ArrayList<>(BoutiquesMigration.TABLES); noms.add("boutique");
        for (String nom : noms) {
            List<List<Object>> lignes = new ArrayList<>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table(c, nom)
                    + " ORDER BY " + (nom.equals("operation_creation") ? "jeton" : "id"))) {
                while (r.next()) {
                    List<Object> ligne = new ArrayList<>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) ligne.add(r.getObject(i));
                    lignes.add(ligne);
                }
            }
            resultat.put(nom, lignes);
        }
        return resultat;
    }

    static List<List<Object>> demandes(Connection c) throws SQLException {
        if (!BoutiquesMigration.tables(c).containsKey(TABLE)) return List.of();
        List<List<Object>> lignes = new ArrayList<>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table(c, TABLE) + " ORDER BY utilisateur_id")) {
            while (r.next()) {
                List<Object> ligne = new ArrayList<>();
                for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) ligne.add(r.getObject(i));
                lignes.add(ligne);
            }
        }
        return lignes;
    }

    private static boolean mariaDb(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static String table(Connection c, String nom) throws SQLException { return identifiant(c, BoutiquesMigration.tables(c).get(nom)); }
    private static String identifiant(Connection c, String nom) throws SQLException {
        if (nom == null || !nom.matches("[a-zA-Z0-9_]+")) throw new IllegalStateException("Identifiant SQL inattendu ; contrôle manuel requis.");
        String guillemet = c.getMetaData().getIdentifierQuoteString().strip();
        return guillemet + nom + guillemet;
    }
}
