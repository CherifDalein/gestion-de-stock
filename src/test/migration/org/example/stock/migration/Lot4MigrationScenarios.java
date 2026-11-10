package org.example.stock.migration;

import java.sql.*;
import java.util.*;

/** Fixtures fictives identiques sur H2 et MariaDB ; la base de départ est celle du lot 2 (deux boutiques, règlements, historiques). */
final class Lot4MigrationScenarios {
    static final List<String> VALIDES = List.of("ancien", "vide", "schema_neuf", "colonne_format_seule", "colonne_proposer_seule", "relance", "nouvelle_boutique");
    static final List<String> INVALIDES = List.of("format_longueur", "format_facultatif", "format_defaut", "format_inconnu", "proposer_facultatif",
            "proposer_defaut", "proposer_type", "proposer_valeur");
    private Lot4MigrationScenarios() {}

    static void schema(Connection c, boolean vide) throws Exception {
        Lot2MigrationScenarios.schema(c, vide);
        Lot2Migration.migrer(c, true);
    }

    static void preparer(Connection c, String scenario) throws Exception {
        if (Set.of("ancien", "vide", "schema_neuf", "nouvelle_boutique").contains(scenario)) return;
        switch (scenario) {
            case "colonne_format_seule" -> {
                // Un format déjà choisi (ou ajouté avant une interruption) n'est pas réécrit.
                sql(c, "ALTER TABLE boutique ADD COLUMN format_recu VARCHAR(16) NOT NULL DEFAULT 'A4'");
                sql(c, "UPDATE boutique SET format_recu='TICKET_80' WHERE id=1");
                return;
            }
            case "colonne_proposer_seule" -> {
                sql(c, "ALTER TABLE boutique ADD COLUMN proposer_impression_recu BOOLEAN NOT NULL DEFAULT TRUE");
                sql(c, "UPDATE boutique SET proposer_impression_recu=FALSE WHERE id=1");
                return;
            }
            default -> {}
        }
        Lot4Migration.migrer(c, true);
        switch (scenario) {
            case "relance" -> sql(c, "UPDATE boutique SET format_recu='TICKET_80', proposer_impression_recu=FALSE WHERE id=1");
            case "format_longueur" -> sql(c, "ALTER TABLE boutique " + alter(c) + " format_recu VARCHAR(15) NOT NULL DEFAULT 'A4'");
            case "format_facultatif" -> sql(c, "ALTER TABLE boutique " + alter(c) + " format_recu VARCHAR(16) NULL DEFAULT 'A4'");
            case "format_defaut" -> sql(c, "ALTER TABLE boutique ALTER COLUMN format_recu SET DEFAULT 'TICKET_80'");
            case "format_inconnu" -> sql(c, "UPDATE boutique SET format_recu='A5' WHERE id=2");
            case "proposer_facultatif" -> sql(c, "ALTER TABLE boutique " + alter(c) + " proposer_impression_recu BOOLEAN NULL DEFAULT TRUE");
            case "proposer_defaut" -> sql(c, "ALTER TABLE boutique ALTER COLUMN proposer_impression_recu SET DEFAULT FALSE");
            case "proposer_type" -> sql(c, "ALTER TABLE boutique " + alter(c) + " proposer_impression_recu INT NOT NULL DEFAULT 1");
            // TINYINT accepte 2 sur MariaDB ; H2 n'a pas de booléen à plusieurs valeurs, le type est alors refusé.
            case "proposer_valeur" -> {
                if (maria(c)) sql(c, "UPDATE boutique SET proposer_impression_recu=2 WHERE id=2");
                else sql(c, "ALTER TABLE boutique " + alter(c) + " proposer_impression_recu SMALLINT NOT NULL DEFAULT 1");
            }
            default -> throw new IllegalStateException("Scénario lot 4 inconnu.");
        }
    }

