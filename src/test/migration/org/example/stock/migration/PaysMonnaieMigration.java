package org.example.stock.migration;

import org.example.stock.pays.Pays;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Pays et monnaie des boutiques, opérateur des comptes de mobile money.
 *
 * <p>Ajoute {@code boutique.pays} (code ISO sur 2 lettres) et {@code boutique.monnaie} (code sur 3 lettres), tous deux obligatoires :
 * les boutiques existantes reçoivent la Guinée (GN) et le franc guinéen (GNF), leur pays et leur monnaie de toujours. Ajoute
 * {@code compte_tresorerie_boutique.operateur} (VARCHAR(40), facultatif) ; le type {@code ORANGE_MONEY} devient {@code MOBILE_MONEY}
 * (dans {@code type} et dans {@code principal_type}, dont l'unicité par boutique est conservée) et chaque compte reçoit l'opérateur
 * « Orange Money ». Aucun montant, aucun compte, aucun mouvement n'est modifié ni supprimé.</p>
 *
 * <p>Déroulement, chaque étape pouvant être reprise après une interruption : colonnes absentes ajoutées vides, remplissage du pays et de la
 * monnaie des boutiques qui n'en ont pas (jamais une valeur déjà posée), colonnes passées en NOT NULL, conversion du type, opérateur des
 * comptes de mobile money qui n'en ont pas. Les lots 5 et 6 (et, par eux, 1 à 4) doivent être terminés. Avant et après, toutes les tables
 * sont comparées (hors colonnes ajoutées, le type converti étant normalisé) ainsi que les totaux des mouvements par compte et par type.
 * Syntaxes acceptées par MariaDB 10.4.</p>
 */
public final class PaysMonnaieMigration {
    static final String VERROU = "stock_pays_monnaie_migration";
    static final String COMPTES = "compte_tresorerie_boutique", BOUTIQUE = "boutique";
    static final String PAYS_DEFAUT = "GN", MONNAIE_DEFAUT = "GNF", OPERATEUR_DEFAUT = "Orange Money";
    static final String ANCIEN_TYPE = "ORANGE_MONEY", NOUVEAU_TYPE = "MOBILE_MONEY";

    /** Colonnes ajoutées, dans l'ordre d'ajout (la définition est celle d'avant le passage en NOT NULL). */
    static final List<Lot1Migration.Champ> CHAMPS = List.of(
            new Lot1Migration.Champ(BOUTIQUE, "pays", "VARCHAR(2) NULL", null, Set.of(Types.VARCHAR, Types.CHAR), 2, 0, false, null),
            new Lot1Migration.Champ(BOUTIQUE, "monnaie", "VARCHAR(3) NULL", null, Set.of(Types.VARCHAR, Types.CHAR), 3, 0, false, null),
            new Lot1Migration.Champ(COMPTES, "operateur", "VARCHAR(40) NULL", null, Set.of(Types.VARCHAR), 40, 0, true, null));

    private PaysMonnaieMigration() {}

    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        if (args.length != 1 || !Set.of("--check", "--apply").contains(args[0])) throw new IllegalArgumentException("Utiliser --check ou --apply");
        try {
            var configuration = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { configuration.load(lecteur); }
            String url = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.url")).replace("createDatabaseIfNotExist=true", "createDatabaseIfNotExist=false");
            boolean appliquer = args[0].equals("--apply");
            if (appliquer) verifierAutorisationApplication(url, System.getenv("PAYS_MONNAIE_BACKUP_DIR"));
            try (var c = DriverManager.getConnection(url, AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.password")))) { migrer(c, appliquer); }
        } catch (Exception e) {
            // Les pilotes peuvent inclure des valeurs métier dans leurs exceptions.
            System.err.println("Migration pays-monnaie interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Connexion ou opération SQL impossible ; aucune donnée privée publiée."));
            System.exit(1);
        }
    }

    static void verifierAutorisationApplication(String url, String dossier) throws Exception {
        BoutiquesMigration.verifierCibleSauvegarde(url);
        if (dossier == null || dossier.isBlank()) throw new IllegalStateException("Une sauvegarde SQL complète est requise : définir PAYS_MONNAIE_BACKUP_DIR sur le répertoire créé par scripts/backup-stock-local.sh.");
        BoutiquesMigration.verifierSauvegarde(dossier);
    }

    static void migrer(Connection c, boolean appliquer) throws SQLException {
        if (!Set.of("H2", "MariaDB").contains(c.getMetaData().getDatabaseProductName())) throw new IllegalStateException("MariaDB ou H2 requis.");
        if (!c.getAutoCommit()) throw new IllegalStateException("Connexion dédiée sans transaction ouverte requise.");
        boolean verrou = false;
        try {
            c.setReadOnly(!appliquer);
            if (appliquer && maria(c)) {
                if (BoutiquesMigration.compter(c, "SELECT GET_LOCK('" + VERROU + "',0)") != 1) throw new IllegalStateException("Une migration pays-monnaie est déjà en cours.");
                verrou = true;
            }
            exigerLots5Et6(c);
            c.setReadOnly(!appliquer);
            verifierPresents(c);
            if (!appliquer) {
                System.out.println("Pays et monnaie vérifiés en lecture seule ; éléments manquants : " + manquants(c) + ". Aucune donnée modifiée.");
                return;
            }
            var avant = photographie(c); var schemas = schemasAnciens(c); var totaux = totaux(c);
            boolean dejaFinalise = finalise(c);
            var valeurs = valeursPosees(c);
            ajouterColonnes(c);
            remplirBoutiques(c);
            rendreObligatoires(c);
            convertirComptes(c);
            verifierPresents(c);
            var apres = valeursPosees(c);
            // Un pays, une monnaie ou un opérateur déjà posé ne change jamais.
            boolean valeursConservees = valeurs.entrySet().stream().allMatch(e -> apres.get(e.getKey()).containsAll(e.getValue()));
            if (manquants(c) != 0 || !finalise(c) || !avant.equals(photographie(c)) || !schemas.equals(schemasAnciens(c)) || !totaux.equals(totaux(c)) || !valeursConservees)
                nonConforme("des données ou colonnes existantes ont changé ; conserver l'application arrêtée et examiner la sauvegarde");
            System.out.println(dejaFinalise ? "Pays et monnaie déjà migrés ; aucune valeur réécrite."
                    : "Migration pays-monnaie terminée ; boutiques en Guinée (GN, GNF), comptes ORANGE_MONEY devenus MOBILE_MONEY (opérateur « " + OPERATEUR_DEFAUT
                    + " »), montants et mouvements inchangés.");
        } finally { if (verrou) BoutiquesMigration.compter(c, "SELECT RELEASE_LOCK('" + VERROU + "')"); }
    }

    /** La migration s'applique après les lots 5 et 6 (donc après 1 à 4) : refus explicite tant qu'ils ne sont pas terminés et conformes. */
    private static void exigerLots5Et6(Connection c) throws SQLException {
        if (!BoutiquesMigration.tables(c).containsKey(BOUTIQUE) || !Lot1Migration.finalise(c) || !Lot2Migration.finalise(c) || !Lot4Migration.finalise(c)
                || !Lot3Migration.finalise(c) || !Lot6Migration.finalise(c) || !Lot5Migration.finalise(c))
            throw new IllegalStateException("Les migrations des lots 5 et 6 (et 1 à 4) doivent être terminées avant pays-monnaie : lancer lot6Check, lot6Migrate puis lot5Check, lot5Migrate (LOT6_BACKUP_DIR, LOT5_BACKUP_DIR) après sauvegarde.");
        Lot6Migration.migrer(c, false);
        Lot5Migration.migrer(c, false);
    }

    // ---- Étapes ----------------------------------------------------------------------------------------------------

    /** Ajoute chaque colonne absente, vide. */
    static void ajouterColonnes(Connection c) throws SQLException {
        for (var champ : CHAMPS) if (!colonnes(c, champ.table()).containsKey(champ.nom()))
            sql(c, "ALTER TABLE " + table(c, champ.table()) + " ADD COLUMN " + champ.nom() + " " + champ.definition());
    }

    /** Une boutique sans pays ou sans monnaie reçoit la Guinée et le franc guinéen ; une valeur déjà posée n'est jamais réécrite. */
    static void remplirBoutiques(Connection c) throws SQLException {
        sql(c, "UPDATE " + table(c, BOUTIQUE) + " SET pays='" + PAYS_DEFAUT + "' WHERE pays IS NULL");
        sql(c, "UPDATE " + table(c, BOUTIQUE) + " SET monnaie='" + MONNAIE_DEFAUT + "' WHERE monnaie IS NULL");
    }

    /** Pays et monnaie obligatoires, une fois toutes les boutiques remplies. */
    static void rendreObligatoires(Connection c) throws SQLException {
        for (var champ : CHAMPS) {
            if (champ.nullable()) continue;
            var col = colonnes(c, champ.table()).get(champ.nom());
            if (col != null && col.nullable() == DatabaseMetaData.columnNoNulls) continue;
            sql(c, "ALTER TABLE " + table(c, champ.table()) + (maria(c) ? " MODIFY COLUMN " : " ALTER COLUMN ") + champ.nom() + " VARCHAR(" + champ.taille() + ") NOT NULL");
        }
    }

    /** ORANGE_MONEY devient MOBILE_MONEY (type et type du compte principal) ; un compte de mobile money sans opérateur reçoit « Orange Money ». */
    static void convertirComptes(Connection c) throws SQLException {
        String t = table(c, COMPTES);
        sql(c, "UPDATE " + t + " SET type='" + NOUVEAU_TYPE + "' WHERE type='" + ANCIEN_TYPE + "'");
        sql(c, "UPDATE " + t + " SET principal_type='" + NOUVEAU_TYPE + "' WHERE principal_type='" + ANCIEN_TYPE + "'");
        sql(c, "UPDATE " + t + " SET operateur='" + OPERATEUR_DEFAUT + "' WHERE type='" + NOUVEAU_TYPE + "' AND operateur IS NULL");
    }

    static boolean finalise(Connection c) throws SQLException { return manquants(c) == 0; }

    /** Éléments à faire : colonnes absentes, boutiques sans pays ou monnaie, colonnes encore facultatives, anciens types, opérateurs vides. */
    private static int manquants(Connection c) throws SQLException {
        int manquants = 0;
        var colonnesBoutique = colonnes(c, BOUTIQUE); var colonnesComptes = colonnes(c, COMPTES);
        for (var champ : CHAMPS) {
            var col = colonnes(c, champ.table()).get(champ.nom());
            if (col == null) manquants++;
            else if (!champ.nullable() && col.nullable() != DatabaseMetaData.columnNoNulls) manquants++;
        }
        if (colonnesBoutique.containsKey("pays") && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, BOUTIQUE) + " WHERE pays IS NULL") != 0) manquants++;
        if (colonnesBoutique.containsKey("monnaie") && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, BOUTIQUE) + " WHERE monnaie IS NULL") != 0) manquants++;
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, COMPTES) + " WHERE type='" + ANCIEN_TYPE + "' OR principal_type='" + ANCIEN_TYPE + "'") != 0) manquants++;
        if (colonnesComptes.containsKey("operateur") && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, COMPTES) + " WHERE type='" + NOUVEAU_TYPE + "' AND operateur IS NULL") != 0) manquants++;
        return manquants;
    }

    // ---- Contrôles -------------------------------------------------------------------------------------------------

    private static void verifierPresents(Connection c) throws SQLException {
        for (var champ : CHAMPS) {
            var col = colonnes(c, champ.table()).get(champ.nom());
            if (col == null) continue;
            if (!champ.types().contains(col.type()) || col.autoIncrement() || champ.taille() > 0 && col.taille() != champ.taille() || !defautNul(col.defaut()))
                nonConforme(champ.cle() + " doit être un VARCHAR(" + champ.taille() + ") sans valeur par défaut");
            if (champ.nullable() && col.nullable() != DatabaseMetaData.columnNullable) nonConforme(champ.cle() + " doit être facultatif");
        }
        verifierValeurs(c);
    }

    private static void verifierValeurs(Connection c) throws SQLException {
        var boutique = colonnes(c, BOUTIQUE);
        if (boutique.containsKey("pays") && boutique.containsKey("monnaie")) {
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT pays, monnaie FROM " + table(c, BOUTIQUE) + " WHERE pays IS NOT NULL OR monnaie IS NOT NULL")) {
                while (r.next()) {
                    String code = r.getString(1), monnaie = r.getString(2);
                    if (code == null || monnaie == null) continue;   // moitié remplie : l'étape de remplissage la complète
                    var pays = Pays.depuisCode(code);
                    if (pays == null || !pays.getCode().equals(code)) nonConforme("une boutique a un pays inconnu");
                    if (!pays.getMonnaie().name().equals(monnaie)) nonConforme("la monnaie d'une boutique n'est pas celle de son pays");
                }
            }
        }
        var comptes = colonnes(c, COMPTES);
        if (comptes.containsKey("operateur") && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, COMPTES) + " WHERE type<>'" + NOUVEAU_TYPE
                + "' AND type<>'" + ANCIEN_TYPE + "' AND operateur IS NOT NULL") != 0) nonConforme("seul un compte de mobile money peut avoir un opérateur");
        if (comptes.containsKey("operateur") && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, COMPTES) + " WHERE operateur IS NOT NULL AND TRIM(operateur)=''") != 0)
            nonConforme("un opérateur ne peut pas être vide");
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, COMPTES) + " WHERE type NOT IN ('ESPECES','" + ANCIEN_TYPE + "','" + NOUVEAU_TYPE + "','BANQUE')") != 0)
            nonConforme("un compte de trésorerie a un type inconnu");
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, COMPTES) + " WHERE principal_type IS NOT NULL AND (type IS NULL OR principal_type<>type)") != 0)
            nonConforme("un compte principal n'a pas le type qu'il représente");
    }

    static Set<String> nouvelles(String table) {
        var resultat = new TreeSet<String>();
        for (var champ : CHAMPS) if (champ.table().equals(table)) resultat.add(champ.nom());
        return resultat;
    }

    /**
     * Toutes les tables et toutes leurs anciennes colonnes. Le type d'un compte de trésorerie (et celui de son principal) est comparé après
     * conversion : seule modification voulue de l'existant.
     */
    static Map<String, List<List<Object>>> photographie(Connection c) throws SQLException {
        var resultat = new TreeMap<String, List<List<Object>>>();
        for (String nom : BoutiquesMigration.tables(c).keySet()) {
            var exclues = nouvelles(nom); var lignes = new ArrayList<List<Object>>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table(c, nom) + " ORDER BY " + PhotographieJdbc.ordre(c, nom))) {
                while (r.next()) {
                    var ligne = new ArrayList<Object>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
                        String colonne = r.getMetaData().getColumnName(i).toLowerCase(Locale.ROOT);
                        if (exclues.contains(colonne)) continue;
                        Object valeur = PhotographieJdbc.valeur(r, i);
                        boolean typeCompte = nom.equals(COMPTES) && (colonne.equals("type") || colonne.equals("principal_type"));
                        ligne.add(typeCompte && ANCIEN_TYPE.equals(valeur) ? NOUVEAU_TYPE : valeur);
                    }
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

    /**
     * Totaux des mouvements de trésorerie, par compte puis par type de compte (le type étant lu après conversion) : une somme par
     * ligne {@code [clé, nombre de mouvements, somme en texte]}. Ils ne doivent pas changer.
     */
    static Map<String, List<List<Object>>> totaux(Connection c) throws SQLException {
        var resultat = new TreeMap<String, List<List<Object>>>();
        if (!BoutiquesMigration.tables(c).containsKey("mouvement_caisse") || !colonnes(c, "mouvement_caisse").containsKey("compte_id")) return resultat;
        String m = table(c, "mouvement_caisse"), t = table(c, COMPTES);
        resultat.put("par compte", lire(c, "SELECT m.compte_id, COUNT(*), CAST(COALESCE(SUM(m.montant),0) AS DECIMAL(24,2)) FROM " + m + " m GROUP BY m.compte_id ORDER BY m.compte_id"));
        resultat.put("par type", lire(c, "SELECT CASE WHEN x.type='" + ANCIEN_TYPE + "' THEN '" + NOUVEAU_TYPE + "' ELSE x.type END, COUNT(*), CAST(COALESCE(SUM(m.montant),0) AS DECIMAL(24,2)) FROM "
                + m + " m JOIN " + t + " x ON x.id=m.compte_id GROUP BY CASE WHEN x.type='" + ANCIEN_TYPE + "' THEN '" + NOUVEAU_TYPE + "' ELSE x.type END ORDER BY 1"));
        return resultat;
    }

    private static List<List<Object>> lire(Connection c, String requete) throws SQLException {
        var lignes = new ArrayList<List<Object>>();
        try (var s = c.createStatement(); var r = s.executeQuery(requete)) {
            while (r.next()) lignes.add(Arrays.asList(String.valueOf(r.getObject(1)), r.getLong(2), r.getBigDecimal(3).toPlainString()));
        }
        return lignes;
    }

    /** Pays, monnaie et opérateurs déjà posés, par colonne : (identifiant, valeur). Ils ne doivent jamais changer. */
    static Map<String, List<List<Object>>> valeursPosees(Connection c) throws SQLException {
        var resultat = new TreeMap<String, List<List<Object>>>();
        for (var champ : CHAMPS) if (colonnes(c, champ.table()).containsKey(champ.nom())) {
            var valeurs = new ArrayList<List<Object>>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT id," + champ.nom() + " FROM " + table(c, champ.table()) + " WHERE " + champ.nom() + " IS NOT NULL ORDER BY id")) {
                while (r.next()) valeurs.add(Arrays.asList(r.getLong(1), String.valueOf(r.getObject(2))));
            }
            resultat.put(champ.cle(), valeurs);
        }
        return resultat;
    }

    private static boolean defautNul(String valeur) { return valeur == null || valeur.strip().equalsIgnoreCase("NULL"); }
    private static boolean maria(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static Map<String, InscriptionEssaiMigration.Colonne> colonnes(Connection c, String nom) throws SQLException { return InscriptionEssaiMigration.colonnes(c, nom); }
    private static String table(Connection c, String nom) throws SQLException {
        String physique = BoutiquesMigration.tables(c).get(nom);
        if (physique == null || !physique.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("Identifiant SQL inattendu.");
        String q = c.getMetaData().getIdentifierQuoteString().strip(); return q + physique + q;
    }
    static void sql(Connection c, String requete) throws SQLException { BoutiquesMigration.executer(c, requete); }
    private static void nonConforme(String raison) { throw new IllegalStateException("Schéma pays-monnaie non conforme : " + raison + ". Aucune réparation automatique."); }
}
