package org.example.stock.migration;

import java.sql.*;
import java.util.*;

/**
 * Fixtures fictives identiques sur H2 et MariaDB ; la base de départ est celle des lots 1 à 4 puis 6 (deux boutiques, comptes de trésorerie
 * multiples, règlements, corbeille) : le lot 5a s'applique après eux.
 */
final class Lot5MigrationScenarios {
    static final List<String> VALIDES = List.of("ancien", "vide", "schema_neuf", "tables_partielles", "sous_comptes_partiels", "relance", "nouveau_compte", "compte_archive",
            "numeros_officiels_evites", "reglage_absent");
    static final List<String> INVALIDES = List.of("numero_taille", "unicite_absente", "sous_compte_officiel", "sous_compte_hors_parent", "parent_inconnu",
            "sous_compte_type_incoherent", "correspondance_compte_vide", "cle_etrangere_absente", "reglage_systeme_inconnu");
    private Lot5MigrationScenarios() {}

    /** Base des lots 1 à 4 et 6 migrés, sans les tables du lot 5a. */
    static void schema(Connection c, boolean vide) throws Exception {
        Lot6MigrationScenarios.schema(c, vide);
        Lot6Migration.migrer(c, true);
    }

    static void preparer(Connection c, String scenario) throws Exception {
        if (Set.of("ancien", "vide", "schema_neuf").contains(scenario)) return;
        switch (scenario) {
            // Interruption après la création de la première table : la seconde et les sous-comptes sont créés, rien d'autre ne bouge.
            case "tables_partielles" -> { c.setReadOnly(false); sql(c, creerSousComptesSeulement(c)); return; }
            // Un compte a déjà son sous-compte (posé à la main sous un autre numéro) : il n'est pas réécrit, les autres sont créés.
            case "sous_comptes_partiels" -> {
                c.setReadOnly(false); Lot5Migration.creerTables(c);
                sql(c, "INSERT INTO sous_compte_boutique(boutique_id,numero,parent_numero,intitule,compte_tresorerie_id,date_creation) SELECT boutique_id,'57150','571','Caisse nommée à la main',id,'2026-01-02 08:00:00.000000'"
                        + " FROM compte_tresorerie_boutique WHERE id=(SELECT MIN(id) FROM compte_tresorerie_boutique WHERE type='ESPECES')");
                return;
            }
            // Un compte archivé a aussi son sous-compte : l'historique doit rester lisible.
            case "compte_archive" -> {
                sql(c, "UPDATE compte_tresorerie_boutique SET actif=FALSE WHERE id=(SELECT MAX(id) FROM compte_tresorerie_boutique)");
                return;
            }
            // Le lot 5a est déjà posé (sous-comptes et correspondance saisis) : seule la table des réglages manque, rien d'autre ne bouge.
            case "reglage_absent" -> { Lot5Migration.migrer(c, true); saisirComptabilite(c); sql(c, "DROP TABLE reglage_comptable_boutique"); return; }
            // Les sous-comptes ne reprennent jamais un numéro officiel (5711 et 5712 sont des comptes du plan) : le premier numéro libre est choisi.
            case "numeros_officiels_evites" -> { return; }
            default -> {}
        }
        Lot5Migration.migrer(c, true);
        switch (scenario) {
            // Après la migration, le gérant a changé la correspondance, ouvert un sous-compte et renommé un compte : une relance n'y touche pas.
            case "relance" -> saisirComptabilite(c);
            case "nouveau_compte" -> sql(c, "INSERT INTO compte_tresorerie_boutique(boutique_id,type,nom,nom_cle,actif,ordre) VALUES(1,'BANQUE','Banque de l''Est','banque de l''est',TRUE,7)");
            case "numero_taille" -> sql(c, "ALTER TABLE sous_compte_boutique " + modifier(c) + " numero VARCHAR(20) NOT NULL");
            case "unicite_absente" -> {
                // MariaDB refuse de retirer l'index dont dépend la clé étrangère : un index propre à boutique_id prend le relais.
                if (maria(c)) sql(c, "CREATE INDEX idx_test_correspondance_boutique ON correspondance_compta(boutique_id)");
                sql(c, "ALTER TABLE correspondance_compta DROP " + (maria(c) ? "INDEX " : "CONSTRAINT ") + "uk_correspondance_compta_cle");
            }
            case "sous_compte_officiel" -> sql(c, "UPDATE sous_compte_boutique SET numero='571', parent_numero='571' WHERE id=(SELECT MIN(id) FROM (SELECT id FROM sous_compte_boutique WHERE parent_numero='571') x)");
            case "sous_compte_hors_parent" -> sql(c, "UPDATE sous_compte_boutique SET numero='5219' WHERE id=(SELECT MIN(id) FROM (SELECT id FROM sous_compte_boutique WHERE parent_numero='571') x)");
            case "parent_inconnu" -> sql(c, "INSERT INTO sous_compte_boutique(boutique_id,numero,parent_numero,intitule,date_creation) VALUES(1,'9999001','9999','Parent inconnu','2026-01-02 08:00:00.000000')");
            case "sous_compte_type_incoherent" -> sql(c, "UPDATE sous_compte_boutique SET parent_numero='521', numero='5219' WHERE id=(SELECT MIN(id) FROM (SELECT id FROM sous_compte_boutique WHERE parent_numero='571') x)");
            case "correspondance_compte_vide" -> sql(c, "INSERT INTO correspondance_compta(boutique_id,cle,compte_numero) VALUES(1,'VENTES_SERVICES','')");
            case "reglage_systeme_inconnu" -> sql(c, "INSERT INTO reglage_comptable_boutique(boutique_id,systeme) VALUES(1,'AUTRE')");
            case "cle_etrangere_absente" -> {
                if (maria(c)) sql(c, "ALTER TABLE sous_compte_boutique DROP FOREIGN KEY fk_sous_compte_compte_tresorerie");
                else sql(c, "ALTER TABLE sous_compte_boutique DROP CONSTRAINT fk_sous_compte_compte_tresorerie");
            }
            default -> throw new IllegalStateException("Scénario lot 5 inconnu.");
        }
    }

