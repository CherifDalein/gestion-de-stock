package org.example.stock.migration;

import jakarta.validation.Validation;
import org.example.stock.form.InscriptionForm;
import org.example.stock.model.Emails;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Ajoute l'inscription publique et les essais sans attribuer d'essai aux boutiques existantes. */
public final class InscriptionEssaiMigration {
    static final String INSCRIPTION = "inscription_boutique";
    static final String PARAMETRES = "parametres_plateforme";
    static final String UNIQUE_JETON = "uk_inscription_boutique_jeton";
    static final List<String> ESSAI = List.of("essai_debut", "essai_fin");
    static final Set<String> CHAMPS_INSCRIPTION = Set.of("email", "empreinte_jeton", "cree_le", "expire_le");
    static final Set<String> CHAMPS_PARAMETRES = Set.of("id", "duree_essai_jours");
    static final String VERROU = "stock_inscription_essai_migration";
    record Colonne(int type, String nomType, int nullable, String defaut, int taille, int decimales, boolean autoIncrement) {}

    private InscriptionEssaiMigration() {}

    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        if (args.length != 1 || !Set.of("--check", "--apply").contains(args[0])) throw new IllegalArgumentException("Utiliser --check ou --apply");
        try {
            Properties config = new Properties();
            try (var reader = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { config.load(reader); }
            String url = AccountEmailMigration.resolve(config.getProperty("spring.datasource.url"))
                    .replace("createDatabaseIfNotExist=true", "createDatabaseIfNotExist=false");
            boolean appliquer = args[0].equals("--apply");
            if (appliquer && url.startsWith("jdbc:mariadb:")) {
                verifierAutorisationApplication(url, System.getenv("INSCRIPTION_ESSAI_BACKUP_DIR"));
            }
            try (Connection c = DriverManager.getConnection(url,
                    AccountEmailMigration.resolve(config.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(config.getProperty("spring.datasource.password")))) {
                migrer(c, appliquer);
            }
        } catch (Exception e) {
            // Une erreur JDBC peut contenir une adresse ou une empreinte : ne jamais la publier.
            System.err.println("Migration inscription et essai interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Connexion ou opération SQL impossible. Vérifiez les paramètres DB_* et le schéma avant de reprendre."));
            System.exit(1);
        }
    }

    static void verifierAutorisationApplication(String url, String dossier) throws Exception {
        BoutiquesMigration.verifierCibleSauvegarde(url);
        if (dossier == null || dossier.isBlank()) throw new IllegalStateException(
                "Une sauvegarde complète est requise : lancer scripts/backup-stock-local.sh puis définir INSCRIPTION_ESSAI_BACKUP_DIR sur son répertoire.");
        BoutiquesMigration.verifierSauvegarde(dossier);
    }

    static void migrer(Connection c, boolean appliquer) throws SQLException {
        if (!Set.of("H2", "MariaDB").contains(c.getMetaData().getDatabaseProductName())) throw new IllegalStateException("Cette migration prend en charge uniquement MariaDB et H2.");
        if (!c.getAutoCommit()) throw new IllegalStateException("Utiliser une connexion dédiée sans transaction ouverte.");
        boolean verrou = false;
        try {
            c.setReadOnly(!appliquer);
            if (appliquer && mariaDb(c)) {
                if (BoutiquesMigration.compter(c, "SELECT GET_LOCK('" + VERROU + "',0)") != 1)
                    throw new IllegalStateException("Une autre migration inscription et essai est en cours.");
                verrou = true;
            }
            executerMigration(c, appliquer);
        } finally {
            if (verrou) BoutiquesMigration.compter(c, "SELECT RELEASE_LOCK('" + VERROU + "')");
        }
    }

