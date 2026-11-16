package org.example.stock.migration;

import java.sql.*;
import java.util.*;

/**
 * Fixtures fictives identiques sur H2 et MariaDB ; la base de départ est celle des lots 1 à 4, 6 puis 5 (deux boutiques, comptes
 * multiples dont plusieurs ORANGE_MONEY, règlements, comptabilité) : la migration pays-monnaie s'applique après eux.
 */
final class PaysMonnaieMigrationScenarios {
    static final List<String> VALIDES = List.of("ancien", "vide", "schema_neuf", "colonnes_partielles", "boutiques_remplies", "types_deja_convertis", "relance", "nouvelle_boutique");
    static final List<String> INVALIDES = List.of("pays_taille", "monnaie_taille", "pays_inconnu", "monnaie_incoherente", "operateur_taille", "operateur_vide",
            "operateur_sur_caisse", "type_compte_inconnu", "principal_incoherent");
    private PaysMonnaieMigrationScenarios() {}

    /** Base des lots 1 à 4, 6 et 5 migrés, sans pays, monnaie ni opérateur. */
    static void schema(Connection c, boolean vide) throws Exception {
        Lot5MigrationScenarios.schema(c, vide);
        Lot5Migration.migrer(c, true);
    }

    static void preparer(Connection c, String scenario) throws Exception {
        if (Set.of("ancien", "vide", "schema_neuf", "nouvelle_boutique").contains(scenario)) return;
        switch (scenario) {
            // Interruption après l'ajout d'une seule colonne : les autres sont ajoutées, ce qui existe n'est pas réécrit.
            case "colonnes_partielles" -> { sql(c, "ALTER TABLE boutique ADD COLUMN pays VARCHAR(2) NULL"); sql(c, "UPDATE boutique SET pays='GN' WHERE id=1"); return; }
            // Colonnes ajoutées et remplies, pas encore obligatoires ni converties.
            case "boutiques_remplies" -> { PaysMonnaieMigration.ajouterColonnes(c); PaysMonnaieMigration.remplirBoutiques(c); return; }
            // Interruption après la conversion des types, avant l'opérateur.
            case "types_deja_convertis" -> {
                PaysMonnaieMigration.ajouterColonnes(c); PaysMonnaieMigration.remplirBoutiques(c);
                sql(c, "UPDATE compte_tresorerie_boutique SET type='MOBILE_MONEY' WHERE type='ORANGE_MONEY'");
                sql(c, "UPDATE compte_tresorerie_boutique SET principal_type='MOBILE_MONEY' WHERE principal_type='ORANGE_MONEY'");
                return;
            }
            default -> {}
        }
        PaysMonnaieMigration.migrer(c, true);
        switch (scenario) {
            // Après la migration, une boutique a été créée au Sénégal et un compte Wave ajouté : une relance n'y touche pas.
            case "relance" -> saisirSenegal(c);
            case "pays_taille" -> sql(c, "ALTER TABLE boutique " + alter(c) + " pays VARCHAR(3) NOT NULL");
            case "monnaie_taille" -> sql(c, "ALTER TABLE boutique " + alter(c) + " monnaie VARCHAR(5) NOT NULL");
            case "pays_inconnu" -> sql(c, "UPDATE boutique SET pays='ZZ' WHERE id=2");
            case "monnaie_incoherente" -> sql(c, "UPDATE boutique SET pays='SN' WHERE id=2");
            case "operateur_taille" -> sql(c, "ALTER TABLE compte_tresorerie_boutique " + alter(c) + " operateur VARCHAR(20) NULL");
            case "operateur_vide" -> sql(c, "UPDATE compte_tresorerie_boutique SET operateur='  ' WHERE boutique_id=1 AND type='MOBILE_MONEY' AND principal_type='MOBILE_MONEY'");
            case "operateur_sur_caisse" -> sql(c, "UPDATE compte_tresorerie_boutique SET operateur='Wave' WHERE type='ESPECES' AND boutique_id=1");
            case "type_compte_inconnu" -> sql(c, "UPDATE compte_tresorerie_boutique SET type='PAYPAL' WHERE type='BANQUE' AND boutique_id=1 AND principal_type='BANQUE'");
            // Le compte principal de la banque porte le type d'un autre compte : la valeur est unique, l'incohérence est détectée par la migration.
            case "principal_incoherent" -> sql(c, "UPDATE compte_tresorerie_boutique SET principal_type='AUTRE_TYPE' WHERE type='BANQUE' AND boutique_id=2 AND principal_type='BANQUE'");
            default -> throw new IllegalStateException("Scénario pays-monnaie inconnu.");
        }
    }

