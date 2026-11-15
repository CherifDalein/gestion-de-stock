package org.example.stock.migration;

import org.example.stock.compta.PlanOfficiel;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Lot 5a (comptabilité) : crée {@code sous_compte_boutique} et {@code correspondance_compta}, puis donne un sous-compte à chaque compte de
 * trésorerie qui n'en a pas (par exemple 5711 « Caisse » sous 571). Lot 5b : crée aussi {@code reglage_comptable_boutique} (le système des états financiers de la boutique, normal par défaut), y compris sur une base où le 5a est déjà posé. Les écritures ne sont jamais stockées : aucune autre table n'est touchée.
 *
 * <p>Déroulement, chaque étape pouvant être reprise après une interruption : création des tables absentes (avec leurs contraintes), puis un
 * sous-compte pour chaque compte de trésorerie qui n'en a pas, dans l'ordre d'affichage des comptes, avec le plus petit numéro libre sous le
 * compte officiel parent (571 caisse, 552 monnaie électronique, 521 banque) qui n'est ni un compte officiel ni déjà pris dans la boutique. Un
 * sous-compte existant, une ligne de correspondance, un sous-compte ouvert à la main ne sont jamais réécrits. Toute autre table est comparée
 * avant et après. Les lots 1 à 4 et le lot 6 doivent être terminés. Syntaxes acceptées par MariaDB 10.4.</p>
 */
public final class Lot5Migration {
    static final String VERROU = "stock_lot5_migration";
    static final String SOUS_COMPTES = "sous_compte_boutique", CORRESPONDANCES = "correspondance_compta", REGLAGES = "reglage_comptable_boutique";
    static final List<String> TABLES = List.of(SOUS_COMPTES, CORRESPONDANCES, REGLAGES);
    private static final String MOTEUR = " ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";

    /** Colonne attendue : types JDBC acceptés, taille (0 si sans objet) et caractère facultatif. */
    record Colonne(String table, String nom, Set<Integer> types, int taille, boolean facultative) {
        String cle() { return table + "." + nom; }
    }

    static final List<Colonne> COLONNES = List.of(
            new Colonne(SOUS_COMPTES, "id", Set.of(Types.BIGINT), 0, false), new Colonne(SOUS_COMPTES, "boutique_id", Set.of(Types.BIGINT), 0, false),
            new Colonne(SOUS_COMPTES, "numero", Set.of(Types.VARCHAR), 8, false), new Colonne(SOUS_COMPTES, "parent_numero", Set.of(Types.VARCHAR), 4, false),
            new Colonne(SOUS_COMPTES, "intitule", Set.of(Types.VARCHAR), 120, false), new Colonne(SOUS_COMPTES, "compte_tresorerie_id", Set.of(Types.BIGINT), 0, true),
            new Colonne(SOUS_COMPTES, "date_creation", Set.of(Types.TIMESTAMP), 0, false),
            new Colonne(CORRESPONDANCES, "id", Set.of(Types.BIGINT), 0, false), new Colonne(CORRESPONDANCES, "boutique_id", Set.of(Types.BIGINT), 0, false),
            new Colonne(CORRESPONDANCES, "cle", Set.of(Types.VARCHAR), 40, false), new Colonne(CORRESPONDANCES, "compte_numero", Set.of(Types.VARCHAR), 8, false),
            new Colonne(CORRESPONDANCES, "mots_cles", Set.of(Types.VARCHAR), 255, true),
            new Colonne(REGLAGES, "id", Set.of(Types.BIGINT), 0, false), new Colonne(REGLAGES, "boutique_id", Set.of(Types.BIGINT), 0, false),
            new Colonne(REGLAGES, "systeme", Set.of(Types.VARCHAR), 10, false));

    /** Unicités attendues : table → colonnes de chaque contrainte unique (hors clé primaire). */
    static final Map<String, List<List<String>>> UNICITES = Map.of(
            SOUS_COMPTES, List.of(List.of("boutique_id", "numero"), List.of("compte_tresorerie_id")),
            CORRESPONDANCES, List.of(List.of("boutique_id", "cle")), REGLAGES, List.of(List.of("boutique_id")));

    /** Clés étrangères attendues : table.colonne → table cible. */
    static final Map<String, String> CLES_ETRANGERES = Map.of(
            SOUS_COMPTES + ".boutique_id", "boutique", SOUS_COMPTES + ".compte_tresorerie_id", "compte_tresorerie_boutique", CORRESPONDANCES + ".boutique_id", "boutique", REGLAGES + ".boutique_id", "boutique");

