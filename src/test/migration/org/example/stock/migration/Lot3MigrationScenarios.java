package org.example.stock.migration;

import java.sql.*;
import java.util.*;

/**
 * Fixtures fictives identiques sur H2 et MariaDB. La base de départ est celle des lots 1, 2 et 4 (boutiques, comptes historiques
 * à énumération, mouvements) à laquelle s'ajoutent des mouvements sur les trois moyens de paiement, des opérations et un remboursement.
 */
final class Lot3MigrationScenarios {
    static final List<String> VALIDES = List.of("ancien", "vide", "schema_neuf", "comptes_manquants", "boutique_sans_compte", "colonnes_partielles",
            "reprise_colonnes", "reprise_remplissage", "reprise_cle_absente", "reprise_unicite_absente", "relance", "nouvelle_boutique",
            "trois_a_seule", "financements_partiels", "relance_financements");
    static final List<String> INVALIDES = List.of("mouvement_compte_inconnu", "operation_source_inconnue", "operation_destination_inconnue", "retour_compte_inconnu",
            "compte_type_inconnu", "principal_incoherent", "nom_facultatif", "type_longueur", "mouvement_autre_boutique", "operation_autre_boutique",
            "autorisation_autre_boutique", "cle_mauvaise_cible", "autorises_colonne_en_trop");
    private Lot3MigrationScenarios() {}

    static void schema(Connection c, boolean vide) throws Exception {
        Lot4MigrationScenarios.schema(c, vide);
        Lot4Migration.migrer(c, true);
        if (!vide) remplir(c);
    }

    /** Activité sur les trois moyens de paiement des deux premières boutiques : de quoi vérifier les totaux par boutique et par type. */
    private static void remplir(Connection c) throws SQLException {
        sql(c, "INSERT INTO operation_tresorerie(id,boutique_id,utilisateur_id,jeton,type,compte_source,compte_destination,montant,motif,date_operation) VALUES"
                + "(300,1,1,'a0000000-0000-0000-0000-000000000300','TRANSFERT','CAISSE','ORANGE_MONEY',40.25,'Versement factice','2025-03-04 08:00:00.123456'),"
                + "(301,1,1,'a0000000-0000-0000-0000-000000000301','DEPENSE','BANQUE',NULL,10.25,'Dépense factice','2025-03-04 09:00:00.123456')");
        sql(c, "INSERT INTO mouvement_caisse(id,boutique_id,date_mouvement,montant,type,motif,source,utilisateur_id,compte,operation_tresorerie_id) VALUES"
                + "(300,1,'2025-03-01 08:00:00.000000',100.00,'ENTREE','Vente Orange','VENTE',1,'ORANGE_MONEY',NULL),"
                + "(301,1,'2025-03-02 08:00:00.000000',250.50,'ENTREE','Vente banque','VENTE',1,'BANQUE',NULL),"
                + "(302,1,'2025-03-04 08:00:00.123456',-40.25,'SORTIE','Transfert fictif','TRANSFERT',1,'CAISSE',300),"
                + "(303,1,'2025-03-04 08:00:00.123456',40.25,'ENTREE','Transfert fictif','TRANSFERT',1,'ORANGE_MONEY',300),"
                + "(304,1,'2025-03-04 09:00:00.123456',-10.25,'SORTIE','Dépense fictive','DEPENSE',1,'BANQUE',301),"
                + "(305,2,'2025-03-05 08:00:00.000000',77.77,'ENTREE','Vente Orange','VENTE',2,'ORANGE_MONEY',NULL)");
        sql(c, "INSERT INTO retour(id,boutique_id,numero,type,date_retour,montant_total,montant_rembourse,compte_tresorerie,montant_avoir) VALUES"
                + "(300,1,'RV-300','VENTE','2025-03-06 08:00:00.000000',15.00,15.00,'ORANGE_MONEY',0),"
                + "(301,2,'RV-301','VENTE','2025-03-06 08:00:00.000000',5.00,0,NULL,5.00)");
    }

