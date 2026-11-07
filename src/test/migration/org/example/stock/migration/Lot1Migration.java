package org.example.stock.migration;

import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Colonnes du lot 1 (nature, unité, seuil, coût moyen, numéros, échéances, avances, délais, soldes initiaux).
 * Les colonnes initialisées depuis l'existant sont d'abord ajoutées facultatives, remplies dans une seule
 * transaction, puis rendues obligatoires : {@code produit.cout_moyen NOT NULL} marque la fin de l'initialisation
 * et interdit toute nouvelle écriture des valeurs lors d'une relance. Aucun stock, montant ou mouvement n'est modifié.
 */
public final class Lot1Migration {
    static final String VERROU = "stock_lot1_migration";
    /** Délai appliqué aux documents historiques restant à payer. */
    static final int DELAI_HISTORIQUE_JOURS = 30;

    /** Définition attendue d'une colonne ; {@code intermediaire} est la forme facultative avant initialisation. */
    record Champ(String table, String nom, String definition, String intermediaire, Set<Integer> types, int taille, int decimales, boolean nullable, String defaut) {
        boolean initialise() { return intermediaire != null; }
        String cle() { return table + "." + nom; }
    }

    private static final Set<Integer> DECIMAL = Set.of(Types.DECIMAL, Types.NUMERIC);
    static final List<Champ> CHAMPS = List.of(
            new Champ("produit", "nature", "VARCHAR(16) DEFAULT 'STOCK' NOT NULL", null, Set.of(Types.VARCHAR), 16, 0, false, "STOCK"),
            new Champ("produit", "unite", "VARCHAR(30) NULL", null, Set.of(Types.VARCHAR), 30, 0, true, null),
            new Champ("produit", "seuil_alerte", "BIGINT DEFAULT 5 NOT NULL", null, Set.of(Types.BIGINT), 0, 0, false, "5"),
            new Champ("produit", "cout_moyen", "DECIMAL(17,2) DEFAULT 0 NOT NULL", "DECIMAL(17,2) NULL", DECIMAL, 17, 2, false, "0"),
            new Champ("detail_vente", "cout_unitaire", "DECIMAL(17,2) NULL", "DECIMAL(17,2) NULL", DECIMAL, 17, 2, true, null),
            new Champ("vente", "numero", "VARCHAR(30) NULL", "VARCHAR(30) NULL", Set.of(Types.VARCHAR), 30, 0, true, null),
            new Champ("vente", "date_echeance", "DATE NULL", "DATE NULL", Set.of(Types.DATE), 0, 0, true, null),
            new Champ("vente", "avance_utilisee", "DECIMAL(17,2) DEFAULT 0 NOT NULL", null, DECIMAL, 17, 2, false, "0"),
            new Champ("achat", "numero", "VARCHAR(30) NULL", "VARCHAR(30) NULL", Set.of(Types.VARCHAR), 30, 0, true, null),
            new Champ("achat", "date_echeance", "DATE NULL", "DATE NULL", Set.of(Types.DATE), 0, 0, true, null),
            new Champ("achat", "avance_utilisee", "DECIMAL(17,2) DEFAULT 0 NOT NULL", null, DECIMAL, 17, 2, false, "0"),
            new Champ("boutique", "delai_clients_jours", "INT DEFAULT 30 NOT NULL", null, Set.of(Types.INTEGER), 0, 0, false, "30"),
            new Champ("boutique", "delai_fournisseurs_jours", "INT DEFAULT 30 NOT NULL", null, Set.of(Types.INTEGER), 0, 0, false, "30"),
            new Champ("boutique", "prochain_numero_vente", "BIGINT DEFAULT 1 NOT NULL", "BIGINT NULL", Set.of(Types.BIGINT), 0, 0, false, "1"),
            new Champ("boutique", "prochain_numero_achat", "BIGINT DEFAULT 1 NOT NULL", "BIGINT NULL", Set.of(Types.BIGINT), 0, 0, false, "1"),
            new Champ("client", "solde_initial", "DECIMAL(17,2) DEFAULT 0 NOT NULL", null, DECIMAL, 17, 2, false, "0"),
            new Champ("client", "echeance_solde_initial", "DATE NULL", null, Set.of(Types.DATE), 0, 0, true, null),
            new Champ("fournisseur", "solde_initial", "DECIMAL(17,2) DEFAULT 0 NOT NULL", null, DECIMAL, 17, 2, false, "0"),
            new Champ("fournisseur", "echeance_solde_initial", "DATE NULL", null, Set.of(Types.DATE), 0, 0, true, null));
    /** Contraintes uniques (boutique_id, numero) ; les numéros vides restent permis jusqu'à leur génération. */
    static final Map<String, String> UNIQUES = Map.of("vente", "uk_vente_boutique_numero", "achat", "uk_achat_boutique_numero");
    static final Map<String, String> PREFIXES = Map.of("vente", "FV-", "achat", "FA-");
    private static final Champ MARQUEUR = CHAMPS.get(3);
    private Lot1Migration() {}

    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        if (args.length != 1 || !Set.of("--check", "--apply").contains(args[0])) throw new IllegalArgumentException("Utiliser --check ou --apply");
        try {
            var configuration = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { configuration.load(lecteur); }
            String url = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.url")).replace("createDatabaseIfNotExist=true", "createDatabaseIfNotExist=false");
            boolean appliquer = args[0].equals("--apply");
            if (appliquer) verifierAutorisationApplication(url, System.getenv("LOT1_BACKUP_DIR"));
            try (var c = DriverManager.getConnection(url, AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.password")))) { migrer(c, appliquer); }
        } catch (Exception e) {
            // Les pilotes peuvent inclure des valeurs métier dans leurs exceptions.
            System.err.println("Migration lot 1 interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Connexion ou opération SQL impossible ; aucune donnée privée publiée."));
            System.exit(1);
        }
    }

