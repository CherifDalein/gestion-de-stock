package org.example.stock.migration;

import jakarta.validation.Validation;
import org.example.stock.config.LengthCheckedBcryptPasswordEncoder;
import org.example.stock.form.InscriptionForm;
import org.example.stock.pays.Pays;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.security.SecureRandom;
import java.util.*;

/**
 * Commande locale avec accès JDBC privilégié, jamais exposée par une route web. Le pays de la boutique créée (donc sa monnaie, définitive)
 * se choisit avec {@code INITIAL_ADMIN_PAYS} (code ISO : GN, SN, CI…) ; Guinée par défaut. Sur une base qui a déjà une boutique, rien ne change
 * son pays.
 */
public class InitialAdmin {
    public static void main(String[] args) {
        if (args.length != 1 || !args[0].equals("--create-if-empty")) throw new IllegalArgumentException("Utiliser --create-if-empty");
        Path identifiants = null;
        try {
            Properties config = new Properties();
            try (var r = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { config.load(r); }
            String url = AccountEmailMigration.resolve(config.getProperty("spring.datasource.url"));
            var cible = java.net.URI.create(url.substring(5));
            if (!Set.of("localhost", "127.0.0.1").contains(cible.getHost()) || !"/stock_pro".equals(cible.getPath()))
                throw new IllegalStateException("Cette commande exige la base locale stock_pro");
            String email = System.getenv().getOrDefault("INITIAL_ADMIN_EMAIL", "admin@stock.local");
            Pays pays = System.getenv("INITIAL_ADMIN_PAYS") == null || System.getenv("INITIAL_ADMIN_PAYS").isBlank() ? Pays.PAR_DEFAUT
                    : Pays.depuisCode(System.getenv("INITIAL_ADMIN_PAYS"));
            if (pays == null) throw new IllegalStateException("INITIAL_ADMIN_PAYS doit être un code pays connu (GN, SN, CI, ML, BF, NE, TG, BJ, GW, CM, GA, CG, TD, CF, GQ)");
            byte[] aleatoire = new byte[24]; new SecureRandom().nextBytes(aleatoire);
            String password = System.getenv().getOrDefault("INITIAL_ADMIN_PASSWORD", Base64.getUrlEncoder().withoutPadding().encodeToString(aleatoire));
            try (Connection c = DriverManager.getConnection(url, AccountEmailMigration.resolve(config.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(config.getProperty("spring.datasource.password")))) {
                // Conserver le secret même si la connexion échoue après une insertion validée par le serveur.
                identifiants = Files.createTempFile("stock-admin-", ".txt", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.writeString(identifiants, "Email : " + email + "\nMot de passe : " + password + "\n");
                creer(c, email, password, pays);
            }
            System.out.println("Premier administrateur créé. Identifiants dans le fichier privé : " + identifiants);
        } catch (Exception e) {
            System.err.println("Création interrompue : " + diagnostic(e));
            if (identifiants != null) System.err.println("Fichier de préparation privé conservé : " + identifiants + ". Vérifiez l'état du compte avant de réessayer.");
            System.exit(1);
        }
    }

    static void creer(Connection c, String email, String password) throws Exception {
        creer(c, email, password, Pays.PAR_DEFAUT);
    }

    static void creer(Connection c, String email, String password, Pays pays) throws Exception {
        if (pays == null) throw new IllegalArgumentException("Pays absent");
        if (!c.getAutoCommit()) throw new IllegalStateException("Utiliser une connexion dédiée sans transaction ouverte");
        InscriptionForm formulaire = new InscriptionForm(); formulaire.setNom("Administrateur"); formulaire.setEmail(email); formulaire.setPassword(password);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            if (!factory.getValidator().validate(formulaire).isEmpty()) throw new IllegalStateException("L'email ou le mot de passe ne respecte pas les règles de création des comptes");
        }
        boolean verrouSql = !c.getMetaData().getDatabaseProductName().equals("H2");
        String verrou = "stock:init-admin:" + c.getCatalog();
        if (verrouSql) try (var s = c.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            s.setString(1, verrou);
            try (var r = s.executeQuery()) { r.next(); if (r.getInt(1) != 1) throw new IllegalStateException("Une création initiale est déjà en cours"); }
        }
        try {
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM utilisateur")) {
                r.next(); if (r.getLong(1) != 0) throw new IllegalStateException("Des comptes existent déjà ; aucun administrateur n'a été ajouté");
            }
            Long boutiqueId = null;
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT id FROM boutique ORDER BY id")) {
                if (r.next()) boutiqueId = r.getLong(1);
                if (r.next()) throw new IllegalStateException("Plusieurs boutiques existent ; initialisation ambiguë");
            }
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                if (boutiqueId == null) {
                    // Avant la migration pays/monnaie, la boutique n'a ni pays ni monnaie : on ne les écrit que si les colonnes existent.
                    boolean avecPays = BoutiquesMigration.colonnes(c, "boutique").keySet().containsAll(List.of("pays", "monnaie"));
                    try (var s = c.prepareStatement("INSERT INTO boutique(nom,date_creation" + (avecPays ? ",pays,monnaie" : "") + ") VALUES(?,?" + (avecPays ? ",?,?" : "") + ")",
                            Statement.RETURN_GENERATED_KEYS)) {
                        s.setString(1, "Boutique principale");
                        s.setTimestamp(2, Timestamp.valueOf(java.time.LocalDateTime.now()));
                        if (avecPays) { s.setString(3, pays.getCode()); s.setString(4, pays.getMonnaie().name()); }
                        s.executeUpdate();
                        try (var ids = s.getGeneratedKeys()) { ids.next(); boutiqueId = ids.getLong(1); }
                    }
                }
                provisionnerTresorerieSiPresente(c, boutiqueId, pays);
                try (var s = c.prepareStatement("INSERT INTO utilisateur(nom,email,mot_de_passe,date_inscription,role,boutique_id) VALUES(?,?,?,?,?,?)")) {
                s.setString(1, formulaire.getNom()); s.setString(2, formulaire.getEmail());
                s.setString(3, new LengthCheckedBcryptPasswordEncoder().encode(password));
                s.setDate(4, java.sql.Date.valueOf(java.time.LocalDate.now())); s.setString(5, "ADMIN");
                s.setLong(6, boutiqueId); s.executeUpdate();
                }
                if (autoCommit) c.commit();
            } catch (Exception e) {
                c.rollback(); throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        } finally {
            if (verrouSql) try (var s = c.prepareStatement("SELECT RELEASE_LOCK(?)")) { s.setString(1, verrou); s.executeQuery().close(); }
        }
    }

