package org.example.stock.migration;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Colonnes du lot 6 (corbeille) : {@code supprime_le} (DATETIME(6), vide) et {@code supprime_par} (BIGINT, vide, sans clé étrangère) sur
 * {@code client}, {@code fournisseur}, {@code produit} et {@code categorie}, plus {@code categorie.nom_cle} (VARCHAR(255), vide) qui porte
 * l'unicité du nom : le nom de la catégorie tant qu'elle est active, vide dans la corbeille. L'unicité {@code (boutique_id, nom)} est
 * remplacée par {@code (boutique_id, nom_cle)} : une clé unique ignore les valeurs vides, donc une catégorie de la corbeille ne bloque plus
 * la création d'une catégorie du même nom.
 *
 * <p>Déroulement, chaque étape pouvant être reprise après une interruption : ajout des colonnes absentes (toutes vides : aucune fiche
 * n'est à la corbeille), remplissage de {@code nom_cle} avec le nom des catégories actives (sans toucher une clé déjà posée), ajout de la
 * nouvelle unicité <b>avant</b> le retrait de l'ancienne (InnoDB peut s'appuyer sur l'unicité pour la clé étrangère {@code boutique_id}).
 * Aucune donnée n'est supprimée ni réécrite : toute table et toute ancienne colonne sont comparées avant et après, et une valeur de
 * corbeille déjà présente ne change jamais. Les lots 3 et 4 (et, par eux, 1 et 2) doivent être terminés. Syntaxes acceptées par MariaDB 10.4.</p>
 */
public final class Lot6Migration {
    static final String VERROU = "stock_lot6_migration";
    static final String UNIQUE_ANCIEN = "uk_categorie_boutique_nom", UNIQUE_NOUVEAU = "uk_categorie_boutique_nom_cle";
    static final List<String> TABLES = List.of("client", "fournisseur", "produit", "categorie");
    private static final Set<Integer> DATE_HEURE = Set.of(Types.TIMESTAMP);

    /** Colonnes ajoutées, dans l'ordre d'ajout. */
    static final List<Lot1Migration.Champ> CHAMPS = champs();

    /**
     * Fiches qui peuvent se référer à une fiche de la corbeille : aucune ligne active ne doit le faire (la suppression est refusée
     * quand une référence existe). {@code actif} : la table a elle-même une corbeille, ses lignes supprimées sont ignorées.
     */
    record Reference(String table, String colonne, String cible, boolean corbeille) {}
    static final List<Reference> REFERENCES = List.of(
            new Reference("vente", "client_id", "client", false), new Reference("devis", "client_id", "client", false), new Reference("retour", "client_id", "client", false),
            new Reference("mouvement_caisse", "client_id", "client", false), new Reference("ajustement_solde_depart", "client_id", "client", false),
            new Reference("ligne_inventaire_creances", "client_id", "client", false),
            new Reference("achat", "fournisseur_id", "fournisseur", false), new Reference("retour", "fournisseur_id", "fournisseur", false),
            new Reference("mouvement_caisse", "fournisseur_id", "fournisseur", false), new Reference("ajustement_solde_depart", "fournisseur_id", "fournisseur", false),
            new Reference("ligne_inventaire_creances", "fournisseur_id", "fournisseur", false), new Reference("produit", "fournisseur_id", "fournisseur", true),
            new Reference("detail_achat", "produit_id", "produit", false), new Reference("detail_vente", "produit_id", "produit", false),
            new Reference("detail_devis", "produit_id", "produit", false), new Reference("detail_retour", "produit_id", "produit", false),
            new Reference("mouvement_stock", "produit_id", "produit", false), new Reference("produit", "categorie_id", "categorie", true));

    private Lot6Migration() {}

    private static List<Lot1Migration.Champ> champs() {
        var liste = new ArrayList<Lot1Migration.Champ>();
        for (String table : TABLES) {
            liste.add(new Lot1Migration.Champ(table, "supprime_le", "DATETIME(6) NULL", null, DATE_HEURE, 0, 0, true, null));
            liste.add(new Lot1Migration.Champ(table, "supprime_par", "BIGINT NULL", null, Set.of(Types.BIGINT), 0, 0, true, null));
            if (table.equals("categorie")) liste.add(new Lot1Migration.Champ(table, "nom_cle", "VARCHAR(255) NULL", null, Set.of(Types.VARCHAR), 255, 0, true, null));
        }
        return List.copyOf(liste);
    }

    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        if (args.length != 1 || !Set.of("--check", "--apply").contains(args[0])) throw new IllegalArgumentException("Utiliser --check ou --apply");
        try {
            var configuration = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { configuration.load(lecteur); }
            String url = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.url")).replace("createDatabaseIfNotExist=true", "createDatabaseIfNotExist=false");
            boolean appliquer = args[0].equals("--apply");
            if (appliquer) verifierAutorisationApplication(url, System.getenv("LOT6_BACKUP_DIR"));
            try (var c = DriverManager.getConnection(url, AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.password")))) { migrer(c, appliquer); }
        } catch (Exception e) {
            // Les pilotes peuvent inclure des valeurs métier dans leurs exceptions.
            System.err.println("Migration lot 6 interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Connexion ou opération SQL impossible ; aucune donnée privée publiée."));
            System.exit(1);
        }
    }

    static void verifierAutorisationApplication(String url, String dossier) throws Exception {
        BoutiquesMigration.verifierCibleSauvegarde(url);
        if (dossier == null || dossier.isBlank()) throw new IllegalStateException("Une sauvegarde SQL complète est requise : définir LOT6_BACKUP_DIR sur le répertoire créé par scripts/backup-stock-local.sh.");
        BoutiquesMigration.verifierSauvegarde(dossier);
    }

    static void migrer(Connection c, boolean appliquer) throws SQLException {
        if (!Set.of("H2", "MariaDB").contains(c.getMetaData().getDatabaseProductName())) throw new IllegalStateException("MariaDB ou H2 requis.");
        if (!c.getAutoCommit()) throw new IllegalStateException("Connexion dédiée sans transaction ouverte requise.");
        boolean verrou = false;
        try {
            c.setReadOnly(!appliquer);
            if (appliquer && maria(c)) {
                if (BoutiquesMigration.compter(c, "SELECT GET_LOCK('" + VERROU + "',0)") != 1) throw new IllegalStateException("Une migration lot 6 est déjà en cours.");
                verrou = true;
            }
            exigerLots3Et4(c);
            c.setReadOnly(!appliquer);
            verifierPresents(c);
            if (!appliquer) {
                System.out.println("Lot 6 vérifié en lecture seule ; éléments manquants : " + manquants(c) + ". Aucun stock, montant ni historique modifié.");
                return;
            }
            var avant = photographie(c); var schemas = schemasAnciens(c);
            boolean dejaFinalise = finalise(c);
            var valeurs = valeursLot6(c);
            ajouterColonnes(c);
            remplirCles(c);
            ajouterUnicite(c);
            verifierPresents(c);
            var apres = valeursLot6(c);
            // Une valeur de corbeille déjà présente ne change jamais ; seule une clé encore vide peut être remplie.
            boolean valeursConservees = valeurs.entrySet().stream().allMatch(e -> apres.get(e.getKey()).containsAll(e.getValue()));
            if (manquants(c) != 0 || !finalise(c) || !avant.equals(photographie(c)) || !schemas.equals(schemasAnciens(c)) || !valeursConservees)
                nonConforme("des données ou colonnes existantes ont changé ; conserver l'application arrêtée et examiner la sauvegarde");
            System.out.println(dejaFinalise ? "Lot 6 déjà migré ; aucune valeur réécrite."
                    : "Migration lot 6 terminée ; corbeille vide, unicité du nom des catégories portée par nom_cle, stocks, montants et mouvements inchangés.");
        } finally { if (verrou) BoutiquesMigration.compter(c, "SELECT RELEASE_LOCK('" + VERROU + "')"); }
    }

    /** Le lot 6 s'appuie sur les lots 3 et 4 (donc sur 1 et 2) : refus explicite tant qu'ils ne sont pas terminés et conformes. */
    private static void exigerLots3Et4(Connection c) throws SQLException {
        if (!BoutiquesMigration.tables(c).containsKey("boutique") || !Lot1Migration.finalise(c) || !Lot2Migration.finalise(c) || !Lot4Migration.finalise(c) || !Lot3Migration.finalise(c))
            throw new IllegalStateException("Les migrations des lots 3 et 4 doivent être terminées avant le lot 6 : lancer lot4Check, lot4Migrate puis lot3Check, lot3Migrate (LOT4_BACKUP_DIR, LOT3_BACKUP_DIR) après sauvegarde.");
        Lot4Migration.migrer(c, false);
        Lot3Migration.migrer(c, false);
    }

    /** Ajoute chaque colonne absente avec sa forme finale (toutes sont facultatives et vides). */
    static void ajouterColonnes(Connection c) throws SQLException {
        for (var champ : CHAMPS) if (!colonnes(c, champ.table()).containsKey(champ.nom()))
            sql(c, "ALTER TABLE " + table(c, champ.table()) + " ADD COLUMN " + champ.nom() + " " + champ.definition());
    }

    /** La clé d'une catégorie active est son nom ; une clé déjà posée n'est jamais réécrite. */
    static void remplirCles(Connection c) throws SQLException {
        sql(c, "UPDATE " + table(c, "categorie") + " SET nom_cle = nom WHERE nom_cle IS NULL AND supprime_le IS NULL AND nom IS NOT NULL");
    }

    /** Nouvelle unicité d'abord, puis retrait de l'ancienne : à aucun moment une catégorie n'est sans unicité, et la clé étrangère garde un index. */
    static void ajouterUnicite(Connection c) throws SQLException {
        if (unicitesCategorie(c).values().stream().noneMatch(colonnes -> colonnes.equals(List.of("boutique_id", "nom_cle"))))
            sql(c, "ALTER TABLE " + table(c, "categorie") + " ADD CONSTRAINT " + UNIQUE_NOUVEAU + " UNIQUE (boutique_id,nom_cle)");
        for (var index : unicitesCategorie(c).entrySet()) if (index.getValue().equals(List.of("boutique_id", "nom")))
            sql(c, "ALTER TABLE " + table(c, "categorie") + (maria(c) ? " DROP INDEX " : " DROP CONSTRAINT ") + identifiant(c, index.getKey()));
    }

    static boolean finalise(Connection c) throws SQLException {
        return manquants(c) == 0;
    }

    /** Éléments à ajouter : colonnes absentes, clés à remplir, nouvelle unicité absente, ancienne unicité encore présente. */
    private static int manquants(Connection c) throws SQLException {
        int manquants = 0;
        for (var champ : CHAMPS) if (!colonnes(c, champ.table()).containsKey(champ.nom())) manquants++;
        var categorie = colonnes(c, "categorie");
        if (categorie.containsKey("nom_cle") && categorie.containsKey("supprime_le")
                && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "categorie") + " WHERE nom_cle IS NULL AND supprime_le IS NULL AND nom IS NOT NULL") != 0) manquants++;
        var uniques = unicitesCategorie(c).values();
        if (uniques.stream().noneMatch(colonnes -> colonnes.equals(List.of("boutique_id", "nom_cle")))) manquants++;
        if (uniques.stream().anyMatch(colonnes -> colonnes.equals(List.of("boutique_id", "nom")))) manquants++;
        return manquants;
    }

    private static void verifierPresents(Connection c) throws SQLException {
        for (var champ : CHAMPS) {
            var col = colonnes(c, champ.table()).get(champ.nom());
            if (col != null) verifierColonne(champ, col);
        }
        for (var index : unicitesCategorie(c).entrySet())
            if (index.getKey().equalsIgnoreCase(UNIQUE_NOUVEAU) && !index.getValue().equals(List.of("boutique_id", "nom_cle")))
                nonConforme(UNIQUE_NOUVEAU + " doit porter sur boutique_id et nom_cle");
        verifierValeurs(c);
    }

    private static void verifierColonne(Lot1Migration.Champ champ, InscriptionEssaiMigration.Colonne col) {
        if (!champ.types().contains(col.type()) || col.autoIncrement() || col.nullable() != DatabaseMetaData.columnNullable
                || champ.taille() > 0 && col.taille() != champ.taille() || !defautNul(col.defaut()))
            nonConforme(champ.cle() + " doit être " + champ.definition());
    }

    private static void verifierValeurs(Connection c) throws SQLException {
        for (String nom : TABLES) {
            var col = colonnes(c, nom);
            if (col.containsKey("supprime_le") && col.containsKey("supprime_par") && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, nom)
                    + " WHERE supprime_le IS NULL AND supprime_par IS NOT NULL") != 0) nonConforme(nom + " : un auteur de suppression sans date de suppression");
        }
        var categorie = colonnes(c, "categorie");
        if (categorie.containsKey("nom_cle") && categorie.containsKey("supprime_le")) {
            if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "categorie") + " WHERE supprime_le IS NOT NULL AND nom_cle IS NOT NULL") != 0)
                nonConforme("la clé d'une catégorie de la corbeille doit être vide");
            if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "categorie")
                    + " WHERE supprime_le IS NULL AND nom_cle IS NOT NULL AND (nom IS NULL OR nom_cle <> nom)") != 0)
                nonConforme("la clé d'une catégorie active doit être son nom");
        }
        // Une suppression est refusée tant qu'une fiche s'y réfère : aucune ligne active ne doit viser une fiche de la corbeille.
        for (var ref : REFERENCES) {
            var cible = colonnes(c, ref.cible()); var source = colonnes(c, ref.table());
            if (!cible.containsKey("supprime_le") || source.isEmpty() || !source.containsKey(ref.colonne())) continue;
            boolean sourceAvecCorbeille = ref.corbeille() && source.containsKey("supprime_le");
            if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, ref.table()) + " WHERE " + ref.colonne() + " IN (SELECT id FROM " + table(c, ref.cible())
                    + " WHERE supprime_le IS NOT NULL)" + (sourceAvecCorbeille ? " AND supprime_le IS NULL" : "")) != 0)
                nonConforme(ref.table() + "." + ref.colonne() + " : une fiche active se réfère à une fiche de la corbeille");
        }
    }

    static Set<String> nouvelles(String table) {
        var resultat = new TreeSet<String>();
        for (var champ : CHAMPS) if (champ.table().equals(table)) resultat.add(champ.nom());
        return resultat;
    }

    /** Toutes les tables et toutes leurs anciennes colonnes, y compris stocks, montants et mouvements. */
    static Map<String, List<List<Object>>> photographie(Connection c) throws SQLException {
        var resultat = new TreeMap<String, List<List<Object>>>();
        for (String nom : BoutiquesMigration.tables(c).keySet()) {
            var exclues = nouvelles(nom); var lignes = new ArrayList<List<Object>>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table(c, nom) + " ORDER BY " + PhotographieJdbc.ordre(c, nom))) {
                while (r.next()) {
                    var ligne = new ArrayList<Object>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
                        if (exclues.contains(r.getMetaData().getColumnName(i).toLowerCase(Locale.ROOT))) continue;
                        ligne.add(PhotographieJdbc.valeur(r, i));
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

    /** Valeurs non vides des colonnes du lot 6 déjà présentes, par colonne : (identifiant, valeur en texte). Elles ne doivent jamais changer. */
    static Map<String, List<List<Object>>> valeursLot6(Connection c) throws SQLException {
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

    /**
     * Unicités de {@code categorie} : nom de la contrainte (celui qu'on retire avec DROP) et colonnes dans l'ordre. MariaDB nomme l'index comme
     * la contrainte ; H2 donne à l'index un autre nom, on lit donc ses contraintes.
     */
    static Map<String, List<String>> unicitesCategorie(Connection c) throws SQLException {
        if (maria(c)) return new TreeMap<>(BoutiquesMigration.indexesUniques(c, "categorie"));
        var resultat = new TreeMap<String, List<String>>();
        try (var s = c.prepareStatement("SELECT ku.constraint_name, ku.column_name FROM information_schema.table_constraints tc"
                + " JOIN information_schema.key_column_usage ku ON ku.constraint_catalog=tc.constraint_catalog AND ku.constraint_schema=tc.constraint_schema"
                + " AND ku.constraint_name=tc.constraint_name WHERE tc.table_schema=? AND tc.table_name=? AND tc.constraint_type='UNIQUE'"
                + " ORDER BY ku.constraint_name, ku.ordinal_position")) {
            s.setString(1, c.getSchema()); s.setString(2, BoutiquesMigration.tables(c).get("categorie"));
            try (var r = s.executeQuery()) { while (r.next()) resultat.computeIfAbsent(r.getString(1), ignore -> new ArrayList<>()).add(r.getString(2).toLowerCase(Locale.ROOT)); }
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
    private static String identifiant(Connection c, String nom) {
        if (nom == null || !nom.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("Identifiant SQL inattendu.");
        String q; try { q = c.getMetaData().getIdentifierQuoteString().strip(); } catch (SQLException e) { throw new IllegalStateException("Identifiant SQL inattendu."); }
        return q + nom + q;
    }
    static void sql(Connection c, String requete) throws SQLException { BoutiquesMigration.executer(c, requete); }
    private static void nonConforme(String raison) { throw new IllegalStateException("Schéma lot 6 non conforme : " + raison + ". Aucune réparation automatique."); }
}
