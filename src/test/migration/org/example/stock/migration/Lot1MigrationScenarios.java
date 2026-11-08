package org.example.stock.migration;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** Fixtures fictives identiques sur H2 et MariaDB ; plusieurs boutiques, documents soldés et à crédit. */
final class Lot1MigrationScenarios {
    static final List<String> VALIDES = List.of("ancien", "vide", "schema_neuf", "colonnes_partielles", "reprise_initialisation",
            "reprise_finalisation", "relance", "nouvelle_boutique");
    static final List<String> INVALIDES = List.of("nature_longueur", "nature_defaut", "nature_inconnue", "seuil_type", "cout_moyen_echelle",
            "numero_longueur", "numero_sans_unique", "numero_unique_global", "echeance_type", "avance_nullable", "avance_negative",
            "delai_defaut", "prochain_defaut", "prochain_facultatif_apres_fin", "solde_nullable", "fin_sans_colonne",
            "cle_partenaire_absente", "cle_partenaire_mauvaise_cible", "mouvement_partenaire_type");
    /** Identifiants réservés aux lignes créées après la migration par le scénario de relance. */
    private static final long RELANCE_MIN = 70, RELANCE_MAX = 79;
    private Lot1MigrationScenarios() {}

    static void schema(Connection c, boolean vide) throws Exception {
        IdentiteBoutiqueMigrationScenarios.schema(c, vide);
        IdentiteBoutiqueMigration.migrer(c, true);
        if (!vide) remplir(c);
    }

    /** Ajoute aux données historiques une seconde boutique active : vente soldée, trop-perçu, sans date et grand identifiant. */
    private static void remplir(Connection c) throws SQLException {
        sql(c, "UPDATE boutique SET initialisation_terminee=TRUE WHERE id IN (1,2)");
        sql(c, "INSERT INTO vente(id,boutique_id,date_vente,montant_total,montant_verse,client_id) VALUES"
                + "(3,2,'2025-02-10 23:30:00.500000',25.00,25.00,2),"
                + "(4,1,NULL,10.00,0,NULL),"
                + "(5,1,'2025-01-31 12:00:00.000000',10.00,12.00,1),"
                + "(1234567,2,'2025-03-01 10:00:00.000000',40.00,NULL,2)");
        sql(c, "INSERT INTO detail_vente(id,vente_id,produit_id,quantite,prix_unitaire) VALUES(2,3,4,1,25.00),(3,1234567,30,2,20.00),(4,5,1,1,10.00)");
        sql(c, "INSERT INTO achat(id,boutique_id,date_achat,montant_total,montant_verse,fournisseur_id) VALUES"
                + "(3,2,'2025-02-15 09:00:00.000000',20.00,20.00,2),"
                + "(4,2,'2025-12-15 08:00:00.000000',50.00,10.00,2)");
        sql(c, "INSERT INTO detail_achat(id,achat_id,produit_id,quantite,prix_achat_unitaire,prix_vente_unitaire) VALUES(3,3,4,1,20.00,25.00),(4,4,30,5,10.00,NULL)");
    }

