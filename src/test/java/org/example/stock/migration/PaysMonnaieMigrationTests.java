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

class PaysMonnaieMigrationTests {
    @TempDir Path dossierPrive;
    static Stream<String> scenarios() { return Stream.concat(PaysMonnaieMigrationScenarios.VALIDES.stream(), PaysMonnaieMigrationScenarios.INVALIDES.stream()); }

    @ParameterizedTest @MethodSource("scenarios")
    void lePaysEtLaMonnaieSontAjoutesSansRienReecrire(String scenario) throws Exception {
        try (var c = base(scenario.equals("vide"), scenario.equals("schema_neuf"))) {
            PaysMonnaieMigrationScenarios.preparer(c, scenario);
            PaysMonnaieMigrationScenarios.verifier(c, scenario);
        }
    }

    @Test void uneTransactionOuverteEstRefuseeSansDDL() throws Exception {
        try (var c = base(false, false)) {
            var avant = PaysMonnaieMigrationScenarios.etatComplet(c);
            c.setAutoCommit(false);
            assertThatThrownBy(() -> PaysMonnaieMigration.migrer(c, true)).hasMessageContaining("transaction ouverte");
            c.rollback(); c.setAutoCommit(true);
            assertThat(PaysMonnaieMigrationScenarios.etatComplet(c)).isEqualTo(avant);
        }
    }

    @Test void lesLots5Et6NonMigresSontRefusesSansModification() throws Exception {
        for (var depart : List.<ThrowingConsumer>of(Lot5MigrationScenarios::schema, Lot6MigrationScenarios::schema)) {
            try (var c = DriverManager.getConnection("jdbc:h2:mem:pm_prerequis_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "")) {
                depart.accept(c, false);
                var avant = Lot3MigrationScenarios.etatComplet(c);
                for (boolean appliquer : List.of(false, true))
                    assertThatThrownBy(() -> PaysMonnaieMigration.migrer(c, appliquer)).isInstanceOf(IllegalStateException.class).hasMessageContaining("lots 5 et 6");
                assertThat(Lot3MigrationScenarios.etatComplet(c)).isEqualTo(avant);
            }
        }
    }

    @Test void lesControlesDesMigrationsPrecedentesAcceptentUneBaseMigree() throws Exception {
        try (var c = base(false, false)) {
            PaysMonnaieMigration.migrer(c, true);
            var avant = PaysMonnaieMigrationScenarios.etatComplet(c);
            Lot1Migration.migrer(c, false); Lot2Migration.migrer(c, false); Lot4Migration.migrer(c, false); Lot3Migration.migrer(c, false);
            Lot6Migration.migrer(c, false); Lot5Migration.migrer(c, false);
            assertThat(PaysMonnaieMigrationScenarios.etatComplet(c)).isEqualTo(avant);
        }
    }

    @Test void laMigrationDuneAncienneBaseEtLeSqlNeufProduisentLeMemeSchema() throws Exception {
        try (var migree = base(true, false); var neuve = base(false, true)) {
            PaysMonnaieMigration.migrer(migree, true);
            for (String table : List.of("boutique", "compte_tresorerie_boutique")) {
                var migre = new TreeMap<>(InscriptionEssaiMigration.colonnes(migree, table)); var neuf = new TreeMap<>(InscriptionEssaiMigration.colonnes(neuve, table));
                assertThat(migre).as("colonnes de " + table).isEqualTo(neuf);
            }
        }
    }

    @Test void leSchemaHistoriqueRetireSeulementPaysMonnaie() throws Exception {
        String actuel = Files.readString(Path.of("docs/database-schema.sql"));
        String ancien = SchemaHistorique.sansPaysMonnaie(actuel);
        for (var champ : PaysMonnaieMigration.CHAMPS) {
            assertThat(actuel).contains("    " + champ.nom() + " ");
            assertThat(ancien).doesNotContain("    " + champ.nom() + " ");
        }
        assertThat(ancien).doesNotContain("Pays et monnaie (migration pays-monnaie)").contains("principal_type VARCHAR(32) NULL");
        assertThat(actuel.length() - ancien.length()).isPositive();
        assertThat(SchemaHistorique.sansLot5(actuel)).doesNotContain("monnaie VARCHAR(3)", "operateur VARCHAR(40)");
    }

    @Test void sauvegardeVerifieeEtCibleLocaleSontObligatoires() throws Exception {
        String url = "jdbc:mariadb://localhost:3306/stock_pro";
        assertThatThrownBy(() -> PaysMonnaieMigration.verifierAutorisationApplication(url, null)).hasMessageContaining("PAYS_MONNAIE_BACKUP_DIR");
        assertThatThrownBy(() -> PaysMonnaieMigration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("Sauvegarde SQL complète");
        Path dump = dossierPrive.resolve("stock_pro.sql"); Files.writeString(dump, "-- copie factice privée\n");
        String empreinte = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dump)));
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), empreinte + "  stock_pro.sql\n");
        assertThatCode(() -> PaysMonnaieMigration.verifierAutorisationApplication(url, dossierPrive.toString())).doesNotThrowAnyException();
        for (String cible : List.of("jdbc:mariadb://example.test:3306/stock_pro", "jdbc:mariadb://localhost:13306/stock_pro", "jdbc:mariadb://localhost:3306/autre", "jdbc:mysql://localhost:3306/stock_pro"))
            assertThatThrownBy(() -> PaysMonnaieMigration.verifierAutorisationApplication(cible, dossierPrive.toString())).isInstanceOf(IllegalStateException.class);
    }

    private interface ThrowingConsumer { void accept(Connection c, boolean vide) throws Exception; }

    private static Connection base(boolean vide, boolean neuf) throws Exception {
        var c = DriverManager.getConnection("jdbc:h2:mem:pm_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
        if (neuf) IdentiteBoutiqueMigrationScenarios.schemaNeufPaysMonnaie(c); else PaysMonnaieMigrationScenarios.schema(c, vide);
        return c;
    }
}
