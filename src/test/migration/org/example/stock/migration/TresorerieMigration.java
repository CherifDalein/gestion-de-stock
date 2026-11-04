package org.example.stock.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Ajouts relançables : aucun montant, compte utilisateur ou document historique n'est réécrit. */
public final class TresorerieMigration {
    static final String COMPTES = "compte_tresorerie_boutique", OPERATIONS = "operation_tresorerie";
    static final String VERROU = "stock_tresorerie_migration";
    static final Set<String> NOUVELLES_TABLES = Set.of(COMPTES, OPERATIONS);
    static final Set<String> VALEURS_COMPTES = Set.of("CAISSE", "ORANGE_MONEY", "BANQUE");
    private TresorerieMigration() {}

    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        if (args.length != 1 || !Set.of("--check", "--apply").contains(args[0]))
            throw new IllegalArgumentException("Utiliser --check ou --apply");
        try {
            var configuration = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { configuration.load(lecteur); }
            String url = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.url"))
                    .replace("createDatabaseIfNotExist=true", "createDatabaseIfNotExist=false");
            boolean appliquer = args[0].equals("--apply");
            if (appliquer) verifierAutorisationApplication(url, System.getenv("TRESORERIE_BACKUP_DIR"));
            try (var c = DriverManager.getConnection(url,
                    AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.password")))) { migrer(c, appliquer); }
        } catch (Exception e) {
            // Les pilotes peuvent inclure des données privées dans leurs exceptions.
            System.err.println("Migration trésorerie interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Connexion ou opération SQL impossible ; aucune valeur JDBC publiée."));
            System.exit(1);
        }
    }

    static void verifierAutorisationApplication(String url, String dossier) throws Exception {
        BoutiquesMigration.verifierCibleSauvegarde(url);
        if (dossier == null || dossier.isBlank()) throw new IllegalStateException(
                "Une sauvegarde SQL complète est requise : lancer scripts/backup-stock-local.sh puis définir TRESORERIE_BACKUP_DIR sur son répertoire.");
        BoutiquesMigration.verifierSauvegarde(dossier);
    }

    static void migrer(Connection c, boolean appliquer) throws SQLException {
        if (!Set.of("H2", "MariaDB").contains(c.getMetaData().getDatabaseProductName()))
            throw new IllegalStateException("Cette migration prend en charge uniquement MariaDB et H2.");
        if (!c.getAutoCommit()) throw new IllegalStateException("Connexion dédiée sans transaction ouverte requise.");
        boolean verrou = false;
        try {
            c.setReadOnly(!appliquer);
            if (appliquer && maria(c)) {
                if (BoutiquesMigration.compter(c, "SELECT GET_LOCK('" + VERROU + "',0)") != 1)
                    throw new IllegalStateException("Une migration trésorerie est déjà en cours.");
                verrou = true;
            }
            if (!colonnes(c, "boutique").containsKey(InitialisationBoutiqueMigration.CHAMP))
                throw new IllegalStateException("La migration du démarrage des boutiques doit être terminée avant la trésorerie.");
            InitialisationBoutiqueMigration.migrer(c, false);
            c.setReadOnly(!appliquer);
            verifierPresents(c);
            if (!appliquer) {
                System.out.println("Trésorerie vérifiée en lecture seule ; tables/colonnes manquantes : " + manquants(c)
                        + ", comptes de boutique à ajouter : " + comptesManquants(c) + ". Aucun solde ni historique modifié.");
                return;
            }
            var avant = photographie(c);
            var schemas = schemasAnciens(c);
            var nouvellesValeurs = valeursNouvelles(c);
            var operations = donnees(c, OPERATIONS);
            var comptes = donnees(c, COMPTES);
            if (!existe(c, COMPTES)) creerComptes(c);
            if (!existe(c, OPERATIONS)) creerOperations(c);
            ajouterColonnes(c);
            ajouterRelation(c);
            ajouterIndexOperations(c);
            ajouterComptesManquants(c);
            verifierPresents(c);
            if (manquants(c) != 0 || comptesManquants(c) != 0 || !avant.equals(photographie(c))
                    || !schemas.equals(schemasAnciens(c)) || !operations.equals(donnees(c, OPERATIONS))
                    || !donnees(c, COMPTES).containsAll(comptes))
                nonConforme("des données ou anciennes colonnes ont changé ; garder l'application arrêtée et examiner la sauvegarde");
            var apres = valeursNouvelles(c);
            for (var entree : nouvellesValeurs.entrySet())
                if (!entree.getValue().equals(apres.get(entree.getKey()))) nonConforme("un compte ou lien de mouvement existant a changé");
            if (!nouvellesValeurs.containsKey("compte") && BoutiquesMigration.compter(c,
                    "SELECT COUNT(*) FROM " + table(c, "mouvement_caisse") + " WHERE compte<>'CAISSE' OR compte IS NULL") != 0)
                nonConforme("un ancien mouvement a reçu un compte différent de la caisse");
            if (!nouvellesValeurs.containsKey("operation_tresorerie_id") && BoutiquesMigration.compter(c,
                    "SELECT COUNT(*) FROM " + table(c, "mouvement_caisse") + " WHERE operation_tresorerie_id IS NOT NULL") != 0)
                nonConforme("un lien de transfert ou de dépense historique a été inventé");
            System.out.println("Migration trésorerie terminée ; données des dix-sept anciennes tables conservées, anciens mouvements en caisse et trois comptes par boutique.");
        } finally { if (verrou) BoutiquesMigration.compter(c, "SELECT RELEASE_LOCK('" + VERROU + "')"); }
    }

