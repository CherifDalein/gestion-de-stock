package org.example.stock.migration;

import jakarta.validation.Validation;
import org.example.stock.form.InscriptionForm;
import org.example.stock.model.Emails;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Migration JDBC des accès plateforme, sans démarrage Spring ni modification des rôles de boutique. */
public final class PlateformeMigration {
    record Champ(String table, String nom, boolean defaut) {}
    static final List<Champ> CHAMPS = List.of(new Champ("boutique", "actif", true),
            new Champ("boutique", "acces_offert", false), new Champ("utilisateur", "administrateur_plateforme", false));
    record Colonne(int type, int nullable, String defaut, int taille) {}
    record Compte(long id, String email, String empreinte, String role, long boutique) {}
    record Attribution(long id, long boutique, boolean dejaAttribue) {}

    private PlateformeMigration() {}

    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        if (args.length != 1 || !Set.of("--check", "--apply").contains(args[0])) {
            throw new IllegalArgumentException("Utiliser --check ou --apply");
        }
        try {
            Properties config = new Properties();
            try (var reader = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { config.load(reader); }
            String url = AccountEmailMigration.resolve(config.getProperty("spring.datasource.url"))
                    .replace("createDatabaseIfNotExist=true", "createDatabaseIfNotExist=false");
            boolean appliquer = args[0].equals("--apply");
            if (appliquer && url.startsWith("jdbc:mariadb:")) {
                BoutiquesMigration.verifierCibleSauvegarde(url);
                String dossier = System.getenv("PLATEFORME_BACKUP_DIR");
                if (dossier == null || dossier.isBlank()) throw new IllegalStateException(
                        "Une sauvegarde complète est requise : lancer scripts/backup-stock-local.sh puis définir PLATEFORME_BACKUP_DIR sur son répertoire.");
                BoutiquesMigration.verifierSauvegarde(dossier);
            }
            try (Connection c = DriverManager.getConnection(url,
                    AccountEmailMigration.resolve(config.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(config.getProperty("spring.datasource.password")))) {
                migrer(c, appliquer, System.getenv("PLATEFORME_ADMIN_EMAIL"));
            }
        } catch (Exception e) {
            // Ne jamais afficher les emails, empreintes, mots de passe ni messages JDBC bruts.
            System.err.println("Migration plateforme interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Connexion ou opération SQL impossible. Vérifiez les paramètres DB_* et le schéma avant de reprendre."));
            System.exit(1);
        }
    }

    static void migrer(Connection c, boolean appliquer, String emailPlateforme) throws SQLException {
        if (!Set.of("H2", "MariaDB").contains(c.getMetaData().getDatabaseProductName())) {
            throw new IllegalStateException("Cette migration prend en charge uniquement MariaDB et H2.");
        }
        if (!c.getAutoCommit()) throw new IllegalStateException("Utiliser une connexion dédiée sans transaction ouverte.");
        boolean verrou = false;
        try {
            c.setReadOnly(!appliquer);
            if (appliquer && mariaDb(c)) {
                if (BoutiquesMigration.compter(c, "SELECT GET_LOCK('stock_plateforme_migration',0)") != 1) {
                    throw new IllegalStateException("Une autre migration plateforme est en cours.");
                }
                verrou = true;
            }
            executerMigration(c, appliquer, emailPlateforme);
        } finally {
            if (verrou) BoutiquesMigration.compter(c, "SELECT RELEASE_LOCK('stock_plateforme_migration')");
        }
    }

