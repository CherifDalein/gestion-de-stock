package org.example.stock.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/** Charge la configuration et résout les pilotes sans ouvrir de connexion. */
class DatabaseConfigurationTests {
    private final ApplicationContextRunner contexte = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void laConnexionLocaleUtiliseLePiloteMariaDbSansDialecteImpose() {
        contexte.run(context -> {
            String url = context.getEnvironment().getProperty("spring.datasource.url");
            assertThat(url).startsWith("jdbc:mariadb://localhost:3306/stock_pro?");
            assertThat(pilote(url)).isEqualTo("org.mariadb.jdbc.Driver");
            assertThat(DriverManager.getDriver(url).getClass().getName()).isEqualTo("org.mariadb.jdbc.Driver");
            assertThat(context.getEnvironment().getProperty("spring.jpa.properties.hibernate.dialect")).isNull();
            assertThat(context.getEnvironment().getProperty("spring.datasource.driver-class-name")).isNull();
        });
    }

    @Test
    void uneUrlMysqlFournieParLaConfigurationConserveLePiloteMysql() {
        contexte.withPropertyValues("DB_URL=jdbc:mysql://localhost:3306/mysql-configuration-test")
                .run(context -> {
                    String url = context.getEnvironment().getProperty("spring.datasource.url");
                    assertThat(url).isEqualTo("jdbc:mysql://localhost:3306/mysql-configuration-test");
                    assertThat(pilote(url)).isEqualTo("com.mysql.cj.jdbc.Driver");
                    assertThat(DriverManager.getDriver(url).getClass().getName()).isEqualTo("com.mysql.cj.jdbc.Driver");
                });
    }

    @Test
    void leProfilTestResteIsoleAvecH2MemeSiUneUrlDistanteEstFournie() {
        contexte.withPropertyValues("spring.profiles.active=test", "DB_URL=jdbc:mysql://localhost:3306/ne-pas-utiliser")
                .run(context -> {
                    assertThat(context.getEnvironment().getProperty("spring.datasource.url")).startsWith("jdbc:h2:mem:");
                    assertThat(context.getEnvironment().getProperty("spring.datasource.driver-class-name")).isEqualTo("org.h2.Driver");
                    assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("create-drop");
                });
    }

    private String pilote(String url) {
        DataSourceProperties properties = new DataSourceProperties();
        properties.setUrl(url);
        return properties.determineDriverClassName();
    }
}
