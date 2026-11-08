package org.example.stock.migration;

import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Tables et colonnes du lot 2 (devis, retours et avoirs, vendeur des ventes, annulation des règlements).
 * Les quatre tables sont créées vides. Seules deux colonnes reprennent l'existant, {@code vente.vendeur_id} et
 * {@code achat.utilisateur_id}, remplies dans une seule transaction avec l'auteur du premier mouvement de caisse nominatif.
 * {@code boutique.prochain_numero_retour_achat NOT NULL} marque la fin de l'initialisation et interdit toute nouvelle
 * écriture de ces valeurs lors d'une relance. Aucun stock, montant ou mouvement n'est modifié.
 * Seules les syntaxes acceptées par MariaDB 10.4 sont employées.
 */
public final class Lot2Migration {
    static final String VERROU = "stock_lot2_migration";
    static final String DEVIS = "devis", DETAIL_DEVIS = "detail_devis", RETOUR = "retour", DETAIL_RETOUR = "detail_retour";
    /** Dans l'ordre de création : chaque table ne référence que les précédentes et les tables existantes. */
    static final List<String> NOUVELLES_TABLES = List.of(DEVIS, DETAIL_DEVIS, RETOUR, DETAIL_RETOUR);
    static final Set<String> STATUTS_DEVIS = Set.of("EN_COURS", "TRANSFORME", "ANNULE");
    static final Set<String> TYPES_RETOUR = Set.of("VENTE", "ACHAT");
    static final Set<String> COMPTES = Set.of("CAISSE", "ORANGE_MONEY", "BANQUE");

    private static final Set<Integer> DECIMAL = Set.of(Types.DECIMAL, Types.NUMERIC);
    private static final Set<Integer> BIGINT = Set.of(Types.BIGINT);
    private static final Set<Integer> TEXTE = Set.of(Types.VARCHAR);

    /** Clé étrangère simple vers {@code id} d'une autre table, sans cascade. */
    record Cle(String table, String colonne, String nom, String reference) {}

    /** Table nouvelle : colonnes (hors {@code id}), contraintes uniques et clés étrangères nommées. */
    record Table(String nom, List<Lot1Migration.Champ> champs, Map<String, List<String>> uniques, List<Cle> cles) {
        Set<String> noms() {
            var resultat = new TreeSet<>(List.of("id"));
            for (var champ : champs) resultat.add(champ.nom());
            return resultat;
        }
        Set<List<String>> indexUniques() {
            var resultat = new HashSet<List<String>>(); resultat.add(List.of("id")); resultat.addAll(uniques.values());
            return resultat;
        }
    }

    private static Lot1Migration.Champ champ(String table, String nom, String definition, Set<Integer> types, int taille, int decimales, boolean nullable, String defaut) {
        return new Lot1Migration.Champ(table, nom, definition, null, types, taille, decimales, nullable, defaut);
    }
    private static Lot1Migration.Champ id(String table, String nom, boolean nullable) {
        return champ(table, nom, nullable ? "BIGINT NULL" : "BIGINT NOT NULL", BIGINT, 0, 0, nullable, null);
    }
    private static Lot1Migration.Champ texte(String table, String nom, int taille, boolean nullable) {
        return champ(table, nom, "VARCHAR(" + taille + ")" + (nullable ? " NULL" : " NOT NULL"), TEXTE, taille, 0, nullable, null);
    }
    private static Lot1Migration.Champ montant(String table, String nom, boolean nullable, boolean defautZero) {
        return champ(table, nom, "DECIMAL(17,2)" + (defautZero ? " DEFAULT 0" : "") + (nullable ? " NULL" : " NOT NULL"), DECIMAL, 17, 2, nullable, defautZero ? "0" : null);
    }
    private static Lot1Migration.Champ date(String table, String nom, String type, Set<Integer> types, int decimales) {
        return champ(table, nom, type + " NOT NULL", types, 0, decimales, false, null);
    }
    private static Lot1Migration.Champ entier(String table, String nom) {
        return champ(table, nom, "INT NOT NULL", Set.of(Types.INTEGER), 0, 0, false, null);
    }

