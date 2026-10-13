package org.example.stock.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Ajoute des périodes explicites et un journal sans inventer d'abonnement historique. */
public final class AbonnementMigration {
    static final String TABLE = "historique_abonnement";
    static final String UNIQUE_JETON = "uk_historique_abonnement_jeton";
    static final String INDEX_BOUTIQUE = "idx_historique_abonnement_boutique_date";
    static final String VERROU = "stock_abonnement_migration";
    static final List<String> DATES = List.of("abonnement_debut", "abonnement_fin");
    static final Set<String> CHAMPS = Set.of("id", "boutique_id", "acteur_id", "acteur_nom", "action", "cree_le",
            "periode_debut", "periode_fin", "ancienne_fin", "motif", "jeton_action", "quantite");
    static final Set<String> ACTIONS = Set.of("PROLONGATION_ESSAI", "ACTIVATION_ABONNEMENT", "ACCES_OFFERT",
            "RETRAIT_ACCES_OFFERT", "SUSPENSION_BOUTIQUE", "REACTIVATION_BOUTIQUE");
    private AbonnementMigration() {}

    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        if (args.length != 1 || !Set.of("--check", "--apply").contains(args[0]))
            throw new IllegalArgumentException("Utiliser --check ou --apply");
        try {
            Properties configuration = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { configuration.load(lecteur); }
            String url = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.url"))
                    .replace("createDatabaseIfNotExist=true", "createDatabaseIfNotExist=false");
            boolean appliquer = args[0].equals("--apply");
            if (appliquer && url.startsWith("jdbc:mariadb:")) verifierAutorisationApplication(url, System.getenv("ABONNEMENT_BACKUP_DIR"));
            try (var c = DriverManager.getConnection(url,
                    AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.password")))) { migrer(c, appliquer); }
        } catch (Exception e) {
            // Les exceptions JDBC peuvent inclure des valeurs : ne pas les publier.
            System.err.println("Migration des abonnements interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Connexion ou opération SQL impossible. Vérifiez les paramètres DB_* et le schéma avant de reprendre."));
            System.exit(1);
        }
    }

    static void verifierAutorisationApplication(String url, String dossier) throws Exception {
        BoutiquesMigration.verifierCibleSauvegarde(url);
        if (dossier == null || dossier.isBlank()) throw new IllegalStateException(
                "Une sauvegarde complète est requise : lancer scripts/backup-stock-local.sh puis définir ABONNEMENT_BACKUP_DIR sur son répertoire.");
        BoutiquesMigration.verifierSauvegarde(dossier);
    }

    static void migrer(Connection c, boolean appliquer) throws SQLException {
        if (!Set.of("H2", "MariaDB").contains(c.getMetaData().getDatabaseProductName()))
            throw new IllegalStateException("Cette migration prend en charge uniquement MariaDB et H2.");
        if (!c.getAutoCommit()) throw new IllegalStateException("Utiliser une connexion dédiée sans transaction ouverte.");
        boolean verrou = false;
        try {
            c.setReadOnly(!appliquer);
            if (appliquer && mariaDb(c)) {
                if (BoutiquesMigration.compter(c, "SELECT GET_LOCK('" + VERROU + "',0)") != 1)
                    throw new IllegalStateException("Une autre migration des abonnements est en cours.");
                verrou = true;
            }
            executerMigration(c, appliquer);
        } finally { if (verrou) BoutiquesMigration.compter(c, "SELECT RELEASE_LOCK('" + VERROU + "')"); }
    }