    static void preparer(Connection c, String scenario) throws Exception {
        if (Set.of("ancien", "vide", "schema_neuf", "nouvelle_boutique").contains(scenario)) return;
        if (historique(scenario)) { preparerHistorique(c, scenario); return; }
        switch (scenario) {
            case "comptes_manquants" -> { sql(c, "DELETE FROM compte_tresorerie_boutique WHERE boutique_id=2 AND compte<>'CAISSE'"); sql(c, "DELETE FROM mouvement_caisse WHERE id=305"); return; }
            case "boutique_sans_compte" -> { sql(c, "DELETE FROM compte_tresorerie_boutique WHERE boutique_id=2"); sql(c, "DELETE FROM mouvement_caisse WHERE id=305"); return; }
            case "colonnes_partielles" -> {
                sql(c, "ALTER TABLE compte_tresorerie_boutique ADD COLUMN type VARCHAR(32) NULL");
                sql(c, "ALTER TABLE compte_tresorerie_boutique ADD COLUMN actif BOOLEAN NOT NULL DEFAULT TRUE");
                sql(c, "ALTER TABLE mouvement_caisse ADD COLUMN compte_id BIGINT NULL");
                sql(c, "ALTER TABLE retour ADD COLUMN compte_tresorerie_id BIGINT NULL");
                return;
            }
            case "reprise_colonnes" -> { c.setReadOnly(false); Lot3Migration.ajouterColonnes(c); return; }
            case "reprise_remplissage" -> { c.setReadOnly(false); Lot3Migration.ajouterColonnes(c); Lot3Migration.creerAutorises(c); Lot3Migration.remplir(c); return; }
            default -> {}
        }
        Lot3Migration.migrer(c, true);
        switch (scenario) {
            // Interruption après le remplissage : une clé étrangère ou une unicité absente est reposée sans toucher aux données.
            case "reprise_cle_absente" -> sql(c, "ALTER TABLE operation_tresorerie DROP " + (maria(c) ? "FOREIGN KEY" : "CONSTRAINT") + " fk_operation_tresorerie_source");
            case "reprise_unicite_absente" -> sql(c, "ALTER TABLE compte_tresorerie_boutique DROP " + (maria(c) ? "INDEX" : "CONSTRAINT") + " uk_compte_tresorerie_nom");
            // Après la migration, le gérant renomme un compte, en ajoute un deuxième Orange Money, le choisit principal et l'utilise.
            case "relance" -> {
                sql(c, "UPDATE compte_tresorerie_boutique SET nom='Ecobank', nom_cle='ecobank', numero='0102' WHERE boutique_id=1 AND type='BANQUE'");
                sql(c, "UPDATE compte_tresorerie_boutique SET principal_type=NULL WHERE boutique_id=1 AND type='ORANGE_MONEY'");
                sql(c, "INSERT INTO compte_tresorerie_boutique(id,boutique_id,type,nom,nom_cle,numero,principal_type,actif,ordre) VALUES(500,1,'ORANGE_MONEY','Orange Money Labé','orange money labé','622',"
                        + "'ORANGE_MONEY',TRUE,3),(501,1,'ORANGE_MONEY','Ancien Orange','ancien orange',NULL,NULL,FALSE,4)");
                sql(c, "INSERT INTO mouvement_caisse(id,boutique_id,date_mouvement,montant,type,motif,source,utilisateur_id,compte_id) VALUES"
                        + "(400,1,'2026-01-02 08:00:00.000000',12.00,'ENTREE','Après migration','VENTE',1,500)");
                sql(c, "INSERT INTO compte_tresorerie_autorise(utilisateur_id,compte_id) VALUES(1,500)");
            }
            case "principal_incoherent" -> {
                sql(c, "UPDATE compte_tresorerie_boutique SET principal_type=NULL WHERE boutique_id=1 AND type='BANQUE'");
                sql(c, "UPDATE compte_tresorerie_boutique SET principal_type='BANQUE' WHERE boutique_id=1 AND type='ORANGE_MONEY'");
            }
            case "nom_facultatif" -> sql(c, "ALTER TABLE compte_tresorerie_boutique " + alter(c) + " nom VARCHAR(60) NULL");
            case "type_longueur" -> sql(c, "ALTER TABLE compte_tresorerie_boutique " + alter(c) + " type VARCHAR(16) NOT NULL");
            case "mouvement_autre_boutique" -> sql(c, "UPDATE mouvement_caisse SET compte_id=(SELECT MIN(id) FROM compte_tresorerie_boutique WHERE boutique_id=2) WHERE id=300");
            case "operation_autre_boutique" -> sql(c, "UPDATE operation_tresorerie SET compte_source_id=(SELECT MIN(id) FROM compte_tresorerie_boutique WHERE boutique_id=2) WHERE id=300");
            case "autorisation_autre_boutique" -> sql(c, "INSERT INTO compte_tresorerie_autorise(utilisateur_id,compte_id) VALUES(1,(SELECT MIN(id) FROM compte_tresorerie_boutique WHERE boutique_id=2))");
            case "cle_mauvaise_cible" -> {
                sql(c, "UPDATE retour SET compte_tresorerie_id=NULL");
                sql(c, "ALTER TABLE retour DROP " + (maria(c) ? "FOREIGN KEY" : "CONSTRAINT") + " fk_retour_compte_tresorerie");
                sql(c, "ALTER TABLE retour ADD CONSTRAINT fk_retour_compte_tresorerie FOREIGN KEY(compte_tresorerie_id) REFERENCES boutique(id)");
            }
            // Base où seule l'étape 3a est appliquée : les éléments des financements (3b1) sont retirés, la migration les ajoute.
            case "trois_a_seule" -> retirerFinancements(c, true);
            // Interruption pendant 3b1 : les partenaires existent, pas les emprunts ni les colonnes de la boutique.
            case "financements_partiels" -> { retirerFinancements(c, false); }
            // Après la migration, le gérant a saisi un partenaire, un emprunt, son échéancier et un remboursement : une relance n'y touche pas.
            case "relance_financements" -> saisirFinancements(c);
            case "autorises_colonne_en_trop" -> sql(c, "ALTER TABLE compte_tresorerie_autorise ADD COLUMN note VARCHAR(10) NULL");
            default -> throw new IllegalStateException("Scénario lot 3 inconnu.");
        }
    }

