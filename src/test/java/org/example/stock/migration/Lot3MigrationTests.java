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

class Lot3MigrationTests {
    @TempDir Path dossierPrive;
    static Stream<String> scenarios() { return Stream.concat(Lot3MigrationScenarios.VALIDES.stream(), Lot3MigrationScenarios.INVALIDES.stream()); }

    @ParameterizedTest @MethodSource("scenarios")
    void lesComptesDeBaseDeviennentPrincipauxSansRienPerdre(String scenario) throws Exception {
        try (var c = base(scenario.equals("vide"), scenario.equals("schema_neuf"))) {
            Lot3MigrationScenarios.preparer(c, scenario);
            Lot3MigrationScenarios.verifier(c, scenario);
        }
    }

    @Test void uneTransactionOuverteEstRefuseeSansDDL() throws Exception {
        try (var c = base(false, false)) {
            var avant = Lot3MigrationScenarios.etatComplet(c);
            c.setAutoCommit(false);
            assertThatThrownBy(() -> Lot3Migration.migrer(c, true)).hasMessageContaining("transaction ouverte");
            c.rollback(); c.setAutoCommit(true);
            assertThat(Lot3MigrationScenarios.etatComplet(c)).isEqualTo(avant);
        }
    }

    @Test void leLot4NonMigreEstRefuseSansModification() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:h2:mem:lot3_sans_lot4_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "")) {
            Lot4MigrationScenarios.schema(c, false);
            var avant = Lot3MigrationScenarios.etatComplet(c);
            for (boolean appliquer : List.of(false, true))
                assertThatThrownBy(() -> Lot3Migration.migrer(c, appliquer)).isInstanceOf(IllegalStateException.class).hasMessageContaining("lot 4 doit être terminée");
            assertThat(Lot3MigrationScenarios.etatComplet(c)).isEqualTo(avant);
        }
    }

    @Test void laMigrationDuneAncienneBaseEtLeSqlNeufProduisentLeMemeSchema() throws Exception {
        try (var migree = base(true, false); var neuve = base(false, true)) {
            Lot3Migration.migrer(migree, true);
            var schemaMigre = Lot3MigrationScenarios.etatComplet(migree); var schemaNeuf = Lot3MigrationScenarios.etatComplet(neuve);
            assertThat(schemaMigre.get(0)).as("colonnes").isEqualTo(schemaNeuf.get(0));
            assertThat(valeurs(schemaMigre.get(2))).as("contraintes uniques").isEqualTo(valeurs(schemaNeuf.get(2)));
            assertThat(schemaMigre.get(3)).as("clés étrangères").isEqualTo(schemaNeuf.get(3));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<List<String>>> valeurs(Object uniques) {
        var resultat = new TreeMap<String, Set<List<String>>>();
        ((Map<String, Map<String, List<String>>>) uniques).forEach((table, index) -> resultat.put(table, new HashSet<>(index.values())));
        return resultat;
    }

    @Test void leSchemaHistoriqueRetireSeulementLeLot3() throws Exception {
        String actuel = Files.readString(Path.of("docs/database-schema.sql"));
        String ancien = SchemaHistorique.sansLot3(actuel);
        assertThat(actuel).contains("compte_id BIGINT NOT NULL", "compte_source_id", "compte_destination_id", "compte_tresorerie_id", "principal_type", "compte_tresorerie_autorise");
        assertThat(ancien).doesNotContain("compte_id", "compte_source_id", "compte_destination_id", "compte_tresorerie_id", "principal_type", "nom_cle", "compte_tresorerie_autorise",
                        "fk_mouvement_caisse_compte", "fk_operation_tresorerie_source", "fk_operation_tresorerie_destination", "fk_retour_compte_tresorerie", "Lot 3")
                .contains("compte VARCHAR(32) NOT NULL DEFAULT 'CAISSE',", "compte_source VARCHAR(32) NOT NULL,", "compte_destination VARCHAR(32) NULL,",
                        "compte_tresorerie VARCHAR(16) NULL,", "uk_compte_tresorerie_boutique UNIQUE (boutique_id, compte)", "format_recu", "devis");
        assertThat(actuel.length() - ancien.length()).isPositive();
        // Les schémas des lots antérieurs se déduisent de celui du lot 3 : ils n'en contiennent rien non plus.
        assertThat(SchemaHistorique.sansLot4(actuel)).doesNotContain("format_recu", "principal_type", "compte_id");
        assertThat(SchemaHistorique.sansLot2(actuel)).doesNotContain("devis", "principal_type", "compte_id");
        assertThat(SchemaHistorique.sansLot1(actuel)).doesNotContain("principal_type", "compte_id", "retour_id");
    }

    @Test void sauvegardeVerifieeEtCibleLocaleSontObligatoires() throws Exception {
        String url = "jdbc:mariadb://localhost:3306/stock_pro";
        assertThatThrownBy(() -> Lot3Migration.verifierAutorisationApplication(url, null)).hasMessageContaining("LOT3_BACKUP_DIR");
        assertThatThrownBy(() -> Lot3Migration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("Sauvegarde SQL complète");
        Path dump = dossierPrive.resolve("stock_pro.sql"); Files.writeString(dump, "-- copie factice privée\n");
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), "a".repeat(64) + "  stock_pro.sql\n");
        assertThatThrownBy(() -> Lot3Migration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("ne correspond pas");
        String empreinte = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dump)));
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), empreinte + "  stock_pro.sql\n");
        assertThatCode(() -> Lot3Migration.verifierAutorisationApplication(url, dossierPrive.toString())).doesNotThrowAnyException();
        for (String cible : List.of("jdbc:mariadb://example.test:3306/stock_pro", "jdbc:mariadb://localhost:13306/stock_pro", "jdbc:mariadb://localhost:3306/autre", "jdbc:mysql://localhost:3306/stock_pro"))
            assertThatThrownBy(() -> Lot3Migration.verifierAutorisationApplication(cible, dossierPrive.toString())).isInstanceOf(IllegalStateException.class);
    }

    private static Connection base(boolean vide, boolean neuf) throws Exception {
        var c = DriverManager.getConnection("jdbc:h2:mem:lot3_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
        if (neuf) IdentiteBoutiqueMigrationScenarios.schemaNeufLot3(c); else Lot3MigrationScenarios.schema(c, vide);
        return c;
    }
}