    static void preparer(Connection c, String scenario) throws Exception {
        if (Set.of("ancien", "vide", "schema_neuf", "nouvelle_boutique").contains(scenario)) return;
        switch (scenario) {
            case "colonnes_partielles" -> {
                sql(c, "ALTER TABLE produit ADD COLUMN nature VARCHAR(16) DEFAULT 'STOCK' NOT NULL");
                sql(c, "ALTER TABLE vente ADD COLUMN numero VARCHAR(30) NULL");
                sql(c, "ALTER TABLE boutique ADD COLUMN prochain_numero_vente BIGINT NULL");
                sql(c, "ALTER TABLE produit ADD COLUMN cout_moyen DECIMAL(17,2) NULL");
                return;
            }
            case "reprise_initialisation" -> { c.setReadOnly(false); Lot1Migration.ajouterColonnes(c); return; }
            case "reprise_finalisation" -> {
                c.setReadOnly(false); Lot1Migration.ajouterColonnes(c); Lot1Migration.initialiser(c); Lot1Migration.ajouterUniques(c);
                // Le premier compteur est déjà obligatoire ; le coût moyen reste à finaliser.
                sql(c, "ALTER TABLE boutique " + alter(c) + " prochain_numero_vente BIGINT DEFAULT 1 NOT NULL");
                return;
            }
            default -> {}
        }
        Lot1Migration.migrer(c, true);
        switch (scenario) {
            case "relance" -> {
                // Activité postérieure : le code actuel laisse numéro, échéance et coût de ligne vides.
                sql(c, "INSERT INTO client(id,boutique_id,nom,solde_initial,echeance_solde_initial) VALUES(70,1,'Client avec avance',-500.25,'2026-01-15')");
                sql(c, "INSERT INTO produit(id,boutique_id,version,nom,reference,prix_achat,prix_vente,quantite,categorie_id) VALUES(70,1,0,'Produit récent','REC',77.00,90.00,3,1)");
                sql(c, "INSERT INTO vente(id,boutique_id,date_vente,montant_total,montant_verse,client_id) VALUES(70,1,'2026-02-01 00:00:00.000000',90.00,0,70)");
                sql(c, "INSERT INTO detail_vente(id,vente_id,produit_id,quantite,prix_unitaire) VALUES(70,70,70,1,90.00)");
                sql(c, "INSERT INTO mouvement_caisse(id,boutique_id,date_mouvement,montant,type,motif,source,client_id,compte) VALUES"
                        + "(70,1,'2026-02-02 00:00:00.000000',100.00,'ENTREE','Règlement du solde de départ','SOLDE_CLIENT',70,'CAISSE')");
                sql(c, "UPDATE boutique SET delai_clients_jours=45,prochain_numero_vente=prochain_numero_vente+1 WHERE id=1");
            }
            case "nature_longueur" -> sql(c, "ALTER TABLE produit " + alter(c) + " nature VARCHAR(15) DEFAULT 'STOCK' NOT NULL");
            case "nature_defaut" -> sql(c, "ALTER TABLE produit ALTER COLUMN nature SET DEFAULT 'SERVICE'");
            case "nature_inconnue" -> sql(c, "UPDATE produit SET nature='AUTRE' WHERE id=1");
            case "seuil_type" -> sql(c, "ALTER TABLE produit " + alter(c) + " seuil_alerte INT DEFAULT 5 NOT NULL");
            case "cout_moyen_echelle" -> sql(c, "ALTER TABLE produit " + alter(c) + " cout_moyen DECIMAL(18,3) DEFAULT 0 NOT NULL");
            case "numero_longueur" -> sql(c, "ALTER TABLE vente " + alter(c) + " numero VARCHAR(31) NULL");
            case "numero_sans_unique" -> retirerUnique(c, "vente");
            case "numero_unique_global" -> { retirerUnique(c, "achat"); sql(c, "ALTER TABLE achat ADD CONSTRAINT uk_achat_numero UNIQUE (numero)"); }
            case "echeance_type" -> sql(c, "ALTER TABLE vente " + alter(c) + " date_echeance " + (maria(c) ? "DATETIME" : "TIMESTAMP") + " NULL");
            case "avance_nullable" -> sql(c, "ALTER TABLE achat " + alter(c) + " avance_utilisee DECIMAL(17,2) DEFAULT 0 NULL");
            case "avance_negative" -> sql(c, "UPDATE vente SET avance_utilisee=-1 WHERE id=1");
            case "delai_defaut" -> sql(c, "ALTER TABLE boutique ALTER COLUMN delai_clients_jours SET DEFAULT 15");
            case "prochain_defaut" -> sql(c, "ALTER TABLE boutique ALTER COLUMN prochain_numero_achat SET DEFAULT 2");
            case "prochain_facultatif_apres_fin" -> sql(c, "ALTER TABLE boutique " + alter(c) + " prochain_numero_vente BIGINT NULL");
            case "solde_nullable" -> sql(c, "ALTER TABLE fournisseur " + alter(c) + " solde_initial DECIMAL(17,2) DEFAULT 0 NULL");
            case "cle_partenaire_absente" -> supprimerCle(c, "fk_mouvement_caisse_client");
            case "cle_partenaire_mauvaise_cible" -> {
                supprimerCle(c, "fk_mouvement_caisse_fournisseur");
                sql(c, "ALTER TABLE mouvement_caisse ADD CONSTRAINT fk_mouvement_caisse_fournisseur FOREIGN KEY(fournisseur_id) REFERENCES client(id)");
            }
            case "mouvement_partenaire_type" -> {
                supprimerCle(c, "fk_mouvement_caisse_client");
                sql(c, "ALTER TABLE mouvement_caisse " + alter(c) + " client_id INT NULL");
            }
            case "fin_sans_colonne" -> { retirerUnique(c, "achat"); sql(c, "ALTER TABLE achat DROP COLUMN numero"); }
            default -> throw new IllegalStateException("Scénario lot 1 inconnu.");
        }
    }

