package org.example.stock.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Jeux factices partagés par les contrôles H2 et MariaDB ; aucune donnée de la boutique réelle. */
final class TarificationMigrationScenarios {
    static final List<String> VALIDES = List.of("ancien", "vide", "statut_seul", "achat_seul", "stock_seul", "complet", "relance");
    static final Map<String, String> INVALIDES = invalides();
    private TarificationMigrationScenarios() {}

    static void schema(Connection c, boolean vide) throws Exception {
        boolean maria = c.getMetaData().getDatabaseProductName().equals("MariaDB");
        String contenu = Files.readString(Path.of("docs/database-schema.sql"))
                .replaceAll("(?m)^--[^\\r\\n]*", "")
                .replaceAll("(?m)^\\s*prix_definis BOOLEAN NOT NULL DEFAULT TRUE,\\s*$", "")
                .replaceAll("(?m)^\\s*prix_vente_unitaire DECIMAL\\(17,2\\) NULL,\\s*$", "")
                .replaceAll("(?m)^\\s*prix_vente DECIMAL\\(17,2\\) NULL,\\s*$", "");
        if (!maria) contenu = contenu.replace(" ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci", "");
        for (String instruction : contenu.split(";")) {
            instruction = instruction.strip();
            if (!instruction.isEmpty() && !instruction.startsWith("CREATE DATABASE") && !instruction.startsWith("USE ")) sql(c, instruction);
        }
        if (!vide) remplir(c);
    }