    static void verifier(Connection c, String scenario) throws Exception {
        var avant = Lot4Migration.photographie(c); var schemas = Lot4Migration.schemasAnciens(c); var tables = BoutiquesMigration.tables(c);
        var complet = etatComplet(c);
        if (INVALIDES.contains(scenario)) {
            for (boolean appliquer : List.of(false, true)) {
                boolean refuse = false;
                try { Lot4Migration.migrer(c, appliquer); } catch (IllegalStateException e) { refuse = true; }
                exiger(refuse && complet.equals(etatComplet(c)) && tables.equals(BoutiquesMigration.tables(c)), "Schéma lot 4 incohérent accepté ou données modifiées : " + scenario);
            }
            return;
        }
        Lot4Migration.migrer(c, false);
        exiger(complet.equals(etatComplet(c)) && tables.equals(BoutiquesMigration.tables(c)), "Le contrôle a modifié la base");
        boolean dejaFinalise = Lot4Migration.finalise(c);
        var valeurs = Lot4Migration.valeursLot4(c);
        Lot4Migration.migrer(c, true);
        exiger(Lot4Migration.finalise(c) && tables.equals(BoutiquesMigration.tables(c)) && avant.equals(Lot4Migration.photographie(c))
                && schemas.equals(Lot4Migration.schemasAnciens(c)), "Les anciennes données, stocks ou montants ont changé");
        var apresValeurs = Lot4Migration.valeursLot4(c);
        for (var e : valeurs.entrySet()) exiger(e.getValue().equals(apresValeurs.get(e.getKey())), "Une valeur déjà migrée a été réécrite : " + e.getKey());
        exiger(dejaFinalise || !scenario.equals("relance"), "Le scénario de relance doit partir d'une base migrée");
        verifierValeurs(c, scenario);
        var apres = etatComplet(c);
        Lot4Migration.migrer(c, true); Lot4Migration.migrer(c, false);
        exiger(apres.equals(etatComplet(c)), "Relance non idempotente");
        if (scenario.equals("nouvelle_boutique")) {
            sql(c, "INSERT INTO boutique(id,nom,date_creation) VALUES(99,'Nouvelle boutique factice','2026-01-01 00:00:00.000000')");
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE id=99 AND format_recu='A4' AND proposer_impression_recu=TRUE") == 1,
                    "Valeurs par défaut d'une nouvelle boutique incorrectes");
            for (String compte : List.of("CAISSE", "ORANGE_MONEY", "BANQUE")) sql(c, "INSERT INTO compte_tresorerie_boutique(boutique_id,compte) VALUES(99,'" + compte + "')");
            var nouvelle = etatComplet(c);
            Lot4Migration.migrer(c, true);
            exiger(nouvelle.equals(etatComplet(c)), "Nouvelle boutique modifiée par relance");
        }
    }

    /** Toute boutique existante garde A4 et le bouton proposé, sauf le réglage choisi explicitement par le scénario sur la boutique 1. */
    private static void verifierValeurs(Connection c, String scenario) throws SQLException {
        long boutiques = BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique");
        if (!Set.of("vide", "schema_neuf").contains(scenario)) exiger(boutiques >= 2, "Les boutiques de la fixture sont absentes");
        String formatUn = Set.of("relance", "colonne_format_seule").contains(scenario) ? "TICKET_80" : "A4";
        boolean proposerUn = !Set.of("relance", "colonne_proposer_seule").contains(scenario);
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE (id=1 AND format_recu='" + formatUn + "') OR (id<>1 AND format_recu='A4')") == boutiques,
                "Format de reçu inattendu après migration");
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE (id=1 AND proposer_impression_recu=" + (proposerUn ? "TRUE" : "FALSE")
                + ") OR (id<>1 AND proposer_impression_recu=TRUE)") == boutiques, "Réglage du bouton de reçu inattendu après migration");
    }

    static List<Object> etatComplet(Connection c) throws SQLException {
        var schemas = new TreeMap<String, Object>();
        for (String nom : BoutiquesMigration.tables(c).keySet()) schemas.put(nom, new TreeMap<>(InscriptionEssaiMigration.colonnes(c, nom)));
        return List.of(Lot4Migration.photographie(c), Lot4Migration.valeursLot4(c), schemas, Lot2MigrationScenarios.etatComplet(c));
    }

    static void sql(Connection c, String requete) throws SQLException { c.setReadOnly(false); Lot4Migration.sql(c, requete); }
    static boolean maria(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static String alter(Connection c) throws SQLException { return maria(c) ? "MODIFY COLUMN" : "ALTER COLUMN"; }
    static void exiger(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
