package org.example.stock.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Migration JDBC explicite : aucun démarrage Spring, aucune suppression de données métier. */
public final class BoutiquesMigration {
    static final List<String> RACINES = List.of("utilisateur", "categorie", "client", "fournisseur", "produit",
            "achat", "vente", "mouvement_caisse", "operation_creation");
    static final List<String> TABLES = List.of("utilisateur", "categorie", "client", "fournisseur", "produit",
            "achat", "vente", "mouvement_caisse", "operation_creation", "detail_achat", "detail_vente");
    static final String UNIQUE_CATEGORIE = "uk_categorie_boutique_nom";
    record Relation(String enfant, String colonne, String parent, boolean obligatoire) {}
    static final List<Relation> RELATIONS = List.of(
            new Relation("produit", "categorie_id", "categorie", true),
            new Relation("produit", "fournisseur_id", "fournisseur", false),
            new Relation("achat", "fournisseur_id", "fournisseur", false),
            new Relation("vente", "client_id", "client", false),
            new Relation("mouvement_caisse", "utilisateur_id", "utilisateur", false),
            new Relation("mouvement_caisse", "achat_id", "achat", false),
            new Relation("mouvement_caisse", "vente_id", "vente", false),
            new Relation("operation_creation", "utilisateur_id", "utilisateur", true),
            new Relation("operation_creation", "achat_id", "achat", false),
            new Relation("operation_creation", "vente_id", "vente", false),
            new Relation("detail_achat", "achat_id", "achat", true),
            new Relation("detail_achat", "produit_id", "produit", true),
            new Relation("detail_vente", "vente_id", "vente", true),
            new Relation("detail_vente", "produit_id", "produit", true));

    private BoutiquesMigration() {}

