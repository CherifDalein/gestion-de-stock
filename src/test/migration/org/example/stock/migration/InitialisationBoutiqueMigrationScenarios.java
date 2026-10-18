package org.example.stock.migration;

import java.sql.*;
import java.util.*;

/** Scénarios factices identiques pour H2 et MariaDB, sans toucher à une boutique réelle. */
final class InitialisationBoutiqueMigrationScenarios {
    static final List<String> VALIDES = List.of("ancien", "vide", "colonne_seule", "fermeture_partielle", "deja_fermee", "relance");
    static final Map<String, String> INVALIDES = invalides();
    static final Set<Long> UTILISEES = Set.of(1L, 4L, 5L, 6L, 7L, 10L, 11L, 12L);
    private InitialisationBoutiqueMigrationScenarios() {}

    static void schema(Connection c, boolean vide) throws Exception {
        TarificationMigrationScenarios.schema(c, vide);
        if (InscriptionEssaiMigration.colonnes(c, "boutique").containsKey(InitialisationBoutiqueMigration.CHAMP))
            sql(c, "ALTER TABLE boutique DROP COLUMN " + InitialisationBoutiqueMigration.CHAMP);
        TarificationMigration.migrer(c, true);
        if (vide) return;
        for (int id = 3; id <= 13; id++) {
            sql(c, "INSERT INTO boutique(id,nom,date_creation) VALUES(" + id + ",'Boutique factice " + id + "','2025-01-01 00:00:00.123456')");
            sql(c, "INSERT INTO categorie(id,boutique_id,nom) VALUES(" + id + "," + id + ",'Catégorie factice')");
            sql(c, "INSERT INTO produit(id,boutique_id,version,nom,reference,prix_achat,prix_vente,quantite,categorie_id) VALUES("
                    + (id + 10) + "," + id + ",0,'Produit factice','TEST-" + id + "',0,0," + (Set.of(6, 7, 13).contains(id) ? 3 : 0) + "," + id + ")");
        }
        // Boutiques 2 et 13 : plusieurs stocks initiaux et caisse initiale, sans activité commerciale.
        sql(c, "UPDATE produit SET quantite=3 WHERE id=4");
        sql(c, "INSERT INTO produit(id,boutique_id,version,nom,reference,prix_achat,prix_vente,quantite,categorie_id) VALUES(30,2,0,'Second produit initial','INIT-2',0,0,5,2)");
        sql(c, "INSERT INTO mouvement_stock(id,boutique_id,produit_id,acteur_id,acteur_nom,type,cree_le,quantite_avant,quantite_apres,cout_unitaire,prix_vente,motif) VALUES"
                + "(3,2,4,2,'Gérant factice','INITIAL','2025-01-01 00:00:00.123456',0,3,20,25,'Déjà détenu'),"
                + "(4,2,30,2,'Gérant factice','INITIAL','2025-01-01 00:00:00.123456',0,5,0,0,'Déjà détenu'),"
                + "(5,13,23,1,'Gérant factice','INITIAL','2025-01-01 00:00:00.123456',0,3,0,0,'Déjà détenu'),"
                + "(6,7,17,1,'Gérant factice','AJUSTEMENT','2025-01-01 00:00:00.123456',0,3,NULL,NULL,'Ancien comptage')");
        // Les achats et ventes à crédit ne produisent aucun mouvement de caisse.
        sql(c, "INSERT INTO achat(id,boutique_id,date_achat,montant_total,montant_verse) VALUES(2,4,'2025-01-02 00:00:00.123456',10,0)");
        sql(c, "INSERT INTO vente(id,boutique_id,date_vente,montant_total,montant_verse) VALUES(2,5,'2025-01-02 00:00:00.123456',10,0)");
        sql(c, "INSERT INTO mouvement_caisse(id,boutique_id,date_mouvement,montant,type,motif,source) VALUES"
                + "(4,2,'2025-01-01 00:00:00.123456',0,'OUVERTURE','Argent initial','SOLDE_INITIAL'),"
                + "(5,3,'2025-01-01 00:00:00.123456',0,'OUVERTURE','Argent initial','SOLDE_INITIAL'),"
                + "(6,10,'2025-01-01 00:00:00.123456',0,'ENTREE','Source inconnue',NULL),"
                + "(7,11,'2025-01-01 00:00:00.123456',0,'ENTREE','Source initiale détournée','SOLDE_INITIAL'),"
                + "(8,12,'2025-01-01 00:00:00.123456',0,NULL,'Type inconnu','SOLDE_INITIAL')");
    }