    static void creerComptes(Connection c) throws SQLException {
        sql(c, "CREATE TABLE " + COMPTES + " (id BIGINT NOT NULL " + identite(c)
                + " PRIMARY KEY,boutique_id BIGINT NOT NULL,compte VARCHAR(32) NOT NULL,"
                + "CONSTRAINT uk_compte_tresorerie_boutique UNIQUE(boutique_id,compte),"
                + "CONSTRAINT fk_compte_tresorerie_boutique FOREIGN KEY(boutique_id) REFERENCES " + table(c, "boutique") + "(id))" + suffixe(c));
    }

    static void creerOperations(Connection c) throws SQLException {
        sql(c, "CREATE TABLE " + OPERATIONS + " (id BIGINT NOT NULL " + identite(c)
                + " PRIMARY KEY,boutique_id BIGINT NOT NULL,utilisateur_id BIGINT NOT NULL,jeton VARCHAR(36) NOT NULL,"
                + "type VARCHAR(32) NOT NULL,compte_source VARCHAR(32) NOT NULL,compte_destination VARCHAR(32) NULL,"
                + "montant DECIMAL(17,2) NOT NULL,motif VARCHAR(255) NOT NULL,date_operation " + (maria(c) ? "DATETIME(6)" : "TIMESTAMP(6)") + " NOT NULL,"
                + "CONSTRAINT uk_operation_tresorerie_jeton UNIQUE(jeton),"
                + "CONSTRAINT fk_operation_tresorerie_boutique FOREIGN KEY(boutique_id) REFERENCES " + table(c, "boutique") + "(id),"
                + "CONSTRAINT fk_operation_tresorerie_utilisateur FOREIGN KEY(utilisateur_id) REFERENCES " + table(c, "utilisateur") + "(id))" + suffixe(c));
        ajouterIndexOperations(c);
    }

    static void ajouterColonnes(Connection c) throws SQLException {
        var presentes = colonnes(c, "mouvement_caisse");
        if (!presentes.containsKey("compte")) sql(c, "ALTER TABLE " + table(c, "mouvement_caisse") + " ADD COLUMN compte VARCHAR(32) NOT NULL DEFAULT 'CAISSE'");
        if (!presentes.containsKey("operation_tresorerie_id")) sql(c, "ALTER TABLE " + table(c, "mouvement_caisse") + " ADD COLUMN operation_tresorerie_id BIGINT NULL");
    }

