package org.example.stock.migration;

import java.sql.*;
import java.util.*;

/** Même jeu factice pour H2 et MariaDB ; aucun accès à une boutique réelle. */
final class TresorerieMigrationScenarios {
    static final List<String> VALIDES = List.of("ancien", "vide", "comptes_seuls", "operations_seules", "colonne_compte_seule", "colonnes_sans_relation", "comptes_partiels", "complet", "activite_existante", "nouvelle_boutique");
    static final List<String> INVALIDES = List.of("compte_nullable", "compte_longueur", "compte_defaut", "compte_inconnu", "relation_obligatoire", "compte_sans_unique", "compte_sans_fk", "operation_montant", "operation_jeton", "operation_acteur_autre_boutique", "operation_sans_unique", "relation_mauvaise_cible");
    private TresorerieMigrationScenarios() {}

    static void schema(Connection c, boolean vide) throws Exception {
        InitialisationBoutiqueMigrationScenarios.schema(c, vide);
        InitialisationBoutiqueMigration.migrer(c, true);
    }

    static void preparer(Connection c, String scenario) throws SQLException {
        if (Set.of("ancien", "vide", "nouvelle_boutique").contains(scenario)) return;
        if (scenario.equals("comptes_seuls")) { TresorerieMigration.creerComptes(c); return; }
        if (scenario.equals("operations_seules")) { TresorerieMigration.creerOperations(c); return; }
        if (scenario.equals("colonne_compte_seule")) { sql(c, "ALTER TABLE mouvement_caisse ADD COLUMN compte VARCHAR(32) NOT NULL DEFAULT 'CAISSE'"); return; }
        if (scenario.equals("colonnes_sans_relation")) { TresorerieMigration.creerOperations(c); TresorerieMigration.ajouterColonnes(c); return; }
        if (scenario.equals("comptes_partiels")) {
            TresorerieMigration.creerComptes(c);
            sql(c, "INSERT INTO compte_tresorerie_boutique(boutique_id,compte) VALUES(1,'CAISSE'),(1,'BANQUE'),(2,'ORANGE_MONEY')");
            return;
        }
        TresorerieMigration.migrer(c, true);
        switch (scenario) {
            case "complet" -> {}
            case "activite_existante" -> activite(c);
            case "compte_nullable" -> sql(c, "ALTER TABLE mouvement_caisse " + alter(c) + " compte VARCHAR(32) NULL DEFAULT 'CAISSE'");
            case "compte_longueur" -> sql(c, "ALTER TABLE mouvement_caisse " + alter(c) + " compte VARCHAR(33) NOT NULL DEFAULT 'CAISSE'");
            case "compte_defaut" -> sql(c, "ALTER TABLE mouvement_caisse ALTER COLUMN compte SET DEFAULT 'BANQUE'");
            case "compte_inconnu" -> sql(c, "UPDATE mouvement_caisse SET compte='AUTRE' WHERE id=1");
            case "relation_obligatoire" -> {
                sql(c, "DELETE FROM mouvement_caisse");
                sql(c, "ALTER TABLE mouvement_caisse DROP " + (maria(c) ? "FOREIGN KEY" : "CONSTRAINT") + " fk_mouvement_operation_tresorerie");
                sql(c, "ALTER TABLE mouvement_caisse " + alter(c) + " operation_tresorerie_id BIGINT NOT NULL");
            }
            case "compte_sans_unique" -> {
                if (maria(c)) { sql(c, "CREATE INDEX idx_compte_boutique_test ON compte_tresorerie_boutique(boutique_id)"); sql(c, "ALTER TABLE compte_tresorerie_boutique DROP INDEX uk_compte_tresorerie_boutique"); }
                else sql(c, "ALTER TABLE compte_tresorerie_boutique DROP CONSTRAINT uk_compte_tresorerie_boutique");
            }
            case "compte_sans_fk" -> sql(c, "ALTER TABLE compte_tresorerie_boutique DROP " + (maria(c) ? "FOREIGN KEY" : "CONSTRAINT") + " fk_compte_tresorerie_boutique");
            case "operation_montant" -> { activite(c); sql(c, "UPDATE operation_tresorerie SET montant=-1 WHERE id=1"); }
            case "operation_jeton" -> { activite(c); sql(c, "UPDATE operation_tresorerie SET jeton='invalide' WHERE id=1"); }
            case "operation_acteur_autre_boutique" -> { activite(c); sql(c, "UPDATE operation_tresorerie SET utilisateur_id=2 WHERE id=1"); }
            case "operation_sans_unique" -> {
                sql(c, "ALTER TABLE operation_tresorerie DROP " + (maria(c) ? "INDEX" : "CONSTRAINT") + " uk_operation_tresorerie_jeton");
            }
            case "relation_mauvaise_cible" -> {
                sql(c, "ALTER TABLE mouvement_caisse DROP " + (maria(c) ? "FOREIGN KEY" : "CONSTRAINT") + " fk_mouvement_operation_tresorerie");
                sql(c, "ALTER TABLE mouvement_caisse ADD CONSTRAINT fk_mouvement_operation_tresorerie FOREIGN KEY(operation_tresorerie_id) REFERENCES boutique(id)");
            }
            default -> throw new IllegalStateException("Scénario trésorerie inconnu.");
        }
    }