    static final List<Table> TABLES = List.of(
            new Table(DEVIS, List.of(id(DEVIS, "boutique_id", false), texte(DEVIS, "numero", 30, false), id(DEVIS, "client_id", true), id(DEVIS, "utilisateur_id", true),
                    date(DEVIS, "date_devis", "DATETIME(6)", Set.of(Types.TIMESTAMP), 6), date(DEVIS, "date_validite", "DATE", Set.of(Types.DATE), 0),
                    montant(DEVIS, "montant_total", false, false), texte(DEVIS, "statut", 16, false), id(DEVIS, "vente_id", true), texte(DEVIS, "note", 255, true),
                    champ(DEVIS, "version", "BIGINT DEFAULT 0 NOT NULL", BIGINT, 0, 0, false, "0")),
                new TreeMap<>(Map.of("uk_devis_boutique_numero", List.of("boutique_id", "numero"), "uk_devis_vente", List.of("vente_id"))),
                List.of(new Cle(DEVIS, "boutique_id", "fk_devis_boutique", "boutique"), new Cle(DEVIS, "client_id", "fk_devis_client", "client"),
                        new Cle(DEVIS, "utilisateur_id", "fk_devis_utilisateur", "utilisateur"), new Cle(DEVIS, "vente_id", "fk_devis_vente", "vente"))),
            new Table(DETAIL_DEVIS, List.of(id(DETAIL_DEVIS, "devis_id", false), id(DETAIL_DEVIS, "produit_id", false), entier(DETAIL_DEVIS, "quantite"),
                    montant(DETAIL_DEVIS, "prix_unitaire", false, false)), Map.of(),
                    List.of(new Cle(DETAIL_DEVIS, "devis_id", "fk_detail_devis_devis", DEVIS), new Cle(DETAIL_DEVIS, "produit_id", "fk_detail_devis_produit", "produit"))),
            new Table(RETOUR, List.of(id(RETOUR, "boutique_id", false), texte(RETOUR, "numero", 30, false), texte(RETOUR, "type", 16, false),
                    id(RETOUR, "vente_id", true), id(RETOUR, "achat_id", true), id(RETOUR, "client_id", true), id(RETOUR, "fournisseur_id", true), id(RETOUR, "utilisateur_id", true),
                    date(RETOUR, "date_retour", "DATETIME(6)", Set.of(Types.TIMESTAMP), 6), montant(RETOUR, "montant_total", false, false),
                    montant(RETOUR, "montant_rembourse", false, true), texte(RETOUR, "compte_tresorerie", 16, true), montant(RETOUR, "montant_avoir", false, true),
                    texte(RETOUR, "motif", 255, true)),
                    Map.of("uk_retour_boutique_numero", List.of("boutique_id", "numero")),
                    List.of(new Cle(RETOUR, "boutique_id", "fk_retour_boutique", "boutique"), new Cle(RETOUR, "vente_id", "fk_retour_vente", "vente"),
                            new Cle(RETOUR, "achat_id", "fk_retour_achat", "achat"), new Cle(RETOUR, "client_id", "fk_retour_client", "client"),
                            new Cle(RETOUR, "fournisseur_id", "fk_retour_fournisseur", "fournisseur"), new Cle(RETOUR, "utilisateur_id", "fk_retour_utilisateur", "utilisateur"))),
            new Table(DETAIL_RETOUR, List.of(id(DETAIL_RETOUR, "retour_id", false), id(DETAIL_RETOUR, "produit_id", false), id(DETAIL_RETOUR, "detail_vente_id", true),
                    id(DETAIL_RETOUR, "detail_achat_id", true), entier(DETAIL_RETOUR, "quantite"), montant(DETAIL_RETOUR, "prix_unitaire", false, false),
                    montant(DETAIL_RETOUR, "cout_unitaire", true, false)), Map.of(),
                    List.of(new Cle(DETAIL_RETOUR, "retour_id", "fk_detail_retour_retour", RETOUR), new Cle(DETAIL_RETOUR, "produit_id", "fk_detail_retour_produit", "produit"),
                            new Cle(DETAIL_RETOUR, "detail_vente_id", "fk_detail_retour_detail_vente", "detail_vente"),
                            new Cle(DETAIL_RETOUR, "detail_achat_id", "fk_detail_retour_detail_achat", "detail_achat"))));

