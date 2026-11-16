package org.example.stock.migration;

import org.example.stock.config.LengthCheckedBcryptPasswordEncoder;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;
import static org.assertj.core.api.Assertions.*;

class InitialAdminTests {
    @Test
    void seuleUneBaseSansCompteAccepteLePremierAdministrateur() throws Exception {
        try (var c = base()) {
            InitialAdmin.creer(c, " ADMIN@EXAMPLE.TEST ", "MotDePasseTest42");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT role FROM utilisateur")).isEqualTo("ADMIN");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT email FROM utilisateur")).isEqualTo("admin@example.test");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT COUNT(*) FROM boutique")).isEqualTo("1");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT boutique_id FROM utilisateur"))
                    .isEqualTo(AccountEmailMigrationTests.valeur(c, "SELECT id FROM boutique"));
            assertThat(new LengthCheckedBcryptPasswordEncoder().matches("MotDePasseTest42",
                    AccountEmailMigrationTests.valeur(c, "SELECT mot_de_passe FROM utilisateur"))).isTrue();
            // Il peut se connecter (compte actif, ni suspendu ni plateforme) et sa boutique s'ouvre : active, sans période d'essai ni d'abonnement bornée.
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT actif FROM utilisateur")).isIn("1", "true", "TRUE");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT administrateur_plateforme FROM utilisateur")).isIn("0", "false", "FALSE");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT actif FROM boutique")).isIn("1", "true", "TRUE");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT COUNT(*) FROM boutique WHERE essai_debut IS NULL AND essai_fin IS NULL AND abonnement_debut IS NULL AND abonnement_fin IS NULL")).isEqualTo("1");
            assertThatThrownBy(() -> InitialAdmin.creer(c, "autre@example.test", "MotDePasseTest42")).hasMessageContaining("Des comptes existent");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT COUNT(*) FROM utilisateur")).isEqualTo("1");
        }
    }

    @Test
    void desInformationsInvalidesNeCreentAucunAdministrateur() throws Exception {
        try (var c = base()) {
            assertThatThrownBy(() -> InitialAdmin.creer(c, "abc", "MotDePasseTest42")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> InitialAdmin.creer(c, "admin@example.test", "court")).isInstanceOf(IllegalStateException.class);
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT COUNT(*) FROM utilisateur")).isEqualTo("0");
        }
    }

    @Test
    void lePremierAdministrateurProvisionneLesTroisComptesDuSchemaActuel() throws Exception {
        try (var c = base()) {
            InitialAdmin.creer(c, "admin@example.test", "MotDePasseTest42");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique t JOIN utilisateur u ON u.boutique_id=t.boutique_id")).isEqualTo("3");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT GROUP_CONCAT(type ORDER BY ordre SEPARATOR ',') FROM compte_tresorerie_boutique")).isEqualTo("ESPECES,MOBILE_MONEY,BANQUE");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT GROUP_CONCAT(nom ORDER BY ordre SEPARATOR ',') FROM compte_tresorerie_boutique")).isEqualTo("Caisse,Orange Money,Banque");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT GROUP_CONCAT(nom_cle ORDER BY ordre SEPARATOR ',') FROM compte_tresorerie_boutique")).isEqualTo("caisse,orange money,banque");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique WHERE principal_type=type AND actif")).isEqualTo("3");
            assertThatThrownBy(() -> InitialAdmin.creer(c, "autre@example.test", "MotDePasseTest42")).hasMessageContaining("Des comptes existent");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique")).isEqualTo("3");
        }
    }

    @Test
    void uneBoutiqueExistanteConserveSesComptesEtRecoitSeulementLesManquants() throws Exception {
        try (var c = base()) {
            AccountEmailMigration.executer(c, "INSERT INTO boutique(id,nom,date_creation,pays,monnaie) VALUES(42,'Boutique existante',CURRENT_TIMESTAMP,'GN','GNF')");
            AccountEmailMigration.executer(c, "INSERT INTO compte_tresorerie_boutique(id,boutique_id,type,nom,nom_cle,principal_type,actif,ordre) VALUES(99,42,'ESPECES','Caisse','caisse','ESPECES',TRUE,0)");
            // Un compte secondaire déjà nommé « Banque » ne doit pas entrer en conflit avec le compte principal de base.
            AccountEmailMigration.executer(c, "INSERT INTO compte_tresorerie_boutique(id,boutique_id,type,nom,nom_cle,principal_type,actif,ordre) VALUES(98,42,'ORANGE_MONEY','Banque','banque',NULL,TRUE,5)");
            InitialAdmin.creer(c, "admin@example.test", "MotDePasseTest42");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique WHERE boutique_id=42")).isEqualTo("3");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT id FROM compte_tresorerie_boutique WHERE type='ESPECES'")).isEqualTo("99");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique WHERE nom_cle='banque'")).isEqualTo("1");
        }
    }

    @Test
    void unEchecDeCreationAnnuleAussiLaBoutiqueEtSesTroisComptes() throws Exception {
        try (var c = base()) {
            AccountEmailMigration.executer(c, "ALTER TABLE utilisateur ADD CONSTRAINT refus_admin_test CHECK(role<>'ADMIN')");
            assertThatThrownBy(() -> InitialAdmin.creer(c, "admin@example.test", "MotDePasseTest42")).isInstanceOf(SQLException.class);
            for (String nom : java.util.List.of("boutique", "compte_tresorerie_boutique", "utilisateur"))
                assertThat(AccountEmailMigrationTests.valeur(c, "SELECT COUNT(*) FROM " + nom)).isEqualTo("0");
        }
    }

    @Test
    void uneBaseDAvantLeLot3ReçoitLesTroisComptesDeLAncienSchemaSansDoublon() throws Exception {
        try (var c = baseAvantLot3()) {
            AccountEmailMigration.executer(c, "INSERT INTO boutique(id,nom,date_creation,pays,monnaie) VALUES(42,'Boutique existante',CURRENT_TIMESTAMP,'GN','GNF')");
            AccountEmailMigration.executer(c, "INSERT INTO compte_tresorerie_boutique(id,boutique_id,compte) VALUES(99,42,'CAISSE')");
            InitialAdmin.creer(c, "admin@example.test", "MotDePasseTest42");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique WHERE boutique_id=42")).isEqualTo("3");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT id FROM compte_tresorerie_boutique WHERE compte='CAISSE'")).isEqualTo("99");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT COUNT(DISTINCT compte) FROM compte_tresorerie_boutique WHERE compte IN('CAISSE','ORANGE_MONEY','BANQUE')")).isEqualTo("3");
        }
    }

    @Test
    void uneBaseSansTableDeTresorerieResteAcceptee() throws Exception {
        try (var c = baseAvantLot3()) {
            AccountEmailMigration.executer(c, "DROP TABLE compte_tresorerie_boutique");
            InitialAdmin.creer(c, "admin@example.test", "MotDePasseTest42");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT COUNT(*) FROM utilisateur")).isEqualTo("1");
        }
    }

    @Test
    void uneTableDeTresorerieInconnueEstRefuseeAvecLaMigrationAAppliquer() throws Exception {
        try (var c = baseAvantLot3()) {
            AccountEmailMigration.executer(c, "DROP TABLE compte_tresorerie_boutique");
            AccountEmailMigration.executer(c, "CREATE TABLE compte_tresorerie_boutique(id BIGINT AUTO_INCREMENT PRIMARY KEY,boutique_id BIGINT NOT NULL REFERENCES boutique(id),libelle VARCHAR(32))");
            assertThatThrownBy(() -> InitialAdmin.creer(c, "admin@example.test", "MotDePasseTest42"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("lot3Migrate");
            for (String nom : java.util.List.of("boutique", "utilisateur"))
                assertThat(AccountEmailMigrationTests.valeur(c, "SELECT COUNT(*) FROM " + nom)).isEqualTo("0");
        }
    }

    @Test
    void laCauseReelleEstAfficheeSansDonneePrivee() throws Exception {
        try (var c = baseAvantLot3()) {
            AccountEmailMigration.executer(c, "ALTER TABLE utilisateur DROP COLUMN boutique_id");
            var e = catchThrowableOfType(SQLException.class, () -> InitialAdmin.creer(c, "secret.prive@example.test", "MotDePasseTest42"));
            assertThat((Object) e).isNotNull();
            assertThat(InitialAdmin.diagnostic(e)).startsWith("Colonne inconnue : boutique_id").contains("boutiquesMigrate")
                    .doesNotContain("secret.prive").doesNotContain("MotDePasseTest42");
        }
        assertThat(InitialAdmin.diagnostic(new SQLSyntaxErrorException("Unknown column 'u.supprime_le' in 'field list'", "42S22", 1054)))
                .isEqualTo("Colonne inconnue : u.supprime_le ; appliquez d'abord la migration lot6Migrate, puis relancez");
        assertThat(InitialAdmin.diagnostic(new SQLSyntaxErrorException("Table 'stock_pro.reglage_comptable_boutique' doesn't exist", "42S02", 1146)))
                .startsWith("Table inconnue : stock_pro.reglage_comptable_boutique").contains("lot5Migrate");
        assertThat(InitialAdmin.diagnostic(new SQLIntegrityConstraintViolationException("Duplicate entry 'prive@example.test' for key 'uk_utilisateur_email'", "23000", 1062)))
                .doesNotContain("prive@example.test").contains("contrainte");
        assertThat(InitialAdmin.diagnostic(new SQLException("Access denied for user 'root'@'localhost' (using password: YES)", "28000", 1045)))
                .doesNotContain("root").contains("Connexion");
        assertThat(InitialAdmin.diagnostic(new IllegalStateException("Des comptes existent déjà ; aucun administrateur n'a été ajouté"))).startsWith("Des comptes existent");
    }

    /** Schéma actuel, lu dans docs/database-schema.sql (source unique) : les tables que la création de l'administrateur remplit ou rencontre. */
    private static final List<String> TABLES_ACTUELLES = List.of("boutique", "utilisateur", "compte_tresorerie_boutique", "compte_tresorerie_autorise",
            "sous_compte_boutique", "correspondance_compta", "reglage_comptable_boutique");

    static Connection base() throws Exception {
        var c = ouvrir();
        String schema = Files.readString(Path.of("docs/database-schema.sql")).replaceAll("(?m)^\\s*--[^\\r\\n]*", "");
        var instructions = new LinkedHashMap<String, String>();
        for (String partie : schema.split(";")) {
            var m = Pattern.compile("(?s)^\\s*CREATE TABLE IF NOT EXISTS (\\w+)").matcher(partie);
            if (m.find() && TABLES_ACTUELLES.contains(m.group(1))) instructions.put(m.group(1), partie.strip());
        }
        assertThat(instructions.keySet()).containsExactlyInAnyOrderElementsOf(TABLES_ACTUELLES);
        for (String table : TABLES_ACTUELLES) AccountEmailMigration.executer(c, instructions.get(table));
        return c;
    }

    /** Schéma d'avant le lot 3 : un compte de trésorerie se résume à (boutique_id, compte) ; utilisateur sans colonnes plateforme ni statut. */
    static Connection baseAvantLot3() throws SQLException {
        var c = ouvrir();
        AccountEmailMigration.executer(c, "CREATE TABLE boutique(id BIGINT AUTO_INCREMENT PRIMARY KEY,nom VARCHAR(255) NOT NULL,date_creation TIMESTAMP NOT NULL)");
        AccountEmailMigration.executer(c, "CREATE TABLE utilisateur(id BIGINT AUTO_INCREMENT PRIMARY KEY,nom VARCHAR(255),email VARCHAR(255) NOT NULL UNIQUE,mot_de_passe VARCHAR(255),date_inscription DATE,role VARCHAR(255),boutique_id BIGINT NOT NULL REFERENCES boutique(id))");
        AccountEmailMigration.executer(c, "CREATE TABLE compte_tresorerie_boutique(id BIGINT AUTO_INCREMENT PRIMARY KEY,boutique_id BIGINT NOT NULL REFERENCES boutique(id),compte VARCHAR(32) NOT NULL,CONSTRAINT uk_compte_tresorerie_boutique UNIQUE(boutique_id,compte))");
        return c;
    }

    private static Connection ouvrir() throws SQLException {
        return DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE", "sa", "");
    }
}
