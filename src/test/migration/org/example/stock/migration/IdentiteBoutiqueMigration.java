package org.example.stock.migration;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Téléphone secondaire et logo facultatifs ; aucun fichier, téléphone ou nom n'est inventé. */
public final class IdentiteBoutiqueMigration {
    static final String TABLE = "logo_boutique", CHAMP = "telephone_secondaire", VERROU = "stock_identite_boutique_migration";
    /** Taille du PNG conservé ; les fichiers d'entrée peuvent faire deux Mio avant normalisation. */
    static final int TAILLE_MAX = 384 * 1024;
    private IdentiteBoutiqueMigration() {}

    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        if (args.length != 1 || !Set.of("--check", "--apply").contains(args[0])) throw new IllegalArgumentException("Utiliser --check ou --apply");
        try {
            var configuration = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { configuration.load(lecteur); }
            String url = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.url")).replace("createDatabaseIfNotExist=true", "createDatabaseIfNotExist=false");
            boolean appliquer = args[0].equals("--apply");
            if (appliquer) verifierAutorisationApplication(url, System.getenv("IDENTITE_BOUTIQUE_BACKUP_DIR"));
            try (var c = DriverManager.getConnection(url, AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.password")))) { migrer(c, appliquer); }
        } catch (Exception e) {
            System.err.println("Migration identité boutique interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Connexion ou opération SQL impossible ; aucune donnée privée publiée."));
            System.exit(1);
        }
    }

    static void verifierAutorisationApplication(String url, String dossier) throws Exception {
        BoutiquesMigration.verifierCibleSauvegarde(url);
        if (dossier == null || dossier.isBlank()) throw new IllegalStateException("Une sauvegarde SQL complète est requise : définir IDENTITE_BOUTIQUE_BACKUP_DIR sur le répertoire créé par scripts/backup-stock-local.sh.");
        BoutiquesMigration.verifierSauvegarde(dossier);
    }