    static void verifierAutorisationApplication(String url, String dossier) throws Exception {
        BoutiquesMigration.verifierCibleSauvegarde(url);
        if (dossier == null || dossier.isBlank()) throw new IllegalStateException("Une sauvegarde SQL complète est requise : définir LOT1_BACKUP_DIR sur le répertoire créé par scripts/backup-stock-local.sh.");
        BoutiquesMigration.verifierSauvegarde(dossier);
    }

    static void migrer(Connection c, boolean appliquer) throws SQLException {
        if (!Set.of("H2", "MariaDB").contains(c.getMetaData().getDatabaseProductName())) throw new IllegalStateException("MariaDB ou H2 requis.");
        if (!c.getAutoCommit()) throw new IllegalStateException("Connexion dédiée sans transaction ouverte requise.");
        boolean verrou = false;
        try {
            c.setReadOnly(!appliquer);
            if (appliquer && maria(c)) {
                if (BoutiquesMigration.compter(c, "SELECT GET_LOCK('" + VERROU + "',0)") != 1) throw new IllegalStateException("Une migration lot 1 est déjà en cours.");
                verrou = true;
            }
            var tables = BoutiquesMigration.tables(c);
            if (!tables.containsKey(IdentiteBoutiqueMigration.TABLE) || !colonnes(c, "boutique").containsKey(IdentiteBoutiqueMigration.CHAMP))
                throw new IllegalStateException("La migration de l'identité boutique doit être terminée avant le lot 1.");
            IdentiteBoutiqueMigration.migrer(c, false);
            c.setReadOnly(!appliquer);
            verifierPresents(c);
            if (!appliquer) {
                System.out.println("Lot 1 vérifié en lecture seule ; éléments manquants : " + manquants(c) + (finalise(c) ? ", initialisation terminée"
                        : ", lignes à initialiser : " + aInitialiser(c)) + ". Aucun stock, montant ni historique modifié.");
                return;
            }
            var avant = photographie(c); var schemas = schemasAnciens(c);
            boolean dejaFinalise = finalise(c);
            var valeurs = dejaFinalise ? valeursLot1(c) : null;
            if (!dejaFinalise) {
                ajouterColonnes(c);
                verifierPresents(c);
                initialiser(c);
                verifierInitialisation(c);
                ajouterUniques(c);
                finaliser(c);
            }
            verifierPresents(c);
            if (manquants(c) != 0 || !finalise(c) || !avant.equals(photographie(c)) || !schemas.equals(schemasAnciens(c)) || valeurs != null && !valeurs.equals(valeursLot1(c)))
                nonConforme("des données ou colonnes existantes ont changé ; conserver l'application arrêtée et examiner la sauvegarde");
            System.out.println(dejaFinalise ? "Lot 1 déjà migré ; aucune valeur réécrite."
                    : "Migration lot 1 terminée ; vingt tables conservées, stocks, montants et mouvements inchangés, documents historiques numérotés.");
        } finally { if (verrou) BoutiquesMigration.compter(c, "SELECT RELEASE_LOCK('" + VERROU + "')"); }
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
            sql(c, "UPDATE " + table(c, "produit") + " SET cout_moyen=COALESCE(prix_achat,0) WHERE cout_moyen IS NULL");
            // Approximation assumée : le coût des ventes passées est le prix d'achat actuel du produit.
            sql(c, "UPDATE " + table(c, "detail_vente") + " d SET cout_unitaire=(SELECT p.prix_achat FROM " + table(c, "produit")
                    + " p WHERE p.id=d.produit_id) WHERE d.cout_unitaire IS NULL AND d.produit_id IS NOT NULL");
            for (String document : List.of("vente", "achat")) {
                String date = document.equals("vente") ? "date_vente" : "date_achat";
                var numeros = new ArrayList<Long>();
                try (var s = c.createStatement(); var r = s.executeQuery("SELECT id FROM " + table(c, document) + " WHERE numero IS NULL ORDER BY id")) {
                    while (r.next()) numeros.add(r.getLong(1));
                }
                try (var s = c.prepareStatement("UPDATE " + table(c, document) + " SET numero=? WHERE id=? AND numero IS NULL")) {
                    for (long id : numeros) { s.setString(1, numero(document, id)); s.setLong(2, id); s.addBatch(); }
                    if (!numeros.isEmpty()) s.executeBatch();
                }
                var echeances = new LinkedHashMap<Long, LocalDate>();
                try (var s = c.createStatement(); var r = s.executeQuery("SELECT id," + date + ",montant_total,montant_verse FROM " + table(c, document) + " WHERE date_echeance IS NULL ORDER BY id")) {
                    while (r.next()) {
                        var jour = r.getObject(2, LocalDateTime.class);
                        if (jour != null && resteAPayer(r.getBigDecimal(3), r.getBigDecimal(4))) echeances.put(r.getLong(1), jour.toLocalDate().plusDays(DELAI_HISTORIQUE_JOURS));
                    }
                }
                try (var s = c.prepareStatement("UPDATE " + table(c, document) + " SET date_echeance=? WHERE id=? AND date_echeance IS NULL")) {
                    for (var e : echeances.entrySet()) { s.setObject(1, e.getValue()); s.setLong(2, e.getKey()); s.addBatch(); }
                    if (!echeances.isEmpty()) s.executeBatch();
                }
                // Au-delà du plus grand identifiant de toute la table : aucun futur numéro ne rejoint un numéro migré.
                long prochain = BoutiquesMigration.compter(c, "SELECT COALESCE(MAX(id),0)+1 FROM " + table(c, document));
                try (var s = c.prepareStatement("UPDATE " + table(c, "boutique") + " SET prochain_numero_" + document + "=? WHERE prochain_numero_" + document + " IS NULL")) {
                    s.setLong(1, prochain); s.executeUpdate();
                }
            }
            c.commit(); valide = true;
        } finally {
            if (!valide) c.rollback();
            c.setAutoCommit(true);
        }
    }

    static String numero(String document, long id) { return PREFIXES.get(document) + String.format(Locale.ROOT, "%06d", id); }
    static boolean resteAPayer(BigDecimal total, BigDecimal verse) {
        return (total == null ? BigDecimal.ZERO : total).compareTo(verse == null ? BigDecimal.ZERO : verse) > 0;
    }

    private static void verifierInitialisation(Connection c) throws SQLException {
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "produit") + " WHERE cout_moyen IS NULL") != 0
                || BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "detail_vente") + " WHERE cout_unitaire IS NULL AND produit_id IS NOT NULL") != 0
                || BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "boutique") + " WHERE prochain_numero_vente IS NULL OR prochain_numero_achat IS NULL") != 0)
            nonConforme("initialisation incomplète des coûts ou des compteurs");
        for (String document : List.of("vente", "achat"))
            if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, document) + " WHERE numero IS NULL") != 0) nonConforme("un document historique n'a pas de numéro");
    }

    static void ajouterUniques(Connection c) throws SQLException {
        for (var e : new TreeMap<>(UNIQUES).entrySet()) if (!uniqueNumero(c, e.getKey()))
            sql(c, "ALTER TABLE " + table(c, e.getKey()) + " ADD CONSTRAINT " + e.getValue() + " UNIQUE (boutique_id,numero)");
    }

    /** Les compteurs puis, en dernier, le coût moyen deviennent obligatoires : ce dernier DDL clôt l'initialisation. */
    static void finaliser(Connection c) throws SQLException {
        for (var champ : CHAMPS) if (champ.initialise() && !champ.nullable() && champ != MARQUEUR && !finale(c, champ)) modifier(c, champ);
        if (!finale(c, MARQUEUR)) modifier(c, MARQUEUR);
    }

    private static void modifier(Connection c, Champ champ) throws SQLException {
        sql(c, "ALTER TABLE " + table(c, champ.table()) + (maria(c) ? " MODIFY COLUMN " : " ALTER COLUMN ") + champ.nom() + " " + champ.definition());
    }

    static boolean finalise(Connection c) throws SQLException { return finale(c, MARQUEUR); }
    private static boolean finale(Connection c, Champ champ) throws SQLException {
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
            if (!champ.types().contains(col.type()) || col.autoIncrement() || champ.taille() > 0 && col.taille() != champ.taille()
                    || DECIMAL.contains(col.type()) && col.decimales() != champ.decimales()) nonConforme(champ.cle() + " doit être " + champ.definition());
            boolean facultatif = col.nullable() == DatabaseMetaData.columnNullable;
            if (facultatif && !champ.nullable()) {
                // Forme intermédiaire admise uniquement avant la fin de l'initialisation.
                if (termine || !champ.initialise() || !defautNul(col.defaut()) && !defautEgal(col.defaut(), champ.defaut())) nonConforme(champ.cle() + " doit être " + champ.definition());
            } else if (facultatif != champ.nullable() || (champ.defaut() == null ? !defautNul(col.defaut()) : !defautEgal(col.defaut(), champ.defaut())))
                nonConforme(champ.cle() + " doit être " + champ.definition());
        }
        if (maria(c)) try (var s = c.prepareStatement("SELECT table_name,column_name,character_set_name,collation_name FROM information_schema.columns WHERE table_schema=? AND character_set_name IS NOT NULL")) {
            s.setString(1, c.getCatalog());
            try (var r = s.executeQuery()) {
                while (r.next()) for (var champ : CHAMPS) if (champ.table().equalsIgnoreCase(r.getString(1)) && champ.nom().equalsIgnoreCase(r.getString(2))
                        && !"utf8mb4".equalsIgnoreCase(r.getString(3))) nonConforme(champ.cle() + " doit utiliser utf8mb4");
            }
        }
        for (var e : UNIQUES.entrySet()) {
            for (var index : indexesUniques(c, e.getKey()).values())
                if (index.contains("numero") && !index.equals(List.of("boutique_id", "numero"))) nonConforme("le numéro de " + e.getKey() + " doit être unique par boutique uniquement");
            if (termine && !uniqueNumero(c, e.getKey())) nonConforme("contrainte unique (boutique_id, numero) absente sur " + e.getKey());
        }
        if (maria(c)) try (var s = c.prepareStatement("SELECT table_name FROM information_schema.statistics WHERE table_schema=? AND column_name='numero' AND sub_part IS NOT NULL")) {
            s.setString(1, c.getCatalog());
            try (var r = s.executeQuery()) { if (r.next()) nonConforme("la contrainte de numéro doit couvrir la colonne complète"); }
        }
        verifierValeurs(c);
    }

    private static void verifierValeurs(Connection c) throws SQLException {
        var produit = colonnes(c, "produit");
        if (produit.containsKey("nature") && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "produit") + " WHERE nature IS NULL OR nature NOT IN ('STOCK','SERVICE')") != 0)
            nonConforme("un produit a une nature inconnue");
        if (produit.containsKey("seuil_alerte") && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "produit") + " WHERE seuil_alerte<0") != 0) nonConforme("un seuil d'alerte est négatif");
        if (produit.containsKey("cout_moyen") && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "produit") + " WHERE cout_moyen<0") != 0) nonConforme("un coût moyen est négatif");
        if (colonnes(c, "detail_vente").containsKey("cout_unitaire") && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "detail_vente") + " WHERE cout_unitaire<0") != 0)
            nonConforme("un coût de vente est négatif");
        for (String document : List.of("vente", "achat")) if (colonnes(c, document).containsKey("avance_utilisee")
                && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, document) + " WHERE avance_utilisee<0") != 0) nonConforme("une avance utilisée est négative");
        var boutique = colonnes(c, "boutique");
        for (String nom : List.of("delai_clients_jours", "delai_fournisseurs_jours")) if (boutique.containsKey(nom)
                && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "boutique") + " WHERE " + nom + "<0") != 0) nonConforme("un délai de paiement est négatif");
        for (String nom : List.of("prochain_numero_vente", "prochain_numero_achat")) if (boutique.containsKey(nom)
                && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "boutique") + " WHERE " + nom + "<1") != 0) nonConforme("un prochain numéro doit être au moins 1");
    }

    private static boolean uniqueNumero(Connection c, String nom) throws SQLException {
        return indexesUniques(c, nom).containsValue(List.of("boutique_id", "numero"));
    }

    private static Map<String, List<String>> indexesUniques(Connection c, String nom) throws SQLException {
        var positions = new TreeMap<String, TreeMap<Integer, String>>();
        try (var r = c.getMetaData().getIndexInfo(c.getCatalog(), c.getSchema(), BoutiquesMigration.tables(c).get(nom), true, false)) {
            while (r.next()) if (r.getString("INDEX_NAME") != null && r.getString("COLUMN_NAME") != null)
                positions.computeIfAbsent(r.getString("INDEX_NAME").toLowerCase(Locale.ROOT), k -> new TreeMap<>()).put(r.getInt("ORDINAL_POSITION"), r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        var resultat = new TreeMap<String, List<String>>();
        positions.forEach((index, champs) -> resultat.put(index, new ArrayList<>(champs.values())));
        return resultat;
    }

    private static int manquants(Connection c) throws SQLException {
        int manquants = 0;
        for (var champ : CHAMPS) if (!finale(c, champ)) manquants++;
        for (String nom : UNIQUES.keySet()) if (!uniqueNumero(c, nom)) manquants++;
        return manquants;
    }

    private static long aInitialiser(Connection c) throws SQLException {
        long total = 0;
        for (var champ : CHAMPS) if (champ.initialise()) {
            var cols = colonnes(c, champ.table());
            String filtre = !cols.containsKey(champ.nom()) ? "" : " WHERE " + champ.nom() + " IS NULL";
            if (champ.nom().equals("date_echeance")) continue; // Dépend du reste à payer ; compté avec les numéros.
            if (champ.nom().equals("cout_unitaire")) filtre = filtre.isEmpty() ? " WHERE produit_id IS NOT NULL" : filtre + " AND produit_id IS NOT NULL";
            total += BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, champ.table()) + filtre);
        }
        return total;
    }

    static Set<String> nouvelles(String table) {
        var resultat = new TreeSet<String>();
        for (var champ : CHAMPS) if (champ.table().equals(table)) resultat.add(champ.nom());
        return resultat;
    }

    /** Toutes les tables et colonnes antérieures au lot 1, y compris stocks, montants, mouvements et logos. */
    static Map<String, List<List<Object>>> photographie(Connection c) throws SQLException {
        var resultat = new TreeMap<String, List<List<Object>>>();
        for (String nom : BoutiquesMigration.tables(c).keySet()) {
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
        for (String nom : BoutiquesMigration.tables(c).keySet()) {
            var champs = new TreeMap<>(colonnes(c, nom));
            champs.keySet().removeAll(nouvelles(nom));
            resultat.put(nom, champs);
        }
        return resultat;
    }

    /** Valeurs des colonnes du lot 1 déjà présentes, par table et identifiant. */
    static Map<String, List<List<Object>>> valeursLot1(Connection c) throws SQLException {
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
    private static boolean maria(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static Map<String, InscriptionEssaiMigration.Colonne> colonnes(Connection c, String nom) throws SQLException { return InscriptionEssaiMigration.colonnes(c, nom); }
    private static String table(Connection c, String nom) throws SQLException {
        String physique = BoutiquesMigration.tables(c).get(nom);
        if (physique == null || !physique.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("Identifiant SQL inattendu.");
        String q = c.getMetaData().getIdentifierQuoteString().strip(); return q + physique + q;
    }
    static void sql(Connection c, String requete) throws SQLException { BoutiquesMigration.executer(c, requete); }
    private static void nonConforme(String raison) { throw new IllegalStateException("Schéma lot 1 non conforme : " + raison + ". Aucune réparation automatique."); }
}