    static void ajouterRelation(Connection c) throws SQLException {
        if (!relations(c, "mouvement_caisse").containsKey("operation_tresorerie_id"))
            sql(c, "ALTER TABLE " + table(c, "mouvement_caisse") + " ADD CONSTRAINT fk_mouvement_operation_tresorerie FOREIGN KEY(operation_tresorerie_id) REFERENCES " + table(c, OPERATIONS) + "(id)");
    }

    private static void ajouterIndexOperations(Connection c) throws SQLException {
        if (!indexes(c, OPERATIONS, false).containsKey("idx_operation_tresorerie_boutique_date"))
            sql(c, "CREATE INDEX idx_operation_tresorerie_boutique_date ON " + table(c, OPERATIONS) + "(boutique_id,date_operation,id)");
    }

    private static void ajouterComptesManquants(Connection c) throws SQLException {
        // Chaque INSERT est idempotent et reprend une interruption entre deux comptes.
        String requete = "INSERT INTO " + table(c, COMPTES) + "(boutique_id,compte) SELECT b.id,? FROM " + table(c, "boutique")
                + " b WHERE NOT EXISTS (SELECT 1 FROM " + table(c, COMPTES) + " t WHERE t.boutique_id=b.id AND t.compte=?)";
        try (var s = c.prepareStatement(requete)) {
            for (String compte : List.of("CAISSE", "ORANGE_MONEY", "BANQUE")) { s.setString(1, compte); s.setString(2, compte); s.executeUpdate(); }
        }
    }

