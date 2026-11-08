package org.example.stock.migration;

import java.sql.*;
import java.util.*;

/** Fixtures fictives identiques sur H2 et MariaDB ; deux boutiques, des règlements nominatifs ou non, des documents sans règlement. */
final class Lot2MigrationScenarios {
    static final List<String> VALIDES = List.of("ancien", "vide", "schema_neuf", "tables_partielles", "colonnes_partielles", "reprise_initialisation",
            "reprise_finalisation", "relance", "nouvelle_boutique");
    static final List<String> INVALIDES = List.of("devis_statut_longueur", "devis_numero_facultatif", "devis_validite_type", "devis_montant_echelle", "devis_version_defaut",
            "devis_sans_unique_numero", "devis_sans_unique_vente", "devis_sans_fk_vente", "devis_fk_mauvaise_cible", "devis_statut_inconnu", "devis_colonne_en_trop",
            "detail_devis_sans_fk", "retour_type_inconnu", "retour_compte_inconnu", "retour_compte_longueur", "retour_rembourse_defaut", "retour_fk_cascade",
            "retour_montant_negatif", "detail_retour_sans_fk", "vendeur_type", "vendeur_sans_fk", "vendeur_mauvaise_cible", "achat_utilisateur_mauvaise_cible",
            "annulation_sans_unique", "annulation_unique_composite", "annulation_sans_fk", "annulation_mauvaise_cible", "retour_lien_sans_fk", "prochain_defaut",
            "prochain_facultatif_apres_fin", "prochain_negatif", "fin_sans_colonne", "fin_sans_table");
    /** Identifiants réservés aux lignes créées après la migration par le scénario de relance. */
    private static final long RELANCE_MIN = 80, RELANCE_MAX = 89;
    private static final String DATE = "'2026-02-01 08:00:00.000000'";
    private Lot2MigrationScenarios() {}

    static void schema(Connection c, boolean vide) throws Exception {
        Lot1MigrationScenarios.schema(c, vide);
        Lot1Migration.migrer(c, true);
        if (!vide) remplir(c);
    }

    /** Règlements nominatifs ou non, rattachés à des ventes et à des achats des deux boutiques, insérés volontairement dans le désordre. */
    private static void remplir(Connection c) throws SQLException {
        sql(c, "INSERT INTO mouvement_caisse(id,boutique_id,date_mouvement,montant,type,motif,source,utilisateur_id,achat_id,vente_id) VALUES"
                + "(10,2,'2025-02-10 23:30:00.000000',5.00,'ENCAISSEMENT','Acompte anonyme','VENTE',NULL,NULL,3),"
                + "(11,2,'2025-02-10 23:31:00.000000',10.00,'ENCAISSEMENT','Premier règlement nominatif','VENTE',2,NULL,3),"
                + "(12,2,'2025-02-10 23:32:00.000000',10.00,'ENCAISSEMENT','Second règlement nominatif','VENTE',2,NULL,3),"
                + "(21,1,'2025-01-31 12:05:00.000000',12.00,'ENCAISSEMENT','Règlement saisi en second','VENTE',3,NULL,5),"
                + "(20,1,'2025-01-31 12:00:00.000000',-1.00,'ENCAISSEMENT','Règlement de plus petit identifiant','VENTE',1,NULL,5),"
                + "(22,2,'2025-03-01 10:00:00.000000',1.00,'ENCAISSEMENT','Règlement anonyme','VENTE',NULL,NULL,1234567),"
                + "(30,2,'2025-02-15 09:00:00.000000',-10.00,'DECAISSEMENT','Versement anonyme','ACHAT',NULL,3,NULL),"
                + "(31,2,'2025-02-15 09:01:00.000000',-10.00,'DECAISSEMENT','Versement nominatif','ACHAT',2,3,NULL)");
    }

