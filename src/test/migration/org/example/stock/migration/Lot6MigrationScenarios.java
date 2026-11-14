package org.example.stock.migration;

import java.sql.*;
import java.util.*;

/**
 * Fixtures fictives identiques sur H2 et MariaDB ; la base de départ est celle des lots 1, 2, 4 puis 3 (deux boutiques, comptes
 * multiples, règlements, historiques) : le lot 6 s'applique après eux.
 */
final class Lot6MigrationScenarios {
    static final List<String> VALIDES = List.of("ancien", "vide", "schema_neuf", "colonnes_partielles", "cle_non_remplie", "unicite_nouvelle_seule",
            "unicite_ancienne_absente", "relance", "nouvelle_boutique");
    static final List<String> INVALIDES = List.of("supprime_le_type", "supprime_le_obligatoire", "supprime_par_type", "nom_cle_taille", "nom_cle_obligatoire",
            "unicite_mauvaises_colonnes", "cle_categorie_supprimee_non_vide", "cle_differente_du_nom", "auteur_sans_date", "fiche_active_vers_corbeille");
    private Lot6MigrationScenarios() {}

    /** Base des lots 1, 2, 4 et 3 migrés, sans les colonnes du lot 6. */
    static void schema(Connection c, boolean vide) throws Exception {
        Lot3MigrationScenarios.schema(c, vide);
        Lot3Migration.migrer(c, true);
    }

    static void preparer(Connection c, String scenario) throws Exception {
        if (Set.of("ancien", "vide", "schema_neuf", "nouvelle_boutique").contains(scenario)) return;
        switch (scenario) {
            // Interruption après l'ajout de deux colonnes : les autres sont ajoutées, ce qui existe n'est pas réécrit.
            case "colonnes_partielles" -> {
                sql(c, "ALTER TABLE client ADD COLUMN supprime_le DATETIME(6) NULL");
                sql(c, "ALTER TABLE categorie ADD COLUMN nom_cle VARCHAR(255) NULL");
                return;
            }
            // Colonnes toutes ajoutées, clés pas encore remplies, ancienne unicité encore là.
            case "cle_non_remplie" -> { c.setReadOnly(false); Lot6Migration.ajouterColonnes(c); return; }
            // Interruption entre l'ajout de la nouvelle unicité et le retrait de l'ancienne.
            case "unicite_nouvelle_seule" -> {
                c.setReadOnly(false); Lot6Migration.ajouterColonnes(c); Lot6Migration.remplirCles(c);
                sql(c, "ALTER TABLE categorie ADD CONSTRAINT " + Lot6Migration.UNIQUE_NOUVEAU + " UNIQUE (boutique_id,nom_cle)");
                return;
            }
            // Ancienne unicité retirée à la main, nouvelle absente : la migration pose la nouvelle.
            case "unicite_ancienne_absente" -> {
                if (maria(c)) sql(c, "CREATE INDEX idx_test_categorie_boutique ON categorie(boutique_id)");
                sql(c, "ALTER TABLE categorie DROP " + (maria(c) ? "INDEX " : "CONSTRAINT ") + Lot6Migration.UNIQUE_ANCIEN);
                return;
            }
            default -> {}
        }
        Lot6Migration.migrer(c, true);
        switch (scenario) {
            // Après la migration, le gérant a mis des fiches à la corbeille et recréé une catégorie du même nom : une relance n'y touche pas.
            case "relance" -> saisirCorbeille(c);
            case "supprime_le_type" -> sql(c, "ALTER TABLE client " + alter(c) + " supprime_le VARCHAR(30) NULL");
            case "supprime_le_obligatoire" -> {
                sql(c, "INSERT INTO fournisseur(id,boutique_id,nom,solde_initial,supprime_le) VALUES(601,1,'Fournisseur factice',0,'2026-01-01 08:00:00.000000')");
                sql(c, "UPDATE fournisseur SET supprime_le='2000-01-01 00:00:00' WHERE supprime_le IS NULL");
                sql(c, "ALTER TABLE fournisseur " + alter(c) + " supprime_le DATETIME(6) NOT NULL DEFAULT '2000-01-01 00:00:00'");
            }
            case "supprime_par_type" -> sql(c, "ALTER TABLE produit " + alter(c) + " supprime_par INT NULL");
            case "nom_cle_taille" -> sql(c, "ALTER TABLE categorie " + alter(c) + " nom_cle VARCHAR(100) NULL");
            case "nom_cle_obligatoire" -> { sql(c, "UPDATE categorie SET nom_cle=nom WHERE nom_cle IS NULL"); sql(c, "ALTER TABLE categorie " + alter(c) + " nom_cle VARCHAR(255) NOT NULL"); }
            case "unicite_mauvaises_colonnes" -> {
                sql(c, "ALTER TABLE categorie ADD CONSTRAINT uk_test_categorie UNIQUE (boutique_id,nom_cle,id)");
                sql(c, "ALTER TABLE categorie DROP " + (maria(c) ? "INDEX " : "CONSTRAINT ") + Lot6Migration.UNIQUE_NOUVEAU);
                sql(c, "ALTER TABLE categorie ADD CONSTRAINT " + Lot6Migration.UNIQUE_NOUVEAU + " UNIQUE (boutique_id,nom)");
            }
            case "cle_categorie_supprimee_non_vide" -> { saisirCorbeille(c); sql(c, "UPDATE categorie SET nom_cle='Boissons de la corbeille' WHERE id=600"); }
            case "cle_differente_du_nom" -> sql(c, "UPDATE categorie SET nom_cle='Autre nom' WHERE id=(SELECT MIN(id) FROM (SELECT id FROM categorie WHERE nom IS NOT NULL) x)");
            case "auteur_sans_date" -> { saisirCorbeille(c); sql(c, "UPDATE client SET supprime_le=NULL WHERE id=600"); }
            // Une vente active se réfère à un client de la corbeille : la suppression aurait dû être refusée.
            case "fiche_active_vers_corbeille" -> {
                saisirCorbeille(c);
                sql(c, "INSERT INTO vente(id,boutique_id,date_vente,montant_total,montant_verse,client_id) VALUES(600,1,'2026-01-05 00:00:00.000000',10.00,0,600)");
            }
            default -> throw new IllegalStateException("Scénario lot 6 inconnu.");
        }
    }

