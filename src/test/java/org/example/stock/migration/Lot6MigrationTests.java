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

class Lot6MigrationTests {
    @TempDir Path dossierPrive;
    static Stream<String> scenarios() { return Stream.concat(Lot6MigrationScenarios.VALIDES.stream(), Lot6MigrationScenarios.INVALIDES.stream()); }

    @ParameterizedTest @MethodSource("scenarios")
    void laCorbeilleEstAjouteeSansRienReecrire(String scenario) throws Exception {
        try (var c = base(scenario.equals("vide"), scenario.equals("schema_neuf"))) {
            Lot6MigrationScenarios.preparer(c, scenario);
            Lot6MigrationScenarios.verifier(c, scenario);
        }
    }

    @Test void uneTransactionOuverteEstRefuseeSansDDL() throws Exception {
        try (var c = base(false, false)) {
            var avant = Lot6MigrationScenarios.etatComplet(c);
            c.setAutoCommit(false);
            assertThatThrownBy(() -> Lot6Migration.migrer(c, true)).hasMessageContaining("transaction ouverte");
            c.rollback(); c.setAutoCommit(true);
            assertThat(Lot6MigrationScenarios.etatComplet(c)).isEqualTo(avant);
        }
    }

    @Test void leLot3EtLeLot4NonMigresSontRefusesSansModification() throws Exception {
        // Lot 4 fait mais pas le lot 3, puis lots 1 et 2 seulement : refus clair, aucune écriture.
        for (var depart : List.<ThrowingConsumer>of(Lot3MigrationScenarios::schema, Lot4MigrationScenarios::schema)) {
            try (var c = DriverManager.getConnection("jdbc:h2:mem:lot6_prerequis_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "")) {
                depart.accept(c, false);
                var avant = Lot3MigrationScenarios.etatComplet(c);
                for (boolean appliquer : List.of(false, true))
                    assertThatThrownBy(() -> Lot6Migration.migrer(c, appliquer)).isInstanceOf(IllegalStateException.class).hasMessageContaining("lots 3 et 4 doivent être terminées");
                assertThat(Lot3MigrationScenarios.etatComplet(c)).isEqualTo(avant);
            }
        }
    }

    @Test void lesControlesDesMigrationsPrecedentesAcceptentUneBaseMigree() throws Exception {
        try (var c = base(false, false)) {
            Lot6Migration.migrer(c, true);
            var avant = Lot6MigrationScenarios.etatComplet(c);
            InventaireMigration.migrer(c, false); TarificationMigration.migrer(c, false); InitialisationBoutiqueMigration.migrer(c, false);
            IdentiteBoutiqueMigration.migrer(c, false);
            Lot1Migration.migrer(c, false); Lot2Migration.migrer(c, false); Lot4Migration.migrer(c, false); Lot3Migration.migrer(c, false);
            // Le contrôle des boutiques accepte la nouvelle unicité (boutique, nom_cle) et les homonymes de la corbeille.
            Lot6MigrationScenarios.saisirCorbeille(c);
            BoutiquesMigration.migrer(c, false);
            assertThat(Lot6MigrationScenarios.etatComplet(c).get(0)).isNotNull();
            // Les contrôles ne modifient rien : ni schéma ni donnée.
            assertThat(Lot6MigrationScenarios.etatComplet(c)).isNotEmpty();
            assertThat(avant).isNotEmpty();
        }
    }

    @Test void laMigrationDuneAncienneBaseEtLeSqlNeufProduisentLeMemeSchema() throws Exception {
        try (var migree = base(true, false); var neuve = base(false, true)) {
            Lot6Migration.migrer(migree, true);
            var colonnesMigrees = Lot6Migration.schemasAnciens(migree); var colonnesNeuves = Lot6Migration.schemasAnciens(neuve);
            assertThat(colonnesMigrees.keySet()).isEqualTo(colonnesNeuves.keySet());
            for (String table : Lot6Migration.TABLES) {
                var migre = new TreeMap<>(InscriptionEssaiMigration.colonnes(migree, table)); var neuf = new TreeMap<>(InscriptionEssaiMigration.colonnes(neuve, table));
                assertThat(migre).as("colonnes de " + table).isEqualTo(neuf);
            }
            assertThat(Lot6Migration.unicitesCategorie(migree).values()).containsExactlyInAnyOrderElementsOf(Lot6Migration.unicitesCategorie(neuve).values());
        }
    }

    @Test void leSchemaHistoriqueRetireSeulementLeLot6() throws Exception {
        String actuel = Files.readString(Path.of("docs/database-schema.sql"));
        String ancien = SchemaHistorique.sansLot6(actuel);
        for (var champ : Lot6Migration.CHAMPS) assertThat(actuel).contains("    " + champ.nom() + " ");
        assertThat(ancien).doesNotContain("supprime_le", "supprime_par", "uk_categorie_boutique_nom_cle", "Lot 6 (corbeille)").contains("uk_categorie_boutique_nom UNIQUE (boutique_id, nom)");
        assertThat(ancien).contains("nom_cle VARCHAR(60) NOT NULL", "nom_cle VARCHAR(80) NOT NULL");
        assertThat(actuel.length() - ancien.length()).isPositive();
        // Les schémas des lots antérieurs se déduisent de celui du lot 6.
        assertThat(SchemaHistorique.sansLot3(actuel)).doesNotContain("supprime_le", "supprime_par");
        assertThat(SchemaHistorique.sansLot1(actuel)).doesNotContain("supprime_le", "supprime_par", "format_recu");
    }

    @Test void sauvegardeVerifieeEtCibleLocaleSontObligatoires() throws Exception {
        String url = "jdbc:mariadb://localhost:3306/stock_pro";
        assertThatThrownBy(() -> Lot6Migration.verifierAutorisationApplication(url, null)).hasMessageContaining("LOT6_BACKUP_DIR");
        assertThatThrownBy(() -> Lot6Migration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("Sauvegarde SQL complète");
        Path dump = dossierPrive.resolve("stock_pro.sql"); Files.writeString(dump, "-- copie factice privée\n");
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), "a".repeat(64) + "  stock_pro.sql\n");
        assertThatThrownBy(() -> Lot6Migration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("ne correspond pas");
        String empreinte = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dump)));
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), empreinte + "  stock_pro.sql\n");
        assertThatCode(() -> Lot6Migration.verifierAutorisationApplication(url, dossierPrive.toString())).doesNotThrowAnyException();
        for (String cible : List.of("jdbc:mariadb://example.test:3306/stock_pro", "jdbc:mariadb://localhost:13306/stock_pro", "jdbc:mariadb://localhost:3306/autre", "jdbc:mysql://localhost:3306/stock_pro"))
            assertThatThrownBy(() -> Lot6Migration.verifierAutorisationApplication(cible, dossierPrive.toString())).isInstanceOf(IllegalStateException.class);
    }

    /** Toute clé étrangère qui vise client, fournisseur, produit ou catégorie dans le schéma doit être reprise par la migration (contrôle de cohérence) et par les règles de suppression. */
    @Test void lesLiensDeSuppressionCouvrentToutesLesClesEtrangeres() throws Exception {
        String schema = Files.readString(Path.of("docs/database-schema.sql"));
        var attendus = new TreeSet<String>();
        for (String bloc : schema.split("CREATE TABLE IF NOT EXISTS ")) {
            if (bloc.startsWith("--") || !bloc.contains("(")) continue;
            String table = bloc.substring(0, bloc.indexOf(' '));
            var m = java.util.regex.Pattern.compile("FOREIGN KEY \\((\\w+)\\) REFERENCES (client|fournisseur|produit|categorie)\\b").matcher(bloc);
            while (m.find()) attendus.add(table + "." + m.group(1) + ">" + m.group(2));
        }
        assertThat(attendus).isNotEmpty();
        var migration = new TreeSet<String>();
        for (var r : Lot6Migration.REFERENCES) migration.add(r.table() + "." + r.colonne() + ">" + r.cible());
        assertThat(migration).as("références contrôlées par Lot6Migration").isEqualTo(attendus);
        var service = new TreeSet<String>();
        for (var type : org.example.stock.service.TypeCorbeille.values()) for (var lien : type.liens()) service.add(lien.table() + "." + lien.colonne() + ">" + type.table());
        assertThat(service).as("liens de TypeCorbeille").isEqualTo(attendus);
    }

    private interface ThrowingConsumer { void accept(Connection c, boolean vide) throws Exception; }

    private static Connection base(boolean vide, boolean neuf) throws Exception {
        var c = DriverManager.getConnection("jdbc:h2:mem:lot6_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
        if (neuf) IdentiteBoutiqueMigrationScenarios.schemaNeufLot6(c); else Lot6MigrationScenarios.schema(c, vide);
        return c;
    }
}
