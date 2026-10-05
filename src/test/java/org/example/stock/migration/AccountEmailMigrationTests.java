package org.example.stock.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class AccountEmailMigrationTests {
    @Test
    void leControleNeModifieNiEmailsNiSchema() throws Exception {
        try (var c = base()) {
            creer(c, " ADMIN@EXAMPLE.TEST ");
            AccountEmailMigration.migrer(c, false);
            assertThat(valeur(c, "SELECT email FROM utilisateur")).isEqualTo(" ADMIN@EXAMPLE.TEST ");
            assertThat(AccountEmailMigration.indexUnique(c)).isFalse();
            assertThat(sauvegardeExiste(c)).isFalse();
        }
    }

    @Test
    void laMigrationConserveIdentifiantsEmpreintesRolesEtLiensPuisAccepteUneRelance() throws Exception {
        try (var c = base()) {
            creer(c, " ADMIN@EXAMPLE.TEST ");
            AccountEmailMigration.executer(c, "CREATE TABLE mouvement_caisse(id BIGINT PRIMARY KEY, utilisateur_id BIGINT REFERENCES utilisateur(id))");
            AccountEmailMigration.executer(c, "INSERT INTO mouvement_caisse VALUES(1,1)");
            AccountEmailMigration.migrer(c, true);
            assertThat(valeur(c, "SELECT email FROM utilisateur")).isEqualTo("admin@example.test");
            assertThat(valeur(c, "SELECT email FROM " + AccountEmailMigration.BACKUP)).isEqualTo(" ADMIN@EXAMPLE.TEST ");
            assertThat(valeur(c, "SELECT mot_de_passe FROM utilisateur")).isEqualTo("empreinte-fictive");
            assertThat(valeur(c, "SELECT role FROM utilisateur")).isEqualTo("ADMIN");
            assertThat(valeur(c, "SELECT utilisateur_id FROM mouvement_caisse")).isEqualTo("1");
            assertThat(AccountEmailMigration.indexUnique(c)).isTrue();
            assertThat(AccountEmailMigration.emailObligatoire(c)).isTrue();
            AccountEmailMigration.migrer(c, true);
            assertThat(valeur(c, "SELECT COUNT(*) FROM utilisateur")).isEqualTo("1");
            assertThat(valeur(c, "SELECT email FROM " + AccountEmailMigration.BACKUP)).isEqualTo(" ADMIN@EXAMPLE.TEST ");
            assertThatThrownBy(() -> AccountEmailMigration.executer(c,
                    "INSERT INTO utilisateur(id,email) VALUES(2,'admin@example.test')")).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void lesDoublonsNormalisesArretentLaMigrationAvantLaCopieEtLesModifications() throws Exception {
        try (var c = base()) {
            creer(c, " ADMIN@EXAMPLE.TEST ");
            AccountEmailMigration.executer(c, "INSERT INTO utilisateur(id,email) VALUES(2,'admin@example.test')");
            assertThatThrownBy(() -> AccountEmailMigration.migrer(c, true)).hasMessageContaining("doublons normalisés");
            assertThat(sauvegardeExiste(c)).isFalse();
            assertThat(AccountEmailMigration.indexUnique(c)).isFalse();
            assertThat(valeur(c, "SELECT email FROM utilisateur WHERE id=1")).isEqualTo(" ADMIN@EXAMPLE.TEST ");
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "a b@example.test"})
    void lesEmailsInvalidesNeSontPasReecrits(String email) throws Exception {
        try (var c = base()) {
            creer(c, email);
            assertThatThrownBy(() -> AccountEmailMigration.migrer(c, true)).hasMessageContaining("Emails invalides");
            assertThat(sauvegardeExiste(c)).isFalse();
            assertThat(valeur(c, "SELECT email FROM utilisateur")).isEqualTo(email);
        }
    }

    @Test
    void uneCopieExistanteNestPasEcrasee() throws Exception {
        try (var c = base()) {
            creer(c, " ADMIN@EXAMPLE.TEST ");
            AccountEmailMigration.executer(c, "CREATE TABLE " + AccountEmailMigration.BACKUP + "(id BIGINT)");
            AccountEmailMigration.executer(c, "INSERT INTO " + AccountEmailMigration.BACKUP + " VALUES(99)");
            assertThatThrownBy(() -> AccountEmailMigration.migrer(c, true)).hasMessageContaining("existe déjà");
            assertThat(valeur(c, "SELECT id FROM " + AccountEmailMigration.BACKUP)).isEqualTo("99");
            assertThat(valeur(c, "SELECT email FROM utilisateur")).isEqualTo(" ADMIN@EXAMPLE.TEST ");
        }
    }

    @Test
    void laNormalisationUnicodeUtiliseLaMemeRegleQueLApplication() throws Exception {
        try (var c = base()) {
            creer(c, "\tIDENTITÉ@EXAMPLE.TEST\t");
            AccountEmailMigration.migrer(c, true);
            assertThat(valeur(c, "SELECT email FROM utilisateur")).isEqualTo("identité@example.test");
        }
    }

    static Connection base() throws SQLException {
        var c = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE", "sa", "");
        AccountEmailMigration.executer(c, "CREATE TABLE utilisateur(id BIGINT PRIMARY KEY,nom VARCHAR(255),email VARCHAR(255),mot_de_passe VARCHAR(255),role VARCHAR(255))");
        return c;
    }
    static void creer(Connection c, String email) throws SQLException {
        try (var s = c.prepareStatement("INSERT INTO utilisateur VALUES(1,'Admin',?,'empreinte-fictive','ADMIN')")) { s.setString(1, email); s.executeUpdate(); }
    }
    static String valeur(Connection c, String sql) throws SQLException { try (var s = c.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getString(1); } }
    static boolean sauvegardeExiste(Connection c) throws SQLException {
        try (var r = c.getMetaData().getTables(c.getCatalog(), null, AccountEmailMigration.BACKUP, new String[]{"TABLE"})) { return r.next(); }
    }
}
