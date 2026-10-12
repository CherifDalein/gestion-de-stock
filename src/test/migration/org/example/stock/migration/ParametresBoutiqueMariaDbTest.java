package org.example.stock.migration;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Vérifie les coordonnées dans des bases MariaDB locales jetables ; jamais stock_pro. */
public final class ParametresBoutiqueMariaDbTest {
    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        try {
            Properties config = new Properties();
            try (var r = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { config.load(r); }
            String url = AccountEmailMigration.resolve(config.getProperty("spring.datasource.url"));
            URI cible = URI.create(url.substring(5));
            if (!"mariadb".equals(cible.getScheme()) || !Set.of("localhost", "127.0.0.1").contains(cible.getHost())
                    || !Set.of(-1, 3306).contains(cible.getPort()) || !"/stock_pro".equals(cible.getPath()) || cible.getUserInfo() != null) {
                throw new IllegalStateException("Serveur MariaDB local requis ; seules des bases de test seront utilisées.");
            }
            String serveur = url.replace("/stock_pro?", "/?").replace("/stock_pro", "/")
                    .replace("createDatabaseIfNotExist=true", "createDatabaseIfNotExist=false");
            int reussis = 0;
            try (var c = DriverManager.getConnection(serveur,
                    AccountEmailMigration.resolve(config.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(config.getProperty("spring.datasource.password")))) {
                for (String scenario : List.of("ancien", "relance", "conforme", "vide", "partiel_adresse", "partiel_double",
                        "taille_adresse", "taille_telephone", "taille_email", "type", "obligatoire", "defaut", "defaut_texte_null",
                        "acces_absent", "acces_invalide", "plateforme", "relation")) {
                    c.setReadOnly(false);
                    String base = "stock_parametres_boutique_test_" + scenario + "_" + Long.toUnsignedString(System.nanoTime(), 36);
                    verifierBaseTemporaire(base);
                    sql(c, "CREATE DATABASE " + base + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                    try {
                        c.setCatalog(base);
                        boolean vide = scenario.equals("vide");
                        BoutiquesMariaDbTest.schema(c, !vide);
                        BoutiquesMigration.migrer(c, true);
                        if (!vide) {
                            sql(c, "INSERT INTO boutique(nom,date_creation) VALUES('Deuxième',CURRENT_TIMESTAMP)");
                            sql(c, "INSERT INTO utilisateur(id,email,mot_de_passe,role,boutique_id) VALUES(2,'gerant@example.test','empreinte-gerant','ADMIN',2),(3,'caissier@example.test','empreinte-caissier','CAISSIER',1)");
                        }
                        PlateformeMigration.migrer(c, true, vide ? null : "admin@example.test");
                        AccesUtilisateurMigration.migrer(c, true);
                        c.setReadOnly(false);
                        if (!vide) {
                            sql(c, "UPDATE boutique SET actif=FALSE,acces_offert=TRUE WHERE id=2");
                            sql(c, "UPDATE utilisateur SET actif=FALSE WHERE id=3");
                        }
                        if (scenario.equals("partiel_adresse")) sql(c, "ALTER TABLE boutique ADD COLUMN adresse VARCHAR(500) NULL");
                        if (scenario.equals("partiel_double")) sql(c, "ALTER TABLE boutique ADD COLUMN telephone VARCHAR(50) NULL, ADD COLUMN email VARCHAR(254) NULL");
                        if (Set.of("conforme", "taille_adresse", "taille_telephone", "taille_email", "type", "obligatoire", "defaut", "defaut_texte_null").contains(scenario)) {
                            sql(c, "ALTER TABLE boutique ADD COLUMN adresse VARCHAR(500) NULL, ADD COLUMN telephone VARCHAR(50) NULL, ADD COLUMN email VARCHAR(254) NULL");
                            remplirCoordonnees(c);
                        }
                        if (scenario.equals("taille_adresse")) sql(c, "ALTER TABLE boutique MODIFY COLUMN adresse VARCHAR(501) NULL");
                        if (scenario.equals("taille_telephone")) sql(c, "ALTER TABLE boutique MODIFY COLUMN telephone VARCHAR(51) NULL");
                        if (scenario.equals("taille_email")) sql(c, "ALTER TABLE boutique MODIFY COLUMN email VARCHAR(255) NULL");
                        if (scenario.equals("type")) sql(c, "ALTER TABLE boutique MODIFY COLUMN adresse TEXT NULL");
                        if (scenario.equals("obligatoire")) sql(c, "ALTER TABLE boutique MODIFY COLUMN adresse VARCHAR(500) NOT NULL");
                        if (scenario.equals("defaut")) sql(c, "ALTER TABLE boutique ALTER COLUMN email SET DEFAULT ''");
                        if (scenario.equals("defaut_texte_null")) sql(c, "ALTER TABLE boutique ALTER COLUMN email SET DEFAULT 'NULL'");
                        if (scenario.equals("acces_absent")) sql(c, "ALTER TABLE utilisateur DROP COLUMN actif");
                        if (scenario.equals("acces_invalide")) sql(c, "ALTER TABLE utilisateur ALTER COLUMN actif SET DEFAULT FALSE");
                        if (scenario.equals("plateforme")) sql(c, "UPDATE utilisateur SET administrateur_plateforme=TRUE WHERE id=3");
                        if (scenario.equals("relation")) sql(c, "UPDATE produit SET boutique_id=2 WHERE id=1");
                        boolean valide = Set.of("ancien", "relance", "conforme", "vide").contains(scenario);
                        var colonnesAvant = BoutiquesMigration.colonnes(c, "boutique");
                        var avant = ParametresBoutiqueMigration.photographie(c);
                        List<List<String>> contactsAvant = coordonneesSiPresentes(c);
                        try {
                            ParametresBoutiqueMigration.migrer(c, false);
                            if (!valide) throw new AssertionError("Contrôle accepté : " + scenario);
                        } catch (IllegalStateException e) { if (valide) throw e; }
                        if (!colonnesAvant.equals(BoutiquesMigration.colonnes(c, "boutique"))
                                || !avant.equals(ParametresBoutiqueMigration.photographie(c))
                                || !contactsAvant.equals(coordonneesSiPresentes(c))) throw new AssertionError("Écriture pendant le contrôle.");
                        try {
                            ParametresBoutiqueMigration.migrer(c, true);
                            if (!valide) throw new AssertionError("Migration acceptée : " + scenario);
                        } catch (IllegalStateException e) { if (valide) throw e; }
                        if (!avant.equals(ParametresBoutiqueMigration.photographie(c))) throw new AssertionError("Anciennes colonnes ou lignes modifiées.");
                        if (!valide && (!colonnesAvant.equals(BoutiquesMigration.colonnes(c, "boutique"))
                                || !contactsAvant.equals(coordonneesSiPresentes(c)))) throw new AssertionError("Schéma ou coordonnées modifiés avant refus.");
                        if (valide) {
                            if (!scenario.equals("conforme") && BoutiquesMigration.compter(c,
                                    "SELECT COUNT(*) FROM boutique WHERE adresse IS NOT NULL OR telephone IS NOT NULL OR email IS NOT NULL") != 0) {
                                throw new AssertionError("Coordonnées inventées.");
                            }
                            if (scenario.equals("conforme") && !contactsAvant.equals(coordonneesSiPresentes(c))) throw new AssertionError("Coordonnées réinitialisées.");
                            c.setReadOnly(false);
                            remplirCoordonnees(c);
                            List<List<String>> contacts = coordonneesSiPresentes(c);
                            ParametresBoutiqueMigration.migrer(c, false);
                            ParametresBoutiqueMigration.migrer(c, true);
                            if (!avant.equals(ParametresBoutiqueMigration.photographie(c))
                                    || !contacts.equals(coordonneesSiPresentes(c))) throw new AssertionError("Données modifiées à la relance.");
                            c.setReadOnly(false);
                            sql(c, "INSERT INTO boutique(nom,date_creation) VALUES('Nouvelle',CURRENT_TIMESTAMP)");
                            if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE nom='Nouvelle' AND adresse IS NULL AND telephone IS NULL AND email IS NULL") != 1) {
                                throw new AssertionError("Défaut des nouvelles coordonnées incorrect.");
                            }
                        }
                        reussis++; System.out.println("OK paramètres boutique MariaDB : " + scenario);
                    } finally {
                        c.setReadOnly(false); c.setCatalog(null);
                        verifierBaseTemporaire(base);
                        sql(c, "DROP DATABASE " + base);
                    }
                }
            }
            System.out.println(reussis + " scénarios de paramètres boutique MariaDB réussis ; bases temporaires supprimées.");
        } catch (Exception e) {
            System.err.println("Vérification des paramètres boutique MariaDB interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "connexion ou opération SQL impossible ; aucun détail JDBC publié."));
            System.exit(1);
        }
    }

    private static void verifierBaseTemporaire(String base) {
        if (!base.matches("stock_parametres_boutique_test_[a-z0-9_]+")) throw new IllegalStateException("Base temporaire requise.");
    }
    private static void remplirCoordonnees(Connection c) throws SQLException {
        sql(c, "UPDATE boutique SET adresse='Kaloum, Conakry',telephone='+224 600 00 00 00',email='contact@example.test'");
    }
    private static List<List<String>> coordonneesSiPresentes(Connection c) throws SQLException {
        if (!BoutiquesMigration.colonnes(c, "boutique").keySet().containsAll(Set.of("adresse", "telephone", "email"))) return List.of();
        List<List<String>> lignes = new ArrayList<>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT adresse,telephone,email FROM boutique ORDER BY id")) {
            while (r.next()) lignes.add(Arrays.asList(r.getString(1), r.getString(2), r.getString(3)));
        }
        return lignes;
    }
    private static void sql(Connection c, String sql) throws SQLException { BoutiquesMigration.executer(c, sql); }
}
