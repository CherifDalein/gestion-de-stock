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

class InitialisationBoutiqueMigrationTests {
    @TempDir Path dossierPrive;
    static Stream<String> scenarios() { return Stream.concat(InitialisationBoutiqueMigrationScenarios.VALIDES.stream(), InitialisationBoutiqueMigrationScenarios.INVALIDES.keySet().stream()).filter(nom -> !nom.equals("statut_valeur")); }

    @ParameterizedTest @MethodSource("scenarios")
    void fermetureHistoriqueEtRepriseNeModifientAucuneDonneeExistante(String scenario) throws Exception {
        try (var c = base(scenario.equals("vide"))) {
            if (InitialisationBoutiqueMigrationScenarios.INVALIDES.containsKey(scenario)) {
                InitialisationBoutiqueMigrationScenarios.preparer(c, "colonne_seule");
                InitialisationBoutiqueMigrationScenarios.sql(c, InitialisationBoutiqueMigrationScenarios.INVALIDES.get(scenario).replace("MODIFY COLUMN", "ALTER COLUMN"));
            } else InitialisationBoutiqueMigrationScenarios.preparer(c, scenario);
            InitialisationBoutiqueMigrationScenarios.verifier(c, scenario);
        }
    }

    @Test void uneTransactionOuverteEstRefuseeSansDDL() throws Exception {
        try (var c = base(false)) {
            var schema = InitialisationBoutiqueMigration.schemasAnciens(c); var avant = InitialisationBoutiqueMigration.photographie(c);
            c.setAutoCommit(false);
            assertThatThrownBy(() -> InitialisationBoutiqueMigration.migrer(c, true)).hasMessageContaining("transaction ouverte");
            c.rollback(); c.setAutoCommit(true);
            assertThat(InitialisationBoutiqueMigration.schemasAnciens(c)).isEqualTo(schema);
            assertThat(InitialisationBoutiqueMigration.photographie(c)).isEqualTo(avant);
            assertThat(InitialisationBoutiqueMigration.etats(c)).isEmpty();
        }
    }

    @Test void sauvegardeVerifieeEtCibleLocaleSontObligatoires() throws Exception {
        String url = "jdbc:mariadb://localhost:3306/stock_pro";
        assertThatThrownBy(() -> InitialisationBoutiqueMigration.verifierAutorisationApplication(url, null)).hasMessageContaining("INITIALISATION_BACKUP_DIR");
        assertThatThrownBy(() -> InitialisationBoutiqueMigration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("Sauvegarde SQL complète");
        Path dump = dossierPrive.resolve("stock_pro.sql"); Files.writeString(dump, "-- copie factice privée\n");
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), "a".repeat(64) + "  stock_pro.sql\n");
        assertThatThrownBy(() -> InitialisationBoutiqueMigration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("ne correspond pas");
        String empreinte = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dump)));
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), empreinte + "  stock_pro.sql\n");
        assertThatCode(() -> InitialisationBoutiqueMigration.verifierAutorisationApplication(url, dossierPrive.toString())).doesNotThrowAnyException();
        for (String cible : List.of("jdbc:mariadb://example.test:3306/stock_pro", "jdbc:mariadb://localhost:13306/stock_pro", "jdbc:mariadb://localhost:3306/autre", "jdbc:mysql://localhost:3306/stock_pro"))
            assertThatThrownBy(() -> InitialisationBoutiqueMigration.verifierAutorisationApplication(cible, dossierPrive.toString())).isInstanceOf(IllegalStateException.class);
    }

    private static Connection base(boolean vide) throws Exception {
        var c = DriverManager.getConnection("jdbc:h2:mem:demarrage_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
        InitialisationBoutiqueMigrationScenarios.schema(c, vide); return c;
    }
}
