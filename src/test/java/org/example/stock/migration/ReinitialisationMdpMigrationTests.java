package org.example.stock.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class ReinitialisationMdpMigrationTests {
    @Test
    void leControleNeCreeAucuneTableEtConserveToutesLesColonnesDesDouzeTables() throws Exception {
        try (var c = base(true)) {
            var tables = BoutiquesMigration.tables(c);
            var avant = ReinitialisationMdpMigration.photographie(c);
            ReinitialisationMdpMigration.migrer(c, false);
            assertThat(BoutiquesMigration.tables(c)).isEqualTo(tables).doesNotContainKey(ReinitialisationMdpMigration.TABLE);
            assertThat(ReinitialisationMdpMigration.photographie(c)).isEqualTo(avant).hasSize(12);
        }
    }

    @Test
    void laMigrationCreeUneSeuleTableVideSansModifierComptesCoordonneesEtHistoriques() throws Exception {
        try (var c = base(true)) {
            var avant = ReinitialisationMdpMigration.photographie(c);
            Set<String> tablesAvant = new HashSet<>(BoutiquesMigration.tables(c).keySet());
            ReinitialisationMdpMigration.migrer(c, true);
            Set<String> ajoutees = new HashSet<>(BoutiquesMigration.tables(c).keySet()); ajoutees.removeAll(tablesAvant);
            assertThat(ajoutees).containsExactly(ReinitialisationMdpMigration.TABLE);
            assertThat(ReinitialisationMdpMigration.photographie(c)).isEqualTo(avant);
            assertThat(ReinitialisationMdpMigration.demandes(c)).isEmpty();
            assertThat(ReinitialisationMdpMigration.colonnes(c)).containsOnlyKeys(
                    "utilisateur_id", "empreinte_jeton", "empreinte_mot_de_passe", "cree_le", "expire_le");
            assertThat(valeur(c, "SELECT mot_de_passe FROM utilisateur WHERE id=1")).isEqualTo("empreinte-inchangee");
            assertThat(valeur(c, "SELECT actif FROM utilisateur WHERE id=3")).isEqualToIgnoringCase("FALSE");
            assertThat(valeur(c, "SELECT adresse FROM boutique WHERE id=2")).isEqualTo("Kaloum, Conakry");
        }
    }

    @Test
    void unSchemaConformeConserveLesDemandesExistantesYComprisExpireesEtLesComptesDesactives() throws Exception {
        try (var c = base(true)) {
            ReinitialisationMdpMigration.creerTable(c);
            inserer(c, 1, "a".repeat(64), "b".repeat(64), "2025-01-01 00:00:00.123456", "2025-01-01 00:30:00.123456");
            inserer(c, 3, "C".repeat(64), "d".repeat(64), "2025-01-02 00:00:00.000001", "2025-01-02 00:30:00.000001");
            var avant = ReinitialisationMdpMigration.photographie(c);
            var demandes = ReinitialisationMdpMigration.demandes(c);
            ReinitialisationMdpMigration.migrer(c, false);
            ReinitialisationMdpMigration.migrer(c, true);
            ReinitialisationMdpMigration.migrer(c, true);
            assertThat(ReinitialisationMdpMigration.photographie(c)).isEqualTo(avant);
            assertThat(ReinitialisationMdpMigration.demandes(c)).isEqualTo(demandes);
        }
    }

    @Test
    void lesContraintesGarantissentUnJetonParUtilisateurEtUneEmpreinteUniqueLieeAUnUtilisateurExistant() throws Exception {
        try (var c = base(true)) {
            ReinitialisationMdpMigration.migrer(c, true);
            inserer(c, 1, "a".repeat(64), "b".repeat(64));
            assertThatThrownBy(() -> inserer(c, 1, "c".repeat(64), "d".repeat(64))).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> inserer(c, 2, "a".repeat(64), "d".repeat(64))).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> inserer(c, 999, "c".repeat(64), "d".repeat(64))).isInstanceOf(SQLException.class);
            // Plusieurs comptes peuvent avoir la même empreinte de mot de passe : elle n'est pas unique.
            inserer(c, 2, "c".repeat(64), "b".repeat(64));
            assertThat(compter(c, "SELECT COUNT(*) FROM reinitialisation_mot_de_passe")).isEqualTo(2);
        }
    }

    @Test
    void laMigrationDesParametresDoitEtreTermineeSansLaCompleterAutomatiquement() throws Exception {
        try (var c = ParametresBoutiqueMigrationTests.base(true)) {
            var avant = ReinitialisationMdpMigration.photographie(c);
            assertThatThrownBy(() -> ReinitialisationMdpMigration.migrer(c, false)).hasMessageContaining("migration des paramètres boutique");
            assertThatThrownBy(() -> ReinitialisationMdpMigration.migrer(c, true)).hasMessageContaining("migration des paramètres boutique");
            assertThat(BoutiquesMigration.tables(c)).doesNotContainKey(ReinitialisationMdpMigration.TABLE);
            assertThat(BoutiquesMigration.colonnes(c, "boutique")).doesNotContainKeys("adresse", "telephone", "email");
            assertThat(ReinitialisationMdpMigration.photographie(c)).isEqualTo(avant);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALTER TABLE boutique ALTER COLUMN adresse VARCHAR(501)",
            "ALTER TABLE utilisateur ALTER COLUMN actif DROP DEFAULT",
            "UPDATE utilisateur SET administrateur_plateforme=TRUE WHERE id=3",
            "UPDATE produit SET boutique_id=2 WHERE id=1"})
    void lesContraintesEtRelationsAnterieuresSontRecontroleesAvantToutAjout(String modification) throws Exception {
        try (var c = base(true)) {
            sql(c, modification);
            var avant = ReinitialisationMdpMigration.photographie(c);
            assertThatThrownBy(() -> ReinitialisationMdpMigration.migrer(c, true)).isInstanceOf(IllegalStateException.class);
            assertThat(BoutiquesMigration.tables(c)).doesNotContainKey(ReinitialisationMdpMigration.TABLE);
            assertThat(ReinitialisationMdpMigration.photographie(c)).isEqualTo(avant);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALTER TABLE reinitialisation_mot_de_passe DROP COLUMN expire_le",
            "ALTER TABLE reinitialisation_mot_de_passe ADD COLUMN inconnu VARCHAR(50)",
            "ALTER TABLE reinitialisation_mot_de_passe ALTER COLUMN empreinte_jeton VARCHAR(63)",
            "ALTER TABLE reinitialisation_mot_de_passe ALTER COLUMN empreinte_mot_de_passe VARCHAR(65)",
            "ALTER TABLE reinitialisation_mot_de_passe ALTER COLUMN empreinte_mot_de_passe CHAR(64)",
            "ALTER TABLE reinitialisation_mot_de_passe ALTER COLUMN empreinte_jeton DROP NOT NULL",
            "ALTER TABLE reinitialisation_mot_de_passe ALTER COLUMN empreinte_mot_de_passe DROP NOT NULL",
            "ALTER TABLE reinitialisation_mot_de_passe ALTER COLUMN cree_le DROP NOT NULL",
            "ALTER TABLE reinitialisation_mot_de_passe ALTER COLUMN expire_le DROP NOT NULL",
            "ALTER TABLE reinitialisation_mot_de_passe ALTER COLUMN cree_le TIMESTAMP(0)",
            "ALTER TABLE reinitialisation_mot_de_passe ALTER COLUMN expire_le TIMESTAMP(3)",
            "ALTER TABLE reinitialisation_mot_de_passe ALTER COLUMN expire_le TIMESTAMP(6) WITH TIME ZONE",
            "ALTER TABLE reinitialisation_mot_de_passe ALTER COLUMN empreinte_mot_de_passe SET DEFAULT ''",
            "ALTER TABLE reinitialisation_mot_de_passe ALTER COLUMN empreinte_mot_de_passe SET DEFAULT 'NULL'",
            "ALTER TABLE reinitialisation_mot_de_passe ALTER COLUMN cree_le SET DEFAULT CURRENT_TIMESTAMP",
            "ALTER TABLE reinitialisation_mot_de_passe DROP PRIMARY KEY",
            "ALTER TABLE reinitialisation_mot_de_passe DROP CONSTRAINT uk_reinitialisation_mdp_jeton",
            "ALTER TABLE reinitialisation_mot_de_passe DROP CONSTRAINT fk_reinitialisation_mdp_utilisateur",
            "ALTER TABLE reinitialisation_mot_de_passe ADD CONSTRAINT uk_mdp_incorrect UNIQUE(empreinte_mot_de_passe)"})
    void lesSchemasPartielsTypesDefautsEtContraintesIncorrectsSontRefusesSansReparation(String modification) throws Exception {
        try (var c = base(true)) {
            ReinitialisationMdpMigration.creerTable(c);
            if (!modification.contains("VARCHAR(63)")) inserer(c, 1, "a".repeat(64), "b".repeat(64));
            if (modification.endsWith("DROP PRIMARY KEY")) supprimerClePrimaireH2(c);
            else sql(c, modification);
            verifierRefusSansEcriture(c, "non conforme");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"REFERENCES utilisateur(id) ON DELETE CASCADE",
            "REFERENCES utilisateur(id) ON UPDATE CASCADE", "REFERENCES boutique(id)"})
    void uneRelationVersLeMauvaisParentOuAvecCascadeEstRefuseeSansChangement(String cible) throws Exception {
        try (var c = base(true)) {
            ReinitialisationMdpMigration.creerTable(c);
            sql(c, "ALTER TABLE reinitialisation_mot_de_passe DROP CONSTRAINT fk_reinitialisation_mdp_utilisateur");
            sql(c, "ALTER TABLE reinitialisation_mot_de_passe ADD CONSTRAINT fk_incorrecte FOREIGN KEY(utilisateur_id) " + cible);
            verifierRefusSansEcriture(c, "non conforme");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE reinitialisation_mot_de_passe SET empreinte_jeton='court'",
            "UPDATE reinitialisation_mot_de_passe SET empreinte_mot_de_passe='court'",
            "UPDATE reinitialisation_mot_de_passe SET empreinte_jeton=REPEAT('z',64)",
            "UPDATE reinitialisation_mot_de_passe SET empreinte_mot_de_passe=REPEAT('z',64)",
            "UPDATE reinitialisation_mot_de_passe SET expire_le=cree_le",
            "UPDATE reinitialisation_mot_de_passe SET expire_le=TIMESTAMP '2024-01-01 00:00:00'"})
    void lesDemandesIncoherentesSontRefuseesSansLesSupprimerOuChangerLeCompte(String modification) throws Exception {
        try (var c = base(true)) {
            ReinitialisationMdpMigration.creerTable(c);
            inserer(c, 1, "a".repeat(64), "b".repeat(64));
            sql(c, modification);
            verifierRefusSansEcriture(c, "incohérentes");
        }
    }

    @Test
    void uneDemandeOrphelineNestNiReattribueeNiSupprimee() throws Exception {
        try (var c = base(true)) {
            ReinitialisationMdpMigration.creerTable(c);
            sql(c, "SET REFERENTIAL_INTEGRITY FALSE");
            inserer(c, 999, "a".repeat(64), "b".repeat(64));
            sql(c, "SET REFERENTIAL_INTEGRITY TRUE");
            verifierRefusSansEcriture(c, "incohérentes");
        }
    }

    @Test
    void uneBaseVideNeRecoitAucunCompteOuJetonInvente() throws Exception {
        try (var c = base(false)) {
            var avant = ReinitialisationMdpMigration.photographie(c);
            ReinitialisationMdpMigration.migrer(c, true);
            ReinitialisationMdpMigration.migrer(c, false);
            assertThat(ReinitialisationMdpMigration.photographie(c)).isEqualTo(avant);
            assertThat(ReinitialisationMdpMigration.demandes(c)).isEmpty();
            assertThat(compter(c, "SELECT COUNT(*) FROM utilisateur")).isZero();
        }
    }

    @Test
    void lesTablesEtColonnesHistoriquesEnMajusculesSontAcceptees() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:h2:mem:stock_reinitialisation_mdp_test_" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "")) {
            BoutiquesMigrationTests.creerSchema(c, true);
            BoutiquesMigration.migrer(c, true);
            PlateformeMigration.migrer(c, true, "admin@example.test");
            AccesUtilisateurMigration.migrer(c, true);
            ParametresBoutiqueMigration.migrer(c, true);
            var avant = ReinitialisationMdpMigration.photographie(c);
            ReinitialisationMdpMigration.migrer(c, true);
            ReinitialisationMdpMigration.migrer(c, false);
            assertThat(ReinitialisationMdpMigration.photographie(c)).isEqualTo(avant);
        }
    }

    @Test
    void uneTransactionOuverteEstRefuseeSansCreerLeStockage() throws Exception {
        try (var c = base(true)) {
            c.setAutoCommit(false);
            assertThatThrownBy(() -> ReinitialisationMdpMigration.migrer(c, true)).hasMessageContaining("transaction ouverte");
            c.rollback(); c.setAutoCommit(true);
            assertThat(BoutiquesMigration.tables(c)).doesNotContainKey(ReinitialisationMdpMigration.TABLE);
        }
    }

    private static void verifierRefusSansEcriture(Connection c, String message) throws Exception {
        var tables = BoutiquesMigration.tables(c);
        var colonnes = ReinitialisationMdpMigration.colonnes(c);
        var avant = ReinitialisationMdpMigration.photographie(c);
        var demandes = ReinitialisationMdpMigration.demandes(c);
        assertThatThrownBy(() -> ReinitialisationMdpMigration.migrer(c, false)).hasMessageContaining(message);
        assertThatThrownBy(() -> ReinitialisationMdpMigration.migrer(c, true)).hasMessageContaining(message);
        assertThat(BoutiquesMigration.tables(c)).isEqualTo(tables);
        assertThat(ReinitialisationMdpMigration.colonnes(c)).isEqualTo(colonnes);
        assertThat(ReinitialisationMdpMigration.photographie(c)).isEqualTo(avant);
        assertThat(ReinitialisationMdpMigration.demandes(c)).isEqualTo(demandes);
    }

    static Connection base(boolean remplir) throws SQLException {
        var c = ParametresBoutiqueMigrationTests.base(remplir);
        ParametresBoutiqueMigration.migrer(c, true);
        if (remplir) ParametresBoutiqueMigrationTests.remplirCoordonnees(c);
        return c;
    }
    static void inserer(Connection c, long utilisateur, String jeton, String motDePasse) throws SQLException {
        inserer(c, utilisateur, jeton, motDePasse, "2025-01-01 00:00:00.123456", "2025-01-01 00:30:00.123456");
    }
    static void inserer(Connection c, long utilisateur, String jeton, String motDePasse, String creeLe, String expireLe) throws SQLException {
        try (var s = c.prepareStatement("INSERT INTO reinitialisation_mot_de_passe(utilisateur_id,empreinte_jeton,empreinte_mot_de_passe,cree_le,expire_le) VALUES(?,?,?,?,?)")) {
            s.setLong(1, utilisateur); s.setString(2, jeton); s.setString(3, motDePasse);
            s.setTimestamp(4, Timestamp.valueOf(creeLe)); s.setTimestamp(5, Timestamp.valueOf(expireLe)); s.executeUpdate();
        }
    }
    private static void supprimerClePrimaireH2(Connection c) throws SQLException {
        String contrainte;
        try (var r = c.getMetaData().getPrimaryKeys(c.getCatalog(), c.getSchema(),
                BoutiquesMigration.tables(c).get(ReinitialisationMdpMigration.TABLE))) {
            if (!r.next()) throw new IllegalStateException("Clé primaire requise pour préparer le scénario H2.");
            contrainte = r.getString("PK_NAME");
        }
        if (contrainte == null || !contrainte.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalStateException("Nom de contrainte H2 inattendu.");
        }
        String guillemet = c.getMetaData().getIdentifierQuoteString().strip();
        sql(c, "ALTER TABLE reinitialisation_mot_de_passe DROP CONSTRAINT " + guillemet + contrainte + guillemet);
    }
    private static void sql(Connection c, String sql) throws SQLException { BoutiquesMigration.executer(c, sql); }
    private static long compter(Connection c, String sql) throws SQLException { return BoutiquesMigration.compter(c, sql); }
    private static String valeur(Connection c, String sql) throws SQLException { return BoutiquesMigrationTests.valeur(c, sql); }
}