    private static void activite(Connection c) throws SQLException {
        sql(c, "INSERT INTO operation_tresorerie(id,boutique_id,utilisateur_id,jeton,type,compte_source,compte_destination,montant,motif,date_operation) VALUES"
                + "(1,1,1,'a0000000-0000-0000-0000-000000000001','TRANSFERT','CAISSE','ORANGE_MONEY',40.25,'Versement factice','2025-01-04 00:00:00.123456'),"
                + "(2,1,1,'a0000000-0000-0000-0000-000000000002','DEPENSE','ORANGE_MONEY',NULL,10.25,'Dépense factice','2025-01-04 01:00:00.123456')");
        sql(c, "INSERT INTO mouvement_caisse(id,boutique_id,date_mouvement,montant,type,motif,source,utilisateur_id,compte,operation_tresorerie_id) VALUES"
                + "(90,1,'2025-01-04 00:00:00.123456',-40.25,'SORTIE','Transfert fictif','TRANSFERT',1,'CAISSE',1),"
                + "(91,1,'2025-01-04 00:00:00.123456',40.25,'ENTREE','Transfert fictif','TRANSFERT',1,'ORANGE_MONEY',1),"
                + "(92,1,'2025-01-04 01:00:00.123456',-10.25,'SORTIE','Dépense fictive','DEPENSE',1,'ORANGE_MONEY',2)");
    }

    static void verifier(Connection c, String scenario) throws SQLException {
        var avant = TresorerieMigration.photographie(c); var schemas = TresorerieMigration.schemasAnciens(c);
        var tables = BoutiquesMigration.tables(c); var comptes = TresorerieMigration.donnees(c, TresorerieMigration.COMPTES);
        var operations = TresorerieMigration.donnees(c, TresorerieMigration.OPERATIONS);
        var journal = toutesValeursMouvements(c);
        if (INVALIDES.contains(scenario)) {
            for (boolean appliquer : List.of(false, true)) {
                boolean refuse = false;
                try { TresorerieMigration.migrer(c, appliquer); } catch (IllegalStateException e) { refuse = true; }
                exiger(refuse, "Schéma trésorerie incohérent accepté : " + scenario);
                exiger(avant.equals(TresorerieMigration.photographie(c)) && schemas.equals(TresorerieMigration.schemasAnciens(c))
                        && tables.equals(BoutiquesMigration.tables(c)) && comptes.equals(TresorerieMigration.donnees(c, TresorerieMigration.COMPTES))
                        && operations.equals(TresorerieMigration.donnees(c, TresorerieMigration.OPERATIONS)) && journal.equals(toutesValeursMouvements(c)),
                        "Refus ayant modifié des données : " + scenario);
            }
            return;
        }
        TresorerieMigration.migrer(c, false);
        exiger(tables.equals(BoutiquesMigration.tables(c)) && journal.equals(toutesValeursMouvements(c))
                && comptes.equals(TresorerieMigration.donnees(c, TresorerieMigration.COMPTES)), "Check a modifié le schéma ou des données");
        TresorerieMigration.migrer(c, true);
        exiger(BoutiquesMigration.tables(c).size() == 19 && TresorerieMigration.comptesManquants(c) == 0, "Comptes ou tables manquants");
        exiger(avant.equals(TresorerieMigration.photographie(c)) && schemas.equals(TresorerieMigration.schemasAnciens(c))
                && operations.equals(TresorerieMigration.donnees(c, TresorerieMigration.OPERATIONS))
                && TresorerieMigration.donnees(c, TresorerieMigration.COMPTES).containsAll(comptes), "Anciennes données modifiées");
        if (!Set.of("complet", "activite_existante").contains(scenario))
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM mouvement_caisse WHERE compte<>'CAISSE' OR operation_tresorerie_id IS NOT NULL") == 0, "Moyen de paiement ou opération historique inventé");
        var nouveauJournal = toutesValeursMouvements(c); var nouveauxComptes = TresorerieMigration.donnees(c, TresorerieMigration.COMPTES);
        TresorerieMigration.migrer(c, true); TresorerieMigration.migrer(c, false);
        exiger(nouveauJournal.equals(toutesValeursMouvements(c)) && nouveauxComptes.equals(TresorerieMigration.donnees(c, TresorerieMigration.COMPTES)), "Relance non idempotente");
        if (scenario.equals("nouvelle_boutique")) {
            sql(c, "INSERT INTO boutique(id,nom,date_creation) VALUES(99,'Nouvelle factice','2025-01-04 00:00:00.123456')");
            TresorerieMigration.migrer(c, true);
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique WHERE boutique_id=99") == 3
                    && nouveauJournal.equals(toutesValeursMouvements(c)), "Relance sur nouvelle boutique incorrecte");
        }
    }

    static List<List<Object>> toutesValeursMouvements(Connection c) throws SQLException { return TresorerieMigration.donnees(c, "mouvement_caisse"); }
    static void sql(Connection c, String sql) throws SQLException { c.setReadOnly(false); BoutiquesMigration.executer(c, sql); }
    static void exiger(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static boolean maria(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static String alter(Connection c) throws SQLException { return maria(c) ? "MODIFY COLUMN" : "ALTER COLUMN"; }
}
