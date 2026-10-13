package org.example.stock.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.Connection;
import static org.assertj.core.api.Assertions.*;

class InventaireMigrationTests {
    Connection base()throws Exception{var c=AbonnementMigrationTests.base(true);AbonnementMigration.migrer(c,true);return c;}
    @Test void controleNeCreeRienEtMigrationConserveToutesLesDonnees()throws Exception {
        try(var c=base()) {
            var avant=InventaireMigration.photographie(c);InventaireMigration.migrer(c,false);
            assertThat(BoutiquesMigration.tables(c)).hasSize(16);assertThat(InventaireMigration.photographie(c)).isEqualTo(avant);
            InventaireMigration.migrer(c,true);InventaireMigration.migrer(c,true);InventaireMigration.migrer(c,false);
            assertThat(BoutiquesMigration.tables(c)).hasSize(17);assertThat(InventaireMigration.photographie(c)).isEqualTo(avant);
            assertThat(BoutiquesMigration.compter(c,"SELECT COUNT(*) FROM mouvement_stock")).isZero();
        }
    }
    @ParameterizedTest @ValueSource(strings={"ALTER TABLE mouvement_stock ALTER COLUMN motif VARCHAR(200) NOT NULL","ALTER TABLE mouvement_stock ALTER COLUMN quantite_avant INTEGER NOT NULL","DROP INDEX idx_mouvement_stock_produit_date"})
    void schemaIncoherentEstRefuseSansChangerLesDonnees(String ddl)throws Exception {
        try(var c=base()) {
            InventaireMigration.migrer(c,true);BoutiquesMigration.executer(c,ddl);var avant=InventaireMigration.photographie(c);
            assertThatThrownBy(()->InventaireMigration.migrer(c,true)).isInstanceOf(IllegalStateException.class).hasMessageContaining("non conforme");assertThat(InventaireMigration.photographie(c)).isEqualTo(avant);
        }
    }
}