    private static void remplir(Connection c) throws SQLException {
        sql(c, "INSERT INTO boutique(id,nom,date_creation,essai_debut,essai_fin,abonnement_debut,abonnement_fin,actif,acces_offert,adresse,telephone,email) VALUES"
                + "(1,'Boutique historique','2025-01-01 00:00:00.123456','2025-01-01 00:00:00.123456','2025-01-31 00:00:00.123456','2025-01-31 00:00:00.123456','2025-02-28 00:00:00.123456',TRUE,FALSE,'Conakry','+224 600 00 00 00','contact@example.test'),"
                + "(2,'Boutique offerte suspendue','2025-01-01 00:00:00.123456',NULL,NULL,NULL,NULL,FALSE,TRUE,NULL,NULL,NULL)");
        sql(c, "INSERT INTO utilisateur(id,boutique_id,nom,email,mot_de_passe,date_inscription,role,administrateur_plateforme,actif) VALUES"
                + "(1,1,'Plateforme','admin@example.test','empreinte-plateforme','2025-01-01','ADMIN',TRUE,TRUE),"
                + "(2,2,'Gérant','gerant@example.test','empreinte-gerant','2025-01-01','ADMIN',FALSE,TRUE),"
                + "(3,1,'Caissier désactivé','caissier@example.test','empreinte-caissier','2025-01-01','CAISSIER',FALSE,FALSE)");
        sql(c, "INSERT INTO categorie(id,boutique_id,nom) VALUES(1,1,'Épicerie'),(2,2,'Épicerie')");
        sql(c, "INSERT INTO client(id,boutique_id,nom) VALUES(1,1,'Client historique'),(2,2,'Autre client')");
        sql(c, "INSERT INTO fournisseur(id,boutique_id,nom) VALUES(1,1,'Fournisseur historique'),(2,2,'Autre fournisseur')");
        sql(c, "INSERT INTO produit(id,boutique_id,version,nom,reference,prix_achat,prix_vente,quantite,categorie_id,fournisseur_id) VALUES"
                + "(1,1,3,'Produit limite','MAX',999999999999999.99,999999999999999.99,6,1,1),"
                + "(2,1,1,'Produit gratuit historique','ZERO',0,0,0,1,1),"
                + "(3,1,5,'Inventaire historique','INV',100.25,150.75,4,1,1),"
                + "(4,2,2,'Autre boutique','AUTRE',20,25,0,2,2)");
        sql(c, "INSERT INTO achat(id,boutique_id,date_achat,montant_total,montant_verse,fournisseur_id) VALUES(1,1,'2025-01-02 00:00:00.123456',100.50,70.25,1)");
        sql(c, "INSERT INTO detail_achat(id,achat_id,produit_id,quantite,prix_achat_unitaire) VALUES(1,1,1,2,50.25),(2,1,2,1,0)");
        sql(c, "INSERT INTO vente(id,boutique_id,date_vente,montant_total,montant_verse,client_id) VALUES(1,1,'2025-01-03 00:00:00.123456',150.75,100.50,1)");
        sql(c, "INSERT INTO detail_vente(id,vente_id,produit_id,quantite,prix_unitaire) VALUES(1,1,3,1,150.75)");
        sql(c, "INSERT INTO mouvement_caisse(id,boutique_id,date_mouvement,montant,type,motif,source,utilisateur_id,achat_id,vente_id) VALUES"
                + "(1,1,'2025-01-01 00:00:00.123456',500.75,'OUVERTURE','Argent initial','SOLDE_INITIAL',1,NULL,NULL),"
                + "(2,1,'2025-01-02 00:00:00.123456',-70.25,'DECAISSEMENT','Achat initial','ACHAT',1,1,NULL),"
                + "(3,1,'2025-01-03 00:00:00.123456',100.50,'ENCAISSEMENT','Vente initiale','VENTE',1,NULL,1)");
        sql(c, "INSERT INTO operation_creation(jeton,boutique_id,utilisateur_id,type,date_creation,empreinte,achat_id,vente_id) VALUES"
                + "('abcdef01-1234-4567-89ab-000000000001',1,1,'ACHAT','2025-01-01 00:00:00.123456',REPEAT('c',64),1,NULL),"
                + "('abcdef01-1234-4567-89ab-000000000002',1,1,'VENTE','2025-01-01 00:00:00.123456',REPEAT('d',64),NULL,1)");
        sql(c, "INSERT INTO reinitialisation_mot_de_passe VALUES(3,REPEAT('a',64),REPEAT('b',64),'2025-01-01 00:00:00.123456','2025-01-01 00:30:00.123456')");
        sql(c, "INSERT INTO inscription_boutique VALUES('inscription@example.test',REPEAT('e',64),'2025-01-01 00:00:00.123456','2025-01-01 00:30:00.123456')");
        sql(c, "INSERT INTO parametres_plateforme VALUES(1,45)");
        sql(c, "INSERT INTO historique_abonnement(id,boutique_id,acteur_id,acteur_nom,action,cree_le,periode_debut,periode_fin,ancienne_fin,motif,jeton_action,quantite) VALUES"
                + "(1,1,1,'Plateforme','ACTIVATION_ABONNEMENT','2025-01-01 00:00:00.123456','2025-01-31 00:00:00.123456','2025-02-28 00:00:00.123456','2025-01-31 00:00:00.123456','Activation manuelle','abcdef01-1234-4567-89ab-000000000003',1),"
                + "(2,2,1,'Plateforme','ACCES_OFFERT','2025-01-01 00:00:00.123456',NULL,NULL,NULL,'Accès offert','abcdef01-1234-4567-89ab-000000000004',NULL)");
        sql(c, "INSERT INTO mouvement_stock(id,boutique_id,produit_id,acteur_id,acteur_nom,type,cree_le,quantite_avant,quantite_apres,cout_unitaire,motif) VALUES"
                + "(1,1,3,1,'Gérant historique','INITIAL','2025-01-01 00:00:00.123456',0,5,100.25,'Stock déjà détenu'),"
                + "(2,1,3,1,'Gérant historique','AJUSTEMENT','2025-01-03 00:00:00.123456',5,4,NULL,'Comptage')");
    }