    /** Une fiche de chaque type à la corbeille (avec son auteur), et une catégorie active qui reprend le nom d'une catégorie de la corbeille. */
    static void saisirCorbeille(Connection c) throws SQLException {
        sql(c, "INSERT INTO categorie(id,boutique_id,nom,nom_cle,supprime_le,supprime_par) VALUES(600,1,'Boissons',NULL,'2026-01-02 08:00:00.123456',1)");
        sql(c, "INSERT INTO categorie(id,boutique_id,nom,nom_cle) VALUES(601,1,'Boissons','Boissons')");
        sql(c, "INSERT INTO client(id,boutique_id,nom,solde_initial,supprime_le,supprime_par) VALUES(600,1,'Client supprimé',0,'2026-01-02 09:00:00.000000',1)");
        sql(c, "INSERT INTO fournisseur(id,boutique_id,nom,solde_initial,supprime_le,supprime_par) VALUES(600,2,'Fournisseur supprimé',0,'2026-01-02 10:00:00.000000',2)");
        sql(c, "INSERT INTO produit(id,boutique_id,version,nom,reference,prix_achat,prix_vente,quantite,categorie_id,supprime_le,supprime_par) VALUES"
                + "(600,1,0,'Produit supprimé','SUP-600',1,2,0,601,'2026-01-02 11:00:00.000000',1)");
    }

    static void verifier(Connection c, String scenario) throws Exception {
        var avant = Lot6Migration.photographie(c); var schemas = Lot6Migration.schemasAnciens(c); var tables = BoutiquesMigration.tables(c);
        var complet = etatComplet(c);
        if (INVALIDES.contains(scenario)) {
            for (boolean appliquer : List.of(false, true)) {
                boolean refuse = false;
                try { Lot6Migration.migrer(c, appliquer); } catch (IllegalStateException e) { refuse = true; }
                exiger(refuse && complet.equals(etatComplet(c)) && tables.equals(BoutiquesMigration.tables(c)), "Schéma lot 6 incohérent accepté ou données modifiées : " + scenario);
            }
            return;
        }
        Lot6Migration.migrer(c, false);
        exiger(complet.equals(etatComplet(c)) && tables.equals(BoutiquesMigration.tables(c)), "Le contrôle a modifié la base");
        boolean dejaFinalise = Lot6Migration.finalise(c);
        var valeurs = Lot6Migration.valeursLot6(c);
        Lot6Migration.migrer(c, true);
        exiger(Lot6Migration.finalise(c) && tables.equals(BoutiquesMigration.tables(c)) && avant.equals(Lot6Migration.photographie(c))
                && schemas.equals(Lot6Migration.schemasAnciens(c)), "Les anciennes données, stocks ou montants ont changé");
        var apresValeurs = Lot6Migration.valeursLot6(c);
        for (var e : valeurs.entrySet()) exiger(apresValeurs.get(e.getKey()).containsAll(e.getValue()), "Une valeur déjà migrée a été réécrite : " + e.getKey());
        exiger(dejaFinalise || !scenario.equals("relance"), "Le scénario de relance doit partir d'une base migrée");
        verifierCles(c);
        verifierUnicite(c, scenario);
        var apres = etatComplet(c);
        Lot6Migration.migrer(c, true); Lot6Migration.migrer(c, false);
        exiger(apres.equals(etatComplet(c)), "Relance non idempotente");
        if (scenario.equals("relance")) verifierCorbeilleConservee(c);
        if (scenario.equals("nouvelle_boutique")) {
            sql(c, "INSERT INTO boutique(id,nom,date_creation) VALUES(99,'Nouvelle boutique factice','2026-01-01 00:00:00.000000')");
            sql(c, "INSERT INTO categorie(id,boutique_id,nom,nom_cle) VALUES(990,99,'Catégorie factice','Catégorie factice')");
            var nouvelle = etatComplet(c);
            Lot6Migration.migrer(c, true);
            exiger(nouvelle.equals(etatComplet(c)), "Nouvelle boutique modifiée par relance");
        }
    }