    private static void executerMigration(Connection c, boolean appliquer) throws SQLException {
        if (!BoutiquesMigration.tables(c).containsKey(ReinitialisationMdpMigration.TABLE))
            throw new IllegalStateException("La migration de réinitialisation du mot de passe doit être terminée avant l'inscription et l'essai.");
        // Toutes les migrations antérieures doivent être conformes, y compris leurs données.
        ReinitialisationMdpMigration.migrer(c, false);
        c.setReadOnly(!appliquer);
        verifierComposantsPresents(c);
        boolean complet = colonnes(c, "boutique").keySet().containsAll(ESSAI)
                && BoutiquesMigration.tables(c).keySet().containsAll(Set.of(INSCRIPTION, PARAMETRES));
        if (!appliquer || complet) {
            System.out.println(complet ? "Inscription et essai déjà conformes ; toutes les données et les durées conservées."
                    : "Vérification inscription et essai réussie en lecture seule ; aucun essai, compte ou paramètre créé.");
            return;
        }
        var avant = photographie(c);
        var essaisAvant = essais(c);
        var inscriptionsAvant = donnees(c, INSCRIPTION);
        var parametresAvant = donnees(c, PARAMETRES);
        ajouterEssais(c);
        if (!BoutiquesMigration.tables(c).containsKey(INSCRIPTION)) creerInscriptions(c);
        if (!BoutiquesMigration.tables(c).containsKey(PARAMETRES)) creerParametres(c);
        verifierComposantsPresents(c);
        if (!avant.equals(photographie(c)) || !essaisAvant.equals(essais(c))
                || !inscriptionsAvant.equals(donnees(c, INSCRIPTION)) || !parametresAvant.equals(donnees(c, PARAMETRES)))
            throw new IllegalStateException("Les données ont changé pendant l'opération. Garder l'application arrêtée et examiner la sauvegarde.");
        System.out.println("Migration inscription et essai terminée ; anciennes boutiques sans essai imposé, treize tables et comptes conservés.");
    }

    private static void verifierComposantsPresents(Connection c) throws SQLException {
        verifierEssais(c);
        if (BoutiquesMigration.tables(c).containsKey(INSCRIPTION)) verifierInscriptions(c);
        if (BoutiquesMigration.tables(c).containsKey(PARAMETRES)) verifierParametres(c);
    }

    static void ajouterEssais(Connection c) throws SQLException {
        var colonnes = colonnes(c, "boutique");
        List<String> absentes = ESSAI.stream().filter(nom -> !colonnes.containsKey(nom)).toList();
        if (absentes.isEmpty()) return;
        boolean maria = mariaDb(c);
        String date = maria ? "DATETIME(6)" : "TIMESTAMP(6)";
        String declarations = String.join(", ", absentes.stream().map(nom -> (maria ? "ADD COLUMN " : "") + nom + " " + date + " NULL").toList());
        BoutiquesMigration.executer(c, "ALTER TABLE " + table(c, "boutique") + " " + (maria ? declarations : "ADD (" + declarations + ")"));
    }

    static void creerInscriptions(Connection c) throws SQLException {
        String date = mariaDb(c) ? "DATETIME(6)" : "TIMESTAMP(6)";
        BoutiquesMigration.executer(c, "CREATE TABLE " + identifiant(c, INSCRIPTION) + " ("
                + "email VARCHAR(254) NOT NULL PRIMARY KEY, empreinte_jeton VARCHAR(64) NOT NULL, "
                + "cree_le " + date + " NOT NULL, expire_le " + date + " NOT NULL, "
                + "CONSTRAINT " + UNIQUE_JETON + " UNIQUE (empreinte_jeton))" + suffixe(c));
    }

    static void creerParametres(Connection c) throws SQLException {
        BoutiquesMigration.executer(c, "CREATE TABLE " + identifiant(c, PARAMETRES)
                + " (id INTEGER NOT NULL PRIMARY KEY, duree_essai_jours INTEGER NOT NULL)" + suffixe(c));
    }