    private static String creerSousComptesSeulement(Connection c) throws SQLException {
        String moteur = maria(c) ? " ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" : "";
        return "CREATE TABLE sous_compte_boutique (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, boutique_id BIGINT NOT NULL, numero VARCHAR(8) NOT NULL, parent_numero VARCHAR(4) NOT NULL,"
                + " intitule VARCHAR(120) NOT NULL, compte_tresorerie_id BIGINT NULL, date_creation DATETIME(6) NOT NULL, CONSTRAINT uk_sous_compte_boutique_numero UNIQUE (boutique_id, numero),"
                + " CONSTRAINT uk_sous_compte_compte_tresorerie UNIQUE (compte_tresorerie_id), CONSTRAINT fk_sous_compte_boutique FOREIGN KEY (boutique_id) REFERENCES boutique (id),"
                + " CONSTRAINT fk_sous_compte_compte_tresorerie FOREIGN KEY (compte_tresorerie_id) REFERENCES compte_tresorerie_boutique (id))" + moteur;
    }

    /** Le gérant a modifié une correspondance, renommé un sous-compte, ouvert un sous-compte à la main et mis un compte de trésorerie à jour. */
    static void saisirComptabilite(Connection c) throws SQLException {
        sql(c, "INSERT INTO correspondance_compta(boutique_id,cle,compte_numero,mots_cles) VALUES(1,'VENTES_MARCHANDISES','702',NULL)");
        sql(c, "INSERT INTO correspondance_compta(boutique_id,cle,compte_numero,mots_cles) VALUES(1,'DEPENSE_LOYER','623','loyer, bail')");
        sql(c, "UPDATE sous_compte_boutique SET intitule='Caisse principale (renommée)' WHERE id=(SELECT MIN(id) FROM (SELECT id FROM sous_compte_boutique) x)");
        sql(c, "INSERT INTO sous_compte_boutique(boutique_id,numero,parent_numero,intitule,date_creation) VALUES(1,'6589001','658','Pertes diverses','2026-02-02 08:00:00.123456')");
    }