    static void migrer(Connection c, boolean appliquer) throws SQLException {
        if (!Set.of("H2", "MariaDB").contains(c.getMetaData().getDatabaseProductName())) throw new IllegalStateException("MariaDB ou H2 requis.");
        if (!c.getAutoCommit()) throw new IllegalStateException("Connexion dédiée sans transaction ouverte requise.");
        boolean verrou = false;
        try {
            c.setReadOnly(!appliquer);
            if (appliquer && maria(c)) {
                if (BoutiquesMigration.compter(c, "SELECT GET_LOCK('" + VERROU + "',0)") != 1) throw new IllegalStateException("Une migration identité boutique est déjà en cours.");
                verrou = true;
            }
            verifierPreconditions(c);
            TresorerieMigration.migrer(c, false);
            c.setReadOnly(!appliquer);
            verifierPresents(c);
            if (!appliquer) {
                System.out.println("Identité boutique vérifiée en lecture seule ; éléments manquants : " + manquants(c) + ". Aucun logo, téléphone ou historique modifié.");
                return;
            }
            var avant = photographie(c); var schemas = schemasAnciens(c); var telephones = telephones(c); var logos = logos(c);
            if (!colonnes(c, "boutique").containsKey(CHAMP)) sql(c, "ALTER TABLE " + table(c, "boutique") + " ADD COLUMN " + CHAMP + " VARCHAR(50) NULL");
            if (!existe(c, TABLE)) creerLogos(c);
            verifierPresents(c);
            if (manquants(c) != 0 || !avant.equals(photographie(c)) || !schemas.equals(schemasAnciens(c)) || !logos.equals(logos(c))
                    || telephones != null && !telephones.equals(telephones(c))) nonConforme("des données ou colonnes existantes ont changé ; conserver l'application arrêtée et examiner la sauvegarde");
            if (telephones == null && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "boutique") + " WHERE " + CHAMP + " IS NOT NULL") != 0)
                nonConforme("un téléphone secondaire historique a été inventé");
            System.out.println("Migration identité boutique terminée ; dix-neuf anciennes tables conservées, aucun logo ni numéro historique inventé.");
        } finally { if (verrou) BoutiquesMigration.compter(c, "SELECT RELEASE_LOCK('" + VERROU + "')"); }
    }

    private static void verifierPreconditions(Connection c) throws SQLException {
        var tables = BoutiquesMigration.tables(c);
        if (!tables.keySet().containsAll(Set.of("compte_tresorerie_boutique", "operation_tresorerie"))
                || !colonnes(c, "mouvement_caisse").keySet().containsAll(Set.of("compte", "operation_tresorerie_id"))
                || TresorerieMigration.comptesManquants(c) != 0) throw new IllegalStateException("La migration trésorerie doit être terminée avant l'identité de boutique.");
        boolean lien = false;
        try (var r = c.getMetaData().getImportedKeys(c.getCatalog(), c.getSchema(), tables.get("mouvement_caisse"))) {
            while (r.next()) if (r.getString("FKCOLUMN_NAME").equalsIgnoreCase("operation_tresorerie_id")
                    && r.getString("PKTABLE_NAME").equalsIgnoreCase("operation_tresorerie")) lien = true;
        }
        if (!lien) throw new IllegalStateException("La migration trésorerie doit être terminée avant l'identité de boutique.");
    }

    static void creerLogos(Connection c) throws SQLException {
        sql(c, "CREATE TABLE " + TABLE + " (boutique_id BIGINT NOT NULL PRIMARY KEY,contenu " + (maria(c) ? "LONGBLOB" : "BLOB")
                + " NOT NULL,revision VARCHAR(36) NOT NULL,CONSTRAINT fk_logo_boutique FOREIGN KEY(boutique_id) REFERENCES " + table(c, "boutique") + "(id))"
                + (maria(c) ? " ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" : ""));
    }

    private static void verifierPresents(Connection c) throws SQLException {
        if (colonnes(c, "boutique").containsKey(CHAMP)) {
            var champ = colonnes(c, "boutique").get(CHAMP);
            if (champ.type() != Types.VARCHAR || champ.taille() != 50 || champ.nullable() != DatabaseMetaData.columnNullable || champ.autoIncrement() || !defautNul(champ.defaut()))
                nonConforme("telephone_secondaire doit être VARCHAR(50) facultatif sans valeur par défaut");
        }
        if (!existe(c, TABLE)) return;
        var cols = colonnes(c, TABLE);
        if (!cols.keySet().equals(Set.of("boutique_id", "contenu", "revision"))) nonConforme("colonnes de logo inattendues");
        for (var col : cols.values()) if (col.nullable() != DatabaseMetaData.columnNoNulls || col.autoIncrement() || !defautNul(col.defaut())) nonConforme("les champs du logo doivent être obligatoires et sans valeur par défaut");
        if (cols.get("boutique_id").type() != Types.BIGINT) nonConforme("la clé du logo doit être BIGINT");
        if (!Set.of(Types.BLOB, Types.LONGVARBINARY).contains(cols.get("contenu").type())
                || maria(c) && !cols.get("contenu").nomType().equalsIgnoreCase("LONGBLOB")) nonConforme("contenu binaire LONGBLOB requis");
        if (cols.get("revision").type() != Types.VARCHAR || cols.get("revision").taille() != 36) nonConforme("révision VARCHAR(36) requise");
        var cles = new TreeMap<Integer, String>();
        try (var r = c.getMetaData().getPrimaryKeys(c.getCatalog(), c.getSchema(), BoutiquesMigration.tables(c).get(TABLE))) {
            while (r.next()) cles.put(r.getInt("KEY_SEQ"), r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        if (!new ArrayList<>(cles.values()).equals(List.of("boutique_id"))) nonConforme("un seul logo par boutique est requis");
        int relations = 0;
        try (var r = c.getMetaData().getImportedKeys(c.getCatalog(), c.getSchema(), BoutiquesMigration.tables(c).get(TABLE))) {
            while (r.next()) {
                relations++;
                if (r.getShort("KEY_SEQ") != 1 || !r.getString("FKCOLUMN_NAME").equalsIgnoreCase("boutique_id")
                        || !r.getString("PKTABLE_NAME").equalsIgnoreCase("boutique") || !r.getString("PKCOLUMN_NAME").equalsIgnoreCase("id")
                        || c.getCatalog() != null && r.getString("PKTABLE_CAT") != null && !c.getCatalog().equalsIgnoreCase(r.getString("PKTABLE_CAT"))
                        || c.getSchema() != null && r.getString("PKTABLE_SCHEM") != null && !c.getSchema().equalsIgnoreCase(r.getString("PKTABLE_SCHEM"))
                        || !Set.of((int) DatabaseMetaData.importedKeyNoAction, (int) DatabaseMetaData.importedKeyRestrict).contains((int) r.getShort("DELETE_RULE"))
                        || !Set.of((int) DatabaseMetaData.importedKeyNoAction, (int) DatabaseMetaData.importedKeyRestrict).contains((int) r.getShort("UPDATE_RULE"))) nonConforme("clé étrangère boutique sans cascade requise");
            }
        }
        if (relations != 1) nonConforme("clé étrangère boutique requise");
        if (maria(c)) try (var s = c.prepareStatement("SELECT engine,table_collation FROM information_schema.tables WHERE table_schema=? AND table_name=?")) {
            s.setString(1, c.getCatalog()); s.setString(2, BoutiquesMigration.tables(c).get(TABLE));
            try (var r = s.executeQuery()) { if (!r.next() || !"InnoDB".equalsIgnoreCase(r.getString(1)) || !"utf8mb4_unicode_ci".equalsIgnoreCase(r.getString(2))) nonConforme("InnoDB et utf8mb4_unicode_ci requis"); }
        }
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, TABLE) + " l LEFT JOIN " + table(c, "boutique") + " b ON b.id=l.boutique_id WHERE b.id IS NULL") != 0) nonConforme("logo rattaché à une boutique absente");
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT contenu,revision FROM " + table(c, TABLE))) {
            while (r.next()) {
                if (!revisionValide(r.getString(2))) nonConforme("révision de logo invalide");
                long longueur = r.getBlob(1).length();
                if (longueur < 8 || longueur > TAILLE_MAX) nonConforme("taille du logo invalide");
                verifierPng(r.getBytes(1));
            }
        }
    }

    private static void verifierPng(byte[] contenu) {
        if (!Arrays.equals(Arrays.copyOf(contenu, 8), new byte[]{(byte)137,80,78,71,13,10,26,10})) nonConforme("logo PNG normalisé requis");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(contenu))) {
            var lecteurs = ImageIO.getImageReaders(input);
            if (!lecteurs.hasNext()) nonConforme("logo PNG illisible");
            var lecteur = lecteurs.next();
            try {
                lecteur.setInput(input, true, true);
                if (!lecteur.getFormatName().equalsIgnoreCase("PNG") || lecteur.getWidth(0) < 1 || lecteur.getHeight(0) < 1
                        || lecteur.getWidth(0) > 512 || lecteur.getHeight(0) > 512) nonConforme("dimensions du logo normalisé invalides");
                lecteur.read(0);
            } finally { lecteur.dispose(); }
        } catch (IllegalStateException e) { throw e; }
        catch (Exception e) { nonConforme("logo PNG illisible"); }
    }

    static Map<String, List<List<Object>>> photographie(Connection c) throws SQLException {
        var resultat = new TreeMap<String, List<List<Object>>>();
        for (String nom : BoutiquesMigration.tables(c).keySet()) {
            if (nom.equals(TABLE)) continue;
            var lignes = new ArrayList<List<Object>>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table(c, nom) + " ORDER BY " + PhotographieJdbc.ordre(c, nom))) {
                while (r.next()) {
                    var ligne = new ArrayList<Object>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) if (!(nom.equals("boutique") && CHAMP.equalsIgnoreCase(r.getMetaData().getColumnName(i)))) ligne.add(PhotographieJdbc.valeur(r, i));
                    lignes.add(ligne);
                }
            }
            resultat.put(nom, lignes);
        }
        return resultat;
    }

    static Map<String, Map<String, InscriptionEssaiMigration.Colonne>> schemasAnciens(Connection c) throws SQLException {
        var resultat = new TreeMap<String, Map<String, InscriptionEssaiMigration.Colonne>>();
        for (String nom : BoutiquesMigration.tables(c).keySet()) if (!nom.equals(TABLE)) {
            var champs = new TreeMap<>(colonnes(c, nom));
            if (nom.equals("boutique")) champs.remove(CHAMP);
            resultat.put(nom, champs);
        }
        return resultat;
    }

    static List<List<Object>> logos(Connection c) throws SQLException {
        if (!existe(c, TABLE)) return List.of();
        var resultat = new ArrayList<List<Object>>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT boutique_id,contenu,revision FROM " + table(c, TABLE) + " ORDER BY boutique_id")) {
            while (r.next()) resultat.add(Arrays.asList(r.getLong(1), PhotographieJdbc.valeur(r, 2), r.getString(3)));
        }
        return resultat;
    }

    private static List<List<Object>> telephones(Connection c) throws SQLException {
        if (!colonnes(c, "boutique").containsKey(CHAMP)) return null;
        var resultat = new ArrayList<List<Object>>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT id," + CHAMP + " FROM " + table(c, "boutique") + " ORDER BY id")) {
            while (r.next()) resultat.add(Arrays.asList(r.getLong(1), r.getString(2)));
        }
        return resultat;
    }
    private static boolean revisionValide(String revision) { try { return revision != null && UUID.fromString(revision).toString().equals(revision); } catch (IllegalArgumentException e) { return false; } }
    private static boolean defautNul(String valeur) { return valeur == null || valeur.replace("(", "").replace(")", "").strip().equalsIgnoreCase("NULL"); }
    private static int manquants(Connection c) throws SQLException { return (existe(c, TABLE) ? 0 : 1) + (colonnes(c, "boutique").containsKey(CHAMP) ? 0 : 1); }
    private static boolean maria(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static boolean existe(Connection c, String nom) throws SQLException { return BoutiquesMigration.tables(c).containsKey(nom); }
    private static Map<String, InscriptionEssaiMigration.Colonne> colonnes(Connection c, String nom) throws SQLException { return InscriptionEssaiMigration.colonnes(c, nom); }
    private static String table(Connection c, String nom) throws SQLException {
        String physique = BoutiquesMigration.tables(c).get(nom);
        if (physique == null || !physique.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("Identifiant SQL inattendu.");
        String q = c.getMetaData().getIdentifierQuoteString().strip(); return q + physique + q;
    }
    static void sql(Connection c, String requete) throws SQLException { BoutiquesMigration.executer(c, requete); }
    private static void nonConforme(String raison) { throw new IllegalStateException("Schéma identité boutique non conforme : " + raison + ". Aucune réparation automatique."); }
}
