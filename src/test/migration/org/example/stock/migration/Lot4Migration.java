package org.example.stock.migration;

import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Colonnes du lot 4 (reçus) : deux réglages par boutique, {@code boutique.format_recu} (VARCHAR(16) NOT NULL DEFAULT 'A4' :
 * {@code A4} ou {@code TICKET_80}) et {@code boutique.proposer_impression_recu} (booléen NOT NULL DEFAULT TRUE).
 * Aucune table n'est créée et aucune valeur n'est reprise : chaque boutique existante reçoit les valeurs par défaut (A4, bouton
 * proposé), qui reproduisent le comportement d'avant le lot. Une relance n'écrit jamais une valeur déjà présente, donc ne
 * réécrit pas un réglage choisi par le gérant depuis. Le lot 2 doit être terminé (et, par lui, le lot 1 et les précédents).
 * Aucun stock, montant ou mouvement n'est modifié. Seules les syntaxes acceptées par MariaDB 10.4 sont employées.
 */
public final class Lot4Migration {
    static final String VERROU = "stock_lot4_migration";
    static final Set<String> FORMATS = Set.of("A4", "TICKET_80");
    private static final Set<Integer> BOOLEEN = Set.of(Types.BOOLEAN, Types.BIT, Types.TINYINT);

    /** Colonnes ajoutées à {@code boutique}, dans l'ordre d'ajout. */
    static final List<Lot1Migration.Champ> CHAMPS = List.of(
            new Lot1Migration.Champ("boutique", "format_recu", "VARCHAR(16) NOT NULL DEFAULT 'A4'", null, Set.of(Types.VARCHAR), 16, 0, false, "A4"),
            new Lot1Migration.Champ("boutique", "proposer_impression_recu", "BOOLEAN NOT NULL DEFAULT TRUE", null, BOOLEEN, 0, 0, false, "TRUE"));
    private Lot4Migration() {}

    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        if (args.length != 1 || !Set.of("--check", "--apply").contains(args[0])) throw new IllegalArgumentException("Utiliser --check ou --apply");
        try {
            var configuration = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { configuration.load(lecteur); }
            String url = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.url")).replace("createDatabaseIfNotExist=true", "createDatabaseIfNotExist=false");
            boolean appliquer = args[0].equals("--apply");
            if (appliquer) verifierAutorisationApplication(url, System.getenv("LOT4_BACKUP_DIR"));
            try (var c = DriverManager.getConnection(url, AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.password")))) { migrer(c, appliquer); }
        } catch (Exception e) {
            // Les pilotes peuvent inclure des valeurs métier dans leurs exceptions.
            System.err.println("Migration lot 4 interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Connexion ou opération SQL impossible ; aucune donnée privée publiée."));
            System.exit(1);
        }
    }

    static void verifierAutorisationApplication(String url, String dossier) throws Exception {
        BoutiquesMigration.verifierCibleSauvegarde(url);
        if (dossier == null || dossier.isBlank()) throw new IllegalStateException("Une sauvegarde SQL complète est requise : définir LOT4_BACKUP_DIR sur le répertoire créé par scripts/backup-stock-local.sh.");
        BoutiquesMigration.verifierSauvegarde(dossier);
    }

    static void migrer(Connection c, boolean appliquer) throws SQLException {
        if (!Set.of("H2", "MariaDB").contains(c.getMetaData().getDatabaseProductName())) throw new IllegalStateException("MariaDB ou H2 requis.");
        if (!c.getAutoCommit()) throw new IllegalStateException("Connexion dédiée sans transaction ouverte requise.");
        boolean verrou = false;
        try {
            c.setReadOnly(!appliquer);
            if (appliquer && maria(c)) {
                if (BoutiquesMigration.compter(c, "SELECT GET_LOCK('" + VERROU + "',0)") != 1) throw new IllegalStateException("Une migration lot 4 est déjà en cours.");
                verrou = true;
            }
            exigerLot2(c);
            c.setReadOnly(!appliquer);
            verifierPresents(c);
            if (!appliquer) {
                System.out.println("Lot 4 vérifié en lecture seule ; colonnes manquantes : " + manquants(c) + ". Aucun stock, montant ni historique modifié.");
                return;
            }
            var avant = photographie(c); var schemas = schemasAnciens(c);
            boolean dejaFinalise = finalise(c);
            var valeurs = valeursLot4(c);
            ajouterColonnes(c);
            verifierPresents(c);
            var apres = valeursLot4(c);
            // Les valeurs déjà présentes ne changent jamais ; une colonne nouvelle reçoit seulement sa valeur par défaut.
            boolean valeursConservees = valeurs.entrySet().stream().allMatch(e -> e.getValue().equals(apres.get(e.getKey())));
            if (manquants(c) != 0 || !finalise(c) || !avant.equals(photographie(c)) || !schemas.equals(schemasAnciens(c)) || !valeursConservees)
                nonConforme("des données ou colonnes existantes ont changé ; conserver l'application arrêtée et examiner la sauvegarde");
            System.out.println(dejaFinalise ? "Lot 4 déjà migré ; aucune valeur réécrite."
                    : "Migration lot 4 terminée ; format A4 et bouton « Imprimer le reçu » proposé pour chaque boutique, stocks, montants et mouvements inchangés.");
        } finally { if (verrou) BoutiquesMigration.compter(c, "SELECT RELEASE_LOCK('" + VERROU + "')"); }
    }