    private static void executerMigration(Connection c, boolean appliquer, String emailPlateforme) throws SQLException {
        if (!BoutiquesMigration.tables(c).containsKey("boutique")) {
            throw new IllegalStateException("La migration des boutiques doit être terminée avant les accès plateforme.");
        }
        // Reprendre les contrôles complets d'appartenance, relations, contraintes et catégories du premier lot.
        BoutiquesMigration.migrer(c, false);
        c.setReadOnly(!appliquer);
        long presentes = 0;
        for (Champ champ : CHAMPS) if (colonne(c, champ) != null) presentes++;
        boolean ancien = presentes == 0;
        if (!ancien && presentes != CHAMPS.size()) throw new IllegalStateException(
                "Schéma plateforme partiel ou mixte. Examiner ou restaurer la sauvegarde avant toute reprise.");
        if (!ancien) verifierDrapeaux(c);
        Attribution attribution = verifierAttribution(c, emailPlateforme, !ancien);
        List<Compte> avant = comptes(c);
        if (attribution != null && avant.stream().noneMatch(compte -> compte.id() == attribution.id()
                && compte.boutique() == attribution.boutique() && "ADMIN".equals(compte.role())
                && Objects.equals(compte.email(), emailPlateforme))) {
            throw new IllegalStateException("Le compte désigné a changé pendant le contrôle. Aucun droit plateforme n'a été attribué.");
        }
        Map<String, Long> compteursAvant = compteurs(c);
        System.out.println("Accès plateforme : colonnes présentes " + presentes + "/" + CHAMPS.size()
                + " ; comptes conservés " + avant.size() + " ; attribution explicite " + (attribution != null) + ".");
        if (!appliquer) {
            System.out.println("Vérification plateforme réussie en lecture seule ; aucun droit ni donnée modifié.");
            return;
        }
        if (ancien) {
            // DDL à validation implicite MariaDB : une interruption sera refusée comme schéma partiel à la relance.
            for (Champ champ : CHAMPS) BoutiquesMigration.executer(c, "ALTER TABLE " + table(c, champ.table())
                    + " ADD COLUMN " + champ.nom() + " BOOLEAN NOT NULL DEFAULT " + (champ.defaut() ? "TRUE" : "FALSE"));
        }
        if (attribution != null && !attribution.dejaAttribue()) {
            c.setAutoCommit(false);
            try (var s = c.prepareStatement("UPDATE " + table(c, "utilisateur")
                    + " SET administrateur_plateforme=TRUE WHERE id=? AND boutique_id=? AND email=? AND role='ADMIN' AND administrateur_plateforme=FALSE")) {
                s.setLong(1, attribution.id()); s.setLong(2, attribution.boutique()); s.setString(3, emailPlateforme);
                if (s.executeUpdate() != 1) throw new IllegalStateException(
                        "Le compte désigné a changé pendant l'opération. Aucun droit plateforme n'a été attribué ; vérifier l'état avant reprise.");
                verifierDrapeaux(c);
                if (!avant.equals(comptes(c)) || !compteursAvant.equals(compteurs(c))) throw new IllegalStateException(
                        "Les données ou comptes ont changé pendant l'opération. Garder l'application arrêtée et examiner la sauvegarde.");
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback(); throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
        verifierDrapeaux(c);
        if (!avant.equals(comptes(c)) || !compteursAvant.equals(compteurs(c))) throw new IllegalStateException(
                "Les données ou comptes ont changé pendant l'opération. Garder l'application arrêtée et examiner la sauvegarde.");
        System.out.println("Migration plateforme terminée ; rôles, mots de passe, boutiques et historiques conservés. Renouveler les sessions après redémarrage.");
    }

    private static Attribution verifierAttribution(Connection c, String email, boolean colonnePresente) throws SQLException {
        if (email == null) return null;
        InscriptionForm form = new InscriptionForm(); form.setEmail(email);
        boolean invalide;
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            invalide = !factory.getValidator().validateProperty(form, "email").isEmpty();
        }
        if (invalide || !Objects.equals(email, Emails.normaliser(email))) {
            throw new IllegalStateException("PLATEFORME_ADMIN_EMAIL doit être une adresse valide, déjà normalisée en minuscules et sans espaces autour.");
        }
        String sql = "SELECT id,email,role,boutique_id" + (colonnePresente ? ",administrateur_plateforme" : "")
                + " FROM " + table(c, "utilisateur") + " WHERE email=?";
        try (var s = c.prepareStatement(sql)) {
            s.setString(1, email);
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new IllegalStateException("Le compte désigné pour la plateforme n'existe pas. Aucun compte n'est créé automatiquement.");
                long id = r.getLong("id"), boutique = r.getLong("boutique_id");
                boolean exact = Objects.equals(email, r.getString("email"));
                boolean admin = "ADMIN".equals(r.getString("role"));
                boolean deja = colonnePresente && r.getBoolean("administrateur_plateforme");
                if (r.next()) throw new IllegalStateException("Le compte désigné pour la plateforme est ambigu selon la collation SQL.");
                if (!exact) throw new IllegalStateException("Le compte désigné ne correspond pas exactement à une adresse enregistrée normalisée.");
                if (!admin) throw new IllegalStateException("Seul un compte ADMIN existant, déjà rattaché à une boutique, peut recevoir l'accès plateforme.");
                return new Attribution(id, boutique, deja);
            }
        }
    }

