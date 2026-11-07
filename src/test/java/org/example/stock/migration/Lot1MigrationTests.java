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

class Lot1MigrationTests {
    @TempDir Path dossierPrive;
    static Stream<String> scenarios() { return Stream.concat(Lot1MigrationScenarios.VALIDES.stream(), Lot1MigrationScenarios.INVALIDES.stream()); }

    @ParameterizedTest @MethodSource("scenarios")
    void lesAjoutsReprisesEtRefusPreserventStocksMontantsEtHistoriques(String scenario) throws Exception {
        try (var c = base(scenario.equals("vide"), scenario.equals("schema_neuf"))) {
            Lot1MigrationScenarios.preparer(c, scenario);
            Lot1MigrationScenarios.verifier(c, scenario);
        }
    }

    @Test void uneTransactionOuverteEstRefuseeSansDDL() throws Exception {
        try (var c = base(false, false)) {
            var avant = Lot1MigrationScenarios.etatComplet(c);
            c.setAutoCommit(false);
            assertThatThrownBy(() -> Lot1Migration.migrer(c, true)).hasMessageContaining("transaction ouverte");
            c.rollback(); c.setAutoCommit(true);
            assertThat(Lot1MigrationScenarios.etatComplet(c)).isEqualTo(avant);
        }
    }

    @Test void uneIdentiteBoutiqueNonMigreeEstRefuseeSansModification() throws Exception {
        try (var c = base(false, false)) {
            Lot1MigrationScenarios.sql(c, "DROP TABLE logo_boutique");
            var avant = Lot1MigrationScenarios.etatComplet(c);
            assertThatThrownBy(() -> Lot1Migration.migrer(c, true)).hasMessageContaining("identité boutique");
            assertThat(Lot1MigrationScenarios.etatComplet(c)).isEqualTo(avant);
        }
    }

    @Test void lesControlesDesMigrationsPrecedentesAcceptentUneBaseMigree() throws Exception {
        try (var c = base(false, false)) {
            Lot1Migration.migrer(c, true);
            var avant = Lot1MigrationScenarios.etatComplet(c);
            InventaireMigration.migrer(c, false); TarificationMigration.migrer(c, false); InitialisationBoutiqueMigration.migrer(c, false);
            TresorerieMigration.migrer(c, false); IdentiteBoutiqueMigration.migrer(c, false);
            IdentiteBoutiqueMigration.migrer(c, true);
            assertThat(Lot1MigrationScenarios.etatComplet(c)).isEqualTo(avant);
        }
    }

    @Test void leSchemaHistoriqueRetireSeulementLeLot1() throws Exception {
        String actuel = Files.readString(Path.of("docs/database-schema.sql"));
        String ancien = SchemaHistorique.sansLot1(actuel);
        for (var champ : Lot1Migration.CHAMPS) {
            String bloc = ancien.substring(ancien.indexOf("CREATE TABLE IF NOT EXISTS " + champ.table() + " ("));
            assertThat(bloc.substring(0, bloc.indexOf(';'))).doesNotContain(" " + champ.nom() + " ");
            String blocActuel = actuel.substring(actuel.indexOf("CREATE TABLE IF NOT EXISTS " + champ.table() + " ("));
            assertThat(blocActuel.substring(0, blocActuel.indexOf(';'))).contains("    " + champ.nom() + " ");
        }
        assertThat(ancien).doesNotContain("uk_vente_boutique_numero", "uk_achat_boutique_numero")
                .contains("cout_unitaire DECIMAL(17,2) NULL,\n    prix_vente DECIMAL(17,2) NULL");
        assertThat(actuel.length() - ancien.length()).isPositive();
    }

    @Test void lesNumerosMigresGardentSixChiffresAuMinimum() {
        assertThat(Lot1Migration.numero("vente", 7)).isEqualTo("FV-000007");
        assertThat(Lot1Migration.numero("achat", 123456)).isEqualTo("FA-123456");
        assertThat(Lot1Migration.numero("vente", 1234567)).isEqualTo("FV-1234567");
    }

    @Test void sauvegardeVerifieeEtCibleLocaleSontObligatoires() throws Exception {
        String url = "jdbc:mariadb://localhost:3306/stock_pro";
        assertThatThrownBy(() -> Lot1Migration.verifierAutorisationApplication(url, null)).hasMessageContaining("LOT1_BACKUP_DIR");
        assertThatThrownBy(() -> Lot1Migration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("Sauvegarde SQL complète");
        Path dump = dossierPrive.resolve("stock_pro.sql"); Files.writeString(dump, "-- copie factice privée\n");
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), "a".repeat(64) + "  stock_pro.sql\n");
        assertThatThrownBy(() -> Lot1Migration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("ne correspond pas");
        String empreinte = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dump)));
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), empreinte + "  stock_pro.sql\n");
        assertThatCode(() -> Lot1Migration.verifierAutorisationApplication(url, dossierPrive.toString())).doesNotThrowAnyException();
        for (String cible : List.of("jdbc:mariadb://example.test:3306/stock_pro", "jdbc:mariadb://localhost:13306/stock_pro", "jdbc:mariadb://localhost:3306/autre", "jdbc:mysql://localhost:3306/stock_pro"))
            assertThatThrownBy(() -> Lot1Migration.verifierAutorisationApplication(cible, dossierPrive.toString())).isInstanceOf(IllegalStateException.class);
    }

    private static Connection base(boolean vide, boolean neuf) throws Exception {
        var c = DriverManager.getConnection("jdbc:h2:mem:lot1_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
        if (neuf) IdentiteBoutiqueMigrationScenarios.schemaNeuf(c); else Lot1MigrationScenarios.schema(c, vide);
        return c;
    }
}
