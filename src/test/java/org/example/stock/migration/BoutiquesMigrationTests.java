package org.example.stock.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class BoutiquesMigrationTests {
    @TempDir Path dossier;

    @Test
    void leControleNeCreeNiBoutiqueNiColonneEtNeModifieAucuneDonnee() throws Exception {
        try (var c = base(true)) {
            Map<String, List<List<Object>>> avant = photographie(c);
            BoutiquesMigration.migrer(c, false);
            assertThat(BoutiquesMigration.tables(c)).doesNotContainKey("boutique");
            for (String table : BoutiquesMigration.RACINES) assertThat(BoutiquesMigration.colonnes(c, table)).doesNotContainKey("boutique_id");
            assertThat(photographie(c)).isEqualTo(avant);
            assertThat(uniciteNom(c)).isTrue();
        }
    }

    @Test
    void laMigrationConserveToutesLesColonnesHistoriquesEtLesComptes() throws Exception {
        try (var c = base(true)) {
            Map<String, List<List<Object>>> avant = photographie(c);
            BoutiquesMigration.migrer(c, true);
            assertThat(photographie(c)).isEqualTo(avant);
            assertThat(valeur(c, "SELECT nom FROM boutique")).isEqualTo("Boutique principale");
            assertThat(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE date_creation IS NOT NULL")).isEqualTo(1);
            for (String table : BoutiquesMigration.RACINES) {
                assertThat(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table + " WHERE boutique_id=1")).isEqualTo(1);
                assertThat(BoutiquesMigration.colonnes(c, table).get("boutique_id")).isEqualTo(DatabaseMetaData.columnNoNulls);
                assertThatThrownBy(() -> execute(c, "UPDATE " + table + " SET boutique_id=NULL")).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> execute(c, "UPDATE " + table + " SET boutique_id=999")).isInstanceOf(SQLException.class);
            }
            assertThat(BoutiquesMigration.colonnes(c, "detail_achat")).doesNotContainKey("boutique_id");
            assertThat(BoutiquesMigration.colonnes(c, "detail_vente")).doesNotContainKey("boutique_id");
            assertThat(valeur(c, "SELECT mot_de_passe FROM utilisateur")).isEqualTo("empreinte-inchangee");
            assertThat(valeur(c, "SELECT role FROM utilisateur")).isEqualTo("ADMIN");
            assertThat(uniciteNom(c)).isFalse();
            BoutiquesMigration.migrer(c, false);
            BoutiquesMigration.migrer(c, true);
            assertThat(photographie(c)).isEqualTo(avant);
            assertThat(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique")).isEqualTo(1);
        }
    }

    @Test
    void laBaseVideRecoitUneBoutiqueSansInventerDeCompte() throws Exception {
        try (var c = base(false)) {
            BoutiquesMigration.migrer(c, true);
            assertThat(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique")).isEqualTo(1);
            for (String table : BoutiquesMigration.TABLES) assertThat(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table)).isZero();
            BoutiquesMigration.migrer(c, true);
        }
    }

    @Test
    void lesNomsDeCategorieSontUniquesParBoutiqueEtLesAutresIndexRestent() throws Exception {
        try (var c = base(true)) {
            execute(c, "CREATE UNIQUE INDEX uk_autre_nom_global ON categorie(nom)");
            execute(c, "CREATE UNIQUE INDEX uk_categorie_nom_id ON categorie(nom,id)");
            execute(c, "CREATE INDEX ix_categorie_nom ON categorie(nom)");
            BoutiquesMigration.migrer(c, true);
            execute(c, "INSERT INTO boutique(nom,date_creation) VALUES('Deuxième',CURRENT_TIMESTAMP)");
            execute(c, "INSERT INTO categorie(id,nom,boutique_id) VALUES(2,'Épicerie',2)");
            assertThatThrownBy(() -> execute(c, "INSERT INTO categorie(id,nom,boutique_id) VALUES(3,'Épicerie',1)")).isInstanceOf(SQLException.class);
            assertThat(BoutiquesMigration.indexesUniques(c, "categorie").values()).contains(List.of("nom", "id"));
            assertThat(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM information_schema.indexes WHERE LOWER(index_name)='ix_categorie_nom'")).isEqualTo(1);
        }
    }

    @Test
    void uneBaseDejaMultiboutiqueNestJamaisRameneeAUneBoutique() throws Exception {
        try (var c = base(true)) {
            BoutiquesMigration.migrer(c, true);
            execute(c, "INSERT INTO boutique(nom,date_creation) VALUES('Deuxième',CURRENT_TIMESTAMP)");
            execute(c, "INSERT INTO client(id,nom,boutique_id) VALUES(2,'Deuxième client',2)");
            execute(c, "INSERT INTO categorie(id,nom,boutique_id) VALUES(2,'Épicerie',2)");
            Map<String, List<List<Object>>> avant = photographie(c);
            BoutiquesMigration.migrer(c, false);
            BoutiquesMigration.migrer(c, true);
            assertThat(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique")).isEqualTo(2);
            assertThat(valeur(c, "SELECT boutique_id FROM client WHERE id=2")).isEqualTo("2");
            assertThat(valeur(c, "SELECT nom FROM boutique WHERE id=2")).isEqualTo("Deuxième");
            assertThat(photographie(c)).isEqualTo(avant);
        }
    }

    @ParameterizedTest
    @MethodSource("relations")
    void lesRelationsOrphelinesArretentAvantTouteModification(BoutiquesMigration.Relation relation) throws Exception {
        try (var c = base(true)) {
            execute(c, "UPDATE " + relation.enfant() + " SET " + relation.colonne() + "=999");
            Map<String, List<List<Object>>> avant = photographie(c);
            assertThatThrownBy(() -> BoutiquesMigration.migrer(c, true)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("orphelines");
            assertThat(BoutiquesMigration.tables(c)).doesNotContainKey("boutique");
            assertThat(photographie(c)).isEqualTo(avant);
        }
    }

    @ParameterizedTest
    @MethodSource("relationsObligatoires")
    void lesParentsObligatoiresAbsentsNeSontPasInventes(BoutiquesMigration.Relation relation) throws Exception {
        try (var c = base(true)) {
            execute(c, "UPDATE " + relation.enfant() + " SET " + relation.colonne() + "=NULL");
            assertThatThrownBy(() -> BoutiquesMigration.migrer(c, true)).hasMessageContaining("orphelines");
            assertThat(BoutiquesMigration.tables(c)).doesNotContainKey("boutique");
        }
    }

    @ParameterizedTest
    @MethodSource("relations")
    void lesRelationsEntreBoutiquesDifferentesSontRefusees(BoutiquesMigration.Relation relation) throws Exception {
        try (var c = base(true)) {
            BoutiquesMigration.migrer(c, true);
            execute(c, "INSERT INTO boutique(nom,date_creation) VALUES('Deuxième',CURRENT_TIMESTAMP)");
            execute(c, "UPDATE " + relation.parent() + " SET boutique_id=2");
            Map<String, List<List<Object>>> avant = photographie(c);
            assertThatThrownBy(() -> BoutiquesMigration.migrer(c, true)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("boutiques différentes");
            assertThat(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique")).isEqualTo(2);
            assertThat(photographie(c)).isEqualTo(avant);
            assertThat(valeur(c, "SELECT boutique_id FROM " + relation.parent())).isEqualTo("2");
        }
    }

    @Test
    void lesLignesDeVenteContenantLeProduitDuneAutreBoutiqueSontRefusees() throws Exception {
        try (var c = base(true)) {
            BoutiquesMigration.migrer(c, true);
            execute(c, "INSERT INTO boutique(nom,date_creation) VALUES('Deuxième',CURRENT_TIMESTAMP)");
            execute(c, "INSERT INTO categorie(id,nom,boutique_id) VALUES(2,'Autre',2)");
            execute(c, "INSERT INTO produit(id,nom,categorie_id,boutique_id) VALUES(2,'Autre produit',2,2)");
            execute(c, "UPDATE detail_vente SET produit_id=2");
            assertThatThrownBy(() -> BoutiquesMigration.migrer(c, false)).hasMessageContaining("detail_vente");
        }
    }

    @Test
    void unSchemaMixteOuUneBoutiqueSansColonnesEstRefuse() throws Exception {
        try (var c = base(true)) {
            execute(c, "ALTER TABLE client ADD COLUMN boutique_id BIGINT");
            assertThatThrownBy(() -> BoutiquesMigration.migrer(c, true)).hasMessageContaining("partiel ou mixte");
            assertThat(BoutiquesMigration.tables(c)).doesNotContainKey("boutique");
        }
        try (var c = base(true)) {
            execute(c, "CREATE TABLE boutique(id BIGINT)");
            assertThatThrownBy(() -> BoutiquesMigration.migrer(c, true)).hasMessageContaining("partiel ou mixte");
            assertThat(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique")).isZero();
        }
    }

    @Test
    void unSchemaMigreSansToutesLesContraintesNestPasRepareSilencieusement() throws Exception {
        try (var c = base(true)) {
            BoutiquesMigration.migrer(c, true);
            execute(c, "ALTER TABLE client DROP CONSTRAINT fk_client_boutique");
            assertThatThrownBy(() -> BoutiquesMigration.migrer(c, true)).hasMessageContaining("partiel");
            assertThat(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique")).isEqualTo(1);
        }
    }

    @Test
    void uneTableMetierManquanteEstRefusee() throws Exception {
        try (var c = base(false)) {
            execute(c, "DROP TABLE detail_vente");
            assertThatThrownBy(() -> BoutiquesMigration.migrer(c, true)).hasMessageContaining("onze tables");
            assertThat(BoutiquesMigration.tables(c)).doesNotContainKey("boutique");
        }
    }

    @Test
    void desCategoriesEnDoublonArretentAvantTouteEcriture() throws Exception {
        try (var c = base(true)) {
            execute(c, "ALTER TABLE categorie DROP CONSTRAINT uk_ancien_nom");
            execute(c, "INSERT INTO categorie VALUES(2,'Épicerie')");
            assertThatThrownBy(() -> BoutiquesMigration.migrer(c, true)).hasMessageContaining("doublon");
            assertThat(BoutiquesMigration.tables(c)).doesNotContainKey("boutique");
        }
    }

    @Test
    void lesMetadonneesEnMajusculesSontAcceptees() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "")) {
            creerSchema(c, true);
            BoutiquesMigration.migrer(c, true);
            BoutiquesMigration.migrer(c, false);
            assertThat(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique")).isEqualTo(1);
        }
    }

    @Test
    void uneSauvegardeAbsenteIncompleteOuAltereeEstRefusee() throws Exception {
        assertThatThrownBy(() -> BoutiquesMigration.verifierSauvegarde(null)).hasMessageContaining("sauvegarde complète");
        assertThatThrownBy(() -> BoutiquesMigration.verifierSauvegarde(dossier.toString())).hasMessageContaining("requis");
        Path dump = dossier.resolve("stock_pro.sql");
        Files.writeString(dump, "-- sauvegarde fictive sans données réelles\n");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dump)));
        Files.writeString(dossier.resolve("SHA256SUMS"), hash + "  stock_pro.sql\n");
        BoutiquesMigration.verifierSauvegarde(dossier.toString());
        Files.writeString(dump, "-- dump modifié\n");
        assertThatThrownBy(() -> BoutiquesMigration.verifierSauvegarde(dossier.toString())).hasMessageContaining("ne correspond pas");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:mariadb://localhost:3306/stock_pro?sslMode=disable", "jdbc:mariadb://127.0.0.1/stock_pro"})
    void leDumpLocalCorrespondUniquementALaBaseLocale(String url) {
        BoutiquesMigration.verifierCibleSauvegarde(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:mariadb://example.test:3306/stock_pro", "jdbc:mariadb://localhost:3307/stock_pro",
            "jdbc:mariadb://localhost:3306/autre", "jdbc:mysql://localhost:3306/stock_pro", "jdbc:mariadb://utilisateur:secret@localhost:3306/stock_pro"})
    void unDumpLocalNePermetPasDeModifierUneAutreCible(String url) {
        assertThatThrownBy(() -> BoutiquesMigration.verifierCibleSauvegarde(url))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("secret");
    }

    static Stream<BoutiquesMigration.Relation> relations() { return BoutiquesMigration.RELATIONS.stream(); }
    static Stream<BoutiquesMigration.Relation> relationsObligatoires() { return relations().filter(BoutiquesMigration.Relation::obligatoire); }
    static Connection base(boolean remplir) throws SQLException {
        var c = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE", "sa", "");
        creerSchema(c, remplir);
        return c;
    }
    static void creerSchema(Connection c, boolean remplir) throws SQLException {
        execute(c, "CREATE TABLE utilisateur(id BIGINT PRIMARY KEY,nom VARCHAR(255),email VARCHAR(255),mot_de_passe VARCHAR(255),role VARCHAR(255))");
        execute(c, "CREATE TABLE categorie(id BIGINT PRIMARY KEY,nom VARCHAR(255),CONSTRAINT uk_ancien_nom UNIQUE(nom))");
        execute(c, "CREATE TABLE client(id BIGINT PRIMARY KEY,nom VARCHAR(255))");
        execute(c, "CREATE TABLE fournisseur(id BIGINT PRIMARY KEY,nom VARCHAR(255))");
        execute(c, "CREATE TABLE produit(id BIGINT PRIMARY KEY,nom VARCHAR(255),quantite BIGINT,prix_achat DECIMAL(17,2),categorie_id BIGINT,fournisseur_id BIGINT)");
        execute(c, "CREATE TABLE achat(id BIGINT PRIMARY KEY,fournisseur_id BIGINT,montant_total DECIMAL(17,2),montant_verse DECIMAL(17,2))");
        execute(c, "CREATE TABLE vente(id BIGINT PRIMARY KEY,client_id BIGINT,montant_total DECIMAL(17,2),montant_verse DECIMAL(17,2))");
        execute(c, "CREATE TABLE mouvement_caisse(id BIGINT PRIMARY KEY,utilisateur_id BIGINT,achat_id BIGINT,vente_id BIGINT,montant DECIMAL(17,2))");
        execute(c, "CREATE TABLE operation_creation(jeton VARCHAR(36) PRIMARY KEY,utilisateur_id BIGINT,achat_id BIGINT,vente_id BIGINT,empreinte VARCHAR(64))");
        execute(c, "CREATE TABLE detail_achat(id BIGINT PRIMARY KEY,achat_id BIGINT,produit_id BIGINT,quantite INT)");
        execute(c, "CREATE TABLE detail_vente(id BIGINT PRIMARY KEY,vente_id BIGINT,produit_id BIGINT,quantite INT)");
        if (remplir) {
            execute(c, "INSERT INTO utilisateur VALUES(1,'Admin','admin@example.test','empreinte-inchangee','ADMIN')");
            execute(c, "INSERT INTO categorie VALUES(1,'Épicerie')");
            execute(c, "INSERT INTO client VALUES(1,'Client historique')");
            execute(c, "INSERT INTO fournisseur VALUES(1,'Fournisseur historique')");
            execute(c, "INSERT INTO produit VALUES(1,'Produit historique',6,999999999999999.99,1,1)");
            execute(c, "INSERT INTO achat VALUES(1,1,32000.50,30000.00)");
            execute(c, "INSERT INTO vente VALUES(1,1,33000.00,30000.00)");
            execute(c, "INSERT INTO mouvement_caisse VALUES(1,1,1,1,-30000.00)");
            execute(c, "INSERT INTO operation_creation VALUES('jeton-historique',1,1,1,'empreinte-historique')");
            execute(c, "INSERT INTO detail_achat VALUES(1,1,1,4)");
            execute(c, "INSERT INTO detail_vente VALUES(1,1,1,2)");
        }
    }
    static void execute(Connection c, String sql) throws SQLException { BoutiquesMigration.executer(c, sql); }
    static String valeur(Connection c, String sql) throws SQLException { try (var s = c.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getString(1); } }
    static boolean uniciteNom(Connection c) throws SQLException { return BoutiquesMigration.indexesUniques(c, "categorie").values().stream().anyMatch(colonnes -> colonnes.equals(List.of("nom"))); }
    static Map<String, List<List<Object>>> photographie(Connection c) throws SQLException {
        Map<String, List<List<Object>>> resultat = new LinkedHashMap<>();
        for (String table : BoutiquesMigration.TABLES) {
            List<List<Object>> lignes = new ArrayList<>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table + " ORDER BY " + (table.equals("operation_creation") ? "jeton" : "id"))) {
                while (r.next()) {
                    List<Object> ligne = new ArrayList<>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
                        if (!r.getMetaData().getColumnName(i).equalsIgnoreCase("boutique_id")) ligne.add(r.getObject(i));
                    }
                    lignes.add(ligne);
                }
            }
            resultat.put(table, lignes);
        }
        return resultat;
    }
}