    static void preparer(Connection c, String scenario) throws Exception {
        if (Set.of("ancien", "vide", "schema_neuf", "nouvelle_boutique").contains(scenario)) return;
        switch (scenario) {
            case "tables_partielles" -> {
                c.setReadOnly(false);
                Lot2Migration.creerTable(c, Lot2Migration.TABLES.get(0));
                Lot2Migration.creerTable(c, Lot2Migration.TABLES.get(1));
                return;
            }
            case "colonnes_partielles" -> {
                sql(c, "ALTER TABLE vente ADD COLUMN vendeur_id BIGINT NULL");
                sql(c, "ALTER TABLE boutique ADD COLUMN prochain_numero_devis BIGINT DEFAULT 1 NOT NULL");
                sql(c, "ALTER TABLE boutique ADD COLUMN prochain_numero_retour_achat BIGINT NULL");
                sql(c, "ALTER TABLE mouvement_caisse ADD COLUMN annulation_de_id BIGINT NULL");
                return;
            }
            case "reprise_initialisation" -> { c.setReadOnly(false); Lot2Migration.creerTables(c); Lot2Migration.ajouterColonnes(c); return; }
            case "reprise_finalisation" -> {
                c.setReadOnly(false); Lot2Migration.creerTables(c); Lot2Migration.ajouterColonnes(c); Lot2Migration.initialiser(c);
                Lot2Migration.ajouterUniques(c); Lot2Migration.ajouterCles(c);
                return; // Le compteur des retours d'achat reste facultatif : il reste à finaliser.
            }
            default -> {}
        }
        Lot2Migration.migrer(c, true);
        switch (scenario) {
            case "relance" -> activiteApresMigration(c);
            case "devis_statut_longueur" -> sql(c, "ALTER TABLE devis " + alter(c) + " statut VARCHAR(15) NOT NULL");
            case "devis_numero_facultatif" -> sql(c, "ALTER TABLE devis " + alter(c) + " numero VARCHAR(30) NULL");
            case "devis_validite_type" -> sql(c, "ALTER TABLE devis " + alter(c) + " date_validite " + (maria(c) ? "DATETIME" : "TIMESTAMP") + " NOT NULL");
            case "devis_montant_echelle" -> sql(c, "ALTER TABLE devis " + alter(c) + " montant_total DECIMAL(18,3) NOT NULL");
            case "devis_version_defaut" -> sql(c, "ALTER TABLE devis ALTER COLUMN version SET DEFAULT 1");
            case "devis_sans_unique_numero" -> retirerUnique(c, "devis", "uk_devis_boutique_numero", "boutique_id");
            case "devis_sans_unique_vente" -> retirerUniqueSimple(c, "devis", "uk_devis_vente", "fk_devis_vente", "vente_id", "vente");
            case "devis_sans_fk_vente" -> supprimerCle(c, "devis", "fk_devis_vente");
            case "devis_fk_mauvaise_cible" -> {
                supprimerCle(c, "devis", "fk_devis_client");
                sql(c, "ALTER TABLE devis ADD CONSTRAINT fk_devis_client FOREIGN KEY(client_id) REFERENCES fournisseur(id)");
            }
            case "devis_statut_inconnu" -> sql(c, "INSERT INTO devis(id,boutique_id,numero,date_devis,date_validite,montant_total,statut) VALUES(81,1,'DV-X',"
                    + DATE + ",'2026-03-01',1.00,'AUTRE')");
            case "devis_colonne_en_trop" -> sql(c, "ALTER TABLE devis ADD COLUMN supplement VARCHAR(10) NULL");
            case "detail_devis_sans_fk" -> supprimerCle(c, "detail_devis", "fk_detail_devis_devis");
            case "retour_type_inconnu" -> sql(c, "INSERT INTO retour(id,boutique_id,numero,type,date_retour,montant_total) VALUES(81,1,'RX-1','AUTRE'," + DATE + ",1.00)");
            case "retour_compte_inconnu" -> sql(c, "INSERT INTO retour(id,boutique_id,numero,type,date_retour,montant_total,compte_tresorerie) VALUES(81,1,'RX-1','VENTE',"
                    + DATE + ",1.00,'AUTRE')");
            case "retour_compte_longueur" -> sql(c, "ALTER TABLE retour " + alter(c) + " compte_tresorerie VARCHAR(32) NULL");
            case "retour_rembourse_defaut" -> sql(c, "ALTER TABLE retour ALTER COLUMN montant_rembourse SET DEFAULT 1");
            case "retour_fk_cascade" -> {
                supprimerCle(c, "retour", "fk_retour_vente");
                sql(c, "ALTER TABLE retour ADD CONSTRAINT fk_retour_vente FOREIGN KEY(vente_id) REFERENCES vente(id) ON DELETE CASCADE");
            }
            case "retour_montant_negatif" -> sql(c, "INSERT INTO retour(id,boutique_id,numero,type,date_retour,montant_total,montant_avoir) VALUES(81,1,'RX-1','VENTE',"
                    + DATE + ",1.00,-1.00)");
            case "detail_retour_sans_fk" -> supprimerCle(c, "detail_retour", "fk_detail_retour_detail_vente");
            case "vendeur_type" -> { supprimerCle(c, "vente", "fk_vente_vendeur"); sql(c, "ALTER TABLE vente " + alter(c) + " vendeur_id INT NULL"); }
            case "vendeur_sans_fk" -> supprimerCle(c, "vente", "fk_vente_vendeur");
            case "vendeur_mauvaise_cible" -> {
                supprimerCle(c, "vente", "fk_vente_vendeur");
                sql(c, "UPDATE vente SET vendeur_id=NULL");
                sql(c, "ALTER TABLE vente ADD CONSTRAINT fk_vente_vendeur FOREIGN KEY(vendeur_id) REFERENCES client(id)");
            }
            case "achat_utilisateur_mauvaise_cible" -> {
                supprimerCle(c, "achat", "fk_achat_utilisateur");
                sql(c, "UPDATE achat SET utilisateur_id=NULL");
                sql(c, "ALTER TABLE achat ADD CONSTRAINT fk_achat_utilisateur FOREIGN KEY(utilisateur_id) REFERENCES fournisseur(id)");
            }
            case "annulation_sans_unique" -> retirerUniqueSimple(c, "mouvement_caisse", Lot2Migration.UNIQUE_ANNULATION, "fk_mouvement_caisse_annulation", "annulation_de_id", "mouvement_caisse");
            case "annulation_unique_composite" -> {
                retirerUniqueSimple(c, "mouvement_caisse", Lot2Migration.UNIQUE_ANNULATION, "fk_mouvement_caisse_annulation", "annulation_de_id", "mouvement_caisse");
                sql(c, "ALTER TABLE mouvement_caisse ADD CONSTRAINT uk_test_annulation UNIQUE (boutique_id, annulation_de_id)");
            }
            case "annulation_sans_fk" -> supprimerCle(c, "mouvement_caisse", "fk_mouvement_caisse_annulation");
            case "annulation_mauvaise_cible" -> {
                supprimerCle(c, "mouvement_caisse", "fk_mouvement_caisse_annulation");
                sql(c, "ALTER TABLE mouvement_caisse ADD CONSTRAINT fk_mouvement_caisse_annulation FOREIGN KEY(annulation_de_id) REFERENCES vente(id)");
            }
            case "retour_lien_sans_fk" -> supprimerCle(c, "mouvement_caisse", "fk_mouvement_caisse_retour");
            case "prochain_defaut" -> sql(c, "ALTER TABLE boutique ALTER COLUMN prochain_numero_devis SET DEFAULT 2");
            case "prochain_facultatif_apres_fin" -> sql(c, "ALTER TABLE boutique " + alter(c) + " prochain_numero_devis BIGINT NULL");
            case "prochain_negatif" -> sql(c, "UPDATE boutique SET prochain_numero_retour_vente=0 WHERE id=1");
            case "fin_sans_colonne" -> { supprimerCle(c, "vente", "fk_vente_vendeur"); sql(c, "ALTER TABLE vente DROP COLUMN vendeur_id"); }
            case "fin_sans_table" -> sql(c, "DROP TABLE detail_devis");
            default -> throw new IllegalStateException("Scénario lot 2 inconnu.");
        }
    }