    static void preparer(Connection c, String scenario) throws SQLException {
        if (Set.of("ancien", "vide", "relance").contains(scenario)) return;
        if (Set.of("statut_seul", "achat_seul", "stock_seul").contains(scenario)) {
            var champ = TarificationMigration.CHAMPS.get(scenario.equals("statut_seul") ? 0 : scenario.equals("achat_seul") ? 1 : 2);
            sql(c, "ALTER TABLE " + champ.table() + " ADD COLUMN " + champ.nom() + " " + champ.declaration());
            if (scenario.equals("statut_seul")) nouveauSansPrix(c);
            if (scenario.equals("achat_seul")) sql(c, "UPDATE detail_achat SET prix_vente_unitaire=72.50 WHERE id=1");
            if (scenario.equals("stock_seul")) sql(c, "UPDATE mouvement_stock SET prix_vente=150.75 WHERE id=1");
            return;
        }
        for (var champ : TarificationMigration.CHAMPS) sql(c, "ALTER TABLE " + champ.table() + " ADD COLUMN " + champ.nom() + " " + champ.declaration());
        sql(c, "UPDATE detail_achat SET prix_vente_unitaire=72.50 WHERE id=1");
        sql(c, "UPDATE mouvement_stock SET prix_vente=150.75 WHERE id=1");
        if (scenario.equals("complet")) { nouveauSansPrix(c); return; }
        String modification = INVALIDES.get(scenario);
        if (modification == null) throw new IllegalStateException("Scénario de tarifs inconnu.");
        if (scenario.endsWith("obligatoire")) {
            var champ = TarificationMigration.CHAMPS.get(scenario.startsWith("achat_") ? 1 : 2);
            sql(c, "UPDATE " + champ.table() + " SET " + champ.nom() + "=0 WHERE " + champ.nom() + " IS NULL");
        }
        // MariaDB exige un autre index pour conserver la FK pendant ce scénario de refus.
        if (scenario.equals("inventaire_incoherent")) sql(c, "CREATE INDEX idx_tarification_boutique_test ON mouvement_stock(boutique_id)");
        sql(c, modification);
    }
    private static void nouveauSansPrix(Connection c) throws SQLException {
        sql(c, "INSERT INTO produit(id,boutique_id,version,nom,reference,prix_achat,prix_vente,prix_definis,quantite,categorie_id) VALUES(5,1,0,'Fiche sans tarifs','NOUVEAU',0,0,FALSE,0,1)");
    }

    static void verifier(Connection c, String scenario) throws SQLException {
        var avant = TarificationMigration.photographie(c);
        var schemas = TarificationMigration.schemasAnciens(c);
        var tarifs = TarificationMigration.tarifs(c);
        var tables = BoutiquesMigration.tables(c);
        if (INVALIDES.containsKey(scenario)) {
            for (boolean appliquer : List.of(false, true)) {
                boolean refuse = false;
                try { TarificationMigration.migrer(c, appliquer); } catch (IllegalStateException e) { refuse = true; }
                exiger(refuse, "Schéma ou données incohérents acceptés : " + scenario);
                exiger(avant.equals(TarificationMigration.photographie(c)) && schemas.equals(TarificationMigration.schemasAnciens(c))
                        && tarifs.equals(TarificationMigration.tarifs(c)) && tables.equals(BoutiquesMigration.tables(c)), "Refus avec modification : " + scenario);
            }
            return;
        }
        TarificationMigration.migrer(c, false);
        exiger(tarifs.equals(TarificationMigration.tarifs(c)) && tables.equals(BoutiquesMigration.tables(c)), "Le contrôle a modifié le schéma ou les tarifs");
        TarificationMigration.migrer(c, true);
        var apres = TarificationMigration.tarifs(c);
        exiger(apres.size() == 3, "Colonnes tarifaires incomplètes");
        for (var entree : tarifs.entrySet()) exiger(entree.getValue().equals(apres.get(entree.getKey())), "Tarifs déjà présents modifiés");
        if (!tarifs.containsKey("produit.prix_definis")) exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM produit WHERE prix_definis<>TRUE") == 0, "Un produit existant est devenu sans tarifs");
        if (!tarifs.containsKey("detail_achat.prix_vente_unitaire")) exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM detail_achat WHERE prix_vente_unitaire IS NOT NULL") == 0, "Prix de facture historique inventé");
        if (!tarifs.containsKey("mouvement_stock.prix_vente")) exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM mouvement_stock WHERE prix_vente IS NOT NULL") == 0, "Prix d'inventaire historique inventé");
        TarificationMigration.migrer(c, true); TarificationMigration.migrer(c, false);
        InventaireMigration.migrer(c, false); InventaireMigration.migrer(c, true); AbonnementMigration.migrer(c, false);
        exiger(avant.equals(TarificationMigration.photographie(c)) && schemas.equals(TarificationMigration.schemasAnciens(c))
                && apres.equals(TarificationMigration.tarifs(c)) && tables.equals(BoutiquesMigration.tables(c)), "Données changées lors d'une relance");
    }