    private static String supprimerCle(Connection c) throws SQLException { return maria(c) ? "DROP FOREIGN KEY " : "DROP CONSTRAINT "; }

    /** Retire les éléments de 3b1 d'une base migrée ; {@code tout} retire aussi les partenaires et les colonnes de la boutique. */
    private static void retirerFinancements(Connection c, boolean tout) throws SQLException {
        sql(c, "DROP TABLE imputation_echeance");
        sql(c, "DROP TABLE echeance_emprunt");
        sql(c, "ALTER TABLE operation_tresorerie " + supprimerCle(c) + "fk_operation_tresorerie_emprunt");
        sql(c, "ALTER TABLE operation_tresorerie DROP COLUMN emprunt_id");
        sql(c, "DROP TABLE emprunt");
        if (!tout) return;
        sql(c, "ALTER TABLE operation_tresorerie " + supprimerCle(c) + "fk_operation_tresorerie_partenaire");
        sql(c, "ALTER TABLE operation_tresorerie DROP COLUMN partenaire_financier_id");
        sql(c, "DROP TABLE partenaire_financier");
        sql(c, "ALTER TABLE boutique DROP COLUMN prochain_numero_emprunt");
        sql(c, "ALTER TABLE boutique DROP COLUMN prochain_numero_pret");
    }

    private static void saisirFinancements(Connection c) throws SQLException {
        sql(c, "INSERT INTO partenaire_financier(id,boutique_id,nom,nom_cle,type,telephone,actif,date_creation) VALUES"
                + "(10,1,'BICIGUI','bicigui','BANQUE','622000000',TRUE,'2026-02-01 08:00:00.000000'),(11,1,'Ancien associé','ancien associé','ASSOCIE',NULL,FALSE,'2026-02-02 08:00:00.000000')");
        sql(c, "INSERT INTO emprunt(id,boutique_id,partenaire_id,utilisateur_id,compte_id,numero,sens,date_emprunt,capital,taux_annuel,nombre_echeances,periodicite,date_premiere_echeance,frais_dossier,jeton,date_creation) VALUES"
                + "(20,1,10,1,(SELECT MIN(id) FROM compte_tresorerie_boutique WHERE boutique_id=1),'EM-000001','EMPRUNT','2026-02-01',1200.00,12.00,2,'MENSUELLE','2026-03-01',0,'b0000000-0000-0000-0000-000000000020','2026-02-01 08:00:00.000000')");
        sql(c, "INSERT INTO echeance_emprunt(id,emprunt_id,rang,date_echeance,capital,interet) VALUES(30,20,1,'2026-03-01',600.00,12.00),(31,20,2,'2026-04-01',600.00,6.00)");
        sql(c, "INSERT INTO operation_tresorerie(id,boutique_id,utilisateur_id,jeton,type,compte_source_id,montant,motif,date_operation,partenaire_financier_id,emprunt_id) VALUES"
                + "(400,1,1,'b0000000-0000-0000-0000-000000000400','REMBOURSEMENT',(SELECT MIN(id) FROM compte_tresorerie_boutique WHERE boutique_id=1),612.00,'Remboursement factice','2026-03-01 08:00:00.000000',10,20)");
        sql(c, "INSERT INTO imputation_echeance(id,operation_id,echeance_id,capital,interet,interet_remis) VALUES(40,400,30,600.00,12.00,0)");
        sql(c, "UPDATE boutique SET prochain_numero_emprunt=2 WHERE id=1");
    }