    /** Activité postérieure à la migration : le code actuel laisse vendeur et auteur vides, et le gérant a pu les corriger. */
    private static void activiteApresMigration(Connection c) throws SQLException {
        sql(c, "INSERT INTO vente(id,boutique_id,date_vente,montant_total,montant_verse) VALUES(80,1,'2026-02-01 00:00:00.000000',10.00,10.00)");
        sql(c, "INSERT INTO detail_vente(id,vente_id,produit_id,quantite,prix_unitaire) VALUES(80,80,1,1,10.00)");
        sql(c, "INSERT INTO achat(id,boutique_id,date_achat,montant_total,montant_verse) VALUES(80,1,'2026-02-01 00:00:00.000000',10.00,10.00)");
        sql(c, "INSERT INTO mouvement_caisse(id,boutique_id,date_mouvement,montant,type,motif,source,utilisateur_id,achat_id,vente_id) VALUES"
                + "(80,1,'2026-02-01 00:00:00.000000',10.00,'ENCAISSEMENT','Vente récente','VENTE',1,NULL,80),"
                + "(81,1,'2026-02-01 00:00:00.000000',-10.00,'DECAISSEMENT','Achat récent','ACHAT',1,80,NULL)");
        sql(c, "UPDATE vente SET vendeur_id=3 WHERE id=1");
        sql(c, "UPDATE achat SET utilisateur_id=NULL WHERE id=1");
        // Un retour déjà enregistré par le code actuel : son total sur la vente ne doit pas être remis à zéro par une relance.
        sql(c, "UPDATE vente SET montant_retours=10.00 WHERE id=80");
        sql(c, "UPDATE boutique SET prochain_numero_devis=5,prochain_numero_retour_vente=7,prochain_numero_retour_achat=9 WHERE id=1");
        sql(c, "INSERT INTO devis(id,boutique_id,numero,client_id,utilisateur_id,date_devis,date_validite,montant_total,statut,vente_id,note,version) VALUES"
                + "(80,1,'DV-000001',1,1," + DATE + ",'2026-03-01',100.00,'TRANSFORME',80,'Devis transformé',1)");
        sql(c, "INSERT INTO detail_devis(id,devis_id,produit_id,quantite,prix_unitaire) VALUES(80,80,1,2,50.00)");
        sql(c, "INSERT INTO retour(id,boutique_id,numero,type,vente_id,client_id,utilisateur_id,date_retour,montant_total,montant_rembourse,compte_tresorerie,montant_avoir,motif) VALUES"
                + "(80,1,'RV-000001','VENTE',80,1,1," + DATE + ",10.00,6.00,'ORANGE_MONEY',4.00,'Article défectueux')");
        sql(c, "INSERT INTO detail_retour(id,retour_id,produit_id,detail_vente_id,quantite,prix_unitaire,cout_unitaire) VALUES(80,80,1,80,1,10.00,NULL)");
        sql(c, "INSERT INTO mouvement_caisse(id,boutique_id,date_mouvement,montant,type,motif,source,utilisateur_id,compte,retour_id) VALUES"
                + "(82,1,'2026-02-02 00:00:00.000000',-6.00,'DECAISSEMENT','Remboursement du retour','RETOUR',1,'ORANGE_MONEY',80)");
        sql(c, "INSERT INTO mouvement_caisse(id,boutique_id,date_mouvement,montant,type,motif,source,utilisateur_id,compte,annulation_de_id) VALUES"
                + "(83,1,'2026-02-03 00:00:00.000000',6.00,'ENCAISSEMENT','Annulation du remboursement','ANNULATION',1,'ORANGE_MONEY',82)");
    }