    /**
     * Colonnes ajoutées aux tables existantes. La forme facultative {@code intermediaire} précède l'initialisation :
     * le compteur des retours d'achat est le marqueur de fin.
     */
    static final List<Lot1Migration.Champ> CHAMPS = List.of(
            new Lot1Migration.Champ("vente", "vendeur_id", "BIGINT NULL", "BIGINT NULL", BIGINT, 0, 0, true, null),
            new Lot1Migration.Champ("achat", "utilisateur_id", "BIGINT NULL", "BIGINT NULL", BIGINT, 0, 0, true, null),
            new Lot1Migration.Champ("mouvement_caisse", "retour_id", "BIGINT NULL", null, BIGINT, 0, 0, true, null),
            new Lot1Migration.Champ("mouvement_caisse", "annulation_de_id", "BIGINT NULL", null, BIGINT, 0, 0, true, null),
            new Lot1Migration.Champ("boutique", "prochain_numero_devis", "BIGINT DEFAULT 1 NOT NULL", null, BIGINT, 0, 0, false, "1"),
            new Lot1Migration.Champ("boutique", "prochain_numero_retour_vente", "BIGINT DEFAULT 1 NOT NULL", null, BIGINT, 0, 0, false, "1"),
            new Lot1Migration.Champ("boutique", "prochain_numero_retour_achat", "BIGINT DEFAULT 1 NOT NULL", "BIGINT NULL", BIGINT, 0, 0, false, "1"));
    private static final Lot1Migration.Champ MARQUEUR = CHAMPS.get(6);
    /** Clés étrangères des colonnes ajoutées aux tables existantes. */
    static final List<Cle> CLES = List.of(
            new Cle("vente", "vendeur_id", "fk_vente_vendeur", "utilisateur"), new Cle("achat", "utilisateur_id", "fk_achat_utilisateur", "utilisateur"),
            new Cle("mouvement_caisse", "retour_id", "fk_mouvement_caisse_retour", RETOUR),
            new Cle("mouvement_caisse", "annulation_de_id", "fk_mouvement_caisse_annulation", "mouvement_caisse"));
    static final String UNIQUE_ANNULATION = "uk_mouvement_caisse_annulation";
    private Lot2Migration() {}

    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        if (args.length != 1 || !Set.of("--check", "--apply").contains(args[0])) throw new IllegalArgumentException("Utiliser --check ou --apply");
        try {
            var configuration = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { configuration.load(lecteur); }
            String url = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.url")).replace("createDatabaseIfNotExist=true", "createDatabaseIfNotExist=false");
            boolean appliquer = args[0].equals("--apply");
            if (appliquer) verifierAutorisationApplication(url, System.getenv("LOT2_BACKUP_DIR"));
            try (var c = DriverManager.getConnection(url, AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.password")))) { migrer(c, appliquer); }
        } catch (Exception e) {
            // Les pilotes peuvent inclure des valeurs métier dans leurs exceptions.
            System.err.println("Migration lot 2 interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Connexion ou opération SQL impossible ; aucune donnée privée publiée."));
            System.exit(1);
        }
    }

    static void verifierAutorisationApplication(String url, String dossier) throws Exception {
        BoutiquesMigration.verifierCibleSauvegarde(url);
        if (dossier == null || dossier.isBlank()) throw new IllegalStateException("Une sauvegarde SQL complète est requise : définir LOT2_BACKUP_DIR sur le répertoire créé par scripts/backup-stock-local.sh.");
        BoutiquesMigration.verifierSauvegarde(dossier);
    }

    static void migrer(Connection c, boolean appliquer) throws SQLException {
        if (!Set.of("H2", "MariaDB").contains(c.getMetaData().getDatabaseProductName())) throw new IllegalStateException("MariaDB ou H2 requis.");
        if (!c.getAutoCommit()) throw new IllegalStateException("Connexion dédiée sans transaction ouverte requise.");
        boolean verrou = false;
        try {
            c.setReadOnly(!appliquer);
            if (appliquer && maria(c)) {
                if (BoutiquesMigration.compter(c, "SELECT GET_LOCK('" + VERROU + "',0)") != 1) throw new IllegalStateException("Une migration lot 2 est déjà en cours.");
                verrou = true;
            }
            exigerLot1(c);
            c.setReadOnly(!appliquer);
            verifierPresents(c);
            if (!appliquer) {
                System.out.println("Lot 2 vérifié en lecture seule ; éléments manquants : " + manquants(c) + (finalise(c) ? ", initialisation terminée"
                        : ", lignes à initialiser : " + aInitialiser(c)) + ". Aucun stock, montant ni historique modifié.");
                return;
            }
            var avant = photographie(c); var schemas = schemasAnciens(c); var tables = donneesNouvelles(c);
            boolean dejaFinalise = finalise(c);
            var valeurs = dejaFinalise ? valeursLot2(c) : null;
            if (!dejaFinalise) {
                creerTables(c);
                ajouterColonnes(c);
                verifierPresents(c);
                initialiser(c);
                verifierInitialisation(c);
                ajouterUniques(c);
                ajouterCles(c);
                finaliser(c);
            }
            verifierPresents(c);
            if (manquants(c) != 0 || !finalise(c) || !avant.equals(photographie(c)) || !schemas.equals(schemasAnciens(c)) || !donneesNouvelles(c).entrySet().containsAll(tables.entrySet())
                    || valeurs != null && !valeurs.equals(valeursLot2(c)))
                nonConforme("des données ou colonnes existantes ont changé ; conserver l'application arrêtée et examiner la sauvegarde");
            System.out.println(dejaFinalise ? "Lot 2 déjà migré ; aucune valeur réécrite."
                    : "Migration lot 2 terminée ; quatre tables créées vides, stocks, montants et mouvements inchangés, auteurs des documents historiques repris.");
        } finally { if (verrou) BoutiquesMigration.compter(c, "SELECT RELEASE_LOCK('" + VERROU + "')"); }
    }

