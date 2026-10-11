package org.example.stock.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.*;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class PlateformeMigrationTests {
    static final String ADMIN = "admin@example.test";

    @Test
    void leControleLaisseLesColonnesAbsentesEtLesComptesIntacts() throws Exception {
        try (var c = base(true)) {
            var avant = photographie(c);
            PlateformeMigration.migrer(c, false, ADMIN);
            assertThat(BoutiquesMigration.colonnes(c, "boutique")).doesNotContainKeys("actif", "acces_offert");
            assertThat(BoutiquesMigration.colonnes(c, "utilisateur")).doesNotContainKey("administrateur_plateforme");
            assertThat(photographie(c)).isEqualTo(avant);
        }
    }

    @Test
    void lesValeursParDefautConserventLesBoutiquesSansPromouvoirLesAdmins() throws Exception {
        try (var c = base(true)) {
            var avant = photographie(c);
            PlateformeMigration.migrer(c, true, null);
            assertThat(photographie(c)).isEqualTo(avant);
            assertThat(compter(c, "SELECT COUNT(*) FROM boutique WHERE actif=TRUE AND acces_offert=FALSE")).isEqualTo(2);
            assertThat(compter(c, "SELECT COUNT(*) FROM utilisateur WHERE administrateur_plateforme=TRUE")).isZero();
            assertThat(compter(c, "SELECT COUNT(*) FROM utilisateur WHERE role='ADMIN'")).isEqualTo(2);
            for (var champ : PlateformeMigration.CHAMPS) {
                assertThat(BoutiquesMigration.colonnes(c, champ.table()).get(champ.nom())).isEqualTo(DatabaseMetaData.columnNoNulls);
            }
            // Les défauts SQL protègent aussi les nouvelles lignes insérées sans champs de plateforme.
            sql(c, "INSERT INTO boutique(nom,date_creation) VALUES('Nouvelle',CURRENT_TIMESTAMP)");
            sql(c, "INSERT INTO utilisateur(id,email,role,boutique_id) VALUES(4,'nouveau@example.test','ADMIN',3)");
            assertThat(valeur(c, "SELECT actif FROM boutique WHERE id=3")).isEqualToIgnoringCase("TRUE");
            assertThat(valeur(c, "SELECT acces_offert FROM boutique WHERE id=3")).isEqualToIgnoringCase("FALSE");
            assertThat(valeur(c, "SELECT administrateur_plateforme FROM utilisateur WHERE id=4")).isEqualToIgnoringCase("FALSE");
        }
    }

    @Test
    void seulLeCompteDesigneRecoitLeDroitSansModificationDuRoleDuHashOuDeLaBoutique() throws Exception {
        try (var c = base(true)) {
            var avant = photographie(c);
            PlateformeMigration.migrer(c, true, ADMIN);
            assertThat(photographie(c)).isEqualTo(avant);
            assertThat(compter(c, "SELECT COUNT(*) FROM utilisateur WHERE administrateur_plateforme=TRUE")).isEqualTo(1);
            assertThat(valeur(c, "SELECT administrateur_plateforme FROM utilisateur WHERE id=1")).isEqualToIgnoringCase("TRUE");
            assertThat(valeur(c, "SELECT administrateur_plateforme FROM utilisateur WHERE id=2")).isEqualToIgnoringCase("FALSE");
            assertThat(valeur(c, "SELECT administrateur_plateforme FROM utilisateur WHERE id=3")).isEqualToIgnoringCase("FALSE");
            assertThat(valeur(c, "SELECT mot_de_passe FROM utilisateur WHERE id=1")).isEqualTo("empreinte-inchangee");
            assertThat(valeur(c, "SELECT role FROM utilisateur WHERE id=1")).isEqualTo("ADMIN");
            assertThat(valeur(c, "SELECT boutique_id FROM utilisateur WHERE id=1")).isEqualTo("1");
            PlateformeMigration.migrer(c, true, ADMIN);
            assertThat(compter(c, "SELECT COUNT(*) FROM utilisateur WHERE administrateur_plateforme=TRUE")).isEqualTo(1);
            assertThat(photographie(c)).isEqualTo(avant);
        }
    }

    @Test
    void leControleSurSchemaMigreNAttribuePasUnDroit() throws Exception {
        try (var c = base(true)) {
            PlateformeMigration.migrer(c, true, null);
            PlateformeMigration.migrer(c, false, ADMIN);
            assertThat(compter(c, "SELECT COUNT(*) FROM utilisateur WHERE administrateur_plateforme=TRUE")).isZero();
        }
    }

    @Test
    void lesEtatsEtDroitsExistantsNeSontNiReinitialisesNiRetires() throws Exception {
        try (var c = base(true)) {
            PlateformeMigration.migrer(c, true, ADMIN);
            sql(c, "UPDATE boutique SET actif=FALSE,acces_offert=TRUE WHERE id=2");
            sql(c, "UPDATE utilisateur SET administrateur_plateforme=TRUE WHERE id=2");
            PlateformeMigration.migrer(c, true, null);
            PlateformeMigration.migrer(c, true, ADMIN);
            assertThat(compter(c, "SELECT COUNT(*) FROM utilisateur WHERE administrateur_plateforme=TRUE")).isEqualTo(2);
            assertThat(valeur(c, "SELECT actif FROM boutique WHERE id=2")).isEqualToIgnoringCase("FALSE");
            assertThat(valeur(c, "SELECT acces_offert FROM boutique WHERE id=2")).isEqualToIgnoringCase("TRUE");
            assertThat(valeur(c, "SELECT boutique_id FROM utilisateur WHERE id=2")).isEqualTo("2");
        }
    }

    @Test
    void uneAttributionPeutEtreFaiteApresLaMigrationDesColonnes() throws Exception {
        try (var c = base(true)) {
            PlateformeMigration.migrer(c, true, null);
            PlateformeMigration.migrer(c, true, "gerant@example.test");
            assertThat(valeur(c, "SELECT administrateur_plateforme FROM utilisateur WHERE id=2")).isEqualToIgnoringCase("TRUE");
            assertThat(valeur(c, "SELECT administrateur_plateforme FROM utilisateur WHERE id=1")).isEqualToIgnoringCase("FALSE");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ADMIN@EXAMPLE.TEST ", "ADMIN@EXAMPLE.TEST", "abc", "a b@example.test"})
    void lesAdressesInvalidesOuNonNormaliseesArretentAvantToutDdl(String email) throws Exception {
        try (var c = base(true)) {
            var avant = photographie(c);
            assertThatThrownBy(() -> PlateformeMigration.migrer(c, true, email)).hasMessageContaining("normalisée");
            assertThat(BoutiquesMigration.colonnes(c, "boutique")).doesNotContainKey("actif");
            assertThat(photographie(c)).isEqualTo(avant);
        }
    }

    @Test
    void unCompteAbsentNeDeclencheNiCreationNiMigration() throws Exception {
        try (var c = base(true)) {
            var avant = photographie(c);
            assertThatThrownBy(() -> PlateformeMigration.migrer(c, true, "absent@example.test"))
                    .hasMessageContaining("n'existe pas").hasMessageNotContaining("absent@example.test");
            assertThat(BoutiquesMigration.colonnes(c, "utilisateur")).doesNotContainKey("administrateur_plateforme");
            assertThat(photographie(c)).isEqualTo(avant);
        }
    }

    @Test
    void unCaissierNePeutPasEtrePromuEtSonRoleResteInchange() throws Exception {
        try (var c = base(true)) {
            assertThatThrownBy(() -> PlateformeMigration.migrer(c, true, "caissier@example.test")).hasMessageContaining("ADMIN");
            assertThat(valeur(c, "SELECT role FROM utilisateur WHERE id=3")).isEqualTo("CAISSIER");
            assertThat(BoutiquesMigration.colonnes(c, "boutique")).doesNotContainKey("actif");
        }
    }

    @Test
    void unEmailAmbiguNeDonneJamaisUnDroitAuPremierResultat() throws Exception {
        try (var c = base(true)) {
            sql(c, "INSERT INTO utilisateur(id,email,role,boutique_id) VALUES(4,'admin@example.test','ADMIN',2)");
            assertThatThrownBy(() -> PlateformeMigration.migrer(c, true, ADMIN)).hasMessageContaining("ambigu");
            assertThat(BoutiquesMigration.colonnes(c, "boutique")).doesNotContainKey("actif");
        }
    }

    @Test
    void laCollationNePermetPasDeDesignerUneAdresseDifferenteDeCelleEnregistree() throws Exception {
        try (var c = base(true)) {
            sql(c, "ALTER TABLE utilisateur ALTER COLUMN email VARCHAR_IGNORECASE(255)");
            sql(c, "UPDATE utilisateur SET email='ADMIN@EXAMPLE.TEST' WHERE id=1");
            assertThatThrownBy(() -> PlateformeMigration.migrer(c, true, ADMIN)).hasMessageContaining("exactement");
            assertThat(valeur(c, "SELECT email FROM utilisateur WHERE id=1")).isEqualTo("ADMIN@EXAMPLE.TEST");
            assertThat(BoutiquesMigration.colonnes(c, "boutique")).doesNotContainKey("actif");
        }
    }

    @ParameterizedTest
    @MethodSource("schemasPartiels")
    void lesSchemasPartielsSontRefusesSansAjoutDeColonne(List<PlateformeMigration.Champ> champs) throws Exception {
        try (var c = base(true)) {
            for (var champ : champs) ajouter(c, champ);
            var avant = photographie(c);
            assertThatThrownBy(() -> PlateformeMigration.migrer(c, true, ADMIN)).hasMessageContaining("partiel ou mixte");
            assertThat(photographie(c)).isEqualTo(avant);
            for (var champ : PlateformeMigration.CHAMPS) assertThat(BoutiquesMigration.colonnes(c, champ.table()).containsKey(champ.nom()))
                    .isEqualTo(champs.contains(champ));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALTER TABLE boutique ALTER COLUMN actif DROP NOT NULL",
            "ALTER TABLE boutique ALTER COLUMN actif DROP DEFAULT",
            "ALTER TABLE boutique ALTER COLUMN actif SET DEFAULT FALSE",
            "ALTER TABLE boutique ALTER COLUMN acces_offert SET DEFAULT TRUE",
            "ALTER TABLE utilisateur ALTER COLUMN administrateur_plateforme SET DEFAULT TRUE",
            "ALTER TABLE utilisateur ALTER COLUMN administrateur_plateforme SET DATA TYPE INTEGER"})
    void lesContraintesEtTypesNonConformesNeSontPasReparesAutomatiquement(String modification) throws Exception {
        try (var c = base(true)) {
            PlateformeMigration.migrer(c, true, null);
            sql(c, modification);
            assertThatThrownBy(() -> PlateformeMigration.migrer(c, true, ADMIN)).hasMessageContaining("non conforme");
            assertThat(valeur(c, "SELECT administrateur_plateforme FROM utilisateur WHERE id=1")).isIn("FALSE", "false", "0");
        }
    }

    @Test
    void unDrapeauNumeriqueAutreQueZeroOuUnEstRefuse() throws Exception {
        try (var c = base(true)) {
            PlateformeMigration.migrer(c, true, null);
            sql(c, "ALTER TABLE boutique ALTER COLUMN actif SET DATA TYPE TINYINT");
            sql(c, "ALTER TABLE boutique ALTER COLUMN actif SET DEFAULT 1");
            sql(c, "UPDATE boutique SET actif=2 WHERE id=2");
            assertThatThrownBy(() -> PlateformeMigration.migrer(c, true, ADMIN)).hasMessageContaining("incohérentes");
            assertThat(valeur(c, "SELECT actif FROM boutique WHERE id=2")).isEqualTo("2");
        }
    }

    @Test
    void unDroitPlateformePreexistantSurUnCaissierEstRefuseSansLeRetirer() throws Exception {
        try (var c = base(true)) {
            PlateformeMigration.migrer(c, true, null);
            sql(c, "UPDATE utilisateur SET administrateur_plateforme=TRUE WHERE id=3");
            assertThatThrownBy(() -> PlateformeMigration.migrer(c, true, ADMIN)).hasMessageContaining("sans rôle ADMIN");
            assertThat(valeur(c, "SELECT administrateur_plateforme FROM utilisateur WHERE id=3")).isEqualToIgnoringCase("TRUE");
            assertThat(valeur(c, "SELECT administrateur_plateforme FROM utilisateur WHERE id=1")).isEqualToIgnoringCase("FALSE");
        }
    }

    @Test
    void uneMigrationBoutiquesNonTermineeEstRefusee() throws Exception {
        try (var c = BoutiquesMigrationTests.base(false)) {
            assertThatThrownBy(() -> PlateformeMigration.migrer(c, true, null)).hasMessageContaining("migration des boutiques");
            assertThat(BoutiquesMigration.colonnes(c, "utilisateur")).doesNotContainKey("administrateur_plateforme");
        }
    }

    @Test
    void uneBoutiqueOrphelineEstRefuseeAvantLAttribution() throws Exception {
        try (var c = base(true)) {
            sql(c, "ALTER TABLE utilisateur DROP CONSTRAINT fk_utilisateur_boutique");
            sql(c, "UPDATE utilisateur SET boutique_id=999 WHERE id=2");
            assertThatThrownBy(() -> PlateformeMigration.migrer(c, true, "gerant@example.test")).hasMessageContaining("boutique absente ou invalide");
            assertThat(BoutiquesMigration.colonnes(c, "boutique")).doesNotContainKey("actif");
        }
    }

    @Test
    void uneBaseVideNeRecoitAucunComptePlateforme() throws Exception {
        try (var c = base(false)) {
            PlateformeMigration.migrer(c, true, null);
            assertThat(compter(c, "SELECT COUNT(*) FROM utilisateur")).isZero();
            assertThat(compter(c, "SELECT COUNT(*) FROM boutique")).isEqualTo(1);
            PlateformeMigration.migrer(c, false, null);
        }
    }

    static Stream<List<PlateformeMigration.Champ>> schemasPartiels() {
        return Stream.of(List.of(PlateformeMigration.CHAMPS.get(0)), List.of(PlateformeMigration.CHAMPS.get(1)),
                List.of(PlateformeMigration.CHAMPS.get(2)), List.of(PlateformeMigration.CHAMPS.get(0), PlateformeMigration.CHAMPS.get(1)),
                List.of(PlateformeMigration.CHAMPS.get(0), PlateformeMigration.CHAMPS.get(2)),
                List.of(PlateformeMigration.CHAMPS.get(1), PlateformeMigration.CHAMPS.get(2)));
    }
    static Connection base(boolean remplir) throws SQLException {
        var c = BoutiquesMigrationTests.base(remplir);
        BoutiquesMigration.migrer(c, true);
        if (remplir) {
            sql(c, "INSERT INTO boutique(nom,date_creation) VALUES('Deuxième',CURRENT_TIMESTAMP)");
            sql(c, "INSERT INTO utilisateur(id,nom,email,mot_de_passe,role,boutique_id) VALUES(2,'Gérant','gerant@example.test','empreinte-gerant','ADMIN',2)");
            sql(c, "INSERT INTO utilisateur(id,nom,email,mot_de_passe,role,boutique_id) VALUES(3,'Caissier','caissier@example.test','empreinte-caissier','CAISSIER',1)");
        }
        return c;
    }
    static void ajouter(Connection c, PlateformeMigration.Champ champ) throws SQLException {
        sql(c, "ALTER TABLE " + champ.table() + " ADD COLUMN " + champ.nom() + " BOOLEAN NOT NULL DEFAULT " + (champ.defaut() ? "TRUE" : "FALSE"));
    }
    static void sql(Connection c, String sql) throws SQLException { BoutiquesMigration.executer(c, sql); }
    static long compter(Connection c, String sql) throws SQLException { return BoutiquesMigration.compter(c, sql); }
    static String valeur(Connection c, String sql) throws SQLException { return BoutiquesMigrationTests.valeur(c, sql); }
    static Map<String, List<List<Object>>> photographie(Connection c) throws SQLException {
        Map<String, List<List<Object>>> resultat = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>(BoutiquesMigration.TABLES); tables.add("boutique");
        Set<String> nouvelles = Set.of("actif", "acces_offert", "administrateur_plateforme");
        for (String table : tables) {
            List<List<Object>> lignes = new ArrayList<>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table + " ORDER BY " + (table.equals("operation_creation") ? "jeton" : "id"))) {
                while (r.next()) {
                    List<Object> ligne = new ArrayList<>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
                        if (!nouvelles.contains(r.getMetaData().getColumnName(i).toLowerCase(Locale.ROOT))) ligne.add(r.getObject(i));
                    }
                    lignes.add(ligne);
                }
            }
            resultat.put(table, lignes);
        }
        return resultat;
    }
}
