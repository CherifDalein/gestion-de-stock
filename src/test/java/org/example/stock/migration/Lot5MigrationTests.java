package org.example.stock.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class Lot5MigrationTests {
    @TempDir Path dossierPrive;
    static Stream<String> scenarios() { return Stream.concat(Lot5MigrationScenarios.VALIDES.stream(), Lot5MigrationScenarios.INVALIDES.stream()); }

    @ParameterizedTest @MethodSource("scenarios")
    void LesTablesDeLaComptabiliteSontAjouteesSansRienReecrire(String scenario) throws Exception {
        try (var c = base(scenario.equals("vide"), scenario.equals("schema_neuf"))) {
            Lot5MigrationScenarios.preparer(c, scenario);
            Lot5MigrationScenarios.verifier(c, scenario);
        }
    }

    @Test void uneTransactionOuverteEstRefuseeSansDDL() throws Exception {
        try (var c = base(false, false)) {
            var avant = Lot5MigrationScenarios.etatComplet(c);
            c.setAutoCommit(false);
            assertThatThrownBy(() -> Lot5Migration.migrer(c, true)).hasMessageContaining("transaction ouverte");
            c.rollback(); c.setAutoCommit(true);
            assertThat(Lot5MigrationScenarios.etatComplet(c)).isEqualTo(avant);
        }
    }

    @Test void leLot6EtLesLotsPrecedentsNonMigresSontRefusesSansModification() throws Exception {
        // Lots 1 à 4 faits mais pas le lot 6 ; lot 3 seulement ; lot 4 seulement : refus clair, aucune écriture.
        for (var depart : List.<ThrowingConsumer>of(Lot6MigrationScenarios::schema, Lot3MigrationScenarios::schema, Lot4MigrationScenarios::schema)) {
            try (var c = DriverManager.getConnection("jdbc:h2:mem:lot5_prerequis_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "")) {
                depart.accept(c, false);
                var avant = Lot5MigrationScenarios.etatComplet(c);
                for (boolean appliquer : List.of(false, true))
                    assertThatThrownBy(() -> Lot5Migration.migrer(c, appliquer)).isInstanceOf(IllegalStateException.class).hasMessageContaining("lot 6");
                assertThat(Lot5MigrationScenarios.etatComplet(c)).isEqualTo(avant);
            }
        }
    }

    @Test void laMigrationDuneAncienneBaseEtLeSqlNeufProduisentLeMemeSchema() throws Exception {
        try (var migree = base(true, false); var neuve = base(false, true)) {
            Lot5Migration.migrer(migree, true);
            for (String table : Lot5Migration.TABLES) {
                var migre = new TreeMap<>(InscriptionEssaiMigration.colonnes(migree, table)); var neuf = new TreeMap<>(InscriptionEssaiMigration.colonnes(neuve, table));
                assertThat(migre).as("colonnes de " + table).isEqualTo(neuf);
                assertThat(new TreeMap<>(BoutiquesMigration.indexesUniques(migree, table)).values()).as("unicités de " + table).containsExactlyInAnyOrderElementsOf(BoutiquesMigration.indexesUniques(neuve, table).values());
            }
            assertThat(Lot5MigrationScenarios.etatComplet(migree).get(0)).isNotNull();
        }
    }

    @Test void leSchemaHistoriqueRetireSeulementLeLot5() throws Exception {
        String actuel = Files.readString(Path.of("docs/database-schema.sql"));
        String ancien = SchemaHistorique.sansLot5(actuel);
        assertThat(actuel).contains("sous_compte_boutique", "correspondance_compta", "uk_sous_compte_boutique_numero", "uk_correspondance_compta_cle", "Lot 5a (comptabilité)");
        assertThat(ancien).doesNotContain("sous_compte_boutique", "correspondance_compta", "Lot 5a (comptabilité)").contains("supprime_le", "uk_categorie_boutique_nom_cle");
        assertThat(actuel.length() - ancien.length()).isPositive();
        // Les schémas des lots antérieurs partent de celui du lot 6, donc sans la comptabilité.
        for (String historique : List.of(SchemaHistorique.sansLot6(actuel), SchemaHistorique.sansLot3(actuel), SchemaHistorique.sansLot1(actuel)))
            assertThat(historique).doesNotContain("sous_compte_boutique", "correspondance_compta");
    }

    @Test void sauvegardeVerifieeEtCibleLocaleSontObligatoires() throws Exception {
        String url = "jdbc:mariadb://localhost:3306/stock_pro";
        assertThatThrownBy(() -> Lot5Migration.verifierAutorisationApplication(url, null)).hasMessageContaining("LOT5_BACKUP_DIR");
        assertThatThrownBy(() -> Lot5Migration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("Sauvegarde SQL complète");
        Path dump = dossierPrive.resolve("stock_pro.sql"); Files.writeString(dump, "-- copie factice privée\n");
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), "a".repeat(64) + "  stock_pro.sql\n");
        assertThatThrownBy(() -> Lot5Migration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("ne correspond pas");
        String empreinte = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dump)));
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), empreinte + "  stock_pro.sql\n");
        assertThatCode(() -> Lot5Migration.verifierAutorisationApplication(url, dossierPrive.toString())).doesNotThrowAnyException();
        for (String cible : List.of("jdbc:mariadb://example.test:3306/stock_pro", "jdbc:mariadb://localhost:13306/stock_pro", "jdbc:mariadb://localhost:3306/autre", "jdbc:mysql://localhost:3306/stock_pro"))
            assertThatThrownBy(() -> Lot5Migration.verifierAutorisationApplication(cible, dossierPrive.toString())).isInstanceOf(IllegalStateException.class);
    }

    /** Les parents des sous-comptes de trésorerie et les numéros par défaut de la correspondance existent tous au plan officiel. */
    @Test void lesParentsDeTresorerieSontDesComptesOfficiels() {
        var plan = org.example.stock.compta.PlanOfficiel.charger();
        for (String parent : Lot5Migration.PARENTS.values()) assertThat(plan.existe(parent)).as(parent).isTrue();
        assertThat(Lot5Migration.PARENTS).containsOnlyKeys(java.util.Arrays.stream(org.example.stock.enums.TypeCompteTresorerie.values()).map(Enum::name).toArray(String[]::new));
        for (var type : org.example.stock.enums.TypeCompteTresorerie.values())
            assertThat(org.example.stock.compta.PlanComptesService.parentTresorerie(type)).isEqualTo(Lot5Migration.PARENTS.get(type.name()));
    }

    private interface ThrowingConsumer { void accept(Connection c, boolean vide) throws Exception; }

    private static Connection base(boolean vide, boolean neuf) throws Exception {
        var c = DriverManager.getConnection("jdbc:h2:mem:lot5_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
        if (neuf) IdentiteBoutiqueMigrationScenarios.schemaNeufLot5(c); else Lot5MigrationScenarios.schema(c, vide);
        return c;
    }
}