    /** Scénarios dont l'incohérence est celle de la base historique : elle est introduite avant toute migration. */
    static void preparerHistorique(Connection c, String scenario) throws SQLException {
        switch (scenario) {
            case "mouvement_compte_inconnu" -> sql(c, "UPDATE mouvement_caisse SET compte='AUTRE' WHERE id=300");
            case "operation_source_inconnue" -> sql(c, "UPDATE operation_tresorerie SET compte_source='AUTRE' WHERE id=301");
            case "operation_destination_inconnue" -> sql(c, "UPDATE operation_tresorerie SET compte_destination='AUTRE' WHERE id=300");
            case "retour_compte_inconnu" -> sql(c, "UPDATE retour SET compte_tresorerie='AUTRE' WHERE id=300");
            case "compte_type_inconnu" -> sql(c, "INSERT INTO compte_tresorerie_boutique(boutique_id,compte) VALUES(1,'AUTRE')");
            default -> {}
        }
    }

    static boolean historique(String scenario) {
        return Set.of("mouvement_compte_inconnu", "operation_source_inconnue", "operation_destination_inconnue", "retour_compte_inconnu", "compte_type_inconnu").contains(scenario);
    }

    static void verifier(Connection c, String scenario) throws Exception {
        var avant = Lot3Migration.photographie(c); var schemas = Lot3Migration.schemasAnciens(c); var tables = BoutiquesMigration.tables(c);
        var totaux = Lot3Migration.totauxParType(c); var complet = etatComplet(c);
        if (INVALIDES.contains(scenario)) {
            for (boolean appliquer : List.of(false, true)) {
                boolean refuse = false;
                try { Lot3Migration.migrer(c, appliquer); } catch (IllegalStateException e) { refuse = true; }
                exiger(refuse && complet.equals(etatComplet(c)) && tables.equals(BoutiquesMigration.tables(c)), "Schéma lot 3 incohérent accepté ou données modifiées : " + scenario);
            }
            return;
        }
        Lot3Migration.migrer(c, false);
        exiger(complet.equals(etatComplet(c)) && tables.equals(BoutiquesMigration.tables(c)), "Le contrôle a modifié la base");
        boolean dejaFinalise = Lot3Migration.finalise(c);
        var comptesAvant = Lot3Migration.donneesComptes(c);
        Lot3Migration.migrer(c, true);
        exiger(Lot3Migration.finalise(c) && avant.equals(Lot3Migration.photographie(c)) && schemas.equals(Lot3Migration.schemasAnciens(c)),
                "Les anciennes données, stocks ou montants ont changé");
        exiger(totaux.equals(Lot3Migration.totauxParType(c)), "Les totaux par boutique et par type ont changé");
        verifierValeurs(c, scenario, dejaFinalise, comptesAvant);
        var apres = etatComplet(c);
        Lot3Migration.migrer(c, true); Lot3Migration.migrer(c, false);
        exiger(apres.equals(etatComplet(c)), "Relance non idempotente");
        if (scenario.equals("nouvelle_boutique")) {
            sql(c, "INSERT INTO boutique(id,nom,date_creation) VALUES(99,'Nouvelle boutique factice','2026-01-01 00:00:00.000000')");
            sql(c, "INSERT INTO compte_tresorerie_boutique(boutique_id,type,nom,nom_cle,principal_type,actif,ordre) VALUES"
                    + "(99,'ESPECES','Caisse','caisse','ESPECES',TRUE,0),(99,'ORANGE_MONEY','Orange Money','orange money','ORANGE_MONEY',TRUE,1),(99,'BANQUE','Banque','banque','BANQUE',TRUE,2)");
            var nouvelle = etatComplet(c);
            Lot3Migration.migrer(c, true);
            exiger(nouvelle.equals(etatComplet(c)), "Nouvelle boutique modifiée par relance");
        }
    }

