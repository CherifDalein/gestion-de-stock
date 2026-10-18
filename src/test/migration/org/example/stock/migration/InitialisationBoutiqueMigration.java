package org.example.stock.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Ferme la préparation des boutiques déjà utilisées, sans changer leurs stocks ni leur caisse. */
public final class InitialisationBoutiqueMigration {
    static final String CHAMP = "initialisation_terminee", VERROU = "stock_initialisation_boutique_migration";
    private InitialisationBoutiqueMigration() {}

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
            if (appliquer) verifierAutorisationApplication(url, System.getenv("INITIALISATION_BACKUP_DIR"));
            try (var c = DriverManager.getConnection(url,
                    AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.password")))) { migrer(c, appliquer); }
        } catch (Exception e) {
            System.err.println("Migration du démarrage interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Connexion ou opération SQL impossible ; aucune valeur JDBC publiée."));
            System.exit(1);
        }
    }

    static void verifierAutorisationApplication(String url, String dossier) throws Exception {
        BoutiquesMigration.verifierCibleSauvegarde(url);
        if (dossier == null || dossier.isBlank()) throw new IllegalStateException(
                "Une sauvegarde SQL complète est requise : lancer scripts/backup-stock-local.sh puis définir INITIALISATION_BACKUP_DIR sur son répertoire.");
        BoutiquesMigration.verifierSauvegarde(dossier);
    }

    static void migrer(Connection c, boolean appliquer) throws SQLException {
        boolean maria = c.getMetaData().getDatabaseProductName().equals("MariaDB"), verrou = false;
        if (!maria && !c.getMetaData().getDatabaseProductName().equals("H2")) throw new IllegalStateException("MariaDB ou H2 requis.");
        if (!c.getAutoCommit()) throw new IllegalStateException("Connexion dédiée sans transaction ouverte requise.");
        try {
            c.setReadOnly(!appliquer);
            if (appliquer && maria) {
                if (BoutiquesMigration.compter(c, "SELECT GET_LOCK('" + VERROU + "',0)") != 1)
                    throw new IllegalStateException("Une migration du démarrage est déjà en cours.");
                verrou = true;
            }
            for (var champ : TarificationMigration.CHAMPS)
                if (!InscriptionEssaiMigration.colonnes(c, champ.table()).containsKey(champ.nom()))
                    throw new IllegalStateException("La migration des tarifs doit être terminée avant le démarrage des boutiques.");
            TarificationMigration.migrer(c, false);
            c.setReadOnly(!appliquer);
            verifierColonne(c);
            var utilises = boutiquesUtilisees(c);
            boolean present = presente(c);
            var etatsAvant = etats(c);
            var fermer = new TreeSet<>(utilises);
            fermer.removeIf(id -> Boolean.TRUE.equals(etatsAvant.get(id)));
            if (!appliquer) {
                System.out.println("Démarrage vérifié en lecture seule ; statut " + (present ? "présent" : "à ajouter")
                        + ", boutiques utilisées restant à fermer : " + fermer.size() + ". Aucune donnée modifiée.");
                return;
            }
            var avant = photographie(c);
            var schemasAvant = schemasAnciens(c);
            var tablesAvant = BoutiquesMigration.tables(c);
            if (!present) BoutiquesMigration.executer(c, "ALTER TABLE " + table(c, "boutique")
                    + " ADD COLUMN " + CHAMP + " BOOLEAN NOT NULL DEFAULT FALSE");
            verifierColonne(c);
            // L'ADD COLUMN est auto-validé par MariaDB : cette fermeture reprend aussi une interruption juste après l'ajout.
            try (var requete = c.prepareStatement("UPDATE " + table(c, "boutique") + " SET " + CHAMP + "=TRUE WHERE id=? AND " + CHAMP + "=FALSE")) {
                for (Long id : fermer) { requete.setLong(1, id); requete.addBatch(); }
                if (!fermer.isEmpty()) requete.executeBatch();
            }
            if (!avant.equals(photographie(c)) || !schemasAvant.equals(schemasAnciens(c)) || !tablesAvant.equals(BoutiquesMigration.tables(c)))
                throw new IllegalStateException("Les données ou anciennes colonnes ont changé ; garder l'application arrêtée et examiner la sauvegarde.");
            var attendus = new TreeMap<Long, Boolean>();
            for (Long id : tousLesIds(c)) attendus.put(id, Boolean.TRUE.equals(etatsAvant.get(id)) || utilises.contains(id));
            if (!attendus.equals(etats(c))) throw new IllegalStateException("Le statut d'une boutique est inattendu ; garder l'application arrêtée.");
            System.out.println("Migration du démarrage terminée ; dix-sept tables et toutes les anciennes données conservées. Boutiques utilisées fermées : " + fermer.size() + ".");
        } finally { if (verrou) BoutiquesMigration.compter(c, "SELECT RELEASE_LOCK('" + VERROU + "')"); }
    }

    private static void verifierColonne(Connection c) throws SQLException {
        var col = InscriptionEssaiMigration.colonnes(c, "boutique").get(CHAMP);
        if (col == null) return;
        if (col.nullable() != DatabaseMetaData.columnNoNulls || col.autoIncrement()
                || !Set.of(Types.BOOLEAN, Types.BIT, Types.TINYINT).contains(col.type())
                || col.type() == Types.BIT && col.taille() != 1 || !PlateformeMigration.defautConforme(col.defaut(), false))
            throw new IllegalStateException("Statut de démarrage non conforme : booléen obligatoire avec FALSE pour défaut requis. Aucune réparation automatique.");
        String valeurs = col.type() == Types.BOOLEAN ? "FALSE,TRUE" : "0,1";
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "boutique") + " WHERE " + CHAMP + " IS NULL OR " + CHAMP + " NOT IN (" + valeurs + ")") != 0)
            throw new IllegalStateException("Statut de démarrage non conforme : seules les valeurs zéro et un sont autorisées. Aucune réparation automatique.");
    }

    static Set<Long> boutiquesUtilisees(Connection c) throws SQLException {
        String condition = "EXISTS (SELECT 1 FROM " + table(c, "achat") + " a WHERE a.boutique_id=b.id)"
                + " OR EXISTS (SELECT 1 FROM " + table(c, "vente") + " v WHERE v.boutique_id=b.id)"
                + " OR EXISTS (SELECT 1 FROM " + table(c, "mouvement_caisse") + " m WHERE m.boutique_id=b.id AND (m.source IS NULL OR m.source<>'SOLDE_INITIAL' OR m.type IS NULL OR m.type<>'OUVERTURE'))"
                + " OR EXISTS (SELECT 1 FROM " + table(c, "mouvement_stock") + " m WHERE m.boutique_id=b.id AND m.type='AJUSTEMENT')"
                + " OR EXISTS (SELECT 1 FROM " + table(c, "produit") + " p WHERE p.boutique_id=b.id AND p.quantite>0 AND NOT EXISTS"
                + " (SELECT 1 FROM " + table(c, "mouvement_stock") + " m WHERE m.produit_id=p.id AND m.boutique_id=b.id AND m.type='INITIAL'))";
        var resultat = new TreeSet<Long>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT b.id FROM " + table(c, "boutique") + " b WHERE " + condition + " ORDER BY b.id")) {
            while (r.next()) resultat.add(r.getLong(1));
        }
        return resultat;
    }

    static Map<Long, Boolean> etats(Connection c) throws SQLException {
        var resultat = new TreeMap<Long, Boolean>();
        if (!presente(c)) return resultat;
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT id," + CHAMP + " FROM " + table(c, "boutique") + " ORDER BY id")) {
            while (r.next()) resultat.put(r.getLong(1), r.getBoolean(2));
        }
        return resultat;
    }

    /** Toutes les colonnes des dix-sept tables, hormis le seul nouveau statut. */
    static Map<String, List<List<Object>>> photographie(Connection c) throws SQLException {
        var resultat = new TreeMap<String, List<List<Object>>>();
        for (String nom : BoutiquesMigration.tables(c).keySet()) {
            var lignes = new ArrayList<List<Object>>();
            String ordre = switch (nom) { case "operation_creation" -> "jeton"; case "inscription_boutique" -> "email";
                case "reinitialisation_mot_de_passe" -> "utilisateur_id"; default -> "id"; };
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table(c, nom) + " ORDER BY " + ordre)) {
                while (r.next()) {
                    var ligne = new ArrayList<Object>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
                        if (!(nom.equals("boutique") && CHAMP.equalsIgnoreCase(r.getMetaData().getColumnName(i)))) ligne.add(r.getObject(i));
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
            var colonnes = new TreeMap<>(InscriptionEssaiMigration.colonnes(c, nom));
            if (nom.equals("boutique")) colonnes.remove(CHAMP);
            resultat.put(nom, colonnes);
        }
        return resultat;
    }
    private static Set<Long> tousLesIds(Connection c) throws SQLException {
        var resultat = new TreeSet<Long>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT id FROM " + table(c, "boutique"))) { while (r.next()) resultat.add(r.getLong(1)); }
        return resultat;
    }
    private static boolean presente(Connection c) throws SQLException { return InscriptionEssaiMigration.colonnes(c, "boutique").containsKey(CHAMP); }
    private static String table(Connection c, String nom) throws SQLException {
        String physique = BoutiquesMigration.tables(c).get(nom);
        if (physique == null || !physique.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("Identifiant SQL inattendu.");
        return c.getMetaData().getIdentifierQuoteString().strip() + physique + c.getMetaData().getIdentifierQuoteString().strip();
    }
}
