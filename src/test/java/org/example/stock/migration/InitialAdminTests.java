package org.example.stock.migration;

import org.example.stock.config.LengthCheckedBcryptPasswordEncoder;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class InitialAdminTests {
    @Test
    void seuleUneBaseSansCompteAccepteLePremierAdministrateur() throws Exception {
        try (var c = base()) {
            InitialAdmin.creer(c, " ADMIN@EXAMPLE.TEST ", "MotDePasseTest42");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT role FROM utilisateur")).isEqualTo("ADMIN");
            assertThat(AccountEmailMigrationTests.valeur(c, "SELECT email FROM utilisateur")).isEqualTo("admin@example.test");
            assertThat(new LengthCheckedBcryptPasswordEncoder().matches("MotDePasseTest42",
                    AccountEmailMigrationTests.valeur(c, "SELECT mot_de_passe FROM utilisateur"))).isTrue();
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

    static Connection base() throws SQLException {
        var c = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE", "sa", "");
        AccountEmailMigration.executer(c, "CREATE TABLE utilisateur(id BIGINT AUTO_INCREMENT PRIMARY KEY,nom VARCHAR(255),email VARCHAR(255) NOT NULL UNIQUE,mot_de_passe VARCHAR(255),date_inscription DATE,role VARCHAR(255))");
        return c;
    }
}