    static void verifier(Connection c, String scenario) throws Exception {
        var avant = Lot5Migration.photographie(c); var schemas = Lot5Migration.schemasAnciens(c); var tables = BoutiquesMigration.tables(c);
        var complet = etatComplet(c);
        if (INVALIDES.contains(scenario)) {
            for (boolean appliquer : List.of(false, true)) {
                boolean refuse = false;
                try { Lot5Migration.migrer(c, appliquer); } catch (IllegalStateException e) { refuse = true; }
                exiger(refuse && complet.equals(etatComplet(c)) && tables.equals(BoutiquesMigration.tables(c)), "Schéma lot 5 incohérent accepté ou données modifiées : " + scenario);
            }
            return;
        }
        Lot5Migration.migrer(c, false);
        exiger(complet.equals(etatComplet(c)) && tables.equals(BoutiquesMigration.tables(c)), "Le contrôle a modifié la base");
        var existants = Lot5Migration.sousComptesExistants(c); var correspondances = Lot5Migration.lignesCorrespondance(c);
        Lot5Migration.migrer(c, true);
        exiger(Lot5Migration.finalise(c) && avant.equals(Lot5Migration.photographie(c)) && schemas.equals(Lot5Migration.schemasAnciens(c)), "Les anciennes données, stocks ou montants ont changé");
        var apres = Lot5Migration.sousComptesExistants(c);
        for (var e : existants.entrySet()) exiger(e.getValue().equals(apres.get(e.getKey())), "Un sous-compte déjà présent a été réécrit : " + e.getKey());
        exiger(Lot5Migration.lignesCorrespondance(c).containsAll(correspondances), "Une ligne de correspondance a été réécrite");
        verifierSousComptes(c, scenario);
        verifierContraintes(c);
        var finalEtat = etatComplet(c);
        Lot5Migration.migrer(c, true); Lot5Migration.migrer(c, false);
        exiger(finalEtat.equals(etatComplet(c)), "Relance non idempotente");
        if (scenario.equals("relance")) verifierComptabiliteConservee(c);
        if (scenario.equals("reglage_absent")) { verifierComptabiliteConservee(c); exiger(BoutiquesMigration.tables(c).containsKey("reglage_comptable_boutique"), "La table des réglages n'a pas été créée"); }
        if (scenario.equals("nouveau_compte")) exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM sous_compte_boutique s JOIN compte_tresorerie_boutique t ON t.id=s.compte_tresorerie_id"
                + " WHERE t.nom='Banque de l''Est' AND s.parent_numero='521'") == 1, "Le compte créé après la migration n'a pas reçu son sous-compte");
        if (scenario.equals("sous_comptes_partiels")) exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM sous_compte_boutique WHERE numero='57150' AND intitule='Caisse nommée à la main'") == 1,
                "Le sous-compte posé à la main a été réécrit");
        if (scenario.equals("compte_archive")) exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique t WHERE t.actif=FALSE AND EXISTS (SELECT 1 FROM sous_compte_boutique s WHERE s.compte_tresorerie_id=t.id)")
                == BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique WHERE actif=FALSE"), "Un compte archivé n'a pas de sous-compte");
    }

    /** Chaque compte de trésorerie a un sous-compte sous le bon parent, jamais un numéro officiel, jamais deux fois le même numéro dans une boutique. */
    private static void verifierSousComptes(Connection c, String scenario) throws SQLException {
        long comptes = BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique");
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM sous_compte_boutique WHERE compte_tresorerie_id IS NOT NULL") == comptes, "Un compte de trésorerie n'a pas exactement un sous-compte");
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM sous_compte_boutique s JOIN compte_tresorerie_boutique t ON t.id=s.compte_tresorerie_id WHERE s.boutique_id<>t.boutique_id") == 0,
                "Un sous-compte appartient à une autre boutique que son compte de trésorerie");
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM sous_compte_boutique s JOIN compte_tresorerie_boutique t ON t.id=s.compte_tresorerie_id WHERE (t.type='ESPECES' AND s.parent_numero<>'571')"
                + " OR (t.type='ORANGE_MONEY' AND s.parent_numero<>'552') OR (t.type='BANQUE' AND s.parent_numero<>'521')") == 0, "Un sous-compte n'est pas sous le parent de son type");
        var officiels = org.example.stock.compta.PlanOfficiel.charger();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT numero FROM sous_compte_boutique")) {
            while (r.next()) exiger(!officiels.existe(r.getString(1)), "Un sous-compte reprend un numéro officiel : " + r.getString(1));
        }
        if (scenario.equals("numeros_officiels_evites")) {
            // 5711 et 5712 existent au plan officiel : la première caisse prend 5713.
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM sous_compte_boutique WHERE numero IN ('5711','5712')") == 0, "Un sous-compte a pris un numéro de 5711 ou 5712");
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM sous_compte_boutique WHERE numero='5713'") >= (comptes == 0 ? 0 : 1), "La première caisse devait recevoir 5713");
        }
    }

    /** Les contraintes sont réelles : un doublon de numéro, un second sous-compte pour un même compte et une double clé de correspondance sont refusés. */
    private static void verifierContraintes(Connection c) throws SQLException {
        long boutique = BoutiquesMigration.compter(c, "SELECT COALESCE(MIN(id),0) FROM boutique");
        if (boutique == 0) return;
        sql(c, "INSERT INTO sous_compte_boutique(boutique_id,numero,parent_numero,intitule,date_creation) VALUES(" + boutique + ",'6589900','658','Test','2026-01-01 00:00:00.000000')");
        boolean refuse = false;
        try { sql(c, "INSERT INTO sous_compte_boutique(boutique_id,numero,parent_numero,intitule,date_creation) VALUES(" + boutique + ",'6589900','658','Doublon','2026-01-01 00:00:00.000000')"); }
        catch (SQLException e) { refuse = e.getSQLState() != null && e.getSQLState().startsWith("23"); }
        exiger(refuse, "Deux sous-comptes de même numéro ont été acceptés");
        sql(c, "DELETE FROM sous_compte_boutique WHERE numero='6589900'");
        sql(c, "INSERT INTO correspondance_compta(boutique_id,cle,compte_numero) VALUES(" + boutique + ",'CLE_DE_TEST','701')");
        refuse = false;
        try { sql(c, "INSERT INTO correspondance_compta(boutique_id,cle,compte_numero) VALUES(" + boutique + ",'CLE_DE_TEST','702')"); }
        catch (SQLException e) { refuse = e.getSQLState() != null && e.getSQLState().startsWith("23"); }
        exiger(refuse, "Deux lignes de correspondance de même clé ont été acceptées");
        sql(c, "DELETE FROM correspondance_compta WHERE cle='CLE_DE_TEST'");
        sql(c, "INSERT INTO reglage_comptable_boutique(boutique_id,systeme) VALUES(" + boutique + ",'MINIMAL')");
        refuse = false;
        try { sql(c, "INSERT INTO reglage_comptable_boutique(boutique_id,systeme) VALUES(" + boutique + ",'NORMAL')"); }
        catch (SQLException e) { refuse = e.getSQLState() != null && e.getSQLState().startsWith("23"); }
        exiger(refuse, "Deux réglages comptables pour une même boutique ont été acceptés");
        sql(c, "DELETE FROM reglage_comptable_boutique WHERE boutique_id=" + boutique);
        long compte = BoutiquesMigration.compter(c, "SELECT COALESCE(MIN(compte_tresorerie_id),0) FROM sous_compte_boutique");
        if (compte > 0) {
            refuse = false;
            try { sql(c, "INSERT INTO sous_compte_boutique(boutique_id,numero,parent_numero,intitule,compte_tresorerie_id,date_creation) VALUES(" + boutique + ",'6589901','658','Second',"
                    + compte + ",'2026-01-01 00:00:00.000000')"); }
            catch (SQLException e) { refuse = e.getSQLState() != null && e.getSQLState().startsWith("23"); }
            exiger(refuse, "Deux sous-comptes pour un même compte de trésorerie ont été acceptés");
        }
    }

    private static void verifierComptabiliteConservee(Connection c) throws SQLException {
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM correspondance_compta WHERE cle='VENTES_MARCHANDISES' AND compte_numero='702' AND mots_cles IS NULL") == 1, "Correspondance modifiée");
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM correspondance_compta WHERE cle='DEPENSE_LOYER' AND compte_numero='623' AND mots_cles='loyer, bail'") == 1, "Mots-clés modifiés");
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM sous_compte_boutique WHERE intitule='Caisse principale (renommée)'") == 1, "Sous-compte renommé modifié");
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM sous_compte_boutique WHERE numero='6589001' AND compte_tresorerie_id IS NULL AND intitule='Pertes diverses'") == 1, "Sous-compte manuel modifié");
    }

    static List<Object> etatComplet(Connection c) throws SQLException { return Lot3MigrationScenarios.etatComplet(c); }

    static void sql(Connection c, String requete) throws SQLException { c.setReadOnly(false); Lot5Migration.sql(c, requete); }
    static boolean maria(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static String modifier(Connection c) throws SQLException { return maria(c) ? "MODIFY COLUMN" : "ALTER COLUMN"; }
    static void exiger(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