    /** Compte officiel parent du sous-compte de chaque type de compte de trésorerie. */
    static final Map<String, String> PARENTS = Map.of("ESPECES", "571", "ORANGE_MONEY", "552", "BANQUE", "521");

    private Lot5Migration() {}

    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        if (args.length != 1 || !Set.of("--check", "--apply").contains(args[0])) throw new IllegalArgumentException("Utiliser --check ou --apply");
        try {
            var configuration = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { configuration.load(lecteur); }
            String url = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.url")).replace("createDatabaseIfNotExist=true", "createDatabaseIfNotExist=false");
            boolean appliquer = args[0].equals("--apply");
            if (appliquer) verifierAutorisationApplication(url, System.getenv("LOT5_BACKUP_DIR"));
            try (var c = DriverManager.getConnection(url, AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.password")))) { migrer(c, appliquer); }
        } catch (Exception e) {
            // Les pilotes peuvent inclure des valeurs métier dans leurs exceptions.
            System.err.println("Migration lot 5 interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Connexion ou opération SQL impossible ; aucune donnée privée publiée."));
            System.exit(1);
        }
    }

    static void verifierAutorisationApplication(String url, String dossier) throws Exception {
        BoutiquesMigration.verifierCibleSauvegarde(url);
        if (dossier == null || dossier.isBlank()) throw new IllegalStateException("Une sauvegarde SQL complète est requise : définir LOT5_BACKUP_DIR sur le répertoire créé par scripts/backup-stock-local.sh.");
        BoutiquesMigration.verifierSauvegarde(dossier);
    }

    static void migrer(Connection c, boolean appliquer) throws SQLException {
        if (!Set.of("H2", "MariaDB").contains(c.getMetaData().getDatabaseProductName())) throw new IllegalStateException("MariaDB ou H2 requis.");
        if (!c.getAutoCommit()) throw new IllegalStateException("Connexion dédiée sans transaction ouverte requise.");
        boolean verrou = false;
        try {
            c.setReadOnly(!appliquer);
            if (appliquer && maria(c)) {
                if (BoutiquesMigration.compter(c, "SELECT GET_LOCK('" + VERROU + "',0)") != 1) throw new IllegalStateException("Une migration lot 5 est déjà en cours.");
                verrou = true;
            }
            exigerLot6(c);
            c.setReadOnly(!appliquer);
            verifierPresents(c);
            if (!appliquer) {
                System.out.println("Lot 5 vérifié en lecture seule ; éléments manquants : " + manquants(c) + ". Aucune donnée modifiée.");
                return;
            }
            var avant = photographie(c); var schemas = schemasAnciens(c);
            boolean dejaFinalise = finalise(c);
            var sousComptes = sousComptesExistants(c); var correspondances = lignesCorrespondance(c);
            creerTables(c);
            remplirSousComptes(c);
            verifierPresents(c);
            var sousComptesApres = sousComptesExistants(c);
            boolean conserves = sousComptes.entrySet().stream().allMatch(e -> e.getValue().equals(sousComptesApres.get(e.getKey())))
                    && lignesCorrespondance(c).containsAll(correspondances);
            if (manquants(c) != 0 || !finalise(c) || !avant.equals(photographie(c)) || !schemas.equals(schemasAnciens(c)) || !conserves)
                nonConforme("des données ou colonnes existantes ont changé ; conserver l'application arrêtée et examiner la sauvegarde");
            System.out.println(dejaFinalise ? "Lot 5 déjà migré ; aucune valeur réécrite."
                    : "Migration lot 5 terminée ; un sous-compte par compte de trésorerie, aucune autre donnée modifiée.");
        } finally { if (verrou) BoutiquesMigration.compter(c, "SELECT RELEASE_LOCK('" + VERROU + "')"); }
    }