    private static void executerMigration(Connection c, boolean appliquer) throws SQLException {
        if (!BoutiquesMigration.tables(c).keySet().containsAll(Set.of(InscriptionEssaiMigration.INSCRIPTION, InscriptionEssaiMigration.PARAMETRES))
                || !colonnes(c, "boutique").keySet().containsAll(InscriptionEssaiMigration.ESSAI))
            throw new IllegalStateException("La migration inscription et essai doit être terminée avant les abonnements.");
        InscriptionEssaiMigration.migrer(c, false);
        c.setReadOnly(!appliquer);
        verifierComposantsPresents(c);
        boolean complet = colonnes(c, "boutique").keySet().containsAll(DATES) && BoutiquesMigration.tables(c).containsKey(TABLE);
        if (!appliquer || complet) {
            System.out.println(complet ? "Abonnements déjà conformes ; périodes et historique conservés."
                    : "Vérification des abonnements réussie en lecture seule ; aucun abonnement ni historique créé.");
            return;
        }
        var avant = photographie(c);
        var periodes = periodes(c);
        var historique = donnees(c);
        ajouterPeriodes(c);
        if (!BoutiquesMigration.tables(c).containsKey(TABLE)) creerHistorique(c);
        verifierComposantsPresents(c);
        if (!avant.equals(photographie(c)) || !periodes.equals(periodes(c)) || !historique.equals(donnees(c)))
            throw new IllegalStateException("Les données ont changé pendant l'opération. Garder l'application arrêtée et examiner la sauvegarde.");
        System.out.println("Migration des abonnements terminée ; quinze tables et accès existants conservés, aucun historique inventé.");
    }

    private static void verifierComposantsPresents(Connection c) throws SQLException {
        verifierPeriodes(c);
        if (BoutiquesMigration.tables(c).containsKey(TABLE)) verifierHistorique(c);
    }
    static void ajouterPeriodes(Connection c) throws SQLException {
        var presentes = colonnes(c, "boutique");
        var absentes = DATES.stream().filter(nom -> !presentes.containsKey(nom)).toList();
        if (absentes.isEmpty()) return;
        boolean maria = mariaDb(c);
        String declarations = String.join(", ", absentes.stream().map(nom -> (maria ? "ADD COLUMN " : "") + nom + " " + date(maria) + " NULL").toList());
        BoutiquesMigration.executer(c, "ALTER TABLE " + table(c, "boutique") + " " + (maria ? declarations : "ADD (" + declarations + ")"));
    }
    static void creerHistorique(Connection c) throws SQLException {
        String date = date(mariaDb(c));
        String identite = mariaDb(c) ? "AUTO_INCREMENT" : "GENERATED BY DEFAULT AS IDENTITY";
        BoutiquesMigration.executer(c, "CREATE TABLE " + identifiant(c, TABLE) + " ("
                + "id BIGINT NOT NULL " + identite + " PRIMARY KEY, boutique_id BIGINT NOT NULL, acteur_id BIGINT NOT NULL, "
                + "acteur_nom VARCHAR(255) NOT NULL, action VARCHAR(32) NOT NULL, cree_le " + date + " NOT NULL, "
                + "periode_debut " + date + " NULL, periode_fin " + date + " NULL, ancienne_fin " + date + " NULL, "
                + "motif VARCHAR(500) NULL, jeton_action VARCHAR(36) NOT NULL, quantite INTEGER NULL, "
                + "CONSTRAINT " + UNIQUE_JETON + " UNIQUE (jeton_action), "
                + "CONSTRAINT fk_historique_abonnement_boutique FOREIGN KEY(boutique_id) REFERENCES " + table(c, "boutique") + "(id), "
                + "CONSTRAINT fk_historique_abonnement_acteur FOREIGN KEY(acteur_id) REFERENCES " + table(c, "utilisateur") + "(id)"
                + (mariaDb(c) ? ", INDEX " + INDEX_BOUTIQUE + "(boutique_id,cree_le,id)" : "") + ")"
                + (mariaDb(c) ? " ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" : ""));
        if (!mariaDb(c)) BoutiquesMigration.executer(c, "CREATE INDEX " + INDEX_BOUTIQUE + " ON " + table(c, TABLE) + "(boutique_id,cree_le,id)");
    }
    private static String date(boolean maria) { return maria ? "DATETIME(6)" : "TIMESTAMP(6)"; }

