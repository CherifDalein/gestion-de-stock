package org.example.stock.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.*;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class ParametresBoutiqueMigrationTests {
    @Test
    void leControleNeCreePasDeColonneEtConserveLesDouzeTables() throws Exception {
        try (var c = base(true)) {
            var avant = ParametresBoutiqueMigration.photographie(c);
            ParametresBoutiqueMigration.migrer(c, false);
            assertThat(BoutiquesMigration.colonnes(c, "boutique")).doesNotContainKeys("adresse", "telephone", "email");
            assertThat(ParametresBoutiqueMigration.photographie(c)).isEqualTo(avant).hasSize(12);
        }
    }

    @Test
    void laMigrationAjouteSeulementLesCoordonneesNullesEtConserveToutesLesAnciennesColonnes() throws Exception {
        try (var c = base(true)) {
            var avant = ParametresBoutiqueMigration.photographie(c);
            ParametresBoutiqueMigration.migrer(c, true);
            assertThat(ParametresBoutiqueMigration.photographie(c)).isEqualTo(avant);
            assertThat(compter(c, "SELECT COUNT(*) FROM boutique WHERE adresse IS NULL AND telephone IS NULL AND email IS NULL")).isEqualTo(2);
            for (var champ : ParametresBoutiqueMigration.CHAMPS) {
                assertThat(BoutiquesMigration.colonnes(c, "boutique").get(champ.nom())).isEqualTo(DatabaseMetaData.columnNullable);
            }
            assertThat(valeur(c, "SELECT nom FROM boutique WHERE id=2")).isEqualTo("Deuxième");
            assertThat(valeur(c, "SELECT actif FROM boutique WHERE id=2")).isEqualToIgnoringCase("FALSE");
            assertThat(valeur(c, "SELECT acces_offert FROM boutique WHERE id=2")).isEqualToIgnoringCase("TRUE");
            assertThat(valeur(c, "SELECT actif FROM utilisateur WHERE id=3")).isEqualToIgnoringCase("FALSE");
            assertThat(valeur(c, "SELECT mot_de_passe FROM utilisateur WHERE id=1")).isEqualTo("empreinte-inchangee");
            assertThat(valeur(c, "SELECT role FROM utilisateur WHERE id=1")).isEqualTo("ADMIN");
            assertThat(valeur(c, "SELECT administrateur_plateforme FROM utilisateur WHERE id=1")).isEqualToIgnoringCase("TRUE");
        }
    }

    @Test
    void lesRelancesConserventLesCoordonneesSansReinitialiserLesEtatsNiLesComptes() throws Exception {
        try (var c = base(true)) {
            ParametresBoutiqueMigration.migrer(c, true);
            remplirCoordonnees(c);
            var avant = ParametresBoutiqueMigration.photographie(c);
            var contacts = coordonnees(c);
            ParametresBoutiqueMigration.migrer(c, false);
            ParametresBoutiqueMigration.migrer(c, true);
            ParametresBoutiqueMigration.migrer(c, true);
            assertThat(ParametresBoutiqueMigration.photographie(c)).isEqualTo(avant);
            assertThat(coordonnees(c)).isEqualTo(contacts);
            assertThat(valeur(c, "SELECT actif FROM utilisateur WHERE id=3")).isEqualToIgnoringCase("FALSE");
        }
    }

    @Test
    void unSchemaDejaConformeAccepteLesCoordonneesExistantesSansLesReecrire() throws Exception {
        try (var c = base(true)) {
            ajouterColonnes(c);
            remplirCoordonnees(c);
            var avant = ParametresBoutiqueMigration.photographie(c);
            var contacts = coordonnees(c);
            ParametresBoutiqueMigration.migrer(c, true);
            assertThat(ParametresBoutiqueMigration.photographie(c)).isEqualTo(avant);
            assertThat(coordonnees(c)).isEqualTo(contacts);
        }
    }

    @Test
    void lesNouvellesBoutiquesPeuventOmettreLesCoordonneesEtLesVider() throws Exception {
        try (var c = base(true)) {
            ParametresBoutiqueMigration.migrer(c, true);
            sql(c, "INSERT INTO boutique(nom,date_creation) VALUES('Nouvelle',CURRENT_TIMESTAMP)");
            assertThat(compter(c, "SELECT COUNT(*) FROM boutique WHERE id=3 AND adresse IS NULL AND telephone IS NULL AND email IS NULL AND actif=TRUE AND acces_offert=FALSE")).isEqualTo(1);
            remplirCoordonnees(c);
            sql(c, "UPDATE boutique SET adresse=NULL,telephone=NULL,email=NULL");
            ParametresBoutiqueMigration.migrer(c, false);
            assertThat(compter(c, "SELECT COUNT(*) FROM boutique WHERE adresse IS NULL AND telephone IS NULL AND email IS NULL")).isEqualTo(3);
        }
    }

    @ParameterizedTest
    @MethodSource("schemasPartiels")
    void unSchemaPartielEstRefuseSansCompleterLesColonnes(List<ParametresBoutiqueMigration.Champ> champs) throws Exception {
        try (var c = base(true)) {
            for (var champ : champs) sql(c, "ALTER TABLE boutique ADD COLUMN " + champ.nom() + " VARCHAR(" + champ.taille() + ") NULL");
            var avant = ParametresBoutiqueMigration.photographie(c);
            assertThatThrownBy(() -> ParametresBoutiqueMigration.migrer(c, false)).hasMessageContaining("partiel ou mixte");
            assertThatThrownBy(() -> ParametresBoutiqueMigration.migrer(c, true)).hasMessageContaining("partiel ou mixte");
            assertThat(ParametresBoutiqueMigration.photographie(c)).isEqualTo(avant);
            for (var champ : ParametresBoutiqueMigration.CHAMPS) assertThat(BoutiquesMigration.colonnes(c, "boutique").containsKey(champ.nom()))
                    .isEqualTo(champs.contains(champ));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALTER TABLE boutique ALTER COLUMN adresse VARCHAR(499)",
            "ALTER TABLE boutique ALTER COLUMN adresse VARCHAR(501)",
            "ALTER TABLE boutique ALTER COLUMN telephone VARCHAR(51)",
            "ALTER TABLE boutique ALTER COLUMN email VARCHAR(255)",
            "ALTER TABLE boutique ALTER COLUMN adresse SET NOT NULL",
            "ALTER TABLE boutique ALTER COLUMN telephone SET NOT NULL",
            "ALTER TABLE boutique ALTER COLUMN email SET NOT NULL",
            "ALTER TABLE boutique ALTER COLUMN adresse CLOB",
            "ALTER TABLE boutique ALTER COLUMN telephone CHAR(50)",
            "ALTER TABLE boutique ALTER COLUMN email SET DEFAULT ''",
            "ALTER TABLE boutique ALTER COLUMN email SET DEFAULT 'NULL'"})
    void lesTypesTaillesNullabilitesEtDefautsIncorrectsSontRefusesSansReparation(String modification) throws Exception {
        try (var c = base(true)) {
            ajouterColonnes(c);
            remplirCoordonnees(c);
            sql(c, modification);
            var avant = ParametresBoutiqueMigration.photographie(c);
            var contacts = coordonnees(c);
            assertThatThrownBy(() -> ParametresBoutiqueMigration.migrer(c, false)).hasMessageContaining("non conforme");
            assertThatThrownBy(() -> ParametresBoutiqueMigration.migrer(c, true)).hasMessageContaining("non conforme");
            assertThat(ParametresBoutiqueMigration.photographie(c)).isEqualTo(avant);
            assertThat(coordonnees(c)).isEqualTo(contacts);
        }
    }

    @Test
    void laMigrationDesAccesIndividuelsDoitEtreTerminee() throws Exception {
        try (var c = AccesUtilisateurMigrationTests.base(true)) {
            assertThatThrownBy(() -> ParametresBoutiqueMigration.migrer(c, true)).hasMessageContaining("migration des accès utilisateurs");
            assertThat(BoutiquesMigration.colonnes(c, "boutique")).doesNotContainKeys("adresse", "telephone", "email");
            assertThat(BoutiquesMigration.colonnes(c, "utilisateur")).doesNotContainKey("actif");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALTER TABLE utilisateur ALTER COLUMN actif DROP DEFAULT",
            "ALTER TABLE boutique ALTER COLUMN acces_offert SET DEFAULT TRUE",
            "UPDATE utilisateur SET administrateur_plateforme=TRUE WHERE id=3",
            "UPDATE produit SET boutique_id=2 WHERE id=1"})
    void lesContraintesEtRelationsAnterieuresSontRecontroleesAvantTouteEcriture(String modification) throws Exception {
        try (var c = base(true)) {
            sql(c, modification);
            var avant = ParametresBoutiqueMigration.photographie(c);
            assertThatThrownBy(() -> ParametresBoutiqueMigration.migrer(c, true)).isInstanceOf(IllegalStateException.class);
            assertThat(BoutiquesMigration.colonnes(c, "boutique")).doesNotContainKeys("adresse", "telephone", "email");
            assertThat(ParametresBoutiqueMigration.photographie(c)).isEqualTo(avant);
        }
    }

    @Test
    void uneBaseVideNeRecoitPasDeCoordonneesOuDeCompteInventes() throws Exception {
        try (var c = base(false)) {
            var avant = ParametresBoutiqueMigration.photographie(c);
            ParametresBoutiqueMigration.migrer(c, true);
            ParametresBoutiqueMigration.migrer(c, false);
            assertThat(ParametresBoutiqueMigration.photographie(c)).isEqualTo(avant);
            assertThat(compter(c, "SELECT COUNT(*) FROM utilisateur")).isZero();
            assertThat(compter(c, "SELECT COUNT(*) FROM boutique WHERE adresse IS NULL AND telephone IS NULL AND email IS NULL")).isEqualTo(1);
        }
    }

    @Test
    void lesNomsDeTablesEtColonnesEnMajusculesSontAcceptes() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:h2:mem:stock_parametres_boutique_test_" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "")) {
            BoutiquesMigrationTests.creerSchema(c, true);
            BoutiquesMigration.migrer(c, true);
            PlateformeMigration.migrer(c, true, "admin@example.test");
            AccesUtilisateurMigration.migrer(c, true);
            var avant = ParametresBoutiqueMigration.photographie(c);
            ParametresBoutiqueMigration.migrer(c, true);
            ParametresBoutiqueMigration.migrer(c, false);
            assertThat(ParametresBoutiqueMigration.photographie(c)).isEqualTo(avant);
        }
    }

    @Test
    void uneTransactionOuverteEstRefuseeSansAjout() throws Exception {
        try (var c = base(true)) {
            c.setAutoCommit(false);
            assertThatThrownBy(() -> ParametresBoutiqueMigration.migrer(c, true)).hasMessageContaining("transaction ouverte");
            c.rollback(); c.setAutoCommit(true);
            assertThat(BoutiquesMigration.colonnes(c, "boutique")).doesNotContainKeys("adresse", "telephone", "email");
        }
    }

    static Stream<List<ParametresBoutiqueMigration.Champ>> schemasPartiels() {
        var champs = ParametresBoutiqueMigration.CHAMPS;
        return Stream.of(List.of(champs.get(0)), List.of(champs.get(1)), List.of(champs.get(2)),
                List.of(champs.get(0), champs.get(1)), List.of(champs.get(0), champs.get(2)), List.of(champs.get(1), champs.get(2)));
    }
    static Connection base(boolean remplir) throws SQLException {
        var c = AccesUtilisateurMigrationTests.base(remplir);
        AccesUtilisateurMigration.migrer(c, true);
        if (remplir) sql(c, "UPDATE utilisateur SET actif=FALSE WHERE id=3");
        return c;
    }
    static void ajouterColonnes(Connection c) throws SQLException {
        for (var champ : ParametresBoutiqueMigration.CHAMPS) sql(c, "ALTER TABLE boutique ADD COLUMN " + champ.nom() + " VARCHAR(" + champ.taille() + ") NULL");
    }
    static void remplirCoordonnees(Connection c) throws SQLException {
        sql(c, "UPDATE boutique SET adresse='Kaloum, Conakry',telephone='+224 600 00 00 00',email='contact@example.test'");
    }
    static List<List<String>> coordonnees(Connection c) throws SQLException {
        List<List<String>> lignes = new ArrayList<>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT adresse,telephone,email FROM boutique ORDER BY id")) {
            while (r.next()) lignes.add(Arrays.asList(r.getString(1), r.getString(2), r.getString(3)));
        }
        return lignes;
    }
    static void sql(Connection c, String sql) throws SQLException { BoutiquesMigration.executer(c, sql); }
    static long compter(Connection c, String sql) throws SQLException { return BoutiquesMigration.compter(c, sql); }
    static String valeur(Connection c, String sql) throws SQLException { return BoutiquesMigrationTests.valeur(c, sql); }
}