    /** Le lot 2 s'appuie sur les colonnes du lot 1 : refus explicite tant que celui-ci n'est pas terminé et conforme. */
    private static void exigerLot1(Connection c) throws SQLException {
        if (!BoutiquesMigration.tables(c).containsKey("boutique") || !Lot1Migration.finalise(c))
            throw new IllegalStateException("La migration du lot 1 doit être terminée avant le lot 2 : lancer lot1Check puis lot1Migrate (LOT1_BACKUP_DIR) après sauvegarde.");
        Lot1Migration.migrer(c, false);
    }

    /** Chaque table absente est créée avec toutes ses contraintes ; une table déjà présente est seulement contrôlée. */
    static void creerTables(Connection c) throws SQLException {
        for (var table : TABLES) if (!existe(c, table.nom())) creerTable(c, table);
    }

    static void creerTable(Connection c, Table table) throws SQLException {
        var definition = new StringBuilder("CREATE TABLE " + table.nom() + " (id BIGINT NOT NULL " + identite(c) + " PRIMARY KEY");
        for (var champ : table.champs()) definition.append(',').append(champ.nom()).append(' ').append(champ.definition());
        for (var e : table.uniques().entrySet()) definition.append(",CONSTRAINT ").append(e.getKey()).append(" UNIQUE(").append(String.join(",", e.getValue())).append(')');
        for (var cle : table.cles()) definition.append(",CONSTRAINT ").append(cle.nom()).append(" FOREIGN KEY(").append(cle.colonne()).append(") REFERENCES ")
                .append(table(c, cle.reference())).append("(id)");
        sql(c, definition.append(')').append(suffixe(c)).toString());
    }

    /** Ajoute chaque colonne absente : forme finale si aucune donnée n'est à reprendre, sinon forme facultative. */
    static void ajouterColonnes(Connection c) throws SQLException {
        for (var champ : CHAMPS) if (!colonnes(c, champ.table()).containsKey(champ.nom()))
            sql(c, "ALTER TABLE " + table(c, champ.table()) + " ADD COLUMN " + champ.nom() + " " + (champ.initialise() ? champ.intermediaire() : champ.definition()));
    }

    /** Une seule transaction : une interruption n'enregistre aucune valeur partielle. Seules les valeurs vides sont écrites. */
    static void initialiser(Connection c) throws SQLException {
        c.setAutoCommit(false);
        boolean valide = false;
        try {
            reprendreAuteurs(c, "vente", "vente_id", "vendeur_id");
            reprendreAuteurs(c, "achat", "achat_id", "utilisateur_id");
            sql(c, "UPDATE " + table(c, "boutique") + " SET prochain_numero_retour_achat=1 WHERE prochain_numero_retour_achat IS NULL");
            c.commit(); valide = true;
        } finally {
            if (!valide) c.rollback();
            c.setAutoCommit(true);
        }
    }