    static void verifier(Connection c, String scenario) throws Exception {
        var avant = Lot2Migration.photographie(c); var schemas = Lot2Migration.schemasAnciens(c); var tables = BoutiquesMigration.tables(c);
        var complet = etatComplet(c);
        if (INVALIDES.contains(scenario)) {
            for (boolean appliquer : List.of(false, true)) {
                boolean refuse = false;
                try { Lot2Migration.migrer(c, appliquer); } catch (IllegalStateException e) { refuse = true; }
                exiger(refuse && complet.equals(etatComplet(c)) && tables.equals(BoutiquesMigration.tables(c)), "Schéma lot 2 incohérent accepté ou données modifiées : " + scenario);
            }
            return;
        }
        Lot2Migration.migrer(c, false);
        exiger(complet.equals(etatComplet(c)) && tables.equals(BoutiquesMigration.tables(c)), "Le contrôle a modifié la base");
        boolean dejaFinalise = Lot2Migration.finalise(c);
        var valeurs = Lot2Migration.valeursLot2(c); var nouvelles = Lot2Migration.donneesNouvelles(c);
        Lot2Migration.migrer(c, true);
        exiger(BoutiquesMigration.tables(c).size() == 24 && BoutiquesMigration.tables(c).keySet().containsAll(Lot2Migration.NOUVELLES_TABLES)
                && avant.equals(Lot2Migration.photographie(c)) && schemas.equals(Lot2Migration.schemasAnciens(c)), "Les anciennes données, stocks ou montants ont changé");
        if (dejaFinalise || scenario.equals("reprise_finalisation")) exiger(valeurs.equals(Lot2Migration.valeursLot2(c)), "Des valeurs déjà migrées ont été réécrites");
        exiger(Lot2Migration.donneesNouvelles(c).entrySet().containsAll(nouvelles.entrySet()), "Le contenu des tables nouvelles a changé");
        verifierValeurs(c, scenario);
        var apres = etatComplet(c);
        Lot2Migration.migrer(c, true); Lot2Migration.migrer(c, false);
        exiger(apres.equals(etatComplet(c)), "Relance non idempotente");
        if (scenario.equals("nouvelle_boutique")) {
            sql(c, "INSERT INTO boutique(id,nom,date_creation) VALUES(99,'Nouvelle boutique factice','2026-01-01 00:00:00.000000')");
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE id=99 AND prochain_numero_devis=1 AND prochain_numero_retour_vente=1 AND prochain_numero_retour_achat=1") == 1,
                    "Valeurs par défaut d'une nouvelle boutique incorrectes");
            for (String compte : List.of("CAISSE", "ORANGE_MONEY", "BANQUE")) sql(c, "INSERT INTO compte_tresorerie_boutique(boutique_id,compte) VALUES(99,'" + compte + "')");
            var nouvelle = etatComplet(c);
            Lot2Migration.migrer(c, true);
            exiger(nouvelle.equals(etatComplet(c)), "Nouvelle boutique modifiée par relance");
        }
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM vente WHERE id=1") == 1) verifierContraintes(c);
    }

    /** Auteurs repris selon la règle (premier mouvement nominatif), tables nouvelles vides et compteurs initiaux, puis valeurs attendues des cas limites. */
    private static void verifierValeurs(Connection c, String scenario) throws SQLException {
        boolean relance = scenario.equals("relance");
        String historique = "d.id NOT BETWEEN " + RELANCE_MIN + " AND " + RELANCE_MAX + (relance ? " AND d.id<>1" : "");
        // Oracle indépendant de la migration : utilisateur du mouvement de plus petit identifiant qui en a un.
        for (String[] document : new String[][]{{"vente", "vente_id", "vendeur_id"}, {"achat", "achat_id", "utilisateur_id"}})
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + document[0] + " d WHERE " + historique
                    + " AND COALESCE(d." + document[2] + ",-1)<>COALESCE((SELECT m.utilisateur_id FROM mouvement_caisse m WHERE m.id=(SELECT MIN(m2.id) FROM mouvement_caisse m2 WHERE m2."
                    + document[1] + "=d.id AND m2.utilisateur_id IS NOT NULL)),-1)") == 0, "Auteur historique de " + document[0] + " inattendu");
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE (prochain_numero_devis<>1 OR prochain_numero_retour_vente<>1 OR prochain_numero_retour_achat<>1)"
                + (relance ? " AND id<>1" : "")) == 0, "Compteur du lot 2 mal initialisé");
        if (!relance) for (String table : Lot2Migration.NOUVELLES_TABLES)
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table) == 0, "Table " + table + " non vide après migration");
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM mouvement_caisse WHERE id NOT BETWEEN " + RELANCE_MIN + " AND " + RELANCE_MAX
                + " AND (retour_id IS NOT NULL OR annulation_de_id IS NOT NULL)") == 0, "Mouvement historique rattaché à un retour ou une annulation");
        exiger(Lot2Migration.cles(c, "vente").equals(Map.of("vendeur_id", "utilisateur")) && Lot2Migration.cles(c, "achat").equals(Map.of("utilisateur_id", "utilisateur"))
                && Lot2Migration.cles(c, "mouvement_caisse").equals(Map.of("retour_id", "retour", "annulation_de_id", "mouvement_caisse")), "Clés étrangères du lot 2 absentes");
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM vente WHERE id=1234567") == 1) {
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM vente WHERE (id=3 AND vendeur_id=2) OR (id=5 AND vendeur_id=1) OR (id=4 AND vendeur_id IS NULL)"
                    + " OR (id=1234567 AND vendeur_id IS NULL) OR (id=1 AND vendeur_id=" + (relance ? 3 : 1) + ")") == 5, "Vendeurs historiques");
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM achat WHERE (id=3 AND utilisateur_id=2) OR (id=4 AND utilisateur_id IS NULL) OR (id=1 AND "
                    + (relance ? "utilisateur_id IS NULL" : "utilisateur_id=1") + ")") == 3, "Auteurs d'achats historiques");
        }
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM vente WHERE montant_retours<>0" + (relance ? " AND id<>80" : "")) == 0
                && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM achat WHERE montant_retours<>0") == 0, "Total des retours mal initialisé");
        if (relance) {
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM vente WHERE id=80 AND montant_retours=10.00") == 1, "La relance a réécrit le total des retours");
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM vente WHERE id=80 AND vendeur_id IS NULL") == 1
                    && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM achat WHERE id=80 AND utilisateur_id IS NULL") == 1, "La relance a réécrit une activité postérieure");
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE id=1 AND prochain_numero_devis=5 AND prochain_numero_retour_vente=7 AND prochain_numero_retour_achat=9") == 1,
                    "La relance a réécrit un compteur");
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM mouvement_caisse m JOIN retour r ON r.id=m.retour_id JOIN mouvement_caisse a ON a.annulation_de_id=m.id"
                    + " WHERE m.id=82 AND a.id=83 AND r.id=80") == 1, "La relance a modifié un remboursement ou son annulation");
        }
    }

    /** Les unicités s'appliquent par boutique pour les numéros, globalement pour la vente d'un devis et l'annulation d'un mouvement. */
    private static void verifierContraintes(Connection c) throws SQLException {
        try {
            sql(c, "INSERT INTO devis(id,boutique_id,numero,date_devis,date_validite,montant_total,statut,vente_id) VALUES"
                    + "(70,1,'DV-T1'," + DATE + ",'2026-03-01',1.00,'EN_COURS',1),(71,2,'DV-T1'," + DATE + ",'2026-03-01',1.00,'EN_COURS',NULL),"
                    + "(72,2,'DV-T2'," + DATE + ",'2026-03-01',1.00,'EN_COURS',NULL)");
            refuse(c, "INSERT INTO devis(id,boutique_id,numero,date_devis,date_validite,montant_total,statut) VALUES(73,1,'DV-T1'," + DATE + ",'2026-03-01',1.00,'EN_COURS')", "Numéro de devis en double accepté");
            refuse(c, "INSERT INTO devis(id,boutique_id,numero,date_devis,date_validite,montant_total,statut,vente_id) VALUES(74,1,'DV-T3'," + DATE + ",'2026-03-01',1.00,'ANNULE',1)",
                    "Une vente accepte deux devis");
            sql(c, "INSERT INTO retour(id,boutique_id,numero,type,date_retour,montant_total) VALUES(70,1,'RV-T1','VENTE'," + DATE + ",1.00),(71,2,'RV-T1','ACHAT'," + DATE + ",1.00)");
            refuse(c, "INSERT INTO retour(id,boutique_id,numero,type,date_retour,montant_total) VALUES(72,1,'RV-T1','VENTE'," + DATE + ",1.00)", "Numéro de retour en double accepté");
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM retour WHERE id IN (70,71) AND montant_rembourse=0 AND montant_avoir=0 AND compte_tresorerie IS NULL") == 2, "Valeurs par défaut d'un retour");
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM devis WHERE id IN (70,71,72) AND version=0") == 3, "Version initiale d'un devis");
            sql(c, "INSERT INTO mouvement_caisse(id,boutique_id,date_mouvement,montant,type,motif,source,retour_id) VALUES(70,1," + DATE + ",-1.00,'DECAISSEMENT','Test','RETOUR',70),"
                    + "(71,1," + DATE + ",1.00,'ENCAISSEMENT','Annulation','ANNULATION',NULL)");
            sql(c, "UPDATE mouvement_caisse SET annulation_de_id=70 WHERE id=71");
            refuse(c, "INSERT INTO mouvement_caisse(id,boutique_id,date_mouvement,montant,type,motif,source,annulation_de_id) VALUES(72,1," + DATE + ",1.00,'ENCAISSEMENT','Seconde annulation','ANNULATION',70)",
                    "Un mouvement accepte deux annulations");
        } finally {
            sql(c, "DELETE FROM mouvement_caisse WHERE id=71");
            sql(c, "DELETE FROM mouvement_caisse WHERE id=70");
            sql(c, "DELETE FROM retour WHERE id IN (70,71)");
            sql(c, "DELETE FROM devis WHERE id IN (70,71,72)");
        }
    }

    private static void refuse(Connection c, String requete, String message) throws SQLException {
        try { sql(c, requete); } catch (SQLException e) {
            exiger(e.getSQLState() != null && e.getSQLState().startsWith("23"), "Refus inattendu : " + message);
            return;
        }
        throw new IllegalStateException(message);
    }

    static List<Object> etatComplet(Connection c) throws SQLException {
        var schemas = new TreeMap<String, Object>();
        for (String nom : BoutiquesMigration.tables(c).keySet()) schemas.put(nom, new TreeMap<>(InscriptionEssaiMigration.colonnes(c, nom)));
        var uniques = new TreeMap<String, Object>(); var cles = new TreeMap<String, Object>();
        for (String nom : List.of("vente", "achat", "mouvement_caisse", "boutique", "devis", "detail_devis", "retour", "detail_retour")) if (BoutiquesMigration.tables(c).containsKey(nom)) {
            uniques.put(nom, new HashSet<>(Lot2Migration.indexesUniques(c, nom).values()));
            // Un schéma volontairement invalide (cascade, clé composite) est décrit tel quel au lieu d'interrompre la photographie.
            try { cles.put(nom, Lot2Migration.cles(c, nom)); } catch (IllegalStateException e) { cles.put(nom, e.getMessage()); }
        }
        return List.of(Lot2Migration.photographie(c), Lot2Migration.valeursLot2(c), schemas, uniques, cles, Lot2Migration.donneesNouvelles(c));
    }

    private static void supprimerCle(Connection c, String table, String nom) throws SQLException {
        sql(c, "ALTER TABLE " + table + " DROP " + (maria(c) ? "FOREIGN KEY " : "CONSTRAINT ") + nom);
    }

    private static void retirerUnique(Connection c, String table, String nom, String colonneCle) throws SQLException {
        // InnoDB peut réutiliser la contrainte unique pour la clé étrangère : un index de remplacement est requis.
        if (maria(c)) sql(c, "CREATE INDEX idx_test_" + nom + " ON " + table + "(" + colonneCle + ")");
        sql(c, "ALTER TABLE " + table + " DROP " + (maria(c) ? "INDEX " : "CONSTRAINT ") + nom);
    }

    /** Une unique sur la seule colonne d'une clé étrangère sert d'index à cette clé (H2 comme InnoDB) : la clé est recréée avec son propre index. */
    private static void retirerUniqueSimple(Connection c, String table, String nom, String cle, String colonne, String reference) throws SQLException {
        supprimerCle(c, table, cle);
        sql(c, "ALTER TABLE " + table + " DROP " + (maria(c) ? "INDEX " : "CONSTRAINT ") + nom);
        sql(c, "ALTER TABLE " + table + " ADD CONSTRAINT " + cle + " FOREIGN KEY(" + colonne + ") REFERENCES " + reference + "(id)");
    }
    static void sql(Connection c, String requete) throws SQLException { c.setReadOnly(false); Lot2Migration.sql(c, requete); }
    static boolean maria(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static String alter(Connection c) throws SQLException { return maria(c) ? "MODIFY COLUMN" : "ALTER COLUMN"; }
    static void exiger(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