    /** Une boutique au Sénégal (XOF) avec un compte Wave : valeurs posées par l'application, jamais réécrites par une relance. */
    static void saisirSenegal(Connection c) throws SQLException {
        sql(c, "INSERT INTO boutique(id,nom,date_creation,pays,monnaie) VALUES(70,'Boutique de Dakar factice','2026-01-01 00:00:00.000000','SN','XOF')");
        sql(c, "INSERT INTO compte_tresorerie_boutique(id,boutique_id,type,nom,nom_cle,principal_type,operateur,actif,ordre) VALUES"
                + "(700,70,'MOBILE_MONEY','Wave','wave','MOBILE_MONEY','Wave',TRUE,1),(701,70,'ESPECES','Caisse','caisse','ESPECES',NULL,TRUE,0)");
        sql(c, "UPDATE compte_tresorerie_boutique SET operateur='MTN MoMo' WHERE boutique_id=1 AND type='MOBILE_MONEY' AND principal_type='MOBILE_MONEY'");
        // Le lot 5 est idempotent : il donne un sous-compte aux comptes que l'application vient de créer (sinon il ne serait plus « terminé »).
        Lot5Migration.migrer(c, true);
    }

    static void verifier(Connection c, String scenario) throws Exception {
        var avant = PaysMonnaieMigration.photographie(c); var schemas = PaysMonnaieMigration.schemasAnciens(c); var tables = BoutiquesMigration.tables(c);
        var complet = etatComplet(c);
        if (INVALIDES.contains(scenario)) {
            for (boolean appliquer : List.of(false, true)) {
                boolean refuse = false;
                try { PaysMonnaieMigration.migrer(c, appliquer); } catch (IllegalStateException e) { refuse = true; }
                exiger(refuse && complet.equals(etatComplet(c)) && tables.equals(BoutiquesMigration.tables(c)), "Schéma pays-monnaie incohérent accepté ou données modifiées : " + scenario);
            }
            return;
        }
        PaysMonnaieMigration.migrer(c, false);
        exiger(complet.equals(etatComplet(c)) && tables.equals(BoutiquesMigration.tables(c)), "Le contrôle a modifié la base");
        boolean dejaFinalise = PaysMonnaieMigration.finalise(c);
        var valeurs = PaysMonnaieMigration.valeursPosees(c);
        var totaux = PaysMonnaieMigration.totaux(c);
        PaysMonnaieMigration.migrer(c, true);
        exiger(PaysMonnaieMigration.finalise(c) && tables.equals(BoutiquesMigration.tables(c)) && avant.equals(PaysMonnaieMigration.photographie(c))
                && schemas.equals(PaysMonnaieMigration.schemasAnciens(c)), "Les anciennes données, comptes ou montants ont changé");
        exiger(totaux.equals(PaysMonnaieMigration.totaux(c)), "Un total par compte ou par type a changé");
        var apresValeurs = PaysMonnaieMigration.valeursPosees(c);
        for (var e : valeurs.entrySet()) exiger(apresValeurs.get(e.getKey()).containsAll(e.getValue()), "Une valeur déjà migrée a été réécrite : " + e.getKey());
        exiger(dejaFinalise || !scenario.equals("relance"), "Le scénario de relance doit partir d'une base migrée");
        verifierResultat(c, scenario);
        var apres = etatComplet(c);
        PaysMonnaieMigration.migrer(c, true); PaysMonnaieMigration.migrer(c, false);
        exiger(apres.equals(etatComplet(c)), "Relance non idempotente");
        if (scenario.equals("nouvelle_boutique")) {
            sql(c, "INSERT INTO boutique(id,nom,date_creation,pays,monnaie) VALUES(99,'Nouvelle boutique factice','2026-01-01 00:00:00.000000','CM','XAF')");
            var nouvelle = etatComplet(c);
            PaysMonnaieMigration.migrer(c, true);
            exiger(nouvelle.equals(etatComplet(c)), "Nouvelle boutique modifiée par relance");
        }
    }

