package org.example.stock.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Ajouts uniquement : les prix, factures, stocks et soldes existants restent identiques. */
public final class TarificationMigration {
    static final String VERROU = "stock_tarification_migration";
    record Champ(String table, String nom, String declaration) {}
    static final List<Champ> CHAMPS = List.of(
            new Champ("produit", "prix_definis", "BOOLEAN NOT NULL DEFAULT TRUE"),
            new Champ("detail_achat", "prix_vente_unitaire", "DECIMAL(17,2) NULL"),
            new Champ("mouvement_stock", "prix_vente", "DECIMAL(17,2) NULL"));
    private TarificationMigration() {}

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
            if (appliquer) verifierAutorisationApplication(url, System.getenv("TARIFICATION_BACKUP_DIR"));
            try (var c = DriverManager.getConnection(url,
                    AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.password")))) { migrer(c, appliquer); }
        } catch (Exception e) {
            // Une exception du pilote peut contenir des valeurs privées.
            System.err.println("Migration des tarifs interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Connexion ou opération SQL impossible ; aucune valeur JDBC publiée."));
            System.exit(1);
        }
    }

    static void verifierAutorisationApplication(String url, String dossier) throws Exception {
        BoutiquesMigration.verifierCibleSauvegarde(url);
        if (dossier == null || dossier.isBlank()) throw new IllegalStateException(
                "Une sauvegarde SQL complète est requise : lancer scripts/backup-stock-local.sh puis définir TARIFICATION_BACKUP_DIR sur son répertoire.");
        BoutiquesMigration.verifierSauvegarde(dossier);
    }

    static void migrer(Connection c, boolean appliquer) throws SQLException {
        boolean maria = c.getMetaData().getDatabaseProductName().equals("MariaDB"), verrou = false;
        if (!maria && !c.getMetaData().getDatabaseProductName().equals("H2"))
            throw new IllegalStateException("MariaDB ou H2 requis.");
        if (!c.getAutoCommit()) throw new IllegalStateException("Connexion dédiée sans transaction ouverte requise.");
        try {
            c.setReadOnly(!appliquer);
            if (appliquer && maria) {
                if (BoutiquesMigration.compter(c, "SELECT GET_LOCK('" + VERROU + "',0)") != 1)
                    throw new IllegalStateException("Une migration des tarifs est déjà en cours.");
                verrou = true;
            }
            if (!BoutiquesMigration.tables(c).containsKey(InventaireMigration.TABLE))
                throw new IllegalStateException("La migration d'inventaire doit être terminée avant les tarifs.");
            InventaireMigration.migrer(c, false);
            c.setReadOnly(!appliquer);
            verifierPresents(c);
            var absents = new ArrayList<Champ>();
            for (var champ : CHAMPS) if (!colonnes(c, champ.table()).containsKey(champ.nom())) absents.add(champ);
            if (!appliquer || absents.isEmpty()) {
                System.out.println(absents.isEmpty() ? "Tarifs conformes ; prix et historiques conservés."
                        : "Tarifs vérifiés en lecture seule ; aucun prix ni statut ajouté.");
                return;
            }
            var avant = photographie(c);
            var tarifsAvant = tarifs(c);
            var schemasAvant = schemasAnciens(c);
            var tablesAvant = BoutiquesMigration.tables(c);
            for (var champ : absents) BoutiquesMigration.executer(c,
                    "ALTER TABLE " + table(c, champ.table()) + " ADD COLUMN " + champ.nom() + " " + champ.declaration());
            verifierPresents(c);
            if (!avant.equals(photographie(c)) || !schemasAvant.equals(schemasAnciens(c)) || !tablesAvant.equals(BoutiquesMigration.tables(c)))
                throw new IllegalStateException("Les données ou anciennes colonnes ont changé ; garder l'application arrêtée et examiner la sauvegarde.");
            var tarifsApres = tarifs(c);
            for (var champ : CHAMPS) {
                String cle = champ.table() + "." + champ.nom();
                if (tarifsAvant.containsKey(cle)) {
                    if (!tarifsAvant.get(cle).equals(tarifsApres.get(cle)))
                        throw new IllegalStateException("Un tarif ou statut présent a changé ; garder l'application arrêtée.");
                } else {
                    String condition = champ.table().equals("produit") ? "prix_definis<>TRUE OR prix_definis IS NULL" : champ.nom() + " IS NOT NULL";
                    if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, champ.table()) + " WHERE " + condition) != 0)
                        throw new IllegalStateException("Une valeur historique a été inventée ; garder l'application arrêtée.");
                }
            }
            System.out.println("Migration des tarifs terminée ; dix-sept tables conservées, produits existants tarifés et anciennes lignes sans prix de vente inventé.");
        } finally { if (verrou) BoutiquesMigration.compter(c, "SELECT RELEASE_LOCK('" + VERROU + "')"); }
    }

    private static void verifierPresents(Connection c) throws SQLException {
        for (var champ : CHAMPS) {
            var col = colonnes(c, champ.table()).get(champ.nom());
            if (col == null) continue;
            if (champ.table().equals("produit")) verifierStatut(c, col);
            else verifierPrixFacultatif(c, champ.table(), champ.nom(), col);
        }
        for (String nom : List.of("prix_achat", "prix_vente")) {
            var col = colonnes(c, "produit").get(nom);
            if (col == null || !decimalConforme(col) || col.nullable() != DatabaseMetaData.columnNoNulls)
                nonConforme("les prix actuels des produits doivent rester des DECIMAL(17,2) obligatoires");
        }
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "produit") + " WHERE prix_achat<0 OR prix_vente<0") != 0)
            nonConforme("les prix des produits ne peuvent pas être négatifs");
    }

    private static void verifierStatut(Connection c, InscriptionEssaiMigration.Colonne col) throws SQLException {
        if (col.nullable() != DatabaseMetaData.columnNoNulls || col.autoIncrement()
                || !Set.of(Types.BOOLEAN, Types.BIT, Types.TINYINT).contains(col.type())
                || col.type() == Types.BIT && col.taille() != 1 || !PlateformeMigration.defautConforme(col.defaut(), true))
            nonConforme("produit.prix_definis doit être booléen obligatoire avec TRUE pour défaut");
        String valeurs = col.type() == Types.BOOLEAN ? "FALSE,TRUE" : "0,1";
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "produit") + " WHERE prix_definis IS NULL OR prix_definis NOT IN (" + valeurs + ")") != 0)
            nonConforme("le statut des prix accepte uniquement zéro ou un");
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "produit")
                + " WHERE prix_definis=FALSE AND (prix_achat<>0 OR prix_vente<>0 OR quantite IS NULL OR quantite<>0)") != 0)
            nonConforme("un produit sans tarifs doit avoir ses prix et sa quantité à zéro");
    }

    static void verifierPrixFacultatif(Connection c, String nomTable, String nom,
                                      InscriptionEssaiMigration.Colonne col) throws SQLException {
        if (!decimalConforme(col) || col.nullable() != DatabaseMetaData.columnNullable || col.autoIncrement() || !defautNul(col.defaut()))
            nonConforme(nomTable + "." + nom + " doit être DECIMAL(17,2) facultatif, sans valeur par défaut");
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, nomTable) + " WHERE " + nom + "<0") != 0)
            nonConforme("un prix de vente historique ne peut pas être négatif");
        if (nomTable.equals("mouvement_stock") && BoutiquesMigration.compter(c,
                "SELECT COUNT(*) FROM " + table(c, nomTable) + " WHERE type='AJUSTEMENT' AND " + nom + " IS NOT NULL") != 0)
            nonConforme("un ajustement de quantité ne doit pas enregistrer de prix de vente");
    }

    private static boolean decimalConforme(InscriptionEssaiMigration.Colonne col) {
        return Set.of(Types.DECIMAL, Types.NUMERIC).contains(col.type()) && col.taille() == 17 && col.decimales() == 2;
    }
    private static boolean defautNul(String defaut) {
        if (defaut == null) return true;
        String simple = defaut.replace("(", "").replace(")", "").strip();
        return simple.equalsIgnoreCase("NULL");
    }
    private static void nonConforme(String raison) { throw new IllegalStateException("Schéma des tarifs non conforme : " + raison + ". Aucune réparation automatique."); }

    /** Tous les anciens champs des dix-sept tables, dont les comptes, prix, quantités et journaux. */
    static Map<String, List<List<Object>>> photographie(Connection c) throws SQLException {
        var resultat = new TreeMap<String, List<List<Object>>>();
        for (var nom : BoutiquesMigration.tables(c).keySet()) {
            List<List<Object>> lignes = new ArrayList<>();
            String ordre = switch (nom) { case "operation_creation" -> "jeton"; case "inscription_boutique" -> "email";
                case "reinitialisation_mot_de_passe" -> "utilisateur_id"; default -> "id"; };
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table(c, nom) + " ORDER BY " + ordre)) {
                while (r.next()) {
                    var ligne = new ArrayList<Object>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
                        if (!champNouveau(nom, r.getMetaData().getColumnName(i))) ligne.add(r.getObject(i));
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
            var colonnes = new TreeMap<>(colonnes(c, nom));
            colonnes.keySet().removeIf(col -> champNouveau(nom, col)); resultat.put(nom, colonnes);
        }
        return resultat;
    }
    static Map<String, List<List<Object>>> tarifs(Connection c) throws SQLException {
        var resultat = new TreeMap<String, List<List<Object>>>();
        for (var champ : CHAMPS) if (colonnes(c, champ.table()).containsKey(champ.nom())) {
            List<List<Object>> lignes = new ArrayList<>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT id," + champ.nom() + " FROM " + table(c, champ.table()) + " ORDER BY id")) {
                while (r.next()) lignes.add(Arrays.asList(r.getObject(1), r.getObject(2)));
            }
            resultat.put(champ.table() + "." + champ.nom(), lignes);
        }
        return resultat;
    }
    private static boolean champNouveau(String table, String colonne) {
        return CHAMPS.stream().anyMatch(champ -> champ.table().equals(table) && champ.nom().equalsIgnoreCase(colonne));
    }
    private static Map<String, InscriptionEssaiMigration.Colonne> colonnes(Connection c, String nom) throws SQLException { return InscriptionEssaiMigration.colonnes(c, nom); }
    private static String table(Connection c, String nom) throws SQLException {
        String physique = BoutiquesMigration.tables(c).get(nom);
        if (physique == null || !physique.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("Identifiant SQL inattendu.");
        String guillemet = c.getMetaData().getIdentifierQuoteString().strip(); return guillemet + physique + guillemet;
    }
}