    static void preparer(Connection c, String scenario) throws SQLException {
        if (Set.of("ancien", "vide", "relance").contains(scenario)) return;
        sql(c, "ALTER TABLE boutique ADD COLUMN " + InitialisationBoutiqueMigration.CHAMP + " BOOLEAN NOT NULL DEFAULT FALSE");
        if (scenario.equals("fermeture_partielle")) sql(c, "UPDATE boutique SET initialisation_terminee=TRUE WHERE id IN(1,4)");
        if (scenario.equals("deja_fermee")) sql(c, "UPDATE boutique SET initialisation_terminee=TRUE WHERE id IN(2,8,9)");
        String modification = INVALIDES.get(scenario);
        if (modification != null) sql(c, modification);
    }

    static void verifier(Connection c, String scenario) throws SQLException {
        var avant = InitialisationBoutiqueMigration.photographie(c);
        var schemas = InitialisationBoutiqueMigration.schemasAnciens(c);
        var etats = InitialisationBoutiqueMigration.etats(c);
        var tables = BoutiquesMigration.tables(c);
        if (INVALIDES.containsKey(scenario)) {
            for (boolean appliquer : List.of(false, true)) {
                boolean refuse = false;
                try { InitialisationBoutiqueMigration.migrer(c, appliquer); } catch (IllegalStateException e) { refuse = true; }
                exiger(refuse, "Statut de démarrage incohérent accepté : " + scenario);
                exiger(avant.equals(InitialisationBoutiqueMigration.photographie(c)) && schemas.equals(InitialisationBoutiqueMigration.schemasAnciens(c))
                        && etats.equals(InitialisationBoutiqueMigration.etats(c)) && tables.equals(BoutiquesMigration.tables(c)), "Refus avec modification : " + scenario);
            }
            return;
        }
        exiger(InitialisationBoutiqueMigration.boutiquesUtilisees(c).equals(scenario.equals("vide") ? Set.of() : UTILISEES), "Classification historique incorrecte");
        InitialisationBoutiqueMigration.migrer(c, false);
        exiger(etats.equals(InitialisationBoutiqueMigration.etats(c)), "Le contrôle a modifié les statuts");
        InitialisationBoutiqueMigration.migrer(c, true);
        var apres = InitialisationBoutiqueMigration.etats(c);
        for (var entree : apres.entrySet()) exiger(entree.getValue() == (UTILISEES.contains(entree.getKey()) || Boolean.TRUE.equals(etats.get(entree.getKey()))), "Boutique ouverte ou fermée à tort");
        InitialisationBoutiqueMigration.migrer(c, true); InitialisationBoutiqueMigration.migrer(c, false);
        TarificationMigration.migrer(c, false); TarificationMigration.migrer(c, true); InventaireMigration.migrer(c, false); AbonnementMigration.migrer(c, false);
        exiger(avant.equals(InitialisationBoutiqueMigration.photographie(c)) && schemas.equals(InitialisationBoutiqueMigration.schemasAnciens(c))
                && apres.equals(InitialisationBoutiqueMigration.etats(c)) && tables.equals(BoutiquesMigration.tables(c)), "Anciennes données modifiées ou boutique rouverte");
        if (scenario.equals("relance")) {
            sql(c, "INSERT INTO boutique(id,nom,date_creation) VALUES(99,'Nouvelle après migration','2025-01-04 00:00:00.123456')");
            InitialisationBoutiqueMigration.migrer(c, true);
            exiger(!InitialisationBoutiqueMigration.etats(c).get(99L), "Une nouvelle boutique a été fermée par la relance");
        }
    }

    private static Map<String, String> invalides() {
        var resultat = new LinkedHashMap<String, String>();
        resultat.put("statut_null", "ALTER TABLE boutique MODIFY COLUMN initialisation_terminee BOOLEAN NULL DEFAULT FALSE");
        resultat.put("statut_defaut", "ALTER TABLE boutique MODIFY COLUMN initialisation_terminee BOOLEAN NOT NULL DEFAULT TRUE");
        resultat.put("statut_entier", "ALTER TABLE boutique MODIFY COLUMN initialisation_terminee INTEGER NOT NULL DEFAULT 0");
        resultat.put("statut_sans_defaut", "ALTER TABLE boutique ALTER COLUMN initialisation_terminee DROP DEFAULT");
        resultat.put("statut_valeur", "UPDATE boutique SET initialisation_terminee=2 WHERE id=2");
        resultat.put("tarifs_absents", "ALTER TABLE produit DROP COLUMN prix_definis");
        resultat.put("tarifs_incoherents", "UPDATE produit SET prix_achat=-0.01 WHERE id=4");
        return Collections.unmodifiableMap(resultat);
    }
    static void sql(Connection c, String sql) throws SQLException { c.setReadOnly(false); BoutiquesMigration.executer(c, sql); }
    static void exiger(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
