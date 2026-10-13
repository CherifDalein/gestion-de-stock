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

class TarificationMigrationTests {
    @TempDir Path dossierPrive;
    static Stream<String> scenarios() { return Stream.concat(TarificationMigrationScenarios.VALIDES.stream(), TarificationMigrationScenarios.INVALIDES.keySet().stream()).filter(nom -> !nom.equals("statut_valeur")); }

    @ParameterizedTest @MethodSource("scenarios")
    void controleMigrationRelanceEtRefusPreserventToutesLesDonnees(String scenario) throws Exception {
        try (var c = base(scenario.equals("vide"))) {
            if (TarificationMigrationScenarios.INVALIDES.containsKey(scenario)) {
                TarificationMigrationScenarios.preparer(c, "complet");
                String ddl = TarificationMigrationScenarios.adapterDdlH2(TarificationMigrationScenarios.INVALIDES.get(scenario));
                if (scenario.endsWith("obligatoire")) {
                    var champ = TarificationMigration.CHAMPS.get(scenario.startsWith("achat_") ? 1 : 2);
                    TarificationMigrationScenarios.sql(c, "UPDATE " + champ.table() + " SET " + champ.nom() + "=0 WHERE " + champ.nom() + " IS NULL");
                }
                TarificationMigrationScenarios.sql(c, ddl);
            } else TarificationMigrationScenarios.preparer(c, scenario);
            TarificationMigrationScenarios.verifier(c, scenario);
        }
    }

    @Test void uneTransactionOuverteEstRefuseeSansDDL() throws Exception {
        try (var c = base(false)) {
            var schema = TarificationMigration.schemasAnciens(c); var avant = TarificationMigration.photographie(c);
            c.setAutoCommit(false);
            assertThatThrownBy(() -> TarificationMigration.migrer(c, true)).hasMessageContaining("transaction ouverte");
            c.rollback(); c.setAutoCommit(true);
            assertThat(TarificationMigration.schemasAnciens(c)).isEqualTo(schema); assertThat(TarificationMigration.photographie(c)).isEqualTo(avant);
            assertThat(TarificationMigration.tarifs(c)).isEmpty();
        }
    }

    @Test void uneSauvegardeVerifieeEtUneCibleLocaleSontObligatoires() throws Exception {
        String url = "jdbc:mariadb://localhost:3306/stock_pro";
        assertThatThrownBy(() -> TarificationMigration.verifierAutorisationApplication(url, null)).hasMessageContaining("TARIFICATION_BACKUP_DIR");
        assertThatThrownBy(() -> TarificationMigration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("Sauvegarde SQL complète");
        Path dump = dossierPrive.resolve("stock_pro.sql"); Files.writeString(dump, "-- copie factice privée\n");
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), "a".repeat(64) + "  stock_pro.sql\n");
        assertThatThrownBy(() -> TarificationMigration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("ne correspond pas");
        String empreinte = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dump)));
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), empreinte + "  stock_pro.sql\n");
        assertThatCode(() -> TarificationMigration.verifierAutorisationApplication(url, dossierPrive.toString())).doesNotThrowAnyException();
        for (String cible : List.of("jdbc:mariadb://example.test:3306/stock_pro", "jdbc:mariadb://localhost:13306/stock_pro", "jdbc:mariadb://localhost:3306/autre", "jdbc:mysql://localhost:3306/stock_pro"))
            assertThatThrownBy(() -> TarificationMigration.verifierAutorisationApplication(cible, dossierPrive.toString())).isInstanceOf(IllegalStateException.class);
    }

    private static Connection base(boolean vide) throws Exception {
        var c = DriverManager.getConnection("jdbc:h2:mem:tarifs_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
        TarificationMigrationScenarios.schema(c, vide); return c;
    }
}
