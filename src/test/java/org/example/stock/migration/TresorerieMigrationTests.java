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

class TresorerieMigrationTests {
    @TempDir Path dossierPrive;
    static Stream<String> scenarios() { return Stream.concat(TresorerieMigrationScenarios.VALIDES.stream(), TresorerieMigrationScenarios.INVALIDES.stream()); }

    @ParameterizedTest @MethodSource("scenarios")
    void lesAjoutsEtReprisesConserventLesSoldesDocumentsEtComptesHistoriques(String scenario) throws Exception {
        try (var c = base(scenario.equals("vide"))) {
            TresorerieMigrationScenarios.preparer(c, scenario);
            TresorerieMigrationScenarios.verifier(c, scenario);
        }
    }

    @Test void uneTransactionOuverteEstRefuseeSansModification() throws Exception {
        try (var c = base(false)) {
            var avant = TresorerieMigration.photographie(c); var tables = BoutiquesMigration.tables(c);
            c.setAutoCommit(false);
            assertThatThrownBy(() -> TresorerieMigration.migrer(c, true)).hasMessageContaining("transaction ouverte");
            c.rollback(); c.setAutoCommit(true);
            assertThat(TresorerieMigration.photographie(c)).isEqualTo(avant);
            assertThat(BoutiquesMigration.tables(c)).isEqualTo(tables);
        }
    }

    @Test void lesMigrationsPrecedentesSontRequisesSansFermerUneBoutique() throws Exception {
        try (var c = base(false)) {
            TresorerieMigrationScenarios.sql(c, "ALTER TABLE boutique DROP COLUMN initialisation_terminee");
            var avant = TresorerieMigration.photographie(c);
            assertThatThrownBy(() -> TresorerieMigration.migrer(c, true)).hasMessageContaining("migration du démarrage");
            assertThat(TresorerieMigration.photographie(c)).isEqualTo(avant);
            assertThat(BoutiquesMigration.tables(c)).doesNotContainKey(TresorerieMigration.COMPTES);
        }
    }

    @Test void sauvegardeVerifieeEtCibleLocaleSontObligatoires() throws Exception {
        String url = "jdbc:mariadb://localhost:3306/stock_pro";
        assertThatThrownBy(() -> TresorerieMigration.verifierAutorisationApplication(url, null)).hasMessageContaining("TRESORERIE_BACKUP_DIR");
        assertThatThrownBy(() -> TresorerieMigration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("Sauvegarde SQL complète");
        Path dump = dossierPrive.resolve("stock_pro.sql"); Files.writeString(dump, "-- copie factice privée\n");
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), "a".repeat(64) + "  stock_pro.sql\n");
        assertThatThrownBy(() -> TresorerieMigration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("ne correspond pas");
        String empreinte = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dump)));
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), empreinte + "  stock_pro.sql\n");
        assertThatCode(() -> TresorerieMigration.verifierAutorisationApplication(url, dossierPrive.toString())).doesNotThrowAnyException();
        for (String cible : List.of("jdbc:mariadb://example.test:3306/stock_pro", "jdbc:mariadb://localhost:13306/stock_pro", "jdbc:mariadb://localhost:3306/autre", "jdbc:mysql://localhost:3306/stock_pro"))
            assertThatThrownBy(() -> TresorerieMigration.verifierAutorisationApplication(cible, dossierPrive.toString())).isInstanceOf(IllegalStateException.class);
    }

    private static Connection base(boolean vide) throws Exception {
        var c = DriverManager.getConnection("jdbc:h2:mem:tresorerie_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
        TresorerieMigrationScenarios.schema(c, vide); return c;
    }
}