    private static void verifierDrapeaux(Connection c) throws SQLException {
        for (Champ champ : CHAMPS) {
            Colonne colonne = colonne(c, champ);
            if (colonne == null || colonne.nullable() != DatabaseMetaData.columnNoNulls
                    || !Set.of(Types.BOOLEAN, Types.BIT, Types.TINYINT).contains(colonne.type())
                    || colonne.type() == Types.BIT && colonne.taille() != 1
                    || !defautConforme(colonne.defaut(), champ.defaut())) {
                throw new IllegalStateException("Schéma plateforme non conforme : " + champ.table() + "." + champ.nom()
                        + " doit être booléen, obligatoire et conserver son défaut attendu.");
            }
            String valeurs = colonne.type() == Types.BOOLEAN ? "FALSE,TRUE" : "0,1";
            if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, champ.table()) + " WHERE " + champ.nom()
                    + " IS NULL OR " + champ.nom() + " NOT IN (" + valeurs + ")") > 0) {
                throw new IllegalStateException("Valeurs de drapeaux plateforme incohérentes : " + champ.table() + "." + champ.nom() + ".");
            }
        }
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "utilisateur")
                + " WHERE administrateur_plateforme=TRUE AND (role IS NULL OR role<>'ADMIN')") > 0) {
            throw new IllegalStateException("Un accès plateforme appartient à un compte sans rôle ADMIN. Aucune correction automatique des droits.");
        }
    }

    static boolean defautConforme(String valeur, boolean attendu) {
        if (valeur == null) return false;
        String simple = valeur.replace("(", "").replace(")", "").replace("'", "").strip().toUpperCase(Locale.ROOT);
        return (attendu ? Set.of("TRUE", "1", "B1") : Set.of("FALSE", "0", "B0")).contains(simple);
    }

    private static Colonne colonne(Connection c, Champ champ) throws SQLException {
        String physique = BoutiquesMigration.tables(c).get(champ.table());
        if (physique == null) return null;
        try (var r = c.getMetaData().getColumns(c.getCatalog(), c.getSchema(), physique, "%")) {
            while (r.next()) if (physique.equals(r.getString("TABLE_NAME")) && champ.nom().equalsIgnoreCase(r.getString("COLUMN_NAME"))) {
                return new Colonne(r.getInt("DATA_TYPE"), r.getInt("NULLABLE"), r.getString("COLUMN_DEF"), r.getInt("COLUMN_SIZE"));
            }
        }
        return null;
    }

    private static List<Compte> comptes(Connection c) throws SQLException {
        List<Compte> comptes = new ArrayList<>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT id,email,mot_de_passe,role,boutique_id FROM " + table(c, "utilisateur") + " ORDER BY id")) {
            while (r.next()) comptes.add(new Compte(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getLong(5)));
        }
        return comptes;
    }

    private static Map<String, Long> compteurs(Connection c) throws SQLException {
        Map<String, Long> compteurs = new LinkedHashMap<>();
        for (String nom : BoutiquesMigration.TABLES) compteurs.put(nom, BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, nom)));
        compteurs.put("boutique", BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "boutique")));
        return compteurs;
    }
    private static boolean mariaDb(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static String table(Connection c, String nom) throws SQLException {
        String physique = BoutiquesMigration.tables(c).get(nom);
        if (physique == null || !physique.matches("[a-zA-Z0-9_]+")) throw new IllegalStateException("Identifiant SQL inattendu ; contrôle manuel requis.");
        String guillemet = c.getMetaData().getIdentifierQuoteString().strip();
        return guillemet + physique + guillemet;
    }
}