    private static void verifierPresents(Connection c) throws SQLException {
        var mc = colonnes(c, "mouvement_caisse");
        if (mc.containsKey("compte")) {
            texte(mc.get("compte"), 32, false);
            String defaut = mc.get("compte").defaut();
            if (defaut == null || !defaut.replace("(", "").replace(")", "").strip().equals("'CAISSE'"))
                nonConforme("mouvement_caisse.compte doit avoir CAISSE pour défaut");
            if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "mouvement_caisse") + " WHERE compte NOT IN ('CAISSE','ORANGE_MONEY','BANQUE') OR compte IS NULL") != 0)
                nonConforme("un mouvement utilise un compte inconnu");
        }
        if (mc.containsKey("operation_tresorerie_id")) {
            entier(mc.get("operation_tresorerie_id"), true, false);
            var relation = relations(c, "mouvement_caisse").get("operation_tresorerie_id");
            if (relation != null && !relation.equals(OPERATIONS)) nonConforme("le lien du mouvement ne vise pas une opération trésorerie");
            if (!existe(c, OPERATIONS) && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "mouvement_caisse") + " WHERE operation_tresorerie_id IS NOT NULL") != 0)
                nonConforme("un mouvement possède un lien vers une table d'opérations absente");
        }
        if (existe(c, COMPTES)) {
            var cols = colonnes(c, COMPTES);
            if (!cols.keySet().equals(Set.of("id", "boutique_id", "compte"))) nonConforme("colonnes de compte inattendues");
            verifierDefauts(cols);
            entier(cols.get("id"), false, true); entier(cols.get("boutique_id"), false, false); texte(cols.get("compte"), 32, false);
            primaire(c, COMPTES); uniques(c, COMPTES, Set.of(List.of("id"), List.of("boutique_id", "compte")));
            if (!relations(c, COMPTES).equals(Map.of("boutique_id", "boutique"))) nonConforme("le compte doit appartenir à une boutique sans cascade");
            moteur(c, COMPTES);
            if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, COMPTES) + " t LEFT JOIN " + table(c, "boutique")
                    + " b ON b.id=t.boutique_id WHERE b.id IS NULL OR t.compte NOT IN ('CAISSE','ORANGE_MONEY','BANQUE')") != 0)
                nonConforme("un compte référence une boutique absente ou une valeur inconnue");
        }
        if (existe(c, OPERATIONS)) verifierOperations(c);
    }

    private static void verifierOperations(Connection c) throws SQLException {
        var cols = colonnes(c, OPERATIONS);
        if (!cols.keySet().equals(Set.of("id", "boutique_id", "utilisateur_id", "jeton", "type", "compte_source", "compte_destination", "montant", "motif", "date_operation")))
            nonConforme("colonnes d'opération inattendues");
        verifierDefauts(cols);
        entier(cols.get("id"), false, true); entier(cols.get("boutique_id"), false, false); entier(cols.get("utilisateur_id"), false, false);
        texte(cols.get("jeton"), 36, false); texte(cols.get("type"), 32, false); texte(cols.get("compte_source"), 32, false);
        texte(cols.get("compte_destination"), 32, true); texte(cols.get("motif"), 255, false);
        var montant = cols.get("montant");
        if (!Set.of(Types.DECIMAL, Types.NUMERIC).contains(montant.type()) || montant.taille() != 17 || montant.decimales() != 2
                || montant.nullable() != DatabaseMetaData.columnNoNulls || montant.autoIncrement() || !defautNul(montant.defaut())) nonConforme("montant DECIMAL(17,2) obligatoire requis");
        var date = cols.get("date_operation");
        if (date.type() != Types.TIMESTAMP || date.nullable() != DatabaseMetaData.columnNoNulls || date.autoIncrement() || !defautNul(date.defaut())
                || !maria(c) && date.decimales() != 6) nonConforme("date d'opération à six décimales obligatoire requise");
        primaire(c, OPERATIONS); uniques(c, OPERATIONS, Set.of(List.of("id"), List.of("jeton")));
        if (!relations(c, OPERATIONS).equals(Map.of("boutique_id", "boutique", "utilisateur_id", "utilisateur")))
            nonConforme("les opérations doivent viser une boutique et un utilisateur sans cascade");
        var index = indexes(c, OPERATIONS, false).get("idx_operation_tresorerie_boutique_date");
        if (index != null && !index.equals(List.of("boutique_id", "date_operation", "id"))) nonConforme("index de consultation des opérations incohérent");
        moteur(c, OPERATIONS);
        if (maria(c)) try (var s = c.prepareStatement("SELECT datetime_precision FROM information_schema.columns WHERE table_schema=? AND table_name=? AND column_name='date_operation' AND data_type='datetime'")) {
            s.setString(1, c.getCatalog()); s.setString(2, BoutiquesMigration.tables(c).get(OPERATIONS));
            try (var r = s.executeQuery()) { if (!r.next() || r.getInt(1) != 6) nonConforme("date d'opération DATETIME(6) requise"); }
        }
        String requete = "SELECT o.jeton,o.type,o.compte_source,o.compte_destination,o.montant,o.motif,b.id,u.id,u.boutique_id,o.boutique_id FROM "
                + table(c, OPERATIONS) + " o LEFT JOIN " + table(c, "boutique") + " b ON b.id=o.boutique_id LEFT JOIN "
                + table(c, "utilisateur") + " u ON u.id=o.utilisateur_id";
        try (var s = c.createStatement(); var r = s.executeQuery(requete)) {
            while (r.next()) {
                String jeton = r.getString(1), type = r.getString(2), source = r.getString(3), destination = r.getString(4), motif = r.getString(6);
                if (jeton == null || !jeton.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                        || !Set.of("TRANSFERT", "DEPENSE").contains(type) || !VALEURS_COMPTES.contains(source)
                        || type.equals("TRANSFERT") && (destination == null || !VALEURS_COMPTES.contains(destination) || source.equals(destination))
                        || type.equals("DEPENSE") && destination != null || r.getBigDecimal(5).signum() <= 0 || motif == null || motif.isBlank()
                        || r.getObject(7) == null || r.getObject(8) == null || r.getLong(9) != r.getLong(10)) nonConforme("opération ou appartenance de son acteur incohérente");
            }
        }
        if (colonnes(c, "mouvement_caisse").containsKey("operation_tresorerie_id")) {
            if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "mouvement_caisse") + " m LEFT JOIN " + table(c, OPERATIONS)
                    + " o ON o.id=m.operation_tresorerie_id WHERE m.operation_tresorerie_id IS NOT NULL AND (o.id IS NULL OR m.boutique_id<>o.boutique_id)") != 0)
                nonConforme("un mouvement possède un lien absent ou vers une autre boutique");
        }
    }

    private static void entier(InscriptionEssaiMigration.Colonne col, boolean nullable, boolean identite) {
        if (col == null || col.type() != Types.BIGINT || col.nullable() != (nullable ? DatabaseMetaData.columnNullable : DatabaseMetaData.columnNoNulls)
                || col.autoIncrement() != identite || !identite && !defautNul(col.defaut())) nonConforme("identifiant BIGINT inattendu");
    }
    private static void verifierDefauts(Map<String, InscriptionEssaiMigration.Colonne> cols) {
        for (var entree : cols.entrySet()) if (!entree.getKey().equals("id") && !defautNul(entree.getValue().defaut()))
            nonConforme("les comptes et opérations ne doivent pas comporter de valeur de remplacement implicite");
    }
    private static void texte(InscriptionEssaiMigration.Colonne col, int taille, boolean nullable) {
        if (col == null || col.type() != Types.VARCHAR || col.taille() != taille || col.nullable() != (nullable ? DatabaseMetaData.columnNullable : DatabaseMetaData.columnNoNulls)
                || col.autoIncrement()) nonConforme("colonne texte de trésorerie inattendue");
    }
    private static void primaire(Connection c, String nom) throws SQLException {
        var champs = new TreeMap<Integer, String>();
        try (var r = c.getMetaData().getPrimaryKeys(c.getCatalog(), c.getSchema(), BoutiquesMigration.tables(c).get(nom))) {
            while (r.next()) champs.put(r.getInt("KEY_SEQ"), r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        if (!new ArrayList<>(champs.values()).equals(List.of("id"))) nonConforme("clé primaire id requise");
    }
    private static void uniques(Connection c, String nom, Set<List<String>> attendus) throws SQLException {
        if (!new HashSet<>(indexes(c, nom, true).values()).equals(attendus)) nonConforme("contraintes uniques de trésorerie incohérentes");
    }
    private static Map<String, List<String>> indexes(Connection c, String nom, boolean uniques) throws SQLException {
        var resultat = new TreeMap<String, List<String>>(); var index = new TreeMap<String, TreeMap<Integer, String>>();
        try (var r = c.getMetaData().getIndexInfo(c.getCatalog(), c.getSchema(), BoutiquesMigration.tables(c).get(nom), uniques, false)) {
            while (r.next()) {
                String cle = r.getString("INDEX_NAME"), colonne = r.getString("COLUMN_NAME");
                if (cle != null && colonne != null) index.computeIfAbsent(cle.toLowerCase(Locale.ROOT), k -> new TreeMap<>())
                        .put(r.getInt("ORDINAL_POSITION"), colonne.toLowerCase(Locale.ROOT));
            }
        }
        index.forEach((cle, champs) -> resultat.put(cle, new ArrayList<>(champs.values())));
        return resultat;
    }
    private static Map<String, String> relations(Connection c, String nom) throws SQLException {
        var resultat = new TreeMap<String, String>();
        try (var r = c.getMetaData().getImportedKeys(c.getCatalog(), c.getSchema(), BoutiquesMigration.tables(c).get(nom))) {
            while (r.next()) {
                String colonne = r.getString("FKCOLUMN_NAME").toLowerCase(Locale.ROOT);
                // Les anciennes relations du journal sont vérifiées par leurs migrations historiques.
                if (nom.equals("mouvement_caisse") && !colonne.equals("operation_tresorerie_id")) continue;
                if (r.getShort("KEY_SEQ") != 1 || !r.getString("PKCOLUMN_NAME").equalsIgnoreCase("id")
                        || c.getCatalog() != null && r.getString("PKTABLE_CAT") != null && !c.getCatalog().equalsIgnoreCase(r.getString("PKTABLE_CAT"))
                        || c.getSchema() != null && r.getString("PKTABLE_SCHEM") != null && !c.getSchema().equalsIgnoreCase(r.getString("PKTABLE_SCHEM"))
                        || !Set.of((int) DatabaseMetaData.importedKeyNoAction, (int) DatabaseMetaData.importedKeyRestrict).contains((int) r.getShort("DELETE_RULE"))
                        || !Set.of((int) DatabaseMetaData.importedKeyNoAction, (int) DatabaseMetaData.importedKeyRestrict).contains((int) r.getShort("UPDATE_RULE"))
                        || resultat.put(colonne, r.getString("PKTABLE_NAME").toLowerCase(Locale.ROOT)) != null)
                    nonConforme("relation de trésorerie composite, redondante ou avec cascade");
            }
        }
        return resultat;
    }
    private static void moteur(Connection c, String nom) throws SQLException {
        if (!maria(c)) return;
        try (var s = c.prepareStatement("SELECT engine,table_collation FROM information_schema.tables WHERE table_schema=? AND table_name=?")) {
            s.setString(1, c.getCatalog()); s.setString(2, BoutiquesMigration.tables(c).get(nom));
            try (var r = s.executeQuery()) { if (!r.next() || !"InnoDB".equalsIgnoreCase(r.getString(1)) || !"utf8mb4_unicode_ci".equalsIgnoreCase(r.getString(2))) nonConforme("InnoDB et utf8mb4_unicode_ci requis pour les nouvelles tables"); }
        }
        try (var s = c.prepareStatement("SELECT character_set_name,collation_name FROM information_schema.columns WHERE table_schema=? AND table_name=? AND character_set_name IS NOT NULL")) {
            s.setString(1, c.getCatalog()); s.setString(2, BoutiquesMigration.tables(c).get(nom));
            try (var r = s.executeQuery()) { while (r.next()) if (!"utf8mb4".equalsIgnoreCase(r.getString(1)) || !"utf8mb4_unicode_ci".equalsIgnoreCase(r.getString(2))) nonConforme("les colonnes texte doivent conserver utf8mb4_unicode_ci"); }
        }
        try (var s = c.prepareStatement("SELECT sub_part FROM information_schema.statistics WHERE table_schema=? AND table_name=? AND non_unique=0")) {
            s.setString(1, c.getCatalog()); s.setString(2, BoutiquesMigration.tables(c).get(nom));
            try (var r = s.executeQuery()) { while (r.next()) if (r.getObject(1) != null) nonConforme("les contraintes uniques doivent couvrir les colonnes complètes"); }
        }
    }

    static long comptesManquants(Connection c) throws SQLException {
        if (!existe(c, COMPTES)) return 3 * BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "boutique"));
        long manquants = 0;
        try (var s = c.prepareStatement("SELECT COUNT(*) FROM " + table(c, "boutique") + " b WHERE NOT EXISTS (SELECT 1 FROM " + table(c, COMPTES) + " t WHERE t.boutique_id=b.id AND t.compte=?)")) {
            for (String compte : VALEURS_COMPTES) { s.setString(1, compte); try (var r = s.executeQuery()) { r.next(); manquants += r.getLong(1); } }
        }
        return manquants;
    }
    private static int manquants(Connection c) throws SQLException {
        int manquants = 0;
        for (String nom : NOUVELLES_TABLES) if (!existe(c, nom)) manquants++;
        for (String nom : List.of("compte", "operation_tresorerie_id")) if (!colonnes(c, "mouvement_caisse").containsKey(nom)) manquants++;
        if (colonnes(c, "mouvement_caisse").containsKey("operation_tresorerie_id") && !relations(c, "mouvement_caisse").containsKey("operation_tresorerie_id")) manquants++;
        if (existe(c, OPERATIONS) && !indexes(c, OPERATIONS, false).containsKey("idx_operation_tresorerie_boutique_date")) manquants++;
        return manquants;
    }
    static Map<String, List<List<Object>>> photographie(Connection c) throws SQLException {
        var resultat = new TreeMap<String, List<List<Object>>>();
        for (String nom : BoutiquesMigration.tables(c).keySet()) {
            if (NOUVELLES_TABLES.contains(nom)) continue;
            List<List<Object>> lignes = new ArrayList<>();
            String ordre = PhotographieJdbc.ordre(c, nom);
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table(c, nom) + " ORDER BY " + ordre)) {
                while (r.next()) {
                    List<Object> ligne = new ArrayList<>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
                        if (!(nom.equals("mouvement_caisse") && Set.of("compte", "operation_tresorerie_id").contains(r.getMetaData().getColumnName(i).toLowerCase(Locale.ROOT)))) ligne.add(PhotographieJdbc.valeur(r, i));
                    lignes.add(ligne);
                }
            }
            resultat.put(nom, lignes);
        }
        return resultat;
    }
    static Map<String, Map<String, InscriptionEssaiMigration.Colonne>> schemasAnciens(Connection c) throws SQLException {
        var resultat = new TreeMap<String, Map<String, InscriptionEssaiMigration.Colonne>>();
        for (String nom : BoutiquesMigration.tables(c).keySet()) if (!NOUVELLES_TABLES.contains(nom)) {
            var champs = new TreeMap<>(colonnes(c, nom));
            if (nom.equals("mouvement_caisse")) { champs.remove("compte"); champs.remove("operation_tresorerie_id"); }
            resultat.put(nom, champs);
        }
        return resultat;
    }
    static List<List<Object>> donnees(Connection c, String nom) throws SQLException {
        if (!existe(c, nom)) return List.of();
        List<List<Object>> lignes = new ArrayList<>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table(c, nom) + " ORDER BY id")) {
            while (r.next()) { List<Object> ligne = new ArrayList<>(); for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) ligne.add(PhotographieJdbc.valeur(r, i)); lignes.add(ligne); }
        }
        return lignes;
    }
    private static Map<String, List<List<Object>>> valeursNouvelles(Connection c) throws SQLException {
        var resultat = new TreeMap<String, List<List<Object>>>();
        for (String nom : List.of("compte", "operation_tresorerie_id")) if (colonnes(c, "mouvement_caisse").containsKey(nom)) {
            List<List<Object>> valeurs = new ArrayList<>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT id," + nom + " FROM " + table(c, "mouvement_caisse") + " ORDER BY id")) {
                while (r.next()) valeurs.add(Arrays.asList(r.getObject(1), r.getObject(2)));
            }
            resultat.put(nom, valeurs);
        }
        return resultat;
    }
    private static boolean defautNul(String valeur) { return valeur == null || valeur.replace("(", "").replace(")", "").strip().equalsIgnoreCase("NULL"); }
    private static boolean maria(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static String identite(Connection c) throws SQLException { return maria(c) ? "AUTO_INCREMENT" : "GENERATED BY DEFAULT AS IDENTITY"; }
    private static String suffixe(Connection c) throws SQLException { return maria(c) ? " ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" : ""; }
    private static boolean existe(Connection c, String nom) throws SQLException { return BoutiquesMigration.tables(c).containsKey(nom); }
    private static Map<String, InscriptionEssaiMigration.Colonne> colonnes(Connection c, String nom) throws SQLException { return InscriptionEssaiMigration.colonnes(c, nom); }
    private static String table(Connection c, String nom) throws SQLException { return identifiant(c, BoutiquesMigration.tables(c).get(nom)); }
    private static String identifiant(Connection c, String nom) throws SQLException {
        if (nom == null || !nom.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("Identifiant SQL inattendu.");
        String guillemet = c.getMetaData().getIdentifierQuoteString().strip(); return guillemet + nom + guillemet;
    }
    private static void sql(Connection c, String requete) throws SQLException { BoutiquesMigration.executer(c, requete); }
    private static void nonConforme(String raison) { throw new IllegalStateException("Schéma trésorerie non conforme : " + raison + ". Aucune réparation automatique."); }
}