    private static Map<String, String> invalides() {
        var resultat = new LinkedHashMap<String, String>();
        resultat.put("statut_null", "ALTER TABLE produit MODIFY COLUMN prix_definis BOOLEAN NULL DEFAULT TRUE");
        resultat.put("statut_defaut", "ALTER TABLE produit MODIFY COLUMN prix_definis BOOLEAN NOT NULL DEFAULT FALSE");
        resultat.put("statut_entier", "ALTER TABLE produit MODIFY COLUMN prix_definis INTEGER NOT NULL DEFAULT 1");
        resultat.put("statut_sans_defaut", "ALTER TABLE produit ALTER COLUMN prix_definis DROP DEFAULT");
        resultat.put("statut_valeur", "ALTER TABLE produit MODIFY COLUMN prix_definis TINYINT NOT NULL DEFAULT 1");
        for (var champ : TarificationMigration.CHAMPS.subList(1, 3)) {
            String prefixe = champ.table().equals("detail_achat") ? "achat_" : "stock_";
            String modifier = "ALTER TABLE " + champ.table() + " MODIFY COLUMN " + champ.nom() + " ";
            resultat.put(prefixe + "precision", modifier + "DECIMAL(16,2) NULL");
            resultat.put(prefixe + "decimales", modifier + "DECIMAL(17,3) NULL");
            resultat.put(prefixe + "entier", modifier + "BIGINT NULL");
            resultat.put(prefixe + "obligatoire", modifier + "DECIMAL(17,2) NOT NULL DEFAULT 0");
            resultat.put(prefixe + "defaut", modifier + "DECIMAL(17,2) NULL DEFAULT 0");
            resultat.put(prefixe + "negatif", "UPDATE " + champ.table() + " SET " + champ.nom() + "=-0.01 WHERE id=1");
        }
        resultat.put("stock_ajustement", "UPDATE mouvement_stock SET prix_vente=150 WHERE type='AJUSTEMENT'");
        resultat.put("produit_achat_negatif", "UPDATE produit SET prix_achat=-0.01 WHERE id=4");
        resultat.put("produit_vente_negatif", "UPDATE produit SET prix_vente=-0.01 WHERE id=4");
        resultat.put("sans_prix_achat", "UPDATE produit SET prix_definis=FALSE,prix_vente=0 WHERE id=4");
        resultat.put("sans_prix_vente", "UPDATE produit SET prix_definis=FALSE,prix_achat=0 WHERE id=4");
        resultat.put("sans_prix_stock", "UPDATE produit SET prix_definis=FALSE,prix_achat=0,prix_vente=0,quantite=1 WHERE id=4");
        resultat.put("sans_prix_stock_null", "UPDATE produit SET prix_definis=FALSE,prix_achat=0,prix_vente=0,quantite=NULL WHERE id=4");
        resultat.put("sans_inventaire", "DROP TABLE mouvement_stock");
        resultat.put("inventaire_incoherent", "DROP INDEX idx_mouvement_stock_produit_date ON mouvement_stock");
        resultat.put("abonnement_incoherent", "UPDATE boutique SET abonnement_fin=abonnement_debut WHERE id=1");
        return Collections.unmodifiableMap(resultat);
    }
    static String adapterDdlH2(String sql) { return sql.replace("MODIFY COLUMN", "ALTER COLUMN").replace(" ON mouvement_stock", ""); }
    static void sql(Connection c, String sql) throws SQLException { c.setReadOnly(false); BoutiquesMigration.executer(c, sql); }
    static void exiger(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