    public static void main(String[] args) {
        masquerLogsJdbc();
        if (args.length != 1 || !Set.of("--check", "--apply").contains(args[0])) {
            throw new IllegalArgumentException("Utiliser --check ou --apply");
        }
        try {
            Properties config = new Properties();
            try (var reader = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) {
                config.load(reader);
            }
            // Un contrôle ne doit pas créer silencieusement une base absente.
            String url = AccountEmailMigration.resolve(config.getProperty("spring.datasource.url"))
                    .replace("createDatabaseIfNotExist=true", "createDatabaseIfNotExist=false");
            try (Connection c = DriverManager.getConnection(url,
                    AccountEmailMigration.resolve(config.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(config.getProperty("spring.datasource.password")))) {
                boolean appliquer = args[0].equals("--apply");
                if (appliquer && mariaDb(c)) {
                    verifierCibleSauvegarde(url);
                    verifierSauvegarde(System.getenv("BOUTIQUES_BACKUP_DIR"));
                }
                migrer(c, appliquer);
            }
        } catch (Exception e) {
            // Les messages JDBC peuvent contenir des identifiants, des URL ou des valeurs métier.
            String message = e instanceof IllegalStateException ? e.getMessage()
                    : "Connexion ou opération SQL impossible. Vérifiez le serveur et les paramètres DB_* ; examinez le schéma avant de reprendre.";
            System.err.println("Migration boutiques interrompue : " + message);
            System.exit(1);
        }
    }

    static void migrer(Connection c, boolean appliquer) throws SQLException {
        String produit = c.getMetaData().getDatabaseProductName();
        if (!Set.of("MariaDB", "H2").contains(produit)) {
            throw new IllegalStateException("Cette migration prend en charge uniquement MariaDB et H2.");
        }
        if (!c.getAutoCommit()) throw new IllegalStateException("Utiliser une connexion dédiée sans transaction ouverte.");
        boolean verrou = false;
        try {
            c.setReadOnly(!appliquer);
            if (appliquer && mariaDb(c)) {
                if (compter(c, "SELECT GET_LOCK('stock_boutiques_migration',0)") != 1) {
                    throw new IllegalStateException("Une autre migration boutiques est en cours.");
                }
                verrou = true;
            }
            executerMigration(c, appliquer);
        } finally {
            if (verrou) compter(c, "SELECT RELEASE_LOCK('stock_boutiques_migration')");
        }
    }

    private static void executerMigration(Connection c, boolean appliquer) throws SQLException {
        Map<String, String> tables = tables(c);
        if (!tables.keySet().containsAll(TABLES)) {
            throw new IllegalStateException("Schéma métier incomplet : les onze tables existantes sont requises. Aucun schéma n'est créé automatiquement.");
        }
        for (String table : TABLES) {
            if (!colonnes(c, table).containsKey(table.equals("operation_creation") ? "jeton" : "id")) {
                throw new IllegalStateException("Schéma métier inattendu : identifiant absent dans " + table + ".");
            }
        }
        long avecBoutique = RACINES.stream().filter(table -> {
            try { return colonnes(c, table).containsKey("boutique_id"); }
            catch (SQLException e) { throw new IllegalStateException("Métadonnées SQL indisponibles."); }
        }).count();
        boolean boutiqueExiste = tables.containsKey("boutique");
        boolean ancien = !boutiqueExiste && avecBoutique == 0;
        boolean nouveau = boutiqueExiste && avecBoutique == RACINES.size();
        if (!ancien && !nouveau) {
            throw new IllegalStateException("Schéma boutiques partiel ou mixte. Ne pas rattacher ni fusionner les données automatiquement ; examiner ou restaurer la sauvegarde.");
        }
        verifierRelations(c, nouveau);
        verifierCategories(c, nouveau);
        Map<String, Long> avant = compteurs(c);
        System.out.println("Tables métier contrôlées : " + TABLES.size() + " ; lignes conservées : "
                + avant.values().stream().mapToLong(Long::longValue).sum());
        if (nouveau) {
            verifierSchemaFinal(c);
            System.out.println("Schéma boutiques déjà conforme ; boutiques existantes conservées : " + compter(c, "SELECT COUNT(*) FROM " + table(c, "boutique")) + ".");
            return;
        }
        if (!appliquer) {
            System.out.println("Vérification réussie en lecture seule ; les données existantes pourront être rattachées à une boutique principale.");
            return;
        }

        // Les DDL MariaDB valident implicitement : une interruption laisse un schéma partiel, refusé à la relance.
        executer(c, "CREATE TABLE boutique (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, nom VARCHAR(255) NOT NULL, date_creation "
                + (mariaDb(c) ? "DATETIME(6)" : "TIMESTAMP(6)") + " NOT NULL)");
        long boutique;
        try (var s = c.prepareStatement("INSERT INTO " + table(c, "boutique")
                + " (nom,date_creation) VALUES (?,CURRENT_TIMESTAMP)", Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, "Boutique principale");
            s.executeUpdate();
            try (var r = s.getGeneratedKeys()) {
                if (!r.next()) throw new IllegalStateException("Identifiant de la boutique principale indisponible ; examiner la sauvegarde.");
                boutique = r.getLong(1);
            }
        }
        for (String racine : RACINES) executer(c, "ALTER TABLE " + table(c, racine) + " ADD COLUMN boutique_id BIGINT");
        c.setAutoCommit(false);
        try {
            for (String racine : RACINES) {
                try (var s = c.prepareStatement("UPDATE " + table(c, racine) + " SET boutique_id=?")) {
                    s.setLong(1, boutique); s.executeUpdate();
                }
            }
            c.commit();
        } catch (SQLException e) {
            c.rollback(); throw e;
        } finally {
            c.setAutoCommit(true);
        }
        for (String racine : RACINES) {
            executer(c, "ALTER TABLE " + table(c, racine) + (mariaDb(c)
                    ? " MODIFY COLUMN boutique_id BIGINT NOT NULL" : " ALTER COLUMN boutique_id SET NOT NULL"));
            executer(c, "ALTER TABLE " + table(c, racine) + " ADD CONSTRAINT fk_" + racine
                    + "_boutique FOREIGN KEY (boutique_id) REFERENCES " + table(c, "boutique") + "(id)");
        }
        executer(c, "ALTER TABLE " + table(c, "categorie") + " ADD CONSTRAINT " + UNIQUE_CATEGORIE + " UNIQUE (boutique_id,nom)");
        supprimerUniciteGlobaleCategories(c);
        if (!avant.equals(compteurs(c))) {
            throw new IllegalStateException("Les compteurs métier ont changé. Garder l'application arrêtée et examiner la sauvegarde.");
        }
        verifierRelations(c, true);
        verifierCategories(c, true);
        verifierSchemaFinal(c);
        System.out.println("Migration boutiques terminée ; identifiants, comptes, empreintes et historiques conservés. Renouveler les sessions après redémarrage.");
    }

    private static void verifierRelations(Connection c, boolean boutiques) throws SQLException {
        for (Relation relation : RELATIONS) {
            if (!colonnes(c, relation.enfant()).containsKey(relation.colonne())) {
                throw new IllegalStateException("Schéma métier inattendu : relation absente dans " + relation.enfant() + ".");
            }
            String jointure = " FROM " + table(c, relation.enfant()) + " e LEFT JOIN " + table(c, relation.parent())
                    + " p ON p.id=e." + relation.colonne();
            String condition = "e." + relation.colonne() + " IS NOT NULL AND p.id IS NULL";
            if (relation.obligatoire()) condition = "e." + relation.colonne() + " IS NULL OR (" + condition + ")";
            if (compter(c, "SELECT COUNT(*)" + jointure + " WHERE " + condition) > 0) {
                throw new IllegalStateException("Relations absentes ou orphelines : " + relation.enfant() + "." + relation.colonne() + ". Aucun rattachement automatique autorisé.");
            }
            if (boutiques && RACINES.contains(relation.enfant()) && compter(c, "SELECT COUNT(*)" + jointure
                    + " WHERE e." + relation.colonne() + " IS NOT NULL AND e.boutique_id<>p.boutique_id") > 0) {
                throw new IllegalStateException("Relation entre boutiques différentes : " + relation.enfant() + "." + relation.colonne() + ".");
            }
        }
        if (boutiques) {
            for (String racine : RACINES) {
                if (compter(c, "SELECT COUNT(*) FROM " + table(c, racine) + " e LEFT JOIN " + table(c, "boutique")
                        + " b ON b.id=e.boutique_id WHERE e.boutique_id IS NULL OR b.id IS NULL") > 0) {
                    throw new IllegalStateException("Appartenance à une boutique absente ou invalide : " + racine + ".");
                }
            }
            for (String type : List.of("achat", "vente")) {
                if (compter(c, "SELECT COUNT(*) FROM " + table(c, "detail_" + type) + " d JOIN " + table(c, type)
                        + " o ON o.id=d." + type + "_id JOIN " + table(c, "produit")
                        + " p ON p.id=d.produit_id WHERE o.boutique_id<>p.boutique_id") > 0) {
                    throw new IllegalStateException("Lignes et produits de boutiques différentes : detail_" + type + ".");
                }
            }
        }
    }

    private static void verifierCategories(Connection c, boolean boutiques) throws SQLException {
        if (!colonnes(c, "categorie").containsKey("nom")) throw new IllegalStateException("Nom de catégorie absent du schéma.");
        // GROUP BY utilise la collation de la base, sans remplacer la règle SQL par une normalisation Java.
        String groupe = boutiques ? "boutique_id,nom" : "nom";
        if (compter(c, "SELECT COUNT(*) FROM (SELECT " + groupe + " FROM " + table(c, "categorie")
                + " WHERE nom IS NOT NULL GROUP BY " + groupe + " HAVING COUNT(*)>1) doublons") > 0) {
            throw new IllegalStateException("Catégories en doublon dans une même boutique selon la collation SQL. Corriger sans supprimer leurs historiques.");
        }
    }

    private static void verifierSchemaFinal(Connection c) throws SQLException {
        Map<String, Integer> colonnesBoutique = colonnes(c, "boutique");
        if (!colonnesBoutique.keySet().containsAll(Set.of("id", "nom", "date_creation"))
                || Set.of("id", "nom", "date_creation").stream().anyMatch(nom -> colonnesBoutique.get(nom) != DatabaseMetaData.columnNoNulls)) {
            throw new IllegalStateException("Schéma boutique incomplet ; aucune réparation automatique d'un schéma migré.");
        }
        for (String racine : RACINES) {
            if (colonnes(c, racine).get("boutique_id") != DatabaseMetaData.columnNoNulls || !cleBoutique(c, racine)) {
                throw new IllegalStateException("Schéma boutiques partiel : boutique_id doit être obligatoire et référencer boutique.id dans " + racine + ".");
            }
        }
        Collection<List<String>> indexes = indexesUniques(c, "categorie").values();
        if (indexes.stream().noneMatch(colonnes -> colonnes.size() == 2 && new HashSet<>(colonnes).equals(Set.of("boutique_id", "nom")))
                || indexes.stream().anyMatch(colonnes -> colonnes.equals(List.of("nom")))) {
            throw new IllegalStateException("Unicité des catégories non conforme : seul le couple boutique_id/nom doit limiter les noms.");
        }
    }

    private static void supprimerUniciteGlobaleCategories(Connection c) throws SQLException {
        if (!mariaDb(c)) {
            // H2 expose pour une contrainte UNIQUE un nom d'index différent du nom de contrainte.
            List<String> contraintes = new ArrayList<>();
            try (var s = c.prepareStatement("SELECT tc.constraint_name FROM information_schema.table_constraints tc"
                    + " JOIN information_schema.key_column_usage ku ON ku.constraint_catalog=tc.constraint_catalog"
                    + " AND ku.constraint_schema=tc.constraint_schema AND ku.constraint_name=tc.constraint_name"
                    + " WHERE tc.table_schema=? AND tc.table_name=? AND tc.constraint_type='UNIQUE'"
                    + " GROUP BY tc.constraint_name HAVING COUNT(*)=1 AND LOWER(MAX(ku.column_name))='nom'")) {
                s.setString(1, c.getSchema()); s.setString(2, tables(c).get("categorie"));
                try (var r = s.executeQuery()) { while (r.next()) contraintes.add(r.getString(1)); }
            }
            for (String contrainte : contraintes) executer(c, "ALTER TABLE " + table(c, "categorie") + " DROP CONSTRAINT " + identifiant(c, contrainte));
        }
        // Supprimer exclusivement l'unicité sur nom ; conserver PK, index composés et autres contraintes.
        for (var index : indexesUniques(c, "categorie").entrySet()) {
            if (index.getValue().equals(List.of("nom"))) executer(c, "DROP INDEX " + identifiant(c, index.getKey())
                    + (mariaDb(c) ? " ON " + table(c, "categorie") : ""));
        }
    }

    static void verifierSauvegarde(String repertoire) throws Exception {
        if (repertoire == null || repertoire.isBlank()) {
            throw new IllegalStateException("Une sauvegarde complète est requise : lancer scripts/backup-stock-local.sh puis définir BOUTIQUES_BACKUP_DIR sur son répertoire.");
        }
        Path dossier = Path.of(repertoire), dump = dossier.resolve("stock_pro.sql"), sommes = dossier.resolve("SHA256SUMS");
        if (!Files.isRegularFile(dump) || Files.size(dump) == 0 || !Files.isRegularFile(sommes)) {
            throw new IllegalStateException("Sauvegarde SQL complète et fichier SHA256SUMS requis avant application.");
        }
        String contenu = Files.readString(sommes).strip();
        if (!contenu.matches("[a-fA-F0-9]{64}  stock_pro\\.sql")) throw new IllegalStateException("Format de l'empreinte de sauvegarde inattendu.");
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (var flux = Files.newInputStream(dump)) {
            byte[] buffer = new byte[65536]; int longueur;
            while ((longueur = flux.read(buffer)) >= 0) sha.update(buffer, 0, longueur);
        }
        if (!HexFormat.of().formatHex(sha.digest()).equalsIgnoreCase(contenu.substring(0, 64))) {
            throw new IllegalStateException("L'empreinte de la sauvegarde ne correspond pas au dump SQL. Aucune migration autorisée.");
        }
        System.out.println("Sauvegarde SQL : empreinte SHA-256 vérifiée.");
    }

    static void verifierCibleSauvegarde(String url) {
        try {
            URI cible = URI.create(url.substring(5));
            if (!"mariadb".equals(cible.getScheme()) || !Set.of("localhost", "127.0.0.1").contains(cible.getHost())
                    || !Set.of(-1, 3306).contains(cible.getPort()) || !"/stock_pro".equals(cible.getPath()) || cible.getUserInfo() != null) {
                throw new IllegalStateException("Le dump local ne peut migrer que MariaDB localhost:3306/stock_pro. Préparer une procédure de sauvegarde dédiée pour une autre cible.");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Cible JDBC inattendue ; aucun paramètre de connexion n'est publié.");
        }
    }

    static void masquerLogsJdbc() {
        // Le pilote journalise certains messages SQL avant même de lever l'exception ; ils peuvent contenir des valeurs métier.
        if (org.slf4j.LoggerFactory.getLogger("org.mariadb.jdbc") instanceof ch.qos.logback.classic.Logger logger) {
            logger.setLevel(ch.qos.logback.classic.Level.OFF);
        }
    }

    private static Map<String, Long> compteurs(Connection c) throws SQLException {
        Map<String, Long> resultat = new LinkedHashMap<>();
        for (String nom : TABLES) resultat.put(nom, compter(c, "SELECT COUNT(*) FROM " + table(c, nom)));
        return resultat;
    }

    static Map<String, String> tables(Connection c) throws SQLException {
        Map<String, String> resultat = new HashMap<>();
        try (var r = c.getMetaData().getTables(c.getCatalog(), c.getSchema(), "%", new String[]{"TABLE"})) {
            while (r.next()) resultat.put(r.getString("TABLE_NAME").toLowerCase(Locale.ROOT), r.getString("TABLE_NAME"));
        }
        return resultat;
    }

    static Map<String, Integer> colonnes(Connection c, String nom) throws SQLException {
        Map<String, Integer> resultat = new HashMap<>();
        String physique = tables(c).get(nom);
        if (physique == null) return resultat;
        try (var r = c.getMetaData().getColumns(c.getCatalog(), c.getSchema(), physique, "%")) {
            while (r.next()) if (physique.equals(r.getString("TABLE_NAME")))
                resultat.put(r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), r.getInt("NULLABLE"));
        }
        return resultat;
    }

    static Map<String, List<String>> indexesUniques(Connection c, String nom) throws SQLException {
        Map<String, SortedMap<Integer, String>> positions = new LinkedHashMap<>();
        try (var r = c.getMetaData().getIndexInfo(c.getCatalog(), c.getSchema(), tables(c).get(nom), true, false)) {
            while (r.next()) if (r.getString("COLUMN_NAME") != null)
                positions.computeIfAbsent(r.getString("INDEX_NAME"), ignore -> new TreeMap<>())
                        .put(r.getInt("ORDINAL_POSITION"), r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        Map<String, List<String>> resultat = new LinkedHashMap<>();
        positions.forEach((index, colonnes) -> resultat.put(index, new ArrayList<>(colonnes.values())));
        return resultat;
    }

    private static boolean cleBoutique(Connection c, String nom) throws SQLException {
        try (var r = c.getMetaData().getImportedKeys(c.getCatalog(), c.getSchema(), tables(c).get(nom))) {
            while (r.next()) if ("boutique_id".equalsIgnoreCase(r.getString("FKCOLUMN_NAME"))
                    && "boutique".equalsIgnoreCase(r.getString("PKTABLE_NAME")) && "id".equalsIgnoreCase(r.getString("PKCOLUMN_NAME"))) return true;
        }
        return false;
    }

    private static String table(Connection c, String nom) throws SQLException { return identifiant(c, tables(c).get(nom)); }
    private static String identifiant(Connection c, String nom) throws SQLException {
        if (nom == null || !nom.matches("[a-zA-Z0-9_]+")) throw new IllegalStateException("Identifiant SQL inattendu ; contrôle manuel requis.");
        String guillemet = c.getMetaData().getIdentifierQuoteString().strip();
        return guillemet + nom + guillemet;
    }
    private static boolean mariaDb(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    static void executer(Connection c, String sql) throws SQLException { try (var s = c.createStatement()) { s.execute(sql); } }
    static long compter(Connection c, String sql) throws SQLException { try (var s = c.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getLong(1); } }
}