    /** Premier mouvement (plus petit identifiant) rattaché au document qui a un utilisateur ; sans lui, la colonne reste vide. */
    private static void reprendreAuteurs(Connection c, String document, String lien, String colonne) throws SQLException {
        var auteurs = new LinkedHashMap<Long, Long>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT " + lien + ",utilisateur_id FROM " + table(c, "mouvement_caisse")
                + " WHERE " + lien + " IS NOT NULL AND utilisateur_id IS NOT NULL ORDER BY id")) {
            while (r.next()) auteurs.putIfAbsent(r.getLong(1), r.getLong(2));
        }
        try (var s = c.prepareStatement("UPDATE " + table(c, document) + " SET " + colonne + "=? WHERE id=? AND " + colonne + " IS NULL")) {
            for (var e : auteurs.entrySet()) { s.setLong(1, e.getValue()); s.setLong(2, e.getKey()); s.addBatch(); }
            if (!auteurs.isEmpty()) s.executeBatch();
        }
    }

    private static void verifierInitialisation(Connection c) throws SQLException {
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "boutique") + " WHERE prochain_numero_retour_achat IS NULL") != 0) nonConforme("initialisation incomplète des compteurs");
        for (String[] document : new String[][]{{"vente", "vente_id", "vendeur_id"}, {"achat", "achat_id", "utilisateur_id"}})
            if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, document[0]) + " d WHERE d." + document[2] + " IS NULL AND EXISTS (SELECT 1 FROM "
                    + table(c, "mouvement_caisse") + " m WHERE m." + document[1] + "=d.id AND m.utilisateur_id IS NOT NULL)") != 0) nonConforme("un auteur historique n'a pas été repris");
    }

    static void ajouterUniques(Connection c) throws SQLException {
        if (!uniqueAnnulation(c)) sql(c, "ALTER TABLE " + table(c, "mouvement_caisse") + " ADD CONSTRAINT " + UNIQUE_ANNULATION + " UNIQUE (annulation_de_id)");
    }

    static void ajouterCles(Connection c) throws SQLException {
        for (var cle : CLES) if (!cles(c, cle.table()).containsKey(cle.colonne()))
            sql(c, "ALTER TABLE " + table(c, cle.table()) + " ADD CONSTRAINT " + cle.nom() + " FOREIGN KEY(" + cle.colonne() + ") REFERENCES " + table(c, cle.reference()) + "(id)");
    }

    /** Le compteur des retours d'achat devient obligatoire en dernier : ce DDL clôt l'initialisation. */
    static void finaliser(Connection c) throws SQLException {
        if (!finale(c, MARQUEUR)) sql(c, "ALTER TABLE " + table(c, MARQUEUR.table()) + (maria(c) ? " MODIFY COLUMN " : " ALTER COLUMN ") + MARQUEUR.nom() + " " + MARQUEUR.definition());
    }

    static boolean finalise(Connection c) throws SQLException { return finale(c, MARQUEUR); }
    private static boolean finale(Connection c, Lot1Migration.Champ champ) throws SQLException {
        var col = colonnes(c, champ.table()).get(champ.nom());
        return col != null && (champ.nullable() || col.nullable() == DatabaseMetaData.columnNoNulls);
    }

    private static void verifierPresents(Connection c) throws SQLException {
        boolean termine = finalise(c);
        for (var champ : CHAMPS) {
            var col = colonnes(c, champ.table()).get(champ.nom());
            if (col == null) {
                if (termine) nonConforme(champ.cle() + " absent alors que l'initialisation est terminée");
                continue;
            }
            verifierColonne(champ, col, termine);
        }
        if (termine) for (var table : TABLES) if (!existe(c, table.nom())) nonConforme("table " + table.nom() + " absente alors que l'initialisation est terminée");
        for (var table : TABLES) if (existe(c, table.nom())) verifierTable(c, table);
        for (var cle : CLES) {
            var presente = cles(c, cle.table()).get(cle.colonne());
            if (presente != null && !presente.equals(cle.reference())) nonConforme(cle.table() + "." + cle.colonne() + " doit référencer " + cle.reference());
            if (termine && presente == null) nonConforme("clé étrangère de " + cle.table() + "." + cle.colonne() + " absente alors que l'initialisation est terminée");
        }
        for (var index : indexesUniques(c, "mouvement_caisse").values())
            if (index.contains("annulation_de_id") && !index.equals(List.of("annulation_de_id"))) nonConforme("l'annulation d'un mouvement doit être unique sur sa seule colonne");
        if (termine && !uniqueAnnulation(c)) nonConforme("contrainte unique de mouvement_caisse.annulation_de_id absente");
        verifierValeurs(c);
    }

    private static void verifierColonne(Lot1Migration.Champ champ, InscriptionEssaiMigration.Colonne col, boolean termine) {
        if (!champ.types().contains(col.type()) || col.autoIncrement() || champ.taille() > 0 && col.taille() != champ.taille()
                || DECIMAL.contains(col.type()) && col.decimales() != champ.decimales()) nonConforme(champ.cle() + " doit être " + champ.definition());
        boolean facultatif = col.nullable() == DatabaseMetaData.columnNullable;
        if (facultatif && !champ.nullable()) {
            // Forme intermédiaire admise uniquement avant la fin de l'initialisation.
            if (termine || !champ.initialise() || !defautNul(col.defaut()) && !defautEgal(col.defaut(), champ.defaut())) nonConforme(champ.cle() + " doit être " + champ.definition());
        } else if (facultatif != champ.nullable() || (champ.defaut() == null ? !defautNul(col.defaut()) : !defautEgal(col.defaut(), champ.defaut())))
            nonConforme(champ.cle() + " doit être " + champ.definition());
    }

    /** Une table déjà présente doit avoir exactement la forme attendue : colonnes, clé primaire, uniques, relations sans cascade, moteur. */
    private static void verifierTable(Connection c, Table table) throws SQLException {
        var cols = colonnes(c, table.nom());
        if (!cols.keySet().equals(table.noms())) nonConforme("colonnes inattendues dans " + table.nom());
        var identifiant = cols.get("id");
        if (identifiant.type() != Types.BIGINT || identifiant.nullable() != DatabaseMetaData.columnNoNulls || !identifiant.autoIncrement()) nonConforme(table.nom() + ".id doit être BIGINT auto-incrémenté");
        for (var champ : table.champs()) {
            var col = cols.get(champ.nom());
            verifierColonne(champ, col, true);
            if (col.autoIncrement()) nonConforme(champ.cle() + " ne doit pas être auto-incrémenté");
            if (champ.types().contains(Types.TIMESTAMP) && !maria(c) && col.decimales() != champ.decimales()) nonConforme(champ.cle() + " doit être " + champ.definition());
        }
        var primaire = new TreeMap<Integer, String>();
        try (var r = c.getMetaData().getPrimaryKeys(c.getCatalog(), c.getSchema(), BoutiquesMigration.tables(c).get(table.nom()))) {
            while (r.next()) primaire.put(r.getInt("KEY_SEQ"), r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        if (!new ArrayList<>(primaire.values()).equals(List.of("id"))) nonConforme(table.nom() + " : clé primaire id requise");
        if (!new HashSet<>(indexesUniques(c, table.nom()).values()).equals(table.indexUniques())) nonConforme(table.nom() + " : contraintes uniques incohérentes");
        var attendues = new TreeMap<String, String>();
        for (var cle : table.cles()) attendues.put(cle.colonne(), cle.reference());
        if (!cles(c, table.nom()).equals(attendues)) nonConforme(table.nom() + " : clés étrangères absentes, redondantes ou vers une mauvaise table");
        moteur(c, table.nom());
    }

    private static void verifierValeurs(Connection c) throws SQLException {
        if (existe(c, DEVIS)) {
            if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, DEVIS) + " WHERE statut NOT IN ('EN_COURS','TRANSFORME','ANNULE') OR montant_total<0") != 0) nonConforme("un devis a un statut inconnu ou un montant négatif");
            if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, DEVIS) + " d LEFT JOIN " + table(c, "boutique") + " b ON b.id=d.boutique_id WHERE b.id IS NULL") != 0) nonConforme("un devis référence une boutique absente");
        }
        if (existe(c, RETOUR)) {
            if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, RETOUR) + " WHERE type NOT IN ('VENTE','ACHAT') OR montant_total<0 OR montant_rembourse<0 OR montant_avoir<0"
                    + " OR compte_tresorerie IS NOT NULL AND compte_tresorerie NOT IN ('CAISSE','ORANGE_MONEY','BANQUE')") != 0) nonConforme("un retour a un type ou un compte inconnu ou un montant négatif");
        }
        var boutique = colonnes(c, "boutique");
        for (String nom : List.of("prochain_numero_devis", "prochain_numero_retour_vente", "prochain_numero_retour_achat")) if (boutique.containsKey(nom)
                && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "boutique") + " WHERE " + nom + "<1") != 0) nonConforme("un prochain numéro doit être au moins 1");
    }

    private static void moteur(Connection c, String nom) throws SQLException {
        if (!maria(c)) return;
        String physique = BoutiquesMigration.tables(c).get(nom);
        try (var s = c.prepareStatement("SELECT engine,table_collation FROM information_schema.tables WHERE table_schema=? AND table_name=?")) {
            s.setString(1, c.getCatalog()); s.setString(2, physique);
            try (var r = s.executeQuery()) { if (!r.next() || !"InnoDB".equalsIgnoreCase(r.getString(1)) || !"utf8mb4_unicode_ci".equalsIgnoreCase(r.getString(2))) nonConforme(nom + " : InnoDB et utf8mb4_unicode_ci requis"); }
        }
        try (var s = c.prepareStatement("SELECT character_set_name,collation_name FROM information_schema.columns WHERE table_schema=? AND table_name=? AND character_set_name IS NOT NULL")) {
            s.setString(1, c.getCatalog()); s.setString(2, physique);
            try (var r = s.executeQuery()) { while (r.next()) if (!"utf8mb4".equalsIgnoreCase(r.getString(1)) || !"utf8mb4_unicode_ci".equalsIgnoreCase(r.getString(2))) nonConforme(nom + " : les colonnes texte doivent conserver utf8mb4_unicode_ci"); }
        }
        try (var s = c.prepareStatement("SELECT sub_part FROM information_schema.statistics WHERE table_schema=? AND table_name=? AND non_unique=0")) {
            s.setString(1, c.getCatalog()); s.setString(2, physique);
            try (var r = s.executeQuery()) { while (r.next()) if (r.getObject(1) != null) nonConforme(nom + " : les contraintes uniques doivent couvrir les colonnes complètes"); }
        }
        for (var champ : TABLES.stream().filter(t -> t.nom().equals(nom)).findFirst().orElseThrow().champs()) if (champ.types().contains(Types.TIMESTAMP))
            try (var s = c.prepareStatement("SELECT datetime_precision FROM information_schema.columns WHERE table_schema=? AND table_name=? AND column_name=? AND data_type='datetime'")) {
                s.setString(1, c.getCatalog()); s.setString(2, physique); s.setString(3, champ.nom());
                try (var r = s.executeQuery()) { if (!r.next() || r.getInt(1) != champ.decimales()) nonConforme(champ.cle() + " doit être " + champ.definition()); }
            }
    }

    private static boolean uniqueAnnulation(Connection c) throws SQLException {
        return indexesUniques(c, "mouvement_caisse").containsValue(List.of("annulation_de_id"));
    }

    static Map<String, List<String>> indexesUniques(Connection c, String nom) throws SQLException {
        var positions = new TreeMap<String, TreeMap<Integer, String>>();
        try (var r = c.getMetaData().getIndexInfo(c.getCatalog(), c.getSchema(), BoutiquesMigration.tables(c).get(nom), true, false)) {
            while (r.next()) if (r.getString("INDEX_NAME") != null && r.getString("COLUMN_NAME") != null)
                positions.computeIfAbsent(r.getString("INDEX_NAME").toLowerCase(Locale.ROOT), k -> new TreeMap<>()).put(r.getInt("ORDINAL_POSITION"), r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        var resultat = new TreeMap<String, List<String>>();
        positions.forEach((index, champs) -> resultat.put(index, new ArrayList<>(champs.values())));
        return resultat;
    }

    /**
     * Colonne -> table référencée, pour les clés étrangères d'une table. Pour les tables existantes, seules les colonnes du lot 2
     * sont retenues : les autres clés sont celles des migrations précédentes. Une clé composite ou en cascade est refusée.
     */
    static Map<String, String> cles(Connection c, String nom) throws SQLException {
        var colonnesLot2 = new HashSet<String>();
        for (var champ : CHAMPS) if (champ.table().equals(nom)) colonnesLot2.add(champ.nom());
        boolean nouvelle = NOUVELLES_TABLES.contains(nom);
        var resultat = new TreeMap<String, String>();
        try (var r = c.getMetaData().getImportedKeys(c.getCatalog(), c.getSchema(), BoutiquesMigration.tables(c).get(nom))) {
            while (r.next()) {
                String colonne = r.getString("FKCOLUMN_NAME").toLowerCase(Locale.ROOT);
                if (!nouvelle && !colonnesLot2.contains(colonne)) continue;
                if (r.getShort("KEY_SEQ") != 1 || !r.getString("PKCOLUMN_NAME").equalsIgnoreCase("id")
                        || !Set.of((int) DatabaseMetaData.importedKeyNoAction, (int) DatabaseMetaData.importedKeyRestrict).contains((int) r.getShort("DELETE_RULE"))
                        || !Set.of((int) DatabaseMetaData.importedKeyNoAction, (int) DatabaseMetaData.importedKeyRestrict).contains((int) r.getShort("UPDATE_RULE"))
                        || resultat.put(colonne, r.getString("PKTABLE_NAME").toLowerCase(Locale.ROOT)) != null)
                    nonConforme(nom + "." + colonne + " : relation composite, redondante ou avec cascade");
            }
        }
        return resultat;
    }

    private static int manquants(Connection c) throws SQLException {
        int manquants = 0;
        for (var table : TABLES) if (!existe(c, table.nom())) manquants++;
        for (var champ : CHAMPS) if (!finale(c, champ)) manquants++;
        if (!uniqueAnnulation(c)) manquants++;
        for (var cle : CLES) if (!cle.reference().equals(colonnes(c, cle.table()).containsKey(cle.colonne()) ? cles(c, cle.table()).get(cle.colonne()) : null)) manquants++;
        return manquants;
    }

    private static long aInitialiser(Connection c) throws SQLException {
        long total = 0;
        for (String[] document : new String[][]{{"vente", "vente_id", "vendeur_id"}, {"achat", "achat_id", "utilisateur_id"}}) {
            String filtre = colonnes(c, document[0]).containsKey(document[2]) ? "d." + document[2] + " IS NULL AND " : "";
            total += BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, document[0]) + " d WHERE " + filtre + "EXISTS (SELECT 1 FROM " + table(c, "mouvement_caisse")
                    + " m WHERE m." + document[1] + "=d.id AND m.utilisateur_id IS NOT NULL)");
        }
        if (colonnes(c, "boutique").containsKey(MARQUEUR.nom())) total += BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "boutique") + " WHERE " + MARQUEUR.nom() + " IS NULL");
        else total += BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "boutique"));
        return total;
    }

    static Set<String> nouvelles(String table) {
        var resultat = new TreeSet<String>();
        for (var champ : CHAMPS) if (champ.table().equals(table)) resultat.add(champ.nom());
        return resultat;
    }

    /** Toutes les tables antérieures au lot 2 et toutes leurs anciennes colonnes, y compris stocks, montants et mouvements. */
    static Map<String, List<List<Object>>> photographie(Connection c) throws SQLException {
        var resultat = new TreeMap<String, List<List<Object>>>();
        for (String nom : BoutiquesMigration.tables(c).keySet()) {
            if (NOUVELLES_TABLES.contains(nom)) continue;
            var exclues = nouvelles(nom); var lignes = new ArrayList<List<Object>>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table(c, nom) + " ORDER BY " + PhotographieJdbc.ordre(c, nom))) {
                while (r.next()) {
                    var ligne = new ArrayList<Object>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
                        if (!exclues.contains(r.getMetaData().getColumnName(i).toLowerCase(Locale.ROOT))) ligne.add(PhotographieJdbc.valeur(r, i));
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
            champs.keySet().removeAll(nouvelles(nom));
            resultat.put(nom, champs);
        }
        return resultat;
    }

    /** Contenu des tables nouvelles : une relance ne le modifie jamais. */
    static Map<String, List<List<Object>>> donneesNouvelles(Connection c) throws SQLException {
        var resultat = new TreeMap<String, List<List<Object>>>();
        for (String nom : NOUVELLES_TABLES) if (existe(c, nom)) {
            var lignes = new ArrayList<List<Object>>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table(c, nom) + " ORDER BY id")) {
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

    /** Valeurs des colonnes du lot 2 déjà présentes, par table et identifiant. */
    static Map<String, List<List<Object>>> valeursLot2(Connection c) throws SQLException {
        var resultat = new TreeMap<String, List<List<Object>>>();
        for (var champ : CHAMPS) if (colonnes(c, champ.table()).containsKey(champ.nom())) {
            var valeurs = new ArrayList<List<Object>>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT id," + champ.nom() + " FROM " + table(c, champ.table()) + " ORDER BY id")) {
                while (r.next()) valeurs.add(Arrays.asList(r.getLong(1), r.getObject(2)));
            }
            resultat.put(champ.cle(), valeurs);
        }
        return resultat;
    }

    private static boolean defautNul(String valeur) { return valeur == null || nettoyer(valeur).equalsIgnoreCase("NULL"); }
    private static boolean defautEgal(String valeur, String attendu) {
        if (valeur == null) return false;
        String v = nettoyer(valeur);
        if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) v = v.substring(1, v.length() - 1);
        try { return new BigDecimal(v).compareTo(new BigDecimal(attendu)) == 0; }
        catch (NumberFormatException e) { return v.equals(attendu); }
    }
    private static String nettoyer(String valeur) {
        String v = valeur.strip();
        while (v.startsWith("(") && v.endsWith(")")) v = v.substring(1, v.length() - 1).strip();
        return v;
    }
    private static boolean existe(Connection c, String nom) throws SQLException { return BoutiquesMigration.tables(c).containsKey(nom); }
    private static boolean maria(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static String identite(Connection c) throws SQLException { return maria(c) ? "AUTO_INCREMENT" : "GENERATED BY DEFAULT AS IDENTITY"; }
    private static String suffixe(Connection c) throws SQLException { return maria(c) ? " ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" : ""; }
    private static Map<String, InscriptionEssaiMigration.Colonne> colonnes(Connection c, String nom) throws SQLException { return InscriptionEssaiMigration.colonnes(c, nom); }
    private static String table(Connection c, String nom) throws SQLException {
        String physique = BoutiquesMigration.tables(c).get(nom);
        if (physique == null || !physique.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("Identifiant SQL inattendu.");
        String q = c.getMetaData().getIdentifierQuoteString().strip(); return q + physique + q;
    }
    static void sql(Connection c, String requete) throws SQLException { BoutiquesMigration.executer(c, requete); }
    private static void nonConforme(String raison) { throw new IllegalStateException("Schéma lot 2 non conforme : " + raison + ". Aucune réparation automatique."); }
}
