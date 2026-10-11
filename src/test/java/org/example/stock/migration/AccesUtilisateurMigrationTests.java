package org.example.stock.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class AccesUtilisateurMigrationTests {
    @Test
    void leControleNeCreePasLaColonneEtNeModifieAucunCompteOuDroit() throws Exception {
        try (var c = base(true)) {
            var avant = photographie(c);
            AccesUtilisateurMigration.migrer(c, false);
            assertThat(BoutiquesMigration.colonnes(c, "utilisateur")).doesNotContainKey("actif");
            assertThat(photographie(c)).isEqualTo(avant);
            assertThat(valeur(c, "SELECT administrateur_plateforme FROM utilisateur WHERE id=1")).isEqualToIgnoringCase("TRUE");
        }
    }

    @Test
    void tousLesComptesExistantsRestentActifsSansChangerLeursAnciennesColonnes() throws Exception {
        try (var c = base(true)) {
            var avant = photographie(c);
            AccesUtilisateurMigration.migrer(c, true);
            assertThat(photographie(c)).isEqualTo(avant);
            assertThat(compter(c, "SELECT COUNT(*) FROM utilisateur WHERE actif=TRUE")).isEqualTo(3);
            assertThat(BoutiquesMigration.colonnes(c, "utilisateur").get("actif")).isEqualTo(DatabaseMetaData.columnNoNulls);
            assertThat(valeur(c, "SELECT mot_de_passe FROM utilisateur WHERE id=1")).isEqualTo("empreinte-inchangee");
            assertThat(valeur(c, "SELECT role FROM utilisateur WHERE id=1")).isEqualTo("ADMIN");
            assertThat(valeur(c, "SELECT administrateur_plateforme FROM utilisateur WHERE id=1")).isEqualToIgnoringCase("TRUE");
            assertThat(valeur(c, "SELECT administrateur_plateforme FROM utilisateur WHERE id=2")).isEqualToIgnoringCase("FALSE");
            assertThat(valeur(c, "SELECT boutique_id FROM utilisateur WHERE id=2")).isEqualTo("2");
            assertThat(valeur(c, "SELECT actif FROM boutique WHERE id=2")).isEqualToIgnoringCase("FALSE");
            assertThat(valeur(c, "SELECT acces_offert FROM boutique WHERE id=2")).isEqualToIgnoringCase("TRUE");
            assertThatThrownBy(() -> sql(c, "UPDATE utilisateur SET actif=NULL WHERE id=1")).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void lesRelancesConserventLesComptesDesactivesEtLeursDroitsPlateforme() throws Exception {
        try (var c = base(true)) {
            AccesUtilisateurMigration.migrer(c, true);
            sql(c, "UPDATE utilisateur SET actif=FALSE WHERE id IN(1,3)");
            var avant = photographie(c);
            AccesUtilisateurMigration.migrer(c, false);
            AccesUtilisateurMigration.migrer(c, true);
            AccesUtilisateurMigration.migrer(c, true);
            assertThat(photographie(c)).isEqualTo(avant);
            assertThat(compter(c, "SELECT COUNT(*) FROM utilisateur WHERE actif=FALSE")).isEqualTo(2);
            assertThat(valeur(c, "SELECT actif FROM utilisateur WHERE id=2")).isEqualToIgnoringCase("TRUE");
            assertThat(valeur(c, "SELECT administrateur_plateforme FROM utilisateur WHERE id=1")).isEqualToIgnoringCase("TRUE");
            assertThat(valeur(c, "SELECT mot_de_passe FROM utilisateur WHERE id=3")).isEqualTo("empreinte-caissier");
        }
    }

    @Test
    void uneColonneDejaConformeNestPasReinitialiseeMemeAlaPremiereRelance() throws Exception {
        try (var c = base(true)) {
            sql(c, "ALTER TABLE utilisateur ADD COLUMN actif BOOLEAN NOT NULL DEFAULT TRUE");
            sql(c, "UPDATE utilisateur SET actif=FALSE WHERE id=2");
            AccesUtilisateurMigration.migrer(c, true);
            assertThat(valeur(c, "SELECT actif FROM utilisateur WHERE id=2")).isEqualToIgnoringCase("FALSE");
        }
    }

    @Test
    void leDefautSqlActiveLesNouveauxComptesSansLeurAccorderUnDroitPlateforme() throws Exception {
        try (var c = base(true)) {
            AccesUtilisateurMigration.migrer(c, true);
            sql(c, "INSERT INTO utilisateur(id,email,role,boutique_id) VALUES(4,'nouveau@example.test','CAISSIER',1)");
            assertThat(valeur(c, "SELECT actif FROM utilisateur WHERE id=4")).isEqualToIgnoringCase("TRUE");
            assertThat(valeur(c, "SELECT administrateur_plateforme FROM utilisateur WHERE id=4")).isEqualToIgnoringCase("FALSE");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BOOLEAN DEFAULT TRUE", "BOOLEAN NOT NULL", "BOOLEAN NOT NULL DEFAULT FALSE",
            "VARCHAR(5) NOT NULL DEFAULT 'true'", "INTEGER NOT NULL DEFAULT 1"})
    void uneColonneNonConformeEstRefuseeSansReparation(String definition) throws Exception {
        try (var c = base(true)) {
            if (definition.equals("BOOLEAN NOT NULL")) {
                // Initialiser les anciennes lignes avant de retirer le défaut : H2 refuse l'ajout NOT NULL sans valeur.
                sql(c, "ALTER TABLE utilisateur ADD COLUMN actif BOOLEAN NOT NULL DEFAULT TRUE");
                sql(c, "ALTER TABLE utilisateur ALTER COLUMN actif DROP DEFAULT");
            } else {
                sql(c, "ALTER TABLE utilisateur ADD COLUMN actif " + definition);
            }
            var avant = photographie(c);
            assertThatThrownBy(() -> AccesUtilisateurMigration.migrer(c, true)).hasMessageContaining("non conforme");
            assertThat(photographie(c)).isEqualTo(avant);
            assertThat(valeur(c, "SELECT administrateur_plateforme FROM utilisateur WHERE id=1")).isEqualToIgnoringCase("TRUE");
        }
    }

    @Test
    void uneValeurNulleNestPasTransformeeEnCompteActif() throws Exception {
        try (var c = base(true)) {
            sql(c, "ALTER TABLE utilisateur ADD COLUMN actif BOOLEAN DEFAULT TRUE");
            sql(c, "UPDATE utilisateur SET actif=NULL WHERE id=3");
            assertThatThrownBy(() -> AccesUtilisateurMigration.migrer(c, true)).hasMessageContaining("non conforme");
            assertThat(valeur(c, "SELECT actif FROM utilisateur WHERE id=3")).isNull();
        }
    }

    @Test
    void lesValeursNumeriquesAutresQueZeroEtUnSontRefusees() throws Exception {
        try (var c = base(true)) {
            sql(c, "ALTER TABLE utilisateur ADD COLUMN actif TINYINT NOT NULL DEFAULT 1");
            sql(c, "UPDATE utilisateur SET actif=2 WHERE id=3");
            assertThatThrownBy(() -> AccesUtilisateurMigration.migrer(c, true)).hasMessageContaining("incohérents");
            assertThat(valeur(c, "SELECT actif FROM utilisateur WHERE id=3")).isEqualTo("2");
            assertThat(valeur(c, "SELECT administrateur_plateforme FROM utilisateur WHERE id=1")).isEqualToIgnoringCase("TRUE");
        }
    }

    @Test
    void lesMigrationsPrecedentesDoiventEtreTerminees() throws Exception {
        try (var c = ancienneBase(false)) {
            assertThatThrownBy(() -> AccesUtilisateurMigration.migrer(c, true)).hasMessageContaining("migration plateforme");
            assertThat(BoutiquesMigration.colonnes(c, "utilisateur")).doesNotContainKey("actif");
        }
        try (var c = baseBoutiques(true)) {
            assertThatThrownBy(() -> AccesUtilisateurMigration.migrer(c, true)).hasMessageContaining("migration plateforme");
            assertThat(BoutiquesMigration.colonnes(c, "utilisateur")).doesNotContainKey("actif");
        }
    }

    @Test
    void unSchemaPlateformePartielEstRefuseAvantLAjout() throws Exception {
        try (var c = baseBoutiques(true)) {
            sql(c, "ALTER TABLE boutique ADD COLUMN actif BOOLEAN NOT NULL DEFAULT TRUE");
            assertThatThrownBy(() -> AccesUtilisateurMigration.migrer(c, true)).hasMessageContaining("migration plateforme");
            assertThat(BoutiquesMigration.colonnes(c, "utilisateur")).doesNotContainKey("actif");
        }
    }

    @Test
    void lesContraintesPlateformeSontReverifieesSansLesReparer() throws Exception {
        try (var c = base(true)) {
            sql(c, "ALTER TABLE boutique ALTER COLUMN acces_offert SET DEFAULT TRUE");
            assertThatThrownBy(() -> AccesUtilisateurMigration.migrer(c, true)).hasMessageContaining("non conforme");
            assertThat(BoutiquesMigration.colonnes(c, "utilisateur")).doesNotContainKey("actif");
        }
    }

    @Test
    void unDroitPlateformeIncoherentNestPasReinitialise() throws Exception {
        try (var c = base(true)) {
            sql(c, "UPDATE utilisateur SET administrateur_plateforme=TRUE WHERE id=3");
            assertThatThrownBy(() -> AccesUtilisateurMigration.migrer(c, true)).hasMessageContaining("sans rôle ADMIN");
            assertThat(BoutiquesMigration.colonnes(c, "utilisateur")).doesNotContainKey("actif");
            assertThat(valeur(c, "SELECT administrateur_plateforme FROM utilisateur WHERE id=3")).isEqualToIgnoringCase("TRUE");
        }
    }

    @Test
    void uneRelationEntreBoutiquesEstRefuseeAvantToutDdl() throws Exception {
        try (var c = base(true)) {
            sql(c, "UPDATE produit SET boutique_id=2 WHERE id=1");
            assertThatThrownBy(() -> AccesUtilisateurMigration.migrer(c, true)).hasMessageContaining("boutiques différentes");
            assertThat(BoutiquesMigration.colonnes(c, "utilisateur")).doesNotContainKey("actif");
        }
    }

    @Test
    void laBaseVideNeRecoitAucunCompteOuDroitSupplementaire() throws Exception {
        try (var c = base(false)) {
            AccesUtilisateurMigration.migrer(c, true);
            assertThat(compter(c, "SELECT COUNT(*) FROM utilisateur")).isZero();
            assertThat(compter(c, "SELECT COUNT(*) FROM boutique")).isEqualTo(1);
            AccesUtilisateurMigration.migrer(c, false);
            AccesUtilisateurMigration.migrer(c, true);
        }
    }

    @Test
    void lesMetadonneesEnMajusculesSontAcceptees() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:h2:mem:stock_acces_utilisateur_test_" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "")) {
            BoutiquesMigrationTests.creerSchema(c, true);
            BoutiquesMigration.migrer(c, true);
            PlateformeMigration.migrer(c, true, "admin@example.test");
            AccesUtilisateurMigration.migrer(c, true);
            AccesUtilisateurMigration.migrer(c, false);
            assertThat(compter(c, "SELECT COUNT(*) FROM utilisateur WHERE actif=TRUE AND administrateur_plateforme=TRUE")).isEqualTo(1);
        }
    }

    @Test
    void uneConnexionAvecTransactionOuverteEstRefusee() throws Exception {
        try (var c = base(true)) {
            c.setAutoCommit(false);
            assertThatThrownBy(() -> AccesUtilisateurMigration.migrer(c, true)).hasMessageContaining("transaction ouverte");
            c.rollback(); c.setAutoCommit(true);
            assertThat(BoutiquesMigration.colonnes(c, "utilisateur")).doesNotContainKey("actif");
        }
    }

    static Connection base(boolean remplir) throws SQLException {
        var c = baseBoutiques(remplir);
        PlateformeMigration.migrer(c, true, remplir ? "admin@example.test" : null);
        if (remplir) sql(c, "UPDATE boutique SET actif=FALSE,acces_offert=TRUE WHERE id=2");
        return c;
    }
    static Connection baseBoutiques(boolean remplir) throws SQLException {
        var c = ancienneBase(remplir);
        BoutiquesMigration.migrer(c, true);
        if (remplir) {
            sql(c, "INSERT INTO boutique(nom,date_creation) VALUES('Deuxième',CURRENT_TIMESTAMP)");
            sql(c, "INSERT INTO utilisateur(id,nom,email,mot_de_passe,role,boutique_id) VALUES(2,'Gérant','gerant@example.test','empreinte-gerant','ADMIN',2)");
            sql(c, "INSERT INTO utilisateur(id,nom,email,mot_de_passe,role,boutique_id) VALUES(3,'Caissier','caissier@example.test','empreinte-caissier','CAISSIER',1)");
        }
        return c;
    }
    static Connection ancienneBase(boolean remplir) throws SQLException {
        var c = DriverManager.getConnection("jdbc:h2:mem:stock_acces_utilisateur_test_" + UUID.randomUUID()
                + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE", "sa", "");
        BoutiquesMigrationTests.creerSchema(c, remplir);
        return c;
    }
    static void sql(Connection c, String sql) throws SQLException { BoutiquesMigration.executer(c, sql); }
    static long compter(Connection c, String sql) throws SQLException { return BoutiquesMigration.compter(c, sql); }
    static String valeur(Connection c, String sql) throws SQLException { return BoutiquesMigrationTests.valeur(c, sql); }
    static Map<String, List<List<Object>>> photographie(Connection c) throws SQLException {
        Map<String, List<List<Object>>> resultat = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>(BoutiquesMigration.TABLES); tables.add("boutique");
        for (String table : tables) {
            List<List<Object>> lignes = new ArrayList<>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table + " ORDER BY " + (table.equals("operation_creation") ? "jeton" : "id"))) {
                while (r.next()) {
                    List<Object> ligne = new ArrayList<>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
                        if (!(table.equals("utilisateur") && r.getMetaData().getColumnName(i).equalsIgnoreCase("actif"))) ligne.add(r.getObject(i));
                    }
                    lignes.add(ligne);
                }
            }
            resultat.put(table, lignes);
        }
        return resultat;
    }
}