    /** Boutiques existantes en Guinée, plus aucun ORANGE_MONEY, opérateur « Orange Money » sur les comptes de mobile money, pays et monnaie obligatoires. */
    private static void verifierResultat(Connection c, String scenario) throws SQLException {
        boolean relance = scenario.equals("relance");
        long boutiques = BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique");
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE pays='GN' AND monnaie='GNF'") == boutiques - (relance ? 1 : 0), "Une boutique existante n'est pas en Guinée (GN, GNF)");
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE pays IS NULL OR monnaie IS NULL") == 0, "Une boutique sans pays ou sans monnaie");
        for (String colonne : List.of("pays", "monnaie"))
            exiger(InscriptionEssaiMigration.colonnes(c, "boutique").get(colonne).nullable() == DatabaseMetaData.columnNoNulls, "boutique." + colonne + " doit être obligatoire");
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique WHERE type='ORANGE_MONEY' OR principal_type='ORANGE_MONEY'") == 0, "Un compte est resté ORANGE_MONEY");
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique WHERE type='MOBILE_MONEY' AND operateur IS NULL") == 0, "Un compte de mobile money sans opérateur");
        if (!relance) exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique WHERE type='MOBILE_MONEY' AND operateur<>'Orange Money'") == 0, "Un ancien compte n'a pas l'opérateur Orange Money");
        if (relance) {
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE id=70 AND pays='SN' AND monnaie='XOF'") == 1, "Boutique du Sénégal modifiée");
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique WHERE id=700 AND operateur='Wave' AND type='MOBILE_MONEY'") == 1, "Compte Wave modifié");
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique WHERE boutique_id=1 AND type='MOBILE_MONEY' AND principal_type='MOBILE_MONEY' AND operateur='MTN MoMo'") == 1, "Opérateur choisi réécrit");
        }
        // L'unicité d'un compte principal par type et par boutique est conservée : un second principal du même type est refusé.
        long principaux = BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique WHERE principal_type='MOBILE_MONEY'");
        if (principaux == 0) { exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique") == 0, "Aucun compte principal de mobile money après conversion"); return; }
        boolean refuse = false;
        try {
            sql(c, "INSERT INTO compte_tresorerie_boutique(boutique_id,type,nom,nom_cle,principal_type,operateur,actif,ordre) SELECT boutique_id,'MOBILE_MONEY','Doublon','doublon','MOBILE_MONEY','Wave',TRUE,9 FROM compte_tresorerie_boutique WHERE principal_type='MOBILE_MONEY' ORDER BY id LIMIT 1");
        } catch (SQLException e) { refuse = e.getSQLState() != null && e.getSQLState().startsWith("23"); }
        exiger(refuse, "Deux comptes principaux de mobile money ont été acceptés pour une boutique");
    }

    static List<Object> etatComplet(Connection c) throws SQLException { return Lot3MigrationScenarios.etatComplet(c); }
    static void sql(Connection c, String requete) throws SQLException { c.setReadOnly(false); PaysMonnaieMigration.sql(c, requete); }
    static boolean maria(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static String alter(Connection c) throws SQLException { return maria(c) ? "MODIFY COLUMN" : "ALTER COLUMN"; }
    static void exiger(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