    /** Le lot 5a s'applique après le lot 6 (donc après 1 à 4) : refus explicite tant qu'ils ne sont pas terminés et conformes. */
    private static void exigerLot6(Connection c) throws SQLException {
        if (!BoutiquesMigration.tables(c).containsKey("boutique") || !Lot1Migration.finalise(c) || !Lot2Migration.finalise(c) || !Lot4Migration.finalise(c)
                || !Lot3Migration.finalise(c) || !Lot6Migration.finalise(c))
            throw new IllegalStateException("La migration du lot 6 (et les lots 1 à 4) doit être terminée avant le lot 5 : lancer lot6Check puis lot6Migrate (LOT6_BACKUP_DIR) après sauvegarde.");
        Lot6Migration.migrer(c, false);
    }

    // ---- Création --------------------------------------------------------------------------------------------------

    static void creerTables(Connection c) throws SQLException {
        String moteur = maria(c) ? MOTEUR : "";
        var tables = BoutiquesMigration.tables(c);
        if (!tables.containsKey(SOUS_COMPTES)) sql(c, "CREATE TABLE " + SOUS_COMPTES + " (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, boutique_id BIGINT NOT NULL, numero VARCHAR(8) NOT NULL,"
                + " parent_numero VARCHAR(4) NOT NULL, intitule VARCHAR(120) NOT NULL, compte_tresorerie_id BIGINT NULL, date_creation DATETIME(6) NOT NULL,"
                + " CONSTRAINT uk_sous_compte_boutique_numero UNIQUE (boutique_id, numero), CONSTRAINT uk_sous_compte_compte_tresorerie UNIQUE (compte_tresorerie_id),"
                + " CONSTRAINT fk_sous_compte_boutique FOREIGN KEY (boutique_id) REFERENCES boutique (id),"
                + " CONSTRAINT fk_sous_compte_compte_tresorerie FOREIGN KEY (compte_tresorerie_id) REFERENCES compte_tresorerie_boutique (id))" + moteur);
        if (!tables.containsKey(CORRESPONDANCES)) sql(c, "CREATE TABLE " + CORRESPONDANCES + " (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, boutique_id BIGINT NOT NULL, cle VARCHAR(40) NOT NULL,"
                + " compte_numero VARCHAR(8) NOT NULL, mots_cles VARCHAR(255) NULL, CONSTRAINT uk_correspondance_compta_cle UNIQUE (boutique_id, cle),"
                + " CONSTRAINT fk_correspondance_compta_boutique FOREIGN KEY (boutique_id) REFERENCES boutique (id))" + moteur);
        if (!tables.containsKey(REGLAGES)) sql(c, "CREATE TABLE " + REGLAGES + " (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, boutique_id BIGINT NOT NULL, systeme VARCHAR(10) NOT NULL,"
                + " CONSTRAINT uk_reglage_comptable_boutique UNIQUE (boutique_id), CONSTRAINT fk_reglage_comptable_boutique FOREIGN KEY (boutique_id) REFERENCES boutique (id))" + moteur);
    }