    private static void verifierPeriodes(Connection c) throws SQLException {
        var presentes = colonnes(c, "boutique");
        var noms = DATES.stream().filter(presentes::containsKey).toList();
        for (String nom : noms) {
            var col = presentes.get(nom);
            if (col.nullable() != DatabaseMetaData.columnNullable || !defautNul(col.defaut()) || col.autoIncrement()
                    || !dateConforme(c, "boutique", nom, col)) nonConforme("boutique." + nom + " doit être une date UTC facultative à six décimales, sans défaut ni auto-incrément");
        }
        if (noms.size() == 1 && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "boutique") + " WHERE " + noms.getFirst() + " IS NOT NULL") != 0)
            nonConforme("une colonne d'abonnement isolée contient déjà des dates ; aucune origine ni fin ne sera déduite");
        if (noms.size() == 2 && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "boutique")
                + " WHERE (abonnement_debut IS NULL AND abonnement_fin IS NOT NULL) OR (abonnement_debut IS NOT NULL AND abonnement_fin IS NULL) OR abonnement_fin<=abonnement_debut") != 0)
            nonConforme("les dates d'abonnement doivent être vides ensemble ou présenter une fin strictement après le début");
    }

    private static void verifierHistorique(Connection c) throws SQLException {
        var colonnes = colonnes(c, TABLE);
        if (!colonnes.keySet().equals(CHAMPS)) nonConforme("historique_abonnement nécessite exactement les douze colonnes prévues");
        Set<String> facultatives = Set.of("periode_debut", "periode_fin", "ancienne_fin", "motif", "quantite");
        for (var champ : colonnes.entrySet()) {
            var col = champ.getValue();
            if (col.nullable() != (facultatives.contains(champ.getKey()) ? DatabaseMetaData.columnNullable : DatabaseMetaData.columnNoNulls)
                    || !defautNul(col.defaut()) || col.autoIncrement() != champ.getKey().equals("id"))
                nonConforme("les valeurs nulles, défauts ou auto-incréments de l'historique sont incompatibles");
        }
        for (String nom : List.of("id", "boutique_id", "acteur_id")) verifierNombre(colonnes.get(nom), Types.BIGINT, Set.of("BIGINT"));
        verifierNombre(colonnes.get("quantite"), Types.INTEGER, Set.of("INTEGER", "INT"));
        verifierTexte(colonnes.get("acteur_nom"), 255); verifierTexte(colonnes.get("action"), 32);
        verifierTexte(colonnes.get("motif"), 500); verifierTexte(colonnes.get("jeton_action"), 36);
        for (String nom : List.of("cree_le", "periode_debut", "periode_fin", "ancienne_fin"))
            if (!dateConforme(c, TABLE, nom, colonnes.get(nom))) nonConforme("les dates de l'historique doivent conserver six décimales et utiliser DATETIME sur MariaDB");
        verifierPrimaire(c);
        var uniques = BoutiquesMigration.indexesUniques(c, TABLE).values();
        if (!uniques.contains(List.of("jeton_action")) || uniques.stream().anyMatch(index -> !Set.of(List.of("id"), List.of("jeton_action")).contains(index)))
            nonConforme("seuls id et jeton_action peuvent définir une unicité de l'historique");
        verifierUniqueNomme(c);
        verifierIndexBoutique(c);
        verifierRelations(c);
        verifierMoteurEtCollation(c);
        verifierDonnees(c);
    }
    private static void verifierNombre(InscriptionEssaiMigration.Colonne col, int type, Set<String> noms) {
        if (col.type() != type || !noms.contains(col.nomType().toUpperCase(Locale.ROOT))) nonConforme("les identifiants et quantités doivent être des entiers signés de la taille prévue");
    }
    private static void verifierTexte(InscriptionEssaiMigration.Colonne col, int taille) {
        if (col.type() != Types.VARCHAR || col.taille() != taille) nonConforme("une colonne texte ne correspond pas à la taille VARCHAR attendue");
    }
    private static void verifierPrimaire(Connection c) throws SQLException {
        SortedMap<Integer, String> primaire = new TreeMap<>();
        try (var r = c.getMetaData().getPrimaryKeys(c.getCatalog(), c.getSchema(), BoutiquesMigration.tables(c).get(TABLE))) {
            while (r.next()) primaire.put(r.getInt("KEY_SEQ"), r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        if (!new ArrayList<>(primaire.values()).equals(List.of("id"))) nonConforme("la clé primaire de l'historique doit porter uniquement sur id");
    }
    private static void verifierUniqueNomme(Connection c) throws SQLException {
        String sql = "SELECT tc.constraint_name FROM information_schema.table_constraints tc JOIN information_schema.key_column_usage ku"
                + " ON ku.constraint_schema=tc.constraint_schema AND ku.constraint_name=tc.constraint_name AND ku.table_name=tc.table_name"
                + " WHERE tc.table_schema=? AND tc.table_name=? AND tc.constraint_type='UNIQUE'"
                + " GROUP BY tc.constraint_name HAVING COUNT(*)=1 AND LOWER(MAX(ku.column_name))='jeton_action'";
        boolean trouve = false;
        try (var s = c.prepareStatement(sql)) {
            s.setString(1, mariaDb(c) ? c.getCatalog() : c.getSchema()); s.setString(2, BoutiquesMigration.tables(c).get(TABLE));
            try (var r = s.executeQuery()) { while (r.next()) if (UNIQUE_JETON.equalsIgnoreCase(r.getString(1))) trouve = true; }
        }
        if (!trouve) nonConforme("la contrainte nommée " + UNIQUE_JETON + " est requise sur jeton_action");
    }
    private static void verifierRelations(Connection c) throws SQLException {
        Set<String> relations = new HashSet<>(); int nombre = 0;
        try (var r = c.getMetaData().getImportedKeys(c.getCatalog(), c.getSchema(), BoutiquesMigration.tables(c).get(TABLE))) {
            while (r.next()) {
                nombre++;
                String colonne = r.getString("FKCOLUMN_NAME").toLowerCase(Locale.ROOT);
                String parent = r.getString("PKTABLE_NAME").toLowerCase(Locale.ROOT);
                if (r.getShort("KEY_SEQ") != 1 || !r.getString("PKCOLUMN_NAME").equalsIgnoreCase("id")
                        || !Set.of((int) DatabaseMetaData.importedKeyRestrict, (int) DatabaseMetaData.importedKeyNoAction).contains((int) r.getShort("DELETE_RULE"))
                        || !Set.of((int) DatabaseMetaData.importedKeyRestrict, (int) DatabaseMetaData.importedKeyNoAction).contains((int) r.getShort("UPDATE_RULE")))
                    nonConforme("les relations de l'historique doivent viser une seule clé id sans cascade");
                relations.add(colonne + ":" + parent);
            }
        }
        if (nombre != 2 || !relations.equals(Set.of("boutique_id:boutique", "acteur_id:utilisateur")))
            nonConforme("les deux relations vers la boutique et l'acteur sont obligatoires");
    }
    private static void verifierIndexBoutique(Connection c) throws SQLException {
        SortedMap<Integer, String> colonnes = new TreeMap<>(); boolean nonUnique = true;
        try (var r = c.getMetaData().getIndexInfo(c.getCatalog(), c.getSchema(), BoutiquesMigration.tables(c).get(TABLE), false, false)) {
            while (r.next()) if (INDEX_BOUTIQUE.equalsIgnoreCase(r.getString("INDEX_NAME"))) {
                nonUnique &= r.getBoolean("NON_UNIQUE");
                String nom = r.getString("COLUMN_NAME");
                if (nom != null) colonnes.put(r.getInt("ORDINAL_POSITION"), nom.toLowerCase(Locale.ROOT));
            }
        }
        if (!nonUnique || !new ArrayList<>(colonnes.values()).equals(List.of("boutique_id", "cree_le", "id")))
            nonConforme("l'index de consultation par boutique, date et id est requis sans unicité");
    }
    private static void verifierMoteurEtCollation(Connection c) throws SQLException {
        if (!mariaDb(c)) return;
        try (var s = c.prepareStatement("SELECT engine,table_collation FROM information_schema.tables WHERE table_schema=? AND table_name=?")) {
            s.setString(1, c.getCatalog()); s.setString(2, BoutiquesMigration.tables(c).get(TABLE));
            try (var r = s.executeQuery()) {
                if (!r.next() || !"InnoDB".equalsIgnoreCase(r.getString(1)) || !"utf8mb4_unicode_ci".equalsIgnoreCase(r.getString(2)))
                    nonConforme("l'historique doit utiliser InnoDB et utf8mb4_unicode_ci");
            }
        }
        try (var s = c.prepareStatement("SELECT character_set_name,collation_name FROM information_schema.columns WHERE table_schema=? AND table_name=? AND character_set_name IS NOT NULL")) {
            s.setString(1, c.getCatalog()); s.setString(2, BoutiquesMigration.tables(c).get(TABLE));
            try (var r = s.executeQuery()) { while (r.next()) if (!"utf8mb4".equalsIgnoreCase(r.getString(1)) || !"utf8mb4_unicode_ci".equalsIgnoreCase(r.getString(2))) nonConforme("les colonnes texte de l'historique doivent conserver utf8mb4_unicode_ci"); }
        }
        try (var s = c.prepareStatement("SELECT sub_part FROM information_schema.statistics WHERE table_schema=? AND table_name=? AND non_unique=0")) {
            s.setString(1, c.getCatalog()); s.setString(2, BoutiquesMigration.tables(c).get(TABLE));
            try (var r = s.executeQuery()) { while (r.next()) if (r.getObject(1) != null) nonConforme("les index uniques doivent couvrir les colonnes complètes, sans préfixe"); }
        }
    }
    private static void verifierDonnees(Connection c) throws SQLException {
        String jointure = "SELECT COUNT(*) FROM " + table(c, TABLE) + " h LEFT JOIN " + table(c, "boutique") + " b ON b.id=h.boutique_id LEFT JOIN "
                + table(c, "utilisateur") + " u ON u.id=h.acteur_id WHERE b.id IS NULL OR u.id IS NULL";
        if (BoutiquesMigration.compter(c, jointure) != 0) nonConforme("l'historique contient une boutique ou un acteur absent");
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT acteur_nom,action,periode_debut,periode_fin,ancienne_fin,jeton_action,quantite,motif FROM " + table(c, TABLE))) {
            while (r.next()) {
                String nom = r.getString(1), action = r.getString(2), jeton = r.getString(6), motif = r.getString(8);
                Timestamp debut = r.getTimestamp(3), fin = r.getTimestamp(4), ancienneFin = r.getTimestamp(5);
                Integer quantite = (Integer) r.getObject(7);
                if (nom == null || nom.isBlank() || !nom.equals(nom.strip()) || !ACTIONS.contains(action)) nonConforme("le nom d'acteur ou l'action de l'historique est invalide");
                boolean uuid = false;
                try { uuid = jeton != null && UUID.fromString(jeton).toString().equals(jeton); } catch (IllegalArgumentException ignored) { }
                if (!uuid) nonConforme("le jeton d'action doit être un UUID canonique");
                if ((debut == null) != (fin == null) || (debut != null && !fin.after(debut))) nonConforme("les périodes de l'historique sont incohérentes");
                if (Set.of("PROLONGATION_ESSAI", "ACTIVATION_ABONNEMENT").contains(action)) {
                    if (debut == null || quantite == null || quantite < 1 || quantite > 3650
                            || (action.equals("ACTIVATION_ABONNEMENT") && quantite != 1) || (ancienneFin != null && ancienneFin.after(debut)))
                        nonConforme("les activations et prolongations nécessitent une période et une quantité cohérentes");
                    var finAttendue = action.equals("PROLONGATION_ESSAI") ? debut.toLocalDateTime().plusDays(quantite) : debut.toLocalDateTime().plusMonths(1);
                    if (!fin.toLocalDateTime().equals(finAttendue)) nonConforme("la période accordée doit correspondre exactement aux jours ou au mois enregistrés");
                } else if (debut != null || ancienneFin != null || quantite != null) nonConforme("les changements de statut ne doivent pas inventer de période ni de quantité");
                if (motif != null && (motif.isBlank() || !motif.equals(motif.strip()))) nonConforme("un motif facultatif doit être non vide et sans espaces autour");
            }
        }
    }
    private static boolean dateConforme(Connection c, String nomTable, String nom, InscriptionEssaiMigration.Colonne col) throws SQLException {
        if (col.type() != Types.TIMESTAMP) return false;
        if (!mariaDb(c)) return col.decimales() == 6;
        try (var s = c.prepareStatement("SELECT data_type,datetime_precision FROM information_schema.columns WHERE table_schema=? AND table_name=? AND LOWER(column_name)=?")) {
            s.setString(1, c.getCatalog()); s.setString(2, BoutiquesMigration.tables(c).get(nomTable)); s.setString(3, nom);
            try (var r = s.executeQuery()) {
                if (!r.next() || !"datetime".equalsIgnoreCase(r.getString(1))) return false;
                int precision = r.getInt(2); return !r.wasNull() && precision == 6;
            }
        }
    }

    /** Les quinze tables précédentes, toutes leurs colonnes sauf les nouvelles dates d'abonnement. */
    static Map<String, List<List<Object>>> photographie(Connection c) throws SQLException {
        List<String> noms = new ArrayList<>(BoutiquesMigration.TABLES);
        noms.addAll(List.of("boutique", ReinitialisationMdpMigration.TABLE, InscriptionEssaiMigration.INSCRIPTION, InscriptionEssaiMigration.PARAMETRES));
        Map<String, List<List<Object>>> resultat = new LinkedHashMap<>();
        for (String nom : noms) {
            if (!BoutiquesMigration.tables(c).containsKey(nom)) continue;
            String ordre = nom.equals("operation_creation") ? "jeton" : nom.equals(ReinitialisationMdpMigration.TABLE) ? "utilisateur_id"
                    : nom.equals(InscriptionEssaiMigration.INSCRIPTION) ? "email" : "id";
            List<List<Object>> lignes = new ArrayList<>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table(c, nom) + " ORDER BY " + ordre)) {
                while (r.next()) {
                    List<Object> ligne = new ArrayList<>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
                        if (!(nom.equals("boutique") && DATES.contains(r.getMetaData().getColumnName(i).toLowerCase(Locale.ROOT)))) ligne.add(r.getObject(i));
                    lignes.add(ligne);
                }
            }
            resultat.put(nom, lignes);
        }
        return resultat;
    }
    static List<List<Object>> periodes(Connection c) throws SQLException {
        var colonnes = colonnes(c, "boutique");
        String dates = String.join(",", DATES.stream().map(nom -> colonnes.containsKey(nom) ? nom : "NULL").toList());
        List<List<Object>> resultat = new ArrayList<>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT id," + dates + " FROM " + table(c, "boutique") + " ORDER BY id")) {
            while (r.next()) resultat.add(Arrays.asList(r.getObject(1), r.getObject(2), r.getObject(3)));
        }
        return resultat;
    }
    static List<List<Object>> donnees(Connection c) throws SQLException {
        if (!BoutiquesMigration.tables(c).containsKey(TABLE)) return List.of();
        List<List<Object>> resultat = new ArrayList<>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table(c, TABLE) + " ORDER BY id")) {
            while (r.next()) { List<Object> ligne = new ArrayList<>(); for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) ligne.add(r.getObject(i)); resultat.add(ligne); }
        }
        return resultat;
    }
    private static Map<String, InscriptionEssaiMigration.Colonne> colonnes(Connection c, String nom) throws SQLException { return InscriptionEssaiMigration.colonnes(c, nom); }
    private static boolean defautNul(String defaut) {
        if (defaut == null) return true;
        String valeur = defaut.strip();
        while (valeur.startsWith("(") && valeur.endsWith(")")) valeur = valeur.substring(1, valeur.length() - 1).strip();
        return valeur.equalsIgnoreCase("NULL");
    }
    private static void nonConforme(String raison) { throw new IllegalStateException("Schéma des abonnements non conforme : " + raison + ". Aucune réparation automatique."); }
    private static boolean mariaDb(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static String table(Connection c, String nom) throws SQLException { return identifiant(c, BoutiquesMigration.tables(c).get(nom)); }
    private static String identifiant(Connection c, String nom) throws SQLException {
        if (nom == null || !nom.matches("[a-zA-Z0-9_]+")) throw new IllegalStateException("Identifiant SQL inattendu ; contrôle manuel requis.");
        String guillemet = c.getMetaData().getIdentifierQuoteString().strip(); return guillemet + nom + guillemet;
    }
}