    private static void verifierValeurs(Connection c, String scenario, boolean dejaFinalise, Map<String, List<List<Object>>> comptesAvant) throws SQLException {
        for (var ancienne : Lot3Migration.ANCIENNES_COLONNES)
            exiger(!InscriptionEssaiMigration.colonnes(c, ancienne[0]).containsKey(ancienne[1]), "Ancienne colonne conservée : " + ancienne[0] + "." + ancienne[1]);
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM mouvement_caisse m JOIN compte_tresorerie_boutique x ON x.id=m.compte_id WHERE x.boutique_id<>m.boutique_id") == 0,
                "Un mouvement référence le compte d'une autre boutique");
        for (String table : List.of("partenaire_financier", "emprunt", "echeance_emprunt", "imputation_echeance"))
            exiger(BoutiquesMigration.tables(c).containsKey(table), "Table de financement absente : " + table);
        exiger(InscriptionEssaiMigration.colonnes(c, "operation_tresorerie").keySet().containsAll(Set.of("partenaire_financier_id", "emprunt_id"))
                && InscriptionEssaiMigration.colonnes(c, "boutique").keySet().containsAll(Set.of("prochain_numero_emprunt", "prochain_numero_pret")), "Colonnes de financement absentes");
        if (scenario.equals("relance_financements")) {
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM partenaire_financier") == 2 && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM emprunt WHERE id=20 AND numero='EM-000001'") == 1
                    && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM echeance_emprunt") == 2 && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM imputation_echeance") == 1
                    && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE id=1 AND prochain_numero_emprunt=2") == 1
                    && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM operation_tresorerie WHERE id=400 AND emprunt_id=20 AND partenaire_financier_id=10") == 1,
                    "Une relance a modifié des saisies de financement");
            return;
        }
        if (!scenario.equals("relance_financements")) {
            // Sans saisie : les nouvelles tables sont vides, les compteurs de numéros valent 1 et aucune opération n'a de partenaire ni d'emprunt.
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM partenaire_financier") == 0 && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM emprunt") == 0
                    && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE prochain_numero_emprunt<>1 OR prochain_numero_pret<>1") == 0
                    && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM operation_tresorerie WHERE partenaire_financier_id IS NOT NULL OR emprunt_id IS NOT NULL") == 0,
                    "Les éléments de financement ajoutés doivent être vides");
        }
        if (scenario.equals("vide") || scenario.equals("schema_neuf")) return;
        long boutiques = BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique");
        if (scenario.equals("relance")) {
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique WHERE id IN (500,501)") == 2
                    && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique WHERE boutique_id=1 AND type='BANQUE' AND nom='Ecobank' AND numero='0102' AND principal_type='BANQUE'") == 1
                    && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique WHERE id=500 AND principal_type='ORANGE_MONEY' AND ordre=3") == 1
                    && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique WHERE id=501 AND actif=FALSE") == 1
                    && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_autorise WHERE utilisateur_id=1 AND compte_id=500") == 1
                    && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM mouvement_caisse WHERE id=400 AND compte_id=500") == 1,
                    "Une relance a réécrit un compte, un principal, un archivage ou une autorisation choisis après la migration");
            return;
        }
        // Chaque boutique a ses trois comptes de base : principaux, actifs, nommés « Caisse », « Orange Money » et « Banque ».
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique") == boutiques * 3, "Trois comptes de base par boutique attendus");
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique WHERE principal_type=type AND actif=TRUE AND numero IS NULL AND ("
                + "(type='ESPECES' AND nom='Caisse' AND nom_cle='caisse' AND ordre=0) OR (type='ORANGE_MONEY' AND nom='Orange Money' AND nom_cle='orange money' AND ordre=1)"
                + " OR (type='BANQUE' AND nom='Banque' AND nom_cle='banque' AND ordre=2))") == boutiques * 3, "Comptes de base mal nommés ou non principaux");
        exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_autorise") == 0, "Aucune autorisation ne doit être inventée");
        if (!Set.of("comptes_manquants", "boutique_sans_compte").contains(scenario)) {
            // Chaque mouvement de la fixture est rattaché au compte de base de son moyen de paiement d'origine.
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM mouvement_caisse m JOIN compte_tresorerie_boutique x ON x.id=m.compte_id WHERE "
                    + "(m.id IN (300,303,305) AND x.type='ORANGE_MONEY') OR (m.id IN (301,304) AND x.type='BANQUE') OR (m.id=302 AND x.type='ESPECES')") == 6, "Mouvements mal rattachés");
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM operation_tresorerie o JOIN compte_tresorerie_boutique s ON s.id=o.compte_source_id LEFT JOIN compte_tresorerie_boutique d ON d.id=o.compte_destination_id"
                    + " WHERE (o.id=300 AND s.type='ESPECES' AND d.type='ORANGE_MONEY') OR (o.id=301 AND s.type='BANQUE' AND d.id IS NULL)") == 2, "Opérations mal rattachées");
            exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM retour r LEFT JOIN compte_tresorerie_boutique x ON x.id=r.compte_tresorerie_id WHERE (r.id=300 AND x.type='ORANGE_MONEY') OR (r.id=301 AND x.id IS NULL)") == 2,
                    "Remboursements mal rattachés");
            exiger(totauxParTypeDeLaFixture(c), "Totaux de la fixture inattendus");
        }
    }

    /** Boutique 1, mouvements de la fixture : espèces -40,25 ; Orange Money 100 + 40,25 ; banque 250,50 - 10,25. */
    private static boolean totauxParTypeDeLaFixture(Connection c) throws SQLException {
        return somme(c, "ESPECES").equals("-40.25") && somme(c, "ORANGE_MONEY").equals("140.25") && somme(c, "BANQUE").equals("240.25");
    }

    private static String somme(Connection c, String type) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT SUM(m.montant) FROM mouvement_caisse m JOIN compte_tresorerie_boutique x ON x.id=m.compte_id"
                + " WHERE m.boutique_id=1 AND m.id BETWEEN 300 AND 304 AND x.type='" + type + "'")) {
            r.next(); return r.getBigDecimal(1).setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
        }
    }

    /** Tout le contenu de la base : un contrôle ou un refus ne modifie ni le schéma ni une seule valeur, nouvelles colonnes comprises. */
    static List<Object> etatComplet(Connection c) throws SQLException {
        var schemas = new TreeMap<String, Object>(); var donnees = new TreeMap<String, List<List<Object>>>();
        for (String nom : BoutiquesMigration.tables(c).keySet()) {
            schemas.put(nom, new TreeMap<>(InscriptionEssaiMigration.colonnes(c, nom)));
            var lignes = new ArrayList<List<Object>>();
            String physique = BoutiquesMigration.tables(c).get(nom);
            String q = c.getMetaData().getIdentifierQuoteString().strip();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + q + physique + q + " ORDER BY " + PhotographieJdbc.ordre(c, nom))) {
                while (r.next()) {
                    var ligne = new ArrayList<Object>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) ligne.add(PhotographieJdbc.valeur(r, i));
                    lignes.add(ligne);
                }
            }
            donnees.put(nom, lignes);
        }
        var uniques = new TreeMap<String, Object>(); var cles = new TreeMap<String, Object>();
        for (String nom : List.of("compte_tresorerie_boutique", "compte_tresorerie_autorise", "mouvement_caisse", "operation_tresorerie", "retour"))
            if (BoutiquesMigration.tables(c).containsKey(nom)) { uniques.put(nom, Lot3Migration.indexesUniques(c, nom)); cles.put(nom, Lot3Migration.cles(c, nom)); }
        return List.of(schemas, donnees, uniques, cles);
    }

    static void sql(Connection c, String requete) throws SQLException { c.setReadOnly(false); Lot3Migration.sql(c, requete); }
    static boolean maria(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static String alter(Connection c) throws SQLException { return maria(c) ? "MODIFY COLUMN" : "ALTER COLUMN"; }
    static void exiger(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