    /**
     * Un sous-compte pour chaque compte de trésorerie qui n'en a pas, dans l'ordre d'affichage (type, ordre, identifiant), avec le plus petit
     * numéro libre sous le parent : la même règle que l'application, qui crée les sous-comptes manquants à l'ouverture de la comptabilité.
     */
    static void remplirSousComptes(Connection c) throws SQLException {
        var plan = PlanOfficiel.charger();
        var pris = new HashMap<Long, Set<String>>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT boutique_id, numero FROM " + SOUS_COMPTES)) {
            while (r.next()) pris.computeIfAbsent(r.getLong(1), k -> new HashSet<>()).add(r.getString(2));
        }
        record Compte(long id, long boutique, String type, String nom) {}
        var a_creer = new ArrayList<Compte>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT id, boutique_id, type, nom FROM compte_tresorerie_boutique WHERE id NOT IN (SELECT compte_tresorerie_id FROM "
                + SOUS_COMPTES + " WHERE compte_tresorerie_id IS NOT NULL) ORDER BY boutique_id, CASE type WHEN 'ESPECES' THEN 0 WHEN 'ORANGE_MONEY' THEN 1 ELSE 2 END, ordre, id")) {
            while (r.next()) a_creer.add(new Compte(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4)));
        }
        try (var insertion = c.prepareStatement("INSERT INTO " + SOUS_COMPTES + "(boutique_id, numero, parent_numero, intitule, compte_tresorerie_id, date_creation) VALUES(?,?,?,?,?,?)")) {
            for (Compte compte : a_creer) {
                String parent = PARENTS.get(compte.type());
                if (parent == null) throw new IllegalStateException("Type de compte de trésorerie inconnu : le sous-compte ne peut pas être créé.");
                var utilises = pris.computeIfAbsent(compte.boutique(), k -> new HashSet<>());
                String numero = null;
                for (int i = 1; i < 100000 && (parent + i).length() <= 8; i++) {
                    String candidat = parent + i;
                    if (!plan.existe(candidat) && !utilises.contains(candidat)) { numero = candidat; break; }
                }
                if (numero == null) throw new IllegalStateException("Plus de numéro libre sous le compte " + parent + ".");
                utilises.add(numero);
                String nom = compte.nom() == null ? "" : compte.nom().strip();
                insertion.setLong(1, compte.boutique()); insertion.setString(2, numero); insertion.setString(3, parent);
                insertion.setString(4, nom.length() > 120 ? nom.substring(0, 120) : nom); insertion.setLong(5, compte.id());
                insertion.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
                insertion.executeUpdate();
            }
        }
    }

    // ---- Contrôles -------------------------------------------------------------------------------------------------

    static boolean finalise(Connection c) throws SQLException { return manquants(c) == 0; }

    /** Éléments à ajouter : tables absentes et comptes de trésorerie sans sous-compte. */
    private static int manquants(Connection c) throws SQLException {
        var tables = BoutiquesMigration.tables(c);
        int manquants = 0;
        for (String t : TABLES) if (!tables.containsKey(t)) manquants++;
        if (tables.containsKey(SOUS_COMPTES) && tables.containsKey("compte_tresorerie_boutique"))
            manquants += (int) BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique WHERE id NOT IN (SELECT compte_tresorerie_id FROM " + SOUS_COMPTES + " WHERE compte_tresorerie_id IS NOT NULL)");
        else if (tables.containsKey("compte_tresorerie_boutique")) manquants += (int) BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique");
        return manquants;
    }

    private static void verifierPresents(Connection c) throws SQLException {
        var tables = BoutiquesMigration.tables(c);
        for (Colonne attendue : COLONNES) {
            if (!tables.containsKey(attendue.table())) continue;
            var col = InscriptionEssaiMigration.colonnes(c, attendue.table()).get(attendue.nom());
            if (col == null) nonConforme(attendue.cle() + " est absente");
            boolean facultative = col.nullable() == DatabaseMetaData.columnNullable;
            if (!attendue.types().contains(col.type()) || attendue.taille() > 0 && col.taille() != attendue.taille() || facultative != attendue.facultative()
                    && !attendue.nom().equals("id"))
                nonConforme(attendue.cle() + " n'a pas la forme attendue");
        }
        for (String table : TABLES) {
            if (!tables.containsKey(table)) continue;
            var uniques = BoutiquesMigration.indexesUniques(c, table).values();
            for (var colonnes : UNICITES.get(table)) if (uniques.stream().noneMatch(colonnes::equals)) nonConforme(table + " : unicité " + colonnes + " absente");
            var cles = clesEtrangeres(c, table);
            for (var cle : CLES_ETRANGERES.entrySet()) if (cle.getKey().startsWith(table + ".") && !cle.getValue().equals(cles.get(cle.getKey().substring(table.length() + 1))))
                nonConforme(cle.getKey() + " doit viser " + cle.getValue());
        }
        if (tables.containsKey(SOUS_COMPTES)) verifierSousComptes(c);
        if (tables.containsKey(CORRESPONDANCES) && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + CORRESPONDANCES + " WHERE compte_numero = '' OR cle = ''") != 0)
            nonConforme("une ligne de correspondance a une clé ou un compte vide");
        if (tables.containsKey(REGLAGES) && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + REGLAGES + " WHERE systeme NOT IN ('NORMAL', 'MINIMAL')") != 0)
            nonConforme("un réglage comptable a un système inconnu");
    }

    /** Un sous-compte commence par son parent, n'est jamais un compte officiel, et celui d'un compte de trésorerie suit son type et sa boutique. */
    private static void verifierSousComptes(Connection c) throws SQLException {
        var plan = PlanOfficiel.charger();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT s.numero, s.parent_numero, s.boutique_id, t.type, t.boutique_id FROM " + SOUS_COMPTES
                + " s LEFT JOIN compte_tresorerie_boutique t ON t.id = s.compte_tresorerie_id")) {
            while (r.next()) {
                String numero = r.getString(1), parent = r.getString(2);
                if (!numero.matches("[0-9]{3,8}") || !numero.startsWith(parent) || numero.equals(parent)) nonConforme("le sous-compte " + numero + " doit commencer par son compte parent " + parent);
                if (!plan.existe(parent)) nonConforme("le compte parent " + parent + " n'est pas un compte officiel");
                if (plan.existe(numero)) nonConforme("le sous-compte " + numero + " reprend un numéro officiel");
                if (r.getString(4) != null && (!parent.equals(PARENTS.get(r.getString(4))) || r.getLong(3) != r.getLong(5)))
                    nonConforme("le sous-compte " + numero + " ne correspond pas au type ou à la boutique de son compte de trésorerie");
            }
        }
    }

    private static Map<String, String> clesEtrangeres(Connection c, String table) throws SQLException {
        var resultat = new TreeMap<String, String>();
        try (var r = c.getMetaData().getImportedKeys(c.getCatalog(), c.getSchema(), BoutiquesMigration.tables(c).get(table))) {
            while (r.next()) resultat.put(r.getString("FKCOLUMN_NAME").toLowerCase(Locale.ROOT), r.getString("PKTABLE_NAME").toLowerCase(Locale.ROOT));
        }
        return resultat;
    }

    /** Toutes les tables hors lot 5 et toutes leurs colonnes : rien ne doit changer. */
    static Map<String, List<List<Object>>> photographie(Connection c) throws SQLException {
        var resultat = new TreeMap<String, List<List<Object>>>();
        for (String nom : BoutiquesMigration.tables(c).keySet()) {
            if (TABLES.contains(nom)) continue;
            var lignes = new ArrayList<List<Object>>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table(c, nom) + " ORDER BY " + PhotographieJdbc.ordre(c, nom))) {
                while (r.next()) {
                    var ligne = new ArrayList<Object>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) ligne.add(PhotographieJdbc.valeur(r, i));
                    lignes.add(ligne);
                }
            }
            resultat.put(nom, lignes);
        }
        return resultat;
    }

    static Map<String, Map<String, InscriptionEssaiMigration.Colonne>> schemasAnciens(Connection c) throws SQLException {
        var resultat = new TreeMap<String, Map<String, InscriptionEssaiMigration.Colonne>>();
        for (String nom : BoutiquesMigration.tables(c).keySet()) if (!TABLES.contains(nom)) resultat.put(nom, new TreeMap<>(InscriptionEssaiMigration.colonnes(c, nom)));
        return resultat;
    }

    /** Sous-comptes déjà présents, par identifiant : ils ne doivent jamais être réécrits (la date de création comprise). */
    static Map<Long, List<Object>> sousComptesExistants(Connection c) throws SQLException {
        var resultat = new TreeMap<Long, List<Object>>();
        if (!BoutiquesMigration.tables(c).containsKey(SOUS_COMPTES)) return resultat;
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT id, boutique_id, numero, parent_numero, intitule, compte_tresorerie_id, date_creation FROM " + SOUS_COMPTES + " ORDER BY id")) {
            while (r.next()) resultat.put(r.getLong(1), Arrays.asList(r.getLong(2), r.getString(3), r.getString(4), r.getString(5), r.getObject(6) == null ? null : r.getLong(6), String.valueOf(r.getObject(7))));
        }
        return resultat;
    }

    static List<List<Object>> lignesCorrespondance(Connection c) throws SQLException {
        var resultat = new ArrayList<List<Object>>();
        if (!BoutiquesMigration.tables(c).containsKey(CORRESPONDANCES)) return resultat;
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT id, boutique_id, cle, compte_numero, mots_cles FROM " + CORRESPONDANCES + " ORDER BY id")) {
            while (r.next()) resultat.add(Arrays.asList(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getString(5)));
        }
        return resultat;
    }

    private static boolean maria(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static String table(Connection c, String nom) throws SQLException {
        String physique = BoutiquesMigration.tables(c).get(nom);
        if (physique == null || !physique.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("Identifiant SQL inattendu.");
        String q = c.getMetaData().getIdentifierQuoteString().strip(); return q + physique + q;
    }
    static void sql(Connection c, String requete) throws SQLException { BoutiquesMigration.executer(c, requete); }
    private static void nonConforme(String raison) { throw new IllegalStateException("Schéma lot 5 non conforme : " + raison + ". Aucune réparation automatique."); }
}