    /** Toute catégorie active a pour clé son nom ; aucune fiche n'est à la corbeille après la migration (sauf celles que le scénario y a mises). */
    private static void verifierCles(Connection c) throws SQLException {
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM categorie WHERE supprime_le IS NULL AND nom IS NOT NULL AND (nom_cle IS NULL OR nom_cle<>nom)") == 0,
                "Une catégorie active n'a pas pour clé son nom");
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM categorie WHERE supprime_le IS NOT NULL AND nom_cle IS NOT NULL") == 0, "La clé d'une catégorie de la corbeille n'est pas vide");
        long aLaCorbeille = 0;
        for (String table : Lot6Migration.TABLES) aLaCorbeille += BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table + " WHERE supprime_le IS NOT NULL");
        exiger(aLaCorbeille == (scenarioAvecCorbeille(c) ? 4 : 0), "Le nombre de fiches à la corbeille a changé");
    }

    private static boolean scenarioAvecCorbeille(Connection c) throws SQLException {
        return BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM categorie WHERE id=600") == 1;
    }

    /** L'ancienne unicité (boutique, nom) a disparu, la nouvelle (boutique, clé) existe, et elle refuse deux catégories actives du même nom. */
    private static void verifierUnicite(Connection c, String scenario) throws SQLException {
        var uniques = Lot6Migration.unicitesCategorie(c).values();
        exiger(uniques.stream().anyMatch(u -> u.equals(List.of("boutique_id", "nom_cle"))) && uniques.stream().noneMatch(u -> u.equals(List.of("boutique_id", "nom"))),
                "Unicités de catégorie inattendues après migration : " + scenario);
        sql(c, "INSERT INTO boutique(id,nom,date_creation) VALUES(98,'Boutique de test d''unicité','2026-01-01 00:00:00.000000')");
        boolean refuse = false;
        try {
            sql(c, "INSERT INTO categorie(id,boutique_id,nom,nom_cle) VALUES(650,98,'Doublon actif','Doublon actif')");
            sql(c, "INSERT INTO categorie(id,boutique_id,nom,nom_cle) VALUES(651,98,'Doublon actif','Doublon actif')");
        } catch (SQLException e) { refuse = e.getSQLState() != null && e.getSQLState().startsWith("23"); }
        exiger(refuse, "Deux catégories actives du même nom ont été acceptées");
        // Le même nom, à la corbeille, ne bloque rien.
        sql(c, "INSERT INTO categorie(id,boutique_id,nom,nom_cle,supprime_le) VALUES(652,98,'Doublon actif',NULL,'2026-01-03 08:00:00.000000')");
        sql(c, "INSERT INTO categorie(id,boutique_id,nom,nom_cle,supprime_le) VALUES(653,98,'Doublon actif',NULL,'2026-01-03 09:00:00.000000')");
        sql(c, "DELETE FROM categorie WHERE id IN (650,651,652,653)");
        sql(c, "DELETE FROM boutique WHERE id=98");
    }

    private static void verifierCorbeilleConservee(Connection c) throws SQLException {
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM categorie WHERE id=600 AND nom_cle IS NULL AND supprime_par=1 AND supprime_le IS NOT NULL") == 1, "Catégorie de la corbeille modifiée");
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM categorie WHERE id=601 AND nom_cle='Boissons' AND supprime_le IS NULL") == 1, "Catégorie active modifiée");
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM fournisseur WHERE id=600 AND supprime_par=2") == 1, "Fournisseur de la corbeille modifié");
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM client WHERE id=600 AND supprime_par=1") == 1 && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM produit WHERE id=600 AND supprime_par=1") == 1,
                "Client ou produit de la corbeille modifié");
    }

    static List<Object> etatComplet(Connection c) throws SQLException {
        return List.of(Lot6Migration.photographie(c), Lot6Migration.valeursLot6(c), Lot6Migration.unicitesCategorie(c), Lot3MigrationScenarios.etatComplet(c));
    }

    static void sql(Connection c, String requete) throws SQLException { c.setReadOnly(false); Lot6Migration.sql(c, requete); }
    static boolean maria(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static String alter(Connection c) throws SQLException { return maria(c) ? "MODIFY COLUMN" : "ALTER COLUMN"; }
    static void exiger(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
