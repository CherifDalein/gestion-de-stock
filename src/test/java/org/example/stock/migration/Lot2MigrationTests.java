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

class Lot2MigrationTests {
    @TempDir Path dossierPrive;
    static Stream<String> scenarios() { return Stream.concat(Lot2MigrationScenarios.VALIDES.stream(), Lot2MigrationScenarios.INVALIDES.stream()); }

    @ParameterizedTest @MethodSource("scenarios")
    void lesAjoutsReprisesEtRefusPreserventStocksMontantsEtHistoriques(String scenario) throws Exception {
        try (var c = base(scenario.equals("vide"), scenario.equals("schema_neuf"))) {
            Lot2MigrationScenarios.preparer(c, scenario);
            Lot2MigrationScenarios.verifier(c, scenario);
        }
    }

    @Test void uneTransactionOuverteEstRefuseeSansDDL() throws Exception {
        try (var c = base(false, false)) {
            var avant = Lot2MigrationScenarios.etatComplet(c);
            c.setAutoCommit(false);
            assertThatThrownBy(() -> Lot2Migration.migrer(c, true)).hasMessageContaining("transaction ouverte");
            c.rollback(); c.setAutoCommit(true);
            assertThat(Lot2MigrationScenarios.etatComplet(c)).isEqualTo(avant);
        }
    }

