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

class Lot4MigrationTests {
    @TempDir Path dossierPrive;
    static Stream<String> scenarios() { return Stream.concat(Lot4MigrationScenarios.VALIDES.stream(), Lot4MigrationScenarios.INVALIDES.stream()); }

    @ParameterizedTest @MethodSource("scenarios")
    void lesReglagesParDefautSontAjoutesSansRienReecrire(String scenario) throws Exception {
        try (var c = base(scenario.equals("vide"), scenario.equals("schema_neuf"))) {
            Lot4MigrationScenarios.preparer(c, scenario);
            Lot4MigrationScenarios.verifier(c, scenario);
        }
    }

    @Test void uneTransactionOuverteEstRefuseeSansDDL() throws Exception {
        try (var c = base(false, false)) {
            var avant = Lot4MigrationScenarios.etatComplet(c);
            c.setAutoCommit(false);
            assertThatThrownBy(() -> Lot4Migration.migrer(c, true)).hasMessageContaining("transaction ouverte");
            c.rollback(); c.setAutoCommit(true);
            assertThat(Lot4MigrationScenarios.etatComplet(c)).isEqualTo(avant);
        }
    }

    @Test void leLot2NonMigreEstRefuseSansModification() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:h2:mem:lot4_sans_lot2_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "")) {
            Lot1MigrationScenarios.schema(c, false);
            var avant = Lot4Migration.photographie(c); var tables = BoutiquesMigration.tables(c);
            for (boolean appliquer : List.of(false, true))
                assertThatThrownBy(() -> Lot4Migration.migrer(c, appliquer)).isInstanceOf(IllegalStateException.class).hasMessageContaining("lot 2 doit être terminée");
            assertThat(Lot4Migration.photographie(c)).isEqualTo(avant);
            assertThat(BoutiquesMigration.tables(c)).isEqualTo(tables);
            // Un lot 2 commencé mais inachevé est refusé de la même façon.
            c.setReadOnly(false); Lot2Migration.creerTables(c); Lot2Migration.ajouterColonnes(c);
            assertThatThrownBy(() -> Lot4Migration.migrer(c, true)).hasMessageContaining("lot 2 doit être terminée");
        }
    }

    @Test void lesControlesDesMigrationsPrecedentesAcceptentUneBaseMigree() throws Exception {
        try (var c = base(false, false)) {
            Lot4Migration.migrer(c, true);
            var avant = Lot4MigrationScenarios.etatComplet(c);
            InventaireMigration.migrer(c, false); TarificationMigration.migrer(c, false); InitialisationBoutiqueMigration.migrer(c, false);
            TresorerieMigration.migrer(c, false); IdentiteBoutiqueMigration.migrer(c, false);
            Lot1Migration.migrer(c, false); Lot2Migration.migrer(c, false); Lot2Migration.migrer(c, true);
            assertThat(Lot4MigrationScenarios.etatComplet(c)).isEqualTo(avant);
        }
    }

    @Test void laMigrationDuneAncienneBaseEtLeSqlNeufProduisentLeMemeSchema() throws Exception {
        try (var migree = base(true, false); var neuve = base(false, true)) {
            Lot4Migration.migrer(migree, true);
            var schemaMigre = Lot4MigrationScenarios.etatComplet(migree); var schemaNeuf = Lot4MigrationScenarios.etatComplet(neuve);
            assertThat(schemaMigre.get(2)).as("colonnes").isEqualTo(schemaNeuf.get(2));
        }
    }

    @Test void leSchemaHistoriqueRetireSeulementLeLot4() throws Exception {
        String actuel = Files.readString(Path.of("docs/database-schema.sql"));
        String ancien = SchemaHistorique.sansLot4(actuel);
        for (var champ : Lot4Migration.CHAMPS) {
            assertThat(actuel).contains("    " + champ.nom() + " ");
            assertThat(ancien).doesNotContain(champ.nom());
        }
        // Le lot 2 reste présent dans le schéma du lot 3 (absent) : seul le lot 4 disparaît.
        assertThat(ancien).contains("prochain_numero_retour_achat BIGINT NOT NULL DEFAULT 1,\n    PRIMARY KEY (id)");
        assertThat(actuel.length() - ancien.length()).isPositive();
        // Les schémas des lots antérieurs se déduisent de celui du lot 4.
        assertThat(SchemaHistorique.sansLot2(actuel)).doesNotContain("format_recu", "proposer_impression_recu", "devis");
        assertThat(SchemaHistorique.sansLot1(actuel)).doesNotContain("format_recu", "proposer_impression_recu");
    }

    @Test void sauvegardeVerifieeEtCibleLocaleSontObligatoires() throws Exception {
        String url = "jdbc:mariadb://localhost:3306/stock_pro";
        assertThatThrownBy(() -> Lot4Migration.verifierAutorisationApplication(url, null)).hasMessageContaining("LOT4_BACKUP_DIR");
        assertThatThrownBy(() -> Lot4Migration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("Sauvegarde SQL complète");
        Path dump = dossierPrive.resolve("stock_pro.sql"); Files.writeString(dump, "-- copie factice privée\n");
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), "a".repeat(64) + "  stock_pro.sql\n");
        assertThatThrownBy(() -> Lot4Migration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("ne correspond pas");
        String empreinte = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dump)));
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), empreinte + "  stock_pro.sql\n");
        assertThatCode(() -> Lot4Migration.verifierAutorisationApplication(url, dossierPrive.toString())).doesNotThrowAnyException();
        for (String cible : List.of("jdbc:mariadb://example.test:3306/stock_pro", "jdbc:mariadb://localhost:13306/stock_pro", "jdbc:mariadb://localhost:3306/autre", "jdbc:mysql://localhost:3306/stock_pro"))
            assertThatThrownBy(() -> Lot4Migration.verifierAutorisationApplication(cible, dossierPrive.toString())).isInstanceOf(IllegalStateException.class);
    }

    private static Connection base(boolean vide, boolean neuf) throws Exception {
        var c = DriverManager.getConnection("jdbc:h2:mem:lot4_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
        if (neuf) IdentiteBoutiqueMigrationScenarios.schemaNeuf(c); else Lot4MigrationScenarios.schema(c, vide);
        return c;
    }
}