    private static void verifierEssais(Connection c) throws SQLException {
        Map<String, Colonne> colonnes = colonnes(c, "boutique");
        List<String> presentes = ESSAI.stream().filter(colonnes::containsKey).toList();
        for (String nom : presentes) {
            Colonne col = colonnes.get(nom);
            if (col.nullable() != DatabaseMetaData.columnNullable || !defautNul(col.defaut()) || col.autoIncrement() || !dateConforme(c, "boutique", nom, col))
                nonConforme("boutique." + nom + " doit être une date UTC facultative à six décimales, sans défaut ni auto-incrément");
        }
        if (presentes.size() == 1 && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "boutique") + " WHERE " + presentes.getFirst() + " IS NOT NULL") != 0)
            nonConforme("une colonne d'essai isolée contient déjà des dates ; aucune fin ni origine ne sera déduite");
        if (presentes.size() == 2 && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "boutique")
                + " WHERE (essai_debut IS NULL AND essai_fin IS NOT NULL) OR (essai_debut IS NOT NULL AND essai_fin IS NULL) OR essai_fin<=essai_debut") != 0)
            nonConforme("les deux dates d'essai doivent être vides ensemble ou présenter une fin strictement après le début");
    }

    private static void verifierInscriptions(Connection c) throws SQLException {
        Map<String, Colonne> colonnes = colonnes(c, INSCRIPTION);
        if (!colonnes.keySet().equals(CHAMPS_INSCRIPTION)) nonConforme("inscription_boutique nécessite exactement quatre colonnes");
        verifierObligatoires(colonnes);
        verifierTexte(colonnes.get("email"), 254);
        verifierTexte(colonnes.get("empreinte_jeton"), 64);
        for (String nom : List.of("cree_le", "expire_le")) if (!dateConforme(c, INSCRIPTION, nom, colonnes.get(nom))) nonConforme("les dates d'inscription doivent conserver six décimales et utiliser DATETIME sur MariaDB");
        verifierPrimaire(c, INSCRIPTION, "email");
        var uniques = BoutiquesMigration.indexesUniques(c, INSCRIPTION).values();
        if (!uniques.contains(List.of("empreinte_jeton")) || uniques.stream().anyMatch(index -> !Set.of(List.of("email"), List.of("empreinte_jeton")).contains(index)))
            nonConforme("l'empreinte d'inscription doit être unique sans autre unicité restrictive");
        verifierUniqueNomme(c);
        verifierMoteurEtAbsenceRelations(c, INSCRIPTION);
        verifierCharset(c, INSCRIPTION, "email");
        verifierCharset(c, INSCRIPTION, "empreinte_jeton");
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, INSCRIPTION)
                + " WHERE expire_le<=cree_le OR CHAR_LENGTH(empreinte_jeton)<>64 OR empreinte_jeton NOT REGEXP '^[0-9a-fA-F]{64}$'") != 0)
            nonConforme("les empreintes ou dates des inscriptions sont incohérentes");
        try (var factory = Validation.buildDefaultValidatorFactory(); var s = c.createStatement(); var r = s.executeQuery("SELECT email FROM " + table(c, INSCRIPTION))) {
            while (r.next()) {
                String email = r.getString(1);
                InscriptionForm formulaire = new InscriptionForm(); formulaire.setEmail(email);
                if (!Objects.equals(email, Emails.normaliser(email)) || !factory.getValidator().validateProperty(formulaire, "email").isEmpty())
                    nonConforme("les adresses des inscriptions doivent être valides et normalisées ; aucune adresse corrigée");
            }
        }
    }

    private static void verifierParametres(Connection c) throws SQLException {
        Map<String, Colonne> colonnes = colonnes(c, PARAMETRES);
        if (!colonnes.keySet().equals(CHAMPS_PARAMETRES)) nonConforme("parametres_plateforme nécessite exactement id et duree_essai_jours");
        verifierObligatoires(colonnes);
        if (colonnes.values().stream().anyMatch(col -> col.type() != Types.INTEGER || !Set.of("INTEGER", "INT").contains(col.nomType().toUpperCase(Locale.ROOT))))
            nonConforme("les deux paramètres doivent être INTEGER sans unsigned");
        verifierPrimaire(c, PARAMETRES, "id");
        if (BoutiquesMigration.indexesUniques(c, PARAMETRES).values().stream().anyMatch(index -> !index.equals(List.of("id"))))
            nonConforme("seul id doit définir une unicité des paramètres");
        verifierMoteurEtAbsenceRelations(c, PARAMETRES);
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, PARAMETRES) + " WHERE id<>1 OR duree_essai_jours<1 OR duree_essai_jours>3650") != 0)
            nonConforme("le paramètre facultatif doit avoir id=1 et une durée de 1 à 3650 jours");
    }

    private static void verifierObligatoires(Map<String, Colonne> colonnes) {
        if (colonnes.values().stream().anyMatch(col -> col.nullable() != DatabaseMetaData.columnNoNulls || !defautNul(col.defaut()) || col.autoIncrement()))
            nonConforme("les nouvelles tables ont des colonnes obligatoires sans défaut ni auto-incrément");
    }
    private static void verifierTexte(Colonne col, int taille) {
        if (col.type() != Types.VARCHAR || col.taille() != taille) nonConforme("une colonne texte ne correspond pas à la taille VARCHAR attendue");
    }
    private static void verifierPrimaire(Connection c, String nom, String colonne) throws SQLException {
        SortedMap<Integer, String> primaire = new TreeMap<>();
        try (var r = c.getMetaData().getPrimaryKeys(c.getCatalog(), c.getSchema(), BoutiquesMigration.tables(c).get(nom))) {
            while (r.next()) primaire.put(r.getInt("KEY_SEQ"), r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        if (!new ArrayList<>(primaire.values()).equals(List.of(colonne))) nonConforme("la clé primaire de " + nom + " doit porter uniquement sur " + colonne);
    }
    private static void verifierUniqueNomme(Connection c) throws SQLException {
        String sql = "SELECT tc.constraint_name FROM information_schema.table_constraints tc"
                + " JOIN information_schema.key_column_usage ku ON ku.constraint_schema=tc.constraint_schema AND ku.constraint_name=tc.constraint_name AND ku.table_name=tc.table_name"
                + " WHERE tc.table_schema=? AND tc.table_name=? AND tc.constraint_type='UNIQUE'"
                + " GROUP BY tc.constraint_name HAVING COUNT(*)=1 AND LOWER(MAX(ku.column_name))='empreinte_jeton'";
        boolean trouve = false;
        try (var s = c.prepareStatement(sql)) {
            s.setString(1, mariaDb(c) ? c.getCatalog() : c.getSchema()); s.setString(2, BoutiquesMigration.tables(c).get(INSCRIPTION));
            try (var r = s.executeQuery()) { while (r.next()) if (UNIQUE_JETON.equalsIgnoreCase(r.getString(1))) trouve = true; }
        }
        if (!trouve) nonConforme("la contrainte nommée " + UNIQUE_JETON + " est requise sur l'empreinte");
    }
    private static void verifierMoteurEtAbsenceRelations(Connection c, String nom) throws SQLException {
        try (var r = c.getMetaData().getImportedKeys(c.getCatalog(), c.getSchema(), BoutiquesMigration.tables(c).get(nom))) {
            if (r.next()) nonConforme("aucune clé étrangère n'est prévue pour " + nom);
        }
        if (mariaDb(c)) try (var s = c.prepareStatement("SELECT engine FROM information_schema.tables WHERE table_schema=? AND table_name=?")) {
            s.setString(1, c.getCatalog()); s.setString(2, BoutiquesMigration.tables(c).get(nom));
            try (var r = s.executeQuery()) { if (!r.next() || !"InnoDB".equalsIgnoreCase(r.getString(1))) nonConforme("les nouvelles tables doivent utiliser InnoDB"); }
        }
        if (mariaDb(c)) try (var s = c.prepareStatement("SELECT sub_part FROM information_schema.statistics WHERE table_schema=? AND table_name=? AND non_unique=0")) {
            s.setString(1, c.getCatalog()); s.setString(2, BoutiquesMigration.tables(c).get(nom));
            try (var r = s.executeQuery()) { while (r.next()) if (r.getObject(1) != null) nonConforme("les clés et index uniques doivent couvrir les colonnes complètes, sans préfixe"); }
        }
    }
    private static void verifierCharset(Connection c, String nom, String colonne) throws SQLException {
        if (!mariaDb(c)) return;
        try (var s = c.prepareStatement("SELECT character_set_name,collation_name FROM information_schema.columns WHERE table_schema=? AND table_name=? AND LOWER(column_name)=?")) {
            s.setString(1, c.getCatalog()); s.setString(2, BoutiquesMigration.tables(c).get(nom)); s.setString(3, colonne);
            try (var r = s.executeQuery()) {
                if (!r.next() || !"utf8mb4".equalsIgnoreCase(r.getString(1)) || !"utf8mb4_unicode_ci".equalsIgnoreCase(r.getString(2)))
                    nonConforme("les colonnes d'inscription doivent conserver le jeu utf8mb4 et la collation utf8mb4_unicode_ci");
            }
        }
    }
    private static boolean dateConforme(Connection c, String table, String nom, Colonne col) throws SQLException {
        if (col.type() != Types.TIMESTAMP) return false;
        if (!mariaDb(c)) return col.decimales() == 6;
        // MariaDB JDBC laisse DECIMAL_DIGITS vide pour DATETIME(6).
        try (var s = c.prepareStatement("SELECT data_type,datetime_precision FROM information_schema.columns WHERE table_schema=? AND table_name=? AND LOWER(column_name)=?")) {
            s.setString(1, c.getCatalog()); s.setString(2, BoutiquesMigration.tables(c).get(table)); s.setString(3, nom);
            try (var r = s.executeQuery()) {
                if (!r.next() || !"datetime".equalsIgnoreCase(r.getString(1))) return false;
                int precision = r.getInt(2); return !r.wasNull() && precision == 6;
            }
        }
    }

    static Map<String, Colonne> colonnes(Connection c, String nom) throws SQLException {
        String physique = BoutiquesMigration.tables(c).get(nom);
        Map<String, Colonne> resultat = new LinkedHashMap<>();
        if (physique != null) try (var r = c.getMetaData().getColumns(c.getCatalog(), c.getSchema(), physique, "%")) {
            while (r.next()) if (physique.equals(r.getString("TABLE_NAME"))) resultat.put(r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT),
                    new Colonne(r.getInt("DATA_TYPE"), r.getString("TYPE_NAME"), r.getInt("NULLABLE"), r.getString("COLUMN_DEF"), r.getInt("COLUMN_SIZE"), r.getInt("DECIMAL_DIGITS"), "YES".equalsIgnoreCase(r.getString("IS_AUTOINCREMENT"))));
        }
        return resultat;
    }

    /** Les treize anciennes tables, avec toutes leurs anciennes colonnes et les demandes de récupération. */
    static Map<String, List<List<Object>>> photographie(Connection c) throws SQLException {
        Map<String, List<List<Object>>> resultat = new LinkedHashMap<>();
        List<String> noms = new ArrayList<>(BoutiquesMigration.TABLES); noms.add("boutique"); noms.add(ReinitialisationMdpMigration.TABLE);
        for (String nom : noms) {
            List<List<Object>> lignes = new ArrayList<>();
            String ordre = nom.equals("operation_creation") ? "jeton" : nom.equals(ReinitialisationMdpMigration.TABLE) ? "utilisateur_id" : "id";
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table(c, nom) + " ORDER BY " + ordre)) {
                while (r.next()) {
                    List<Object> ligne = new ArrayList<>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) if (!(nom.equals("boutique") && ESSAI.contains(r.getMetaData().getColumnName(i).toLowerCase(Locale.ROOT)))) ligne.add(r.getObject(i));
                    lignes.add(ligne);
                }
            }
            resultat.put(nom, lignes);
        }
        return resultat;
    }
    static List<List<Object>> essais(Connection c) throws SQLException {
        var colonnes = colonnes(c, "boutique");
        String dates = String.join(",", ESSAI.stream().map(nom -> colonnes.containsKey(nom) ? nom : "NULL").toList());
        List<List<Object>> resultat = new ArrayList<>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT id," + dates + " FROM " + table(c, "boutique") + " ORDER BY id")) {
            while (r.next()) resultat.add(Arrays.asList(r.getObject(1), r.getObject(2), r.getObject(3)));
        }
        return resultat;
    }
    static List<List<Object>> donnees(Connection c, String nom) throws SQLException {
        if (!BoutiquesMigration.tables(c).containsKey(nom)) return List.of();
        List<List<Object>> resultat = new ArrayList<>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table(c, nom) + " ORDER BY " + (nom.equals(INSCRIPTION) ? "email" : "id"))) {
            while (r.next()) {
                List<Object> ligne = new ArrayList<>(); for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) ligne.add(r.getObject(i)); resultat.add(ligne);
            }
        }
        return resultat;
    }
    private static boolean defautNul(String defaut) {
        if (defaut == null) return true;
        String valeur = defaut.strip();
        while (valeur.startsWith("(") && valeur.endsWith(")")) valeur = valeur.substring(1, valeur.length() - 1).strip();
        return valeur.equalsIgnoreCase("NULL");
    }
    private static void nonConforme(String raison) { throw new IllegalStateException("Schéma inscription et essai non conforme : " + raison + ". Aucune réparation automatique."); }
    private static boolean mariaDb(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static String suffixe(Connection c) throws SQLException { return mariaDb(c) ? " ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" : ""; }
    private static String table(Connection c, String nom) throws SQLException { return identifiant(c, BoutiquesMigration.tables(c).get(nom)); }
    private static String identifiant(Connection c, String nom) throws SQLException {
        if (nom == null || !nom.matches("[a-zA-Z0-9_]+")) throw new IllegalStateException("Identifiant SQL inattendu ; contrôle manuel requis.");
        String guillemet = c.getMetaData().getIdentifierQuoteString().strip(); return guillemet + nom + guillemet;
    }
}