    /** Le lot 4 s'appuie sur le lot 2 (donc sur le lot 1) : refus explicite tant qu'il n'est pas terminé et conforme. */
    private static void exigerLot2(Connection c) throws SQLException {
        if (!BoutiquesMigration.tables(c).containsKey("boutique") || !Lot1Migration.finalise(c) || !Lot2Migration.finalise(c))
            throw new IllegalStateException("La migration du lot 2 doit être terminée avant le lot 4 : lancer lot2Check puis lot2Migrate (LOT2_BACKUP_DIR) après sauvegarde.");
        Lot2Migration.migrer(c, false);
    }

    /** Ajoute chaque colonne absente avec sa forme finale (valeur par défaut comprise) : MariaDB et H2 remplissent les lignes existantes. */
    static void ajouterColonnes(Connection c) throws SQLException {
        for (var champ : CHAMPS) if (!colonnes(c, champ.table()).containsKey(champ.nom()))
            sql(c, "ALTER TABLE " + table(c, champ.table()) + " ADD COLUMN " + champ.nom() + " " + champ.definition());
    }

    static boolean finalise(Connection c) throws SQLException {
        return manquants(c) == 0;
    }

    private static int manquants(Connection c) throws SQLException {
        int manquants = 0;
        for (var champ : CHAMPS) if (!colonnes(c, champ.table()).containsKey(champ.nom())) manquants++;
        return manquants;
    }

    private static void verifierPresents(Connection c) throws SQLException {
        for (var champ : CHAMPS) {
            var col = colonnes(c, champ.table()).get(champ.nom());
            if (col != null) verifierColonne(champ, col);
        }
        verifierValeurs(c);
    }

    private static void verifierColonne(Lot1Migration.Champ champ, InscriptionEssaiMigration.Colonne col) {
        boolean booleen = champ.types().equals(BOOLEEN);
        if (!champ.types().contains(col.type()) || col.autoIncrement() || col.nullable() != DatabaseMetaData.columnNoNulls
                || !booleen && champ.taille() > 0 && col.taille() != champ.taille() || booleen && col.type() == Types.BIT && col.taille() != 1
                || !(booleen ? PlateformeMigration.defautConforme(col.defaut(), true) : defautEgal(col.defaut(), champ.defaut())))
            nonConforme(champ.cle() + " doit être " + champ.definition());
    }

    private static void verifierValeurs(Connection c) throws SQLException {
        var boutique = colonnes(c, "boutique");
        if (boutique.containsKey("format_recu") && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "boutique")
                + " WHERE format_recu NOT IN ('A4','TICKET_80')") != 0) nonConforme("le format de reçu d'une boutique est inconnu");
        var proposer = boutique.get("proposer_impression_recu");
        if (proposer != null) {
            String valeurs = proposer.type() == Types.BOOLEAN ? "FALSE,TRUE" : "0,1";
            if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "boutique") + " WHERE proposer_impression_recu IS NULL OR proposer_impression_recu NOT IN ("
                    + valeurs + ")") != 0) nonConforme("seules les valeurs zéro et un sont autorisées pour proposer_impression_recu");
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

    /** Valeurs des colonnes du lot 4 déjà présentes, par colonne et identifiant de boutique (booléens normalisés). */
    static Map<String, List<List<Object>>> valeursLot4(Connection c) throws SQLException {
        var resultat = new TreeMap<String, List<List<Object>>>();
        for (var champ : CHAMPS) if (colonnes(c, champ.table()).containsKey(champ.nom())) {
            var valeurs = new ArrayList<List<Object>>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT id," + champ.nom() + " FROM " + table(c, champ.table()) + " ORDER BY id")) {
                while (r.next()) valeurs.add(Arrays.asList(r.getLong(1), champ.types().equals(BOOLEEN) ? (Object) r.getBoolean(2) : r.getString(2)));
            }
            resultat.put(champ.cle(), valeurs);
        }
        return resultat;
    }

    private static boolean defautEgal(String valeur, String attendu) {
        if (valeur == null) return false;
        String v = valeur.strip();
        while (v.startsWith("(") && v.endsWith(")")) v = v.substring(1, v.length() - 1).strip();
        if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) v = v.substring(1, v.length() - 1);
        try { return new BigDecimal(v).compareTo(new BigDecimal(attendu)) == 0; }
        catch (NumberFormatException e) { return v.equals(attendu); }
    }

    private static boolean maria(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static Map<String, InscriptionEssaiMigration.Colonne> colonnes(Connection c, String nom) throws SQLException { return InscriptionEssaiMigration.colonnes(c, nom); }
    private static String table(Connection c, String nom) throws SQLException {
        String physique = BoutiquesMigration.tables(c).get(nom);
        if (physique == null || !physique.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("Identifiant SQL inattendu.");
        String q = c.getMetaData().getIdentifierQuoteString().strip(); return q + physique + q;
    }
    static void sql(Connection c, String requete) throws SQLException { BoutiquesMigration.executer(c, requete); }
    private static void nonConforme(String raison) { throw new IllegalStateException("Schéma lot 4 non conforme : " + raison + ". Aucune réparation automatique."); }
}