    @Test void leLot1NonMigreEstRefuseSansModification() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:h2:mem:lot2_sans_lot1_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "")) {
            IdentiteBoutiqueMigrationScenarios.schema(c, false);
            IdentiteBoutiqueMigration.migrer(c, true);
            var avant = Lot2Migration.photographie(c); var tables = BoutiquesMigration.tables(c);
            for (boolean appliquer : List.of(false, true))
                assertThatThrownBy(() -> Lot2Migration.migrer(c, appliquer)).isInstanceOf(IllegalStateException.class).hasMessageContaining("lot 1 doit être terminée");
            // Un lot 1 commencé mais inachevé est refusé de la même façon.
            c.setReadOnly(false); Lot1Migration.ajouterColonnes(c);
            var partiel = Lot2Migration.photographie(c); var colonnes = BoutiquesMigration.tables(c);
            assertThatThrownBy(() -> Lot2Migration.migrer(c, true)).hasMessageContaining("lot 1 doit être terminée");
            assertThat(Lot2Migration.photographie(c)).isEqualTo(partiel);
            assertThat(BoutiquesMigration.tables(c)).isEqualTo(colonnes).isEqualTo(tables);
            assertThat(avant.keySet()).isEqualTo(partiel.keySet());
        }
    }

    @Test void lesControlesDesMigrationsPrecedentesAcceptentUneBaseMigree() throws Exception {
        try (var c = base(false, false)) {
            Lot2Migration.migrer(c, true);
            var avant = Lot2MigrationScenarios.etatComplet(c);
            InventaireMigration.migrer(c, false); TarificationMigration.migrer(c, false); InitialisationBoutiqueMigration.migrer(c, false);
            TresorerieMigration.migrer(c, false); IdentiteBoutiqueMigration.migrer(c, false);
            Lot1Migration.migrer(c, false); Lot1Migration.migrer(c, true);
            assertThat(Lot2MigrationScenarios.etatComplet(c)).isEqualTo(avant);
        }
    }

    @Test void laMigrationDuneAncienneBaseEtLeSqlNeufProduisentLeMemeSchema() throws Exception {
        try (var migree = base(true, false); var neuve = base(false, true)) {
            Lot2Migration.migrer(migree, true);
            // Le SQL neuf contient aussi les colonnes du lot 4 (reçus) : la base migrée les reçoit par la migration suivante.
            Lot4Migration.migrer(migree, true);
            var schemaMigre = Lot2MigrationScenarios.etatComplet(migree); var schemaNeuf = Lot2MigrationScenarios.etatComplet(neuve);
            assertThat(schemaMigre.get(2)).as("colonnes").isEqualTo(schemaNeuf.get(2));
            assertThat(schemaMigre.get(3)).as("contraintes uniques").isEqualTo(schemaNeuf.get(3));
            assertThat(schemaMigre.get(4)).as("clés étrangères").isEqualTo(schemaNeuf.get(4));
        }
    }

    @Test void leSchemaHistoriqueRetireSeulementLeLot2() throws Exception {
        String actuel = Files.readString(Path.of("docs/database-schema.sql"));
        String ancien = SchemaHistorique.sansLot2(actuel);
        for (String table : Lot2Migration.NOUVELLES_TABLES) {
            assertThat(actuel).contains("CREATE TABLE IF NOT EXISTS " + table + " (");
            assertThat(ancien).doesNotContain("CREATE TABLE IF NOT EXISTS " + table + " (");
        }
        for (var champ : Lot2Migration.CHAMPS) {
            String bloc = ancien.substring(ancien.indexOf("CREATE TABLE IF NOT EXISTS " + champ.table() + " ("));
            assertThat(bloc.substring(0, bloc.indexOf(';'))).doesNotContain(" " + champ.nom() + " ");
            String blocActuel = actuel.substring(actuel.indexOf("CREATE TABLE IF NOT EXISTS " + champ.table() + " ("));
            assertThat(blocActuel.substring(0, blocActuel.indexOf(';'))).contains("    " + champ.nom() + " ");
        }
        assertThat(ancien).doesNotContain("fk_vente_vendeur", "fk_achat_utilisateur", "fk_mouvement_caisse_retour", "fk_mouvement_caisse_annulation",
                        "uk_mouvement_caisse_annulation", "devis", "retour")
                .contains("uk_vente_boutique_numero", "fk_mouvement_caisse_client", "prochain_numero_achat BIGINT NOT NULL DEFAULT 1,\n    PRIMARY KEY (id)");
        // Les autres tables conservent leurs colonnes et clés étrangères du lot 1 et antérieures.
        assertThat(ancien).contains("    CONSTRAINT fk_vente_client\n        FOREIGN KEY (client_id) REFERENCES client (id)\n);",
                "    CONSTRAINT fk_achat_fournisseur\n        FOREIGN KEY (fournisseur_id) REFERENCES fournisseur (id)\n);");
        assertThat(actuel.length() - ancien.length()).isPositive();
        // Le schéma du lot 1 se déduit de celui du lot 2 : il ne contient rien du lot 2 non plus.
        assertThat(SchemaHistorique.sansLot1(actuel)).doesNotContain("devis", "retour_id", "vendeur_id", "annulation_de_id");
    }

    @Test void sauvegardeVerifieeEtCibleLocaleSontObligatoires() throws Exception {
        String url = "jdbc:mariadb://localhost:3306/stock_pro";
        assertThatThrownBy(() -> Lot2Migration.verifierAutorisationApplication(url, null)).hasMessageContaining("LOT2_BACKUP_DIR");
        assertThatThrownBy(() -> Lot2Migration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("Sauvegarde SQL complète");
        Path dump = dossierPrive.resolve("stock_pro.sql"); Files.writeString(dump, "-- copie factice privée\n");
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), "a".repeat(64) + "  stock_pro.sql\n");
        assertThatThrownBy(() -> Lot2Migration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("ne correspond pas");
        String empreinte = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dump)));
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), empreinte + "  stock_pro.sql\n");
        assertThatCode(() -> Lot2Migration.verifierAutorisationApplication(url, dossierPrive.toString())).doesNotThrowAnyException();
        for (String cible : List.of("jdbc:mariadb://example.test:3306/stock_pro", "jdbc:mariadb://localhost:13306/stock_pro", "jdbc:mariadb://localhost:3306/autre", "jdbc:mysql://localhost:3306/stock_pro"))
            assertThatThrownBy(() -> Lot2Migration.verifierAutorisationApplication(cible, dossierPrive.toString())).isInstanceOf(IllegalStateException.class);
    }

    private static Connection base(boolean vide, boolean neuf) throws Exception {
        var c = DriverManager.getConnection("jdbc:h2:mem:lot2_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
        if (neuf) IdentiteBoutiqueMigrationScenarios.schemaNeuf(c); else Lot2MigrationScenarios.schema(c, vide);
        return c;
    }
}
