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

class IdentiteBoutiqueMigrationTests {
    @TempDir Path dossierPrive;
    static Stream<String> scenarios() { return Stream.concat(IdentiteBoutiqueMigrationScenarios.VALIDES.stream(), IdentiteBoutiqueMigrationScenarios.INVALIDES.stream()); }

    @ParameterizedTest @MethodSource("scenarios")
    void lesAjoutsReprisesEtRefusPreserventToutesLesDonneesEtLesOctetsDuLogo(String scenario) throws Exception {
        try (var c = base(scenario.equals("vide"), scenario.equals("schema_neuf"))) {
            IdentiteBoutiqueMigrationScenarios.preparer(c, scenario);
            IdentiteBoutiqueMigrationScenarios.verifier(c, scenario);
        }
    }

    @Test void uneTransactionOuverteEstRefuseeSansDDL() throws Exception {
        try (var c = base(false, false)) {
            var avant = IdentiteBoutiqueMigration.photographie(c); var tables = BoutiquesMigration.tables(c);
            c.setAutoCommit(false);
            assertThatThrownBy(() -> IdentiteBoutiqueMigration.migrer(c, true)).hasMessageContaining("transaction ouverte");
            c.rollback(); c.setAutoCommit(true);
            assertThat(IdentiteBoutiqueMigration.photographie(c)).isEqualTo(avant);
            assertThat(BoutiquesMigration.tables(c)).isEqualTo(tables);
        }
    }

    @Test void uneTresoreriePartiellementMigreeEstRefuseeSansModification() throws Exception {
        try (var c = base(false, false)) {
            IdentiteBoutiqueMigrationScenarios.sql(c, "DELETE FROM compte_tresorerie_boutique WHERE boutique_id=1 AND compte='BANQUE'");
            var avant = IdentiteBoutiqueMigration.photographie(c); var tables = BoutiquesMigration.tables(c);
            assertThatThrownBy(() -> IdentiteBoutiqueMigration.migrer(c, true)).hasMessageContaining("migration trésorerie");
            assertThat(IdentiteBoutiqueMigration.photographie(c)).isEqualTo(avant);
            assertThat(BoutiquesMigration.tables(c)).isEqualTo(tables);
        }
    }

    @Test void lesPhotographiesHistoriquesRestentCompatiblesAvecUnLogoBinaire() throws Exception {
        try (var c = base(false, false)) {
            IdentiteBoutiqueMigration.migrer(c, true);
            IdentiteBoutiqueMigrationScenarios.insererLogo(c, IdentiteBoutiqueMigrationScenarios.png(2), UUID.randomUUID().toString());
            assertThat(IdentiteBoutiqueMigration.logos(c)).isEqualTo(IdentiteBoutiqueMigration.logos(c));
            assertThat(InventaireMigration.photographie(c)).isEqualTo(InventaireMigration.photographie(c));
            assertThat(TarificationMigration.photographie(c)).isEqualTo(TarificationMigration.photographie(c));
            assertThat(InitialisationBoutiqueMigration.photographie(c)).isEqualTo(InitialisationBoutiqueMigration.photographie(c));
            assertThat(TresorerieMigration.photographie(c)).isEqualTo(TresorerieMigration.photographie(c));
            InventaireMigration.migrer(c, false); TarificationMigration.migrer(c, false);
            InitialisationBoutiqueMigration.migrer(c, false); TresorerieMigration.migrer(c, false);
            assertThat(IdentiteBoutiqueMigration.logos(c)).hasSize(1);
        }
    }

    @Test void sauvegardeVerifieeEtCibleLocaleSontObligatoires() throws Exception {
        String url = "jdbc:mariadb://localhost:3306/stock_pro";
        assertThatThrownBy(() -> IdentiteBoutiqueMigration.verifierAutorisationApplication(url, null)).hasMessageContaining("IDENTITE_BOUTIQUE_BACKUP_DIR");
        assertThatThrownBy(() -> IdentiteBoutiqueMigration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("Sauvegarde SQL complète");
        Path dump = dossierPrive.resolve("stock_pro.sql"); Files.writeString(dump, "-- copie factice privée\n");
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), "a".repeat(64) + "  stock_pro.sql\n");
        assertThatThrownBy(() -> IdentiteBoutiqueMigration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("ne correspond pas");
        String empreinte = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dump)));
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), empreinte + "  stock_pro.sql\n");
        assertThatCode(() -> IdentiteBoutiqueMigration.verifierAutorisationApplication(url, dossierPrive.toString())).doesNotThrowAnyException();
        for (String cible : List.of("jdbc:mariadb://example.test:3306/stock_pro", "jdbc:mariadb://localhost:13306/stock_pro", "jdbc:mariadb://localhost:3306/autre", "jdbc:mysql://localhost:3306/stock_pro"))
            assertThatThrownBy(() -> IdentiteBoutiqueMigration.verifierAutorisationApplication(cible, dossierPrive.toString())).isInstanceOf(IllegalStateException.class);
    }

    private static Connection base(boolean vide, boolean neuf) throws Exception {
        var c = DriverManager.getConnection("jdbc:h2:mem:identite_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
        if (neuf) IdentiteBoutiqueMigrationScenarios.schemaNeuf(c); else IdentiteBoutiqueMigrationScenarios.schema(c, vide);
        return c;
    }
}