    /**
     * Le bootstrap JDBC doit effectuer le même provisionnement que la cascade JPA des nouvelles boutiques : les trois comptes de base,
     * principaux de leur type. Le schéma est reconnu par ses colonnes : celui du lot 3 (type, nom, nom_cle, principal_type, actif, ordre)
     * ou, avant le lot 3, une seule colonne {@code compte}. Idempotent : un compte déjà présent n'est jamais dupliqué.
     */
    private static void provisionnerTresorerieSiPresente(Connection c, Long boutiqueId, Pays pays) throws SQLException {
        String nom = BoutiquesMigration.tables(c).get("compte_tresorerie_boutique");
        // Compatibilité avec les installations en cours de migration des versions précédentes.
        if (nom == null) return;
        if (!nom.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("Nom de table de trésorerie inattendu");
        String guillemet = c.getMetaData().getIdentifierQuoteString().strip();
        String table = guillemet + nom + guillemet;
        var colonnes = BoutiquesMigration.colonnes(c, "compte_tresorerie_boutique").keySet();
        if (colonnes.containsAll(List.of("type", "nom", "nom_cle", "principal_type", "actif", "ordre"))) {
            // Schéma de la migration pays/monnaie : mobile money + opérateur du pays. Avant elle, l'ancien type ORANGE_MONEY reste le seul accepté.
            boolean avecOperateur = colonnes.contains("operateur");
            for (var type : org.example.stock.enums.TypeCompteTresorerie.values()) {
                String valeurType = !avecOperateur && type == org.example.stock.enums.TypeCompteTresorerie.MOBILE_MONEY ? "ORANGE_MONEY" : type.name();
                String nomCompte = org.example.stock.model.CompteTresorerieBoutique.normaliserNom(
                        avecOperateur ? type.getNomParDefaut(pays) : type.getNomParDefaut(Pays.PAR_DEFAUT));
                String operateur = avecOperateur && type.aUnOperateur() ? pays.operateurParDefaut() : null;
                String cle = org.example.stock.model.CompteTresorerieBoutique.cle(nomCompte);
                try (var existe = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE boutique_id=? AND (principal_type=? OR nom_cle=?)")) {
                    existe.setLong(1, boutiqueId); existe.setString(2, valeurType); existe.setString(3, cle);
                    try (var r = existe.executeQuery()) { r.next(); if (r.getLong(1) > 0) continue; }
                }
                try (var s = c.prepareStatement("INSERT INTO " + table + "(boutique_id,type,nom,nom_cle,principal_type,actif,ordre" + (avecOperateur ? ",operateur" : "") + ") VALUES(?,?,?,?,?,?,?" + (avecOperateur ? ",?" : "") + ")")) {
                    s.setLong(1, boutiqueId); s.setString(2, valeurType); s.setString(3, nomCompte); s.setString(4, cle);
                    s.setString(5, valeurType); s.setBoolean(6, true); s.setInt(7, type.ordinal());
                    if (avecOperateur) s.setString(8, operateur);
                    s.executeUpdate();
                }
            }
        } else if (colonnes.contains("compte")) {
            String sql = "INSERT INTO " + table + "(boutique_id,compte) SELECT ?,? WHERE NOT EXISTS (SELECT 1 FROM " + table + " WHERE boutique_id=? AND compte=?)";
            try (var s = c.prepareStatement(sql)) {
                for (String compte : List.of("CAISSE", "ORANGE_MONEY", "BANQUE")) {
                    s.setLong(1, boutiqueId); s.setString(2, compte); s.setLong(3, boutiqueId); s.setString(4, compte);
                    s.executeUpdate();
                }
            }
        } else {
            throw new IllegalStateException("Colonnes inattendues dans compte_tresorerie_boutique ; appliquez d'abord la migration lot3Migrate");
        }
    }

    /** Cause lisible d'un échec, sans valeur privée (jamais le texte brut du serveur, qui peut citer un email ou un mot de passe). */
    static String diagnostic(Exception e) {
        if (e instanceof IllegalStateException) return e.getMessage();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (!(t instanceof SQLException sql)) continue;
            String message = sql.getMessage() == null ? "" : sql.getMessage();
            String etat = sql.getSQLState() == null ? "" : sql.getSQLState();
            var colonne = java.util.regex.Pattern.compile("(?i)Unknown column '([A-Za-z0-9_.]+)'|Column \"([A-Za-z0-9_.]+)\" not found").matcher(message);
            if (sql.getErrorCode() == 1054 || colonne.find()) {
                String id = colonne.find(0) ? (colonne.group(1) != null ? colonne.group(1) : colonne.group(2)) : "inconnue";
                return "Colonne inconnue : " + id.toLowerCase(java.util.Locale.ROOT) + " ; appliquez d'abord " + migrationPour(id) + ", puis relancez";
            }
            var table = java.util.regex.Pattern.compile("(?i)Table '([A-Za-z0-9_.]+)' doesn't exist|Table \"([A-Za-z0-9_.]+)\" not found").matcher(message);
            if (sql.getErrorCode() == 1146 || table.find()) {
                String id = table.find(0) ? (table.group(1) != null ? table.group(1) : table.group(2)) : "inconnue";
                return "Table inconnue : " + id.toLowerCase(java.util.Locale.ROOT) + " ; appliquez d'abord " + migrationPour(id) + ", puis relancez";
            }
            if (sql.getErrorCode() == 1062 || etat.startsWith("23")) return "Une contrainte du schéma refuse l'écriture (doublon ou valeur obligatoire manquante) ; aucun compte n'a été créé";
            if (etat.startsWith("08") || sql.getErrorCode() == 1045) return "Connexion à la base refusée ou impossible ; vérifiez le serveur et les identifiants de application.properties";
            return "Erreur SQL (état " + (etat.isEmpty() ? "inconnu" : etat.replaceAll("[^A-Za-z0-9]", "")) + ", code " + sql.getErrorCode() + ") ; vérifiez le schéma avec les commandes *Check";
        }
        return "Erreur inattendue (" + e.getClass().getSimpleName() + ") ; aucun détail privé n'est affiché";
    }

    private static String migrationPour(String identifiant) {
        String id = identifiant.toLowerCase(java.util.Locale.ROOT);
        id = id.substring(id.lastIndexOf('.') + 1);
        return switch (id) {
            case "pays", "monnaie", "operateur" -> "la migration paysMonnaieMigrate";
            case "supprime_le", "supprime_par" -> "la migration lot6Migrate";
            case "sous_compte_boutique", "correspondance_compta", "reglage_comptable_boutique", "exercice_comptable" -> "la migration lot5Migrate";
            case "type", "nom_cle", "principal_type", "ordre", "compte_id", "compte_tresorerie_autorise" -> "la migration lot3Migrate";
            case "boutique", "boutique_id" -> "la migration boutiquesMigrate";
            case "administrateur_plateforme" -> "la migration plateformeMigrate";
            case "compte" -> "la migration lot3Migrate (ou lot3Check pour l'état actuel)";
            default -> "les migrations de lot (lot1Migrate à lot6Migrate, boutiquesMigrate)";
        };
    }
}
