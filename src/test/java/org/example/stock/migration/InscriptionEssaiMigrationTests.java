package org.example.stock.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class InscriptionEssaiMigrationTests {
    @TempDir Path dossierPrive;

    @Test
    void uneSauvegardeAbsenteOuAltereeInterditLApplicationAvantConnexion() throws Exception {
        String url = "jdbc:mariadb://localhost:3306/stock_pro";
        assertThatThrownBy(() -> InscriptionEssaiMigration.verifierAutorisationApplication(url, null)).hasMessageContaining("INSCRIPTION_ESSAI_BACKUP_DIR");
        assertThatThrownBy(() -> InscriptionEssaiMigration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("Sauvegarde SQL complète");
        Files.writeString(dossierPrive.resolve("stock_pro.sql"), "-- copie de test\n");
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), "a".repeat(64) + "  stock_pro.sql\n");
        assertThatThrownBy(() -> InscriptionEssaiMigration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("ne correspond pas");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:mariadb://serveur-distant:3306/stock_pro", "jdbc:mariadb://localhost:3307/stock_pro", "jdbc:mariadb://localhost:3306/autre", "jdbc:mysql://localhost:3306/stock_pro"})
    void unDumpLocalNePeutPasAutoriserLaMigrationDUneAutreCible(String url) {
        assertThatThrownBy(() -> InscriptionEssaiMigration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("localhost:3306/stock_pro");
    }

    @Test
    void unServeurPriveDeTestPeutUtiliserUnPortIsoleSansCatalogue() {
        assertThat(InscriptionEssaiMariaDbTest.urlServeurTest("jdbc:mariadb://localhost:3306/stock_pro", null))
                .isEqualTo("jdbc:mariadb://localhost:3306/");
        assertThat(InscriptionEssaiMariaDbTest.urlServeurTest("jdbc:mariadb://localhost:3306/stock_pro", "jdbc:mariadb://127.0.0.1:13306/"))
                .isEqualTo("jdbc:mariadb://127.0.0.1:13306/");
        assertThatThrownBy(() -> InscriptionEssaiMariaDbTest.urlServeurTest("", "jdbc:mariadb://127.0.0.1:13306/stock_pro"))
                .hasMessageContaining("sans catalogue");
        assertThatThrownBy(() -> InscriptionEssaiMariaDbTest.urlServeurTest("", "jdbc:mariadb://serveur-distant:13306/"))
                .hasMessageContaining("Serveur MariaDB local");
    }

    @Test
    void leControleNeCreeAucunEssaiEtNeModifieAucuneDesTreizeTables() throws Exception {
        try (var c = base(true)) {
            var tables = BoutiquesMigration.tables(c);
            var avant = InscriptionEssaiMigration.photographie(c);
            InscriptionEssaiMigration.migrer(c, false);
            assertThat(BoutiquesMigration.tables(c)).isEqualTo(tables);
            assertThat(InscriptionEssaiMigration.colonnes(c, "boutique")).doesNotContainKeys("essai_debut", "essai_fin");
            assertThat(InscriptionEssaiMigration.photographie(c)).isEqualTo(avant).hasSize(13);
        }
    }

    @Test
    void laMigrationConserveLesComptesCoordonneesEtLiensExistantsSansImposerDeDelai() throws Exception {
        try (var c = base(true)) {
            var avant = InscriptionEssaiMigration.photographie(c);
            var essais = InscriptionEssaiMigration.essais(c);
            var tablesAvant = new HashSet<>(BoutiquesMigration.tables(c).keySet());
            InscriptionEssaiMigration.migrer(c, true);
            var ajoutees = new HashSet<>(BoutiquesMigration.tables(c).keySet()); ajoutees.removeAll(tablesAvant);
            assertThat(ajoutees).containsExactlyInAnyOrder("inscription_boutique", "parametres_plateforme");
            assertThat(InscriptionEssaiMigration.photographie(c)).isEqualTo(avant);
            assertThat(InscriptionEssaiMigration.essais(c)).isEqualTo(essais);
            assertThat(InscriptionEssaiMigration.donnees(c, InscriptionEssaiMigration.INSCRIPTION)).isEmpty();
            assertThat(InscriptionEssaiMigration.donnees(c, InscriptionEssaiMigration.PARAMETRES)).isEmpty();
            assertThat(valeur(c, "SELECT mot_de_passe FROM utilisateur WHERE id=1")).isEqualTo("empreinte-inchangee");
            assertThat(valeur(c, "SELECT actif FROM utilisateur WHERE id=3")).isEqualToIgnoringCase("FALSE");
            assertThat(valeur(c, "SELECT adresse FROM boutique WHERE id=2")).isEqualTo("Kaloum, Conakry");
        }
    }

    @Test
    void uneRelanceConserveLesEssaisExpiresDemandesEtDureePersonnalisee() throws Exception {
        try (var c = base(true)) {
            InscriptionEssaiMigration.migrer(c, true);
            remplir(c);
            var avant = InscriptionEssaiMigration.photographie(c);
            var essais = InscriptionEssaiMigration.essais(c);
            var inscriptions = InscriptionEssaiMigration.donnees(c, InscriptionEssaiMigration.INSCRIPTION);
            var parametres = InscriptionEssaiMigration.donnees(c, InscriptionEssaiMigration.PARAMETRES);
            InscriptionEssaiMigration.migrer(c, false);
            InscriptionEssaiMigration.migrer(c, true);
            InscriptionEssaiMigration.migrer(c, true);
            assertThat(InscriptionEssaiMigration.photographie(c)).isEqualTo(avant);
            assertThat(InscriptionEssaiMigration.essais(c)).isEqualTo(essais);
            assertThat(InscriptionEssaiMigration.donnees(c, InscriptionEssaiMigration.INSCRIPTION)).isEqualTo(inscriptions);
            assertThat(InscriptionEssaiMigration.donnees(c, InscriptionEssaiMigration.PARAMETRES)).isEqualTo(parametres);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"essai_debut", "essai_fin"})
    void uneSeuleColonneCompatibleEtVidePeutEtreCompleteeSansDeduction(String colonne) throws Exception {
        try (var c = base(true)) {
            sql(c, "ALTER TABLE boutique ADD COLUMN " + colonne + " TIMESTAMP(6) NULL");
            var avant = InscriptionEssaiMigration.photographie(c);
            InscriptionEssaiMigration.migrer(c, false);
            assertThat(InscriptionEssaiMigration.colonnes(c, "boutique").keySet()).doesNotContain(colonne.equals("essai_debut") ? "essai_fin" : "essai_debut");
            InscriptionEssaiMigration.migrer(c, true);
            assertThat(InscriptionEssaiMigration.photographie(c)).isEqualTo(avant);
            assertThat(compter(c, "SELECT COUNT(*) FROM boutique WHERE essai_debut IS NOT NULL OR essai_fin IS NOT NULL")).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"inscriptions", "parametres", "essais"})
    void desComposantsConformesDejaPresentsSontCompletesSansReecrireLesDonnees(String composant) throws Exception {
        try (var c = base(true)) {
            if (composant.equals("inscriptions")) { InscriptionEssaiMigration.creerInscriptions(c); inserer(c, "nouveau@example.test", "a".repeat(64)); }
            if (composant.equals("parametres")) { InscriptionEssaiMigration.creerParametres(c); sql(c, "INSERT INTO parametres_plateforme VALUES(1,45)"); }
            if (composant.equals("essais")) { InscriptionEssaiMigration.ajouterEssais(c); sql(c, "UPDATE boutique SET essai_debut='2025-01-01 00:00:00',essai_fin='2025-02-01 00:00:00' WHERE id=1"); }
            var avant = InscriptionEssaiMigration.photographie(c);
            var essais = InscriptionEssaiMigration.essais(c);
            var inscriptions = InscriptionEssaiMigration.donnees(c, InscriptionEssaiMigration.INSCRIPTION);
            var parametres = InscriptionEssaiMigration.donnees(c, InscriptionEssaiMigration.PARAMETRES);
            InscriptionEssaiMigration.migrer(c, true);
            assertThat(InscriptionEssaiMigration.photographie(c)).isEqualTo(avant);
            assertThat(InscriptionEssaiMigration.essais(c)).isEqualTo(essais);
            assertThat(InscriptionEssaiMigration.donnees(c, InscriptionEssaiMigration.INSCRIPTION)).isEqualTo(inscriptions);
            assertThat(InscriptionEssaiMigration.donnees(c, InscriptionEssaiMigration.PARAMETRES)).isEqualTo(parametres);
        }
    }

    @Test
    void laMigrationPrecedenteDoitEtreTermineeSansCreerSesTablesAutomatiquement() throws Exception {
        try (var c = ReinitialisationMdpMigrationTests.base(true)) {
            var avant = ReinitialisationMdpMigration.photographie(c);
            assertThatThrownBy(() -> InscriptionEssaiMigration.migrer(c, false)).hasMessageContaining("réinitialisation");
            assertThatThrownBy(() -> InscriptionEssaiMigration.migrer(c, true)).hasMessageContaining("réinitialisation");
            assertThat(ReinitialisationMdpMigration.photographie(c)).isEqualTo(avant);
            assertThat(BoutiquesMigration.tables(c)).doesNotContainKeys("inscription_boutique", "parametres_plateforme", "reinitialisation_mot_de_passe");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ALTER TABLE boutique ADD COLUMN essai_debut TIMESTAMP(3) NULL",
            "ALTER TABLE boutique ADD COLUMN essai_fin TIMESTAMP(6) WITH TIME ZONE NULL",
            "ALTER TABLE boutique ADD COLUMN essai_debut VARCHAR(30) NULL",
            "ALTER TABLE boutique ADD COLUMN essai_fin TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP",
            "ALTER TABLE boutique ADD COLUMN essai_debut TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP",
            "ALTER TABLE inscription_boutique DROP COLUMN expire_le",
            "ALTER TABLE inscription_boutique ADD COLUMN mot_de_passe VARCHAR(255)",
            "ALTER TABLE inscription_boutique ALTER COLUMN email VARCHAR(253)",
            "ALTER TABLE inscription_boutique ALTER COLUMN empreinte_jeton CHAR(64)",
            "ALTER TABLE inscription_boutique ALTER COLUMN empreinte_jeton DROP NOT NULL",
            "ALTER TABLE inscription_boutique ALTER COLUMN cree_le TIMESTAMP(0)",
            "ALTER TABLE inscription_boutique ALTER COLUMN expire_le SET DEFAULT CURRENT_TIMESTAMP",
            "ALTER TABLE inscription_boutique ALTER COLUMN empreinte_jeton SET DEFAULT 'NULL'",
            "ALTER TABLE inscription_boutique DROP CONSTRAINT uk_inscription_boutique_jeton",
            "ALTER TABLE inscription_boutique ADD CONSTRAINT uk_date_incorrecte UNIQUE(cree_le)",
            "ALTER TABLE parametres_plateforme DROP COLUMN duree_essai_jours",
            "ALTER TABLE parametres_plateforme ADD COLUMN inconnu INTEGER",
            "ALTER TABLE parametres_plateforme ALTER COLUMN id BIGINT",
            "ALTER TABLE parametres_plateforme ALTER COLUMN duree_essai_jours DROP NOT NULL",
            "ALTER TABLE parametres_plateforme ALTER COLUMN duree_essai_jours SET DEFAULT 30",
            "ALTER TABLE parametres_plateforme ADD CONSTRAINT uk_duree_incorrecte UNIQUE(duree_essai_jours)",
            "ALTER TABLE parametres_plateforme ADD CONSTRAINT fk_incorrecte FOREIGN KEY(id) REFERENCES parametres_plateforme(id)",
            "ALTER TABLE reinitialisation_mot_de_passe ALTER COLUMN cree_le TIMESTAMP(3)",
            "UPDATE utilisateur SET administrateur_plateforme=TRUE WHERE id=3",
            "UPDATE produit SET boutique_id=2 WHERE id=1"})
    void toutSchemaIncompatibleEstRefuseAvantDAjouterLesAutresComposants(String modification) throws Exception {
        try (var c = base(true)) {
            InscriptionEssaiMigration.creerInscriptions(c);
            InscriptionEssaiMigration.creerParametres(c);
            sql(c, modification);
            verifierRefusSansEcriture(c);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE boutique SET essai_fin=essai_debut WHERE id=1",
            "UPDATE boutique SET essai_debut=NULL WHERE id=1",
            "UPDATE boutique SET essai_fin=NULL WHERE id=1",
            "UPDATE inscription_boutique SET empreinte_jeton=REPEAT('z',64)",
            "UPDATE inscription_boutique SET empreinte_jeton='court'",
            "UPDATE inscription_boutique SET expire_le=cree_le",
            "UPDATE inscription_boutique SET email='Majuscules@example.test'",
            "UPDATE inscription_boutique SET email=' mauvais@example.test '",
            "UPDATE inscription_boutique SET email='email-invalide'",
            "UPDATE parametres_plateforme SET id=2",
            "UPDATE parametres_plateforme SET duree_essai_jours=0",
            "UPDATE parametres_plateforme SET duree_essai_jours=3651"})
    void touteDonneeIncoherenteEstRefuseeSansSuppressionNiCorrection(String modification) throws Exception {
        try (var c = base(true)) {
            InscriptionEssaiMigration.migrer(c, true); remplir(c); sql(c, modification);
            verifierRefusSansEcriture(c);
        }
    }

    @Test
    void uneDateIsolerNePermetPasDInventerUneFinDEssai() throws Exception {
        try (var c = base(true)) {
            sql(c, "ALTER TABLE boutique ADD COLUMN essai_debut TIMESTAMP(6) NULL");
            sql(c, "UPDATE boutique SET essai_debut='2025-01-01 00:00:00' WHERE id=1");
            verifierRefusSansEcriture(c);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"inscription_boutique", "parametres_plateforme"})
    void uneClePrimaireAbsenteEstRefuseeSansReparation(String table) throws Exception {
        try (var c = base(true)) {
            InscriptionEssaiMigration.migrer(c, true);
            String nom;
            try (var r = c.getMetaData().getPrimaryKeys(c.getCatalog(), c.getSchema(), BoutiquesMigration.tables(c).get(table))) {
                assertThat(r.next()).isTrue(); nom = r.getString("PK_NAME");
            }
            assertThat(nom).matches("[a-zA-Z0-9_]+");
            sql(c, "ALTER TABLE " + table + " DROP CONSTRAINT \"" + nom + "\"");
            verifierRefusSansEcriture(c);
        }
    }

    @Test
    void uneUniciteEquivalentSansLeNomPrevuEstRefusee() throws Exception {
        try (var c = base(true)) {
            InscriptionEssaiMigration.migrer(c, true);
            sql(c, "ALTER TABLE inscription_boutique DROP CONSTRAINT uk_inscription_boutique_jeton");
            sql(c, "ALTER TABLE inscription_boutique ADD CONSTRAINT nom_inattendu UNIQUE(empreinte_jeton)");
            verifierRefusSansEcriture(c);
        }
    }

    @Test
    void laClePrimaireEtLesUnicitesProtegentLesDemandesSansCreerDeBoutique() throws Exception {
        try (var c = base(true)) {
            InscriptionEssaiMigration.migrer(c, true);
            inserer(c, "nouveau@example.test", "a".repeat(64));
            assertThatThrownBy(() -> inserer(c, "nouveau@example.test", "b".repeat(64))).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> inserer(c, "autre@example.test", "a".repeat(64))).isInstanceOf(SQLException.class);
            assertThat(compter(c, "SELECT COUNT(*) FROM boutique")).isEqualTo(2);
        }
    }

    @Test
    void uneBaseVideResteSansBoutiqueSansCompteEtSansParametreInvente() throws Exception {
        try (var c = base(false)) {
            InscriptionEssaiMigration.migrer(c, true);
            InscriptionEssaiMigration.migrer(c, false);
            assertThat(compter(c, "SELECT COUNT(*) FROM boutique")).isZero();
            assertThat(compter(c, "SELECT COUNT(*) FROM utilisateur")).isZero();
            assertThat(InscriptionEssaiMigration.donnees(c, InscriptionEssaiMigration.PARAMETRES)).isEmpty();
        }
    }

    @Test
    void uneTransactionOuverteEstRefuseeSansEcriture() throws Exception {
        try (var c = base(true)) {
            var tables = BoutiquesMigration.tables(c);
            c.setAutoCommit(false);
            assertThatThrownBy(() -> InscriptionEssaiMigration.migrer(c, true)).hasMessageContaining("transaction ouverte");
            c.rollback(); c.setAutoCommit(true);
            assertThat(BoutiquesMigration.tables(c)).isEqualTo(tables);
        }
    }

    private static void verifierRefusSansEcriture(Connection c) throws Exception {
        var tables = BoutiquesMigration.tables(c);
        var boutique = InscriptionEssaiMigration.colonnes(c, "boutique");
        var schemaInscription = InscriptionEssaiMigration.colonnes(c, InscriptionEssaiMigration.INSCRIPTION);
        var schemaParametres = InscriptionEssaiMigration.colonnes(c, InscriptionEssaiMigration.PARAMETRES);
        var avant = InscriptionEssaiMigration.photographie(c);
        var essais = InscriptionEssaiMigration.essais(c);
        var inscriptions = InscriptionEssaiMigration.donnees(c, InscriptionEssaiMigration.INSCRIPTION);
        var parametres = InscriptionEssaiMigration.donnees(c, InscriptionEssaiMigration.PARAMETRES);
        assertThatThrownBy(() -> InscriptionEssaiMigration.migrer(c, false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InscriptionEssaiMigration.migrer(c, true)).isInstanceOf(IllegalStateException.class);
        assertThat(BoutiquesMigration.tables(c)).isEqualTo(tables);
        assertThat(InscriptionEssaiMigration.colonnes(c, "boutique")).isEqualTo(boutique);
        assertThat(InscriptionEssaiMigration.colonnes(c, InscriptionEssaiMigration.INSCRIPTION)).isEqualTo(schemaInscription);
        assertThat(InscriptionEssaiMigration.colonnes(c, InscriptionEssaiMigration.PARAMETRES)).isEqualTo(schemaParametres);
        assertThat(InscriptionEssaiMigration.photographie(c)).isEqualTo(avant);
        assertThat(InscriptionEssaiMigration.essais(c)).isEqualTo(essais);
        assertThat(InscriptionEssaiMigration.donnees(c, InscriptionEssaiMigration.INSCRIPTION)).isEqualTo(inscriptions);
        assertThat(InscriptionEssaiMigration.donnees(c, InscriptionEssaiMigration.PARAMETRES)).isEqualTo(parametres);
    }

    static Connection base(boolean remplir) throws SQLException {
        var c = ReinitialisationMdpMigrationTests.base(remplir);
        ReinitialisationMdpMigration.migrer(c, true);
        if (remplir) ReinitialisationMdpMigrationTests.inserer(c, 3, "a".repeat(64), "b".repeat(64));
        else sql(c, "DELETE FROM boutique"); // Le helper de l'ancienne migration invente sa boutique principale ; cette fixture doit être réellement vide.
        return c;
    }
    static void remplir(Connection c) throws SQLException {
        c.setReadOnly(false);
        sql(c, "UPDATE boutique SET essai_debut='2025-01-01 00:00:00.123456',essai_fin='2025-01-31 00:00:00.123456' WHERE id=1");
        inserer(c, "nouveau@example.test", "a".repeat(64));
        sql(c, "INSERT INTO parametres_plateforme(id,duree_essai_jours) VALUES(1,45)");
    }
    static void inserer(Connection c, String email, String empreinte) throws SQLException {
        try (var s = c.prepareStatement("INSERT INTO inscription_boutique(email,empreinte_jeton,cree_le,expire_le) VALUES(?,?,?,?)")) {
            s.setString(1, email); s.setString(2, empreinte);
            s.setTimestamp(3, Timestamp.valueOf("2025-01-01 00:00:00.123456"));
            s.setTimestamp(4, Timestamp.valueOf("2025-01-01 00:30:00.123456")); s.executeUpdate();
        }
    }
    private static void sql(Connection c, String sql) throws SQLException { BoutiquesMigration.executer(c, sql); }
    private static long compter(Connection c, String sql) throws SQLException { return BoutiquesMigration.compter(c, sql); }
    private static String valeur(Connection c, String sql) throws SQLException { return BoutiquesMigrationTests.valeur(c, sql); }
}