    static void verifier(Connection c, String scenario) throws Exception {
        var avant = Lot1Migration.photographie(c); var schemas = Lot1Migration.schemasAnciens(c); var tables = BoutiquesMigration.tables(c);
        var complet = etatComplet(c);
        if (INVALIDES.contains(scenario)) {
            for (boolean appliquer : List.of(false, true)) {
                boolean refuse = false;
                try { Lot1Migration.migrer(c, appliquer); } catch (IllegalStateException e) { refuse = true; }
                exiger(refuse && complet.equals(etatComplet(c)) && tables.equals(BoutiquesMigration.tables(c)), "Schéma lot 1 incohérent accepté ou données modifiées : " + scenario);
            }
            return;
        }
        Lot1Migration.migrer(c, false);
        exiger(complet.equals(etatComplet(c)) && tables.equals(BoutiquesMigration.tables(c)), "Le contrôle a modifié la base");
        boolean dejaFinalise = Lot1Migration.finalise(c);
        var valeurs = Lot1Migration.valeursLot1(c);
        Lot1Migration.migrer(c, true);
        exiger(tables.equals(BoutiquesMigration.tables(c)) && BoutiquesMigration.tables(c).keySet().stream().filter(t -> !Lot2Migration.NOUVELLES_TABLES.contains(t)).count() == 20 && avant.equals(Lot1Migration.photographie(c))
                && schemas.equals(Lot1Migration.schemasAnciens(c)), "Les anciennes données, stocks ou montants ont changé");
        if (dejaFinalise || scenario.equals("reprise_finalisation")) exiger(valeurs.equals(Lot1Migration.valeursLot1(c)), "Des valeurs déjà migrées ont été réécrites");
        verifierValeurs(c, scenario);
        var apres = etatComplet(c);
        Lot1Migration.migrer(c, true); Lot1Migration.migrer(c, false);
        exiger(apres.equals(etatComplet(c)), "Relance non idempotente");
        if (scenario.equals("nouvelle_boutique")) {
            sql(c, "INSERT INTO boutique(id,nom,date_creation) VALUES(99,'Nouvelle boutique factice','2026-01-01 00:00:00.000000')");
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE id=99 AND prochain_numero_vente=1 AND prochain_numero_achat=1"
                    + " AND delai_clients_jours=30 AND delai_fournisseurs_jours=30") == 1, "Valeurs par défaut d'une nouvelle boutique incorrectes");
            for (String compte : List.of("CAISSE", "ORANGE_MONEY", "BANQUE")) sql(c, "INSERT INTO compte_tresorerie_boutique(boutique_id,compte) VALUES(99,'" + compte + "')");
            var nouvelle = etatComplet(c);
            Lot1Migration.migrer(c, true);
            exiger(nouvelle.equals(etatComplet(c)), "Nouvelle boutique modifiée par relance");
        }
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM vente WHERE id=1") == 1) verifierUnicite(c);
    }

    /** Règles d'initialisation appliquées à toutes les lignes historiques, puis valeurs attendues des cas limites. */
    private static void verifierValeurs(Connection c, String scenario) throws SQLException {
        String historique = " WHERE id NOT BETWEEN " + RELANCE_MIN + " AND " + RELANCE_MAX;
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM produit" + historique + " AND (cout_moyen<>prix_achat OR nature<>'STOCK' OR seuil_alerte<>5 OR unite IS NOT NULL)") == 0,
                "Produit historique mal initialisé");
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM detail_vente d JOIN produit p ON p.id=d.produit_id WHERE d.id NOT BETWEEN " + RELANCE_MIN + " AND " + RELANCE_MAX
                + " AND (d.cout_unitaire IS NULL OR d.cout_unitaire<>p.prix_achat)") == 0, "Coût de vente historique mal initialisé");
        for (String document : List.of("vente", "achat")) {
            String date = document.equals("vente") ? "date_vente" : "date_achat";
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT id,numero," + date + ",montant_total,montant_verse,date_echeance,avance_utilisee FROM " + document + historique)) {
                while (r.next()) {
                    long id = r.getLong(1); var jour = r.getObject(3, LocalDateTime.class); var echeance = r.getObject(6, LocalDate.class);
                    exiger(Lot1Migration.numero(document, id).equals(r.getString(2)), "Numéro historique inattendu pour " + document + " " + id);
                    var attendue = jour != null && Lot1Migration.resteAPayer(r.getBigDecimal(4), r.getBigDecimal(5)) ? jour.toLocalDate().plusDays(30) : null;
                    exiger(Objects.equals(attendue, echeance), "Échéance historique inattendue pour " + document + " " + id);
                    exiger(r.getBigDecimal(7).signum() == 0, "Avance historique inventée");
                }
            }
            long prochain = BoutiquesMigration.compter(c, "SELECT COALESCE(MAX(id),0)+1 FROM " + document + historique);
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE prochain_numero_" + document + "<>" + prochain
                    + (scenario.equals("relance") && document.equals("vente") ? " AND id<>1" : "")) == 0, "Compteur de " + document + " mal initialisé");
        }
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE delai_fournisseurs_jours<>30 OR delai_clients_jours<>30"
                + (scenario.equals("relance") ? " AND id<>1" : "")) == 0, "Délais historiques inattendus");
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM mouvement_caisse WHERE id NOT BETWEEN " + RELANCE_MIN + " AND " + RELANCE_MAX
                + " AND (client_id IS NOT NULL OR fournisseur_id IS NOT NULL)") == 0, "Mouvement de caisse historique rattaché à un partenaire");
        exiger(Lot1Migration.cles(c).entrySet().containsAll(Map.of("client_id", "client", "fournisseur_id", "fournisseur").entrySet()), "Clés étrangères des partenaires absentes");
        for (String partenaire : List.of("client", "fournisseur"))
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + partenaire + historique + " AND (solde_initial<>0 OR echeance_solde_initial IS NOT NULL)") == 0, "Solde initial historique inventé");
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM vente WHERE id=1234567") == 1) {
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM vente WHERE id=1234567 AND numero='FV-1234567' AND date_echeance='2025-03-31'") == 1, "Vente à crédit à grand identifiant");
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM vente WHERE id=1 AND numero='FV-000001' AND date_echeance='2025-02-02'") == 1, "Vente historique à crédit");
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM vente WHERE id IN (3,4,5) AND date_echeance IS NULL") == 3, "Vente soldée, sans date ou trop-perçue avec échéance");
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM achat WHERE (id=4 AND numero='FA-000004' AND date_echeance='2026-01-14') OR (id=3 AND date_echeance IS NULL)") == 2, "Échéances d'achats");
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE prochain_numero_vente=1234568") == BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique")
                    - (scenario.equals("relance") ? 1 : 0), "Prochain numéro de vente au-delà des numéros migrés");
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM detail_vente WHERE (id=2 AND cout_unitaire=20) OR (id=3 AND cout_unitaire=0) OR (id=1 AND cout_unitaire=100.25)") == 3, "Coûts des ventes passées");
        }
        if (scenario.equals("relance")) exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM vente v JOIN detail_vente d ON d.vente_id=v.id JOIN produit p ON p.id=d.produit_id"
                + " JOIN client k ON k.id=v.client_id WHERE v.id=70 AND v.numero IS NULL AND v.date_echeance IS NULL AND d.cout_unitaire IS NULL AND p.cout_moyen=0"
                + " AND k.solde_initial=-500.25 AND k.echeance_solde_initial='2026-01-15'") == 1, "La relance a réécrit une activité postérieure");
        if (scenario.equals("relance")) exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM mouvement_caisse WHERE id=70 AND client_id=70 AND fournisseur_id IS NULL AND montant=100.00") == 1,
                "La relance a modifié un règlement de solde de départ");
        for (String document : List.of("vente", "achat"))
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + document + " WHERE numero IS NOT NULL") == BoutiquesMigration.compter(c, "SELECT COUNT(DISTINCT numero) FROM " + document),
                    "Numéros migrés en double");
    }

    /** Un numéro est unique dans une boutique, mais peut se répéter dans une autre. */
    private static void verifierUnicite(Connection c) throws SQLException {
        sql(c, "INSERT INTO vente(id,boutique_id,date_vente,montant_total,montant_verse,numero) VALUES(78,2,'2026-01-01 00:00:00.000000',1,1,'FV-000001')");
        boolean refuse = false;
        try { sql(c, "INSERT INTO vente(id,boutique_id,date_vente,montant_total,montant_verse,numero) VALUES(79,1,'2026-01-01 00:00:00.000000',1,1,'FV-000001')"); }
        catch (SQLException e) { refuse = true; }
        sql(c, "DELETE FROM vente WHERE id IN (78,79)");
        exiger(refuse, "Un numéro de vente en double a été accepté dans la même boutique");
    }

    static List<Object> etatComplet(Connection c) throws SQLException {
        var schemas = new TreeMap<String, Object>();
        for (String nom : BoutiquesMigration.tables(c).keySet()) schemas.put(nom, new TreeMap<>(InscriptionEssaiMigration.colonnes(c, nom)));
        var uniques = new TreeMap<String, Object>();
        for (String nom : List.of("vente", "achat")) uniques.put(nom, new HashSet<>(BoutiquesMigration.indexesUniques(c, nom).values()));
        return List.of(Lot1Migration.photographie(c), Lot1Migration.valeursLot1(c), schemas, uniques);
    }

    private static void supprimerCle(Connection c, String nom) throws SQLException {
        sql(c, "ALTER TABLE mouvement_caisse DROP " + (maria(c) ? "FOREIGN KEY " : "CONSTRAINT ") + nom);
    }

    private static void retirerUnique(Connection c, String table) throws SQLException {
        // InnoDB peut réutiliser la contrainte unique pour la clé étrangère boutique_id : un index de remplacement est requis.
        if (maria(c)) sql(c, "CREATE INDEX idx_test_" + table + "_boutique ON " + table + "(boutique_id)");
        sql(c, "ALTER TABLE " + table + " DROP " + (maria(c) ? "INDEX" : "CONSTRAINT") + " uk_" + table + "_boutique_numero");
    }
    static void sql(Connection c, String requete) throws SQLException { c.setReadOnly(false); Lot1Migration.sql(c, requete); }
    static boolean maria(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static String alter(Connection c) throws SQLException { return maria(c) ? "MODIFY COLUMN" : "ALTER COLUMN"; }
    static void exiger(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
