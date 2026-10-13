package org.example.stock.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class AbonnementMigrationTests {
    @TempDir Path dossierPrive;

    @Test
    void laSauvegardeCompleteEtSonEmpreinteSontObligatoiresAvantConnexion() throws Exception {
        String url = "jdbc:mariadb://localhost:3306/stock_pro";
        assertThatThrownBy(() -> AbonnementMigration.verifierAutorisationApplication(url, null)).hasMessageContaining("ABONNEMENT_BACKUP_DIR");
        assertThatThrownBy(() -> AbonnementMigration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("Sauvegarde SQL complète");
        Path dump = dossierPrive.resolve("stock_pro.sql");
        Files.writeString(dump, "-- copie privée de test\n");
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), "a".repeat(64) + "  stock_pro.sql\n");
        assertThatThrownBy(() -> AbonnementMigration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("ne correspond pas");
        String empreinte = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dump)));
        Files.writeString(dossierPrive.resolve("SHA256SUMS"), empreinte + "  stock_pro.sql\n");
        assertThatCode(() -> AbonnementMigration.verifierAutorisationApplication(url, dossierPrive.toString())).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:mariadb://serveur-distant:3306/stock_pro", "jdbc:mariadb://localhost:3307/stock_pro", "jdbc:mariadb://localhost:3306/autre", "jdbc:mysql://localhost:3306/stock_pro"})
    void laSauvegardeLocaleNePermetPasDeMigrerUneAutreBase(String url) {
        assertThatThrownBy(() -> AbonnementMigration.verifierAutorisationApplication(url, dossierPrive.toString())).hasMessageContaining("localhost:3306/stock_pro");
    }

    @Test
    void leControleConserveLesQuinzeTablesSansAjouterDePeriode() throws Exception {
        try (var c = base(true)) {
            var schema = BoutiquesMigration.tables(c); var avant = AbonnementMigration.photographie(c);
            AbonnementMigration.migrer(c, false);
            assertThat(BoutiquesMigration.tables(c)).isEqualTo(schema).hasSize(15);
            assertThat(AbonnementMigration.photographie(c)).isEqualTo(avant).hasSize(15);
            assertThat(InscriptionEssaiMigration.colonnes(c, "boutique")).doesNotContainKeys("abonnement_debut", "abonnement_fin");
        }
    }

    @Test
    void laMigrationConserveLesEssaisComptesCoordonneesEtDemandesSansInventerDAbonnement() throws Exception {
        try (var c = base(true)) {
            var avant = AbonnementMigration.photographie(c); var periodes = AbonnementMigration.periodes(c);
            AbonnementMigration.migrer(c, true);
            assertThat(AbonnementMigration.photographie(c)).isEqualTo(avant);
            assertThat(AbonnementMigration.periodes(c)).isEqualTo(periodes);
            assertThat(AbonnementMigration.donnees(c)).isEmpty();
            assertThat(BoutiquesMigration.tables(c)).hasSize(16);
            assertThat(BoutiquesMigrationTests.valeur(c, "SELECT mot_de_passe FROM utilisateur WHERE id=1")).isEqualTo("empreinte-inchangee");
            assertThat(BoutiquesMigrationTests.valeur(c, "SELECT actif FROM utilisateur WHERE id=3")).isEqualToIgnoringCase("FALSE");
            assertThat(BoutiquesMigrationTests.valeur(c, "SELECT adresse FROM boutique WHERE id=2")).isEqualTo("Kaloum, Conakry");
            assertThat(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE abonnement_debut IS NOT NULL OR abonnement_fin IS NOT NULL")).isZero();
        }
    }

    @Test
    void laRelanceConservePeriodesExpireesEtHistoriqueEtLEtatDesQuinzeTables() throws Exception {
        try (var c = base(true)) {
            AbonnementMigration.migrer(c, true); remplir(c);
            var avant = AbonnementMigration.photographie(c); var periodes = AbonnementMigration.periodes(c); var histoire = AbonnementMigration.donnees(c);
            AbonnementMigration.migrer(c, false); AbonnementMigration.migrer(c, true);
            assertThat(AbonnementMigration.photographie(c)).isEqualTo(avant);
            assertThat(AbonnementMigration.periodes(c)).isEqualTo(periodes);
            assertThat(AbonnementMigration.donnees(c)).isEqualTo(histoire).hasSize(6);
        }
    }

    @Test
    void unMoisCalendaireAccepteLaFinDeFevrierBissextileSansChangerLeJournal() throws Exception {
        try (var c = base(true)) {
            AbonnementMigration.migrer(c, true); inserer(c, "ACTIVATION_ABONNEMENT", 1, true, 1);
            sql(c, "UPDATE historique_abonnement SET periode_debut='2024-01-31 15:00:00.123456',periode_fin='2024-02-29 15:00:00.123456',ancienne_fin='2024-01-31 15:00:00.123456' WHERE id=1");
            var avant = AbonnementMigration.donnees(c);
            AbonnementMigration.migrer(c, false);
            assertThat(AbonnementMigration.donnees(c)).isEqualTo(avant);
            sql(c, "UPDATE historique_abonnement SET periode_fin='2024-03-01 15:00:00.123456' WHERE id=1");
            verifierRefusSansEcriture(c);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"debut", "fin", "periodes", "historique"})
    void unComposantConformeApresInterruptionEstCompleteSansPerte(String composant) throws Exception {
        try (var c = base(true)) {
            switch (composant) {
                case "debut" -> sql(c, "ALTER TABLE boutique ADD COLUMN abonnement_debut TIMESTAMP(6) NULL");
                case "fin" -> sql(c, "ALTER TABLE boutique ADD COLUMN abonnement_fin TIMESTAMP(6) NULL");
                case "periodes" -> { AbonnementMigration.ajouterPeriodes(c); remplirPeriode(c); }
                case "historique" -> { AbonnementMigration.creerHistorique(c); inserer(c, "ACCES_OFFERT", null, false, 1); }
                default -> throw new AssertionError();
            }
            var avant = AbonnementMigration.photographie(c); var periodes = AbonnementMigration.periodes(c); var histoire = AbonnementMigration.donnees(c);
            AbonnementMigration.migrer(c, true);
            assertThat(AbonnementMigration.photographie(c)).isEqualTo(avant);
            assertThat(AbonnementMigration.periodes(c)).isEqualTo(periodes);
            assertThat(AbonnementMigration.donnees(c)).isEqualTo(histoire);
            assertThat(BoutiquesMigration.tables(c)).hasSize(16);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DROP TABLE inscription_boutique", "DROP TABLE parametres_plateforme", "ALTER TABLE boutique DROP COLUMN essai_fin",
            "ALTER TABLE reinitialisation_mot_de_passe ALTER COLUMN cree_le TIMESTAMP(3) NOT NULL", "ALTER TABLE utilisateur ALTER COLUMN actif SET DEFAULT FALSE"})
    void lesPrerequisManquantsOuInvalidesSontRefusesAvantLePremierDDL(String modification) throws Exception {
        try (var c = base(true)) { sql(c, modification); verifierRefusSansEcriture(c); }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ALTER TABLE boutique ALTER COLUMN abonnement_debut TIMESTAMP(3) NULL",
            "ALTER TABLE boutique ALTER COLUMN abonnement_fin SET DEFAULT CURRENT_TIMESTAMP",
            "ALTER TABLE historique_abonnement DROP COLUMN motif",
            "ALTER TABLE historique_abonnement ADD COLUMN paiement VARCHAR(255)",
            "ALTER TABLE historique_abonnement ALTER COLUMN acteur_nom VARCHAR(254) NOT NULL",
            "ALTER TABLE historique_abonnement ALTER COLUMN action CHAR(32) NOT NULL",
            "ALTER TABLE historique_abonnement ALTER COLUMN jeton_action VARCHAR(36) NULL",
            "ALTER TABLE historique_abonnement ALTER COLUMN motif SET DEFAULT 'raison'",
            "ALTER TABLE historique_abonnement ALTER COLUMN quantite BIGINT NULL",
            "ALTER TABLE historique_abonnement ALTER COLUMN cree_le TIMESTAMP(3) NOT NULL",
            "ALTER TABLE historique_abonnement ALTER COLUMN ancienne_fin TIMESTAMP(3) NULL",
            "ALTER TABLE historique_abonnement DROP CONSTRAINT uk_historique_abonnement_jeton",
            "ALTER TABLE historique_abonnement ADD CONSTRAINT unique_incorrecte UNIQUE(cree_le)",
            "DROP INDEX idx_historique_abonnement_boutique_date",
            "ALTER TABLE historique_abonnement DROP CONSTRAINT fk_historique_abonnement_boutique",
            "ALTER TABLE historique_abonnement DROP CONSTRAINT fk_historique_abonnement_acteur"})
    void unSchemaIncompatibleEstRefuseSansReparationNiEcriture(String modification) throws Exception {
        try (var c = base(true)) { AbonnementMigration.migrer(c, true); sql(c, modification); verifierRefusSansEcriture(c); }
    }

    @Test
    void uneUniciteEquivalenteSansLeNomAttenduEstRefusee() throws Exception {
        try (var c = base(true)) {
            AbonnementMigration.migrer(c, true);
            sql(c, "ALTER TABLE historique_abonnement DROP CONSTRAINT uk_historique_abonnement_jeton");
            sql(c, "ALTER TABLE historique_abonnement ADD CONSTRAINT autre_nom UNIQUE(jeton_action)");
            verifierRefusSansEcriture(c);
        }
    }

    @Test
    void desRelationsEnCascadeSontRefuseesPourConserverLeJournal() throws Exception {
        try (var c = base(true)) {
            AbonnementMigration.migrer(c, true);
            sql(c, "ALTER TABLE historique_abonnement DROP CONSTRAINT fk_historique_abonnement_acteur");
            sql(c, "ALTER TABLE historique_abonnement ADD CONSTRAINT fk_historique_abonnement_acteur FOREIGN KEY(acteur_id) REFERENCES utilisateur(id) ON DELETE CASCADE");
            verifierRefusSansEcriture(c);
        }
    }

    @Test
    void unIndexDeConsultationAvecLeMauvaisOrdreEstRefuse() throws Exception {
        try (var c = base(true)) {
            AbonnementMigration.migrer(c, true);
            sql(c, "DROP INDEX idx_historique_abonnement_boutique_date");
            sql(c, "CREATE INDEX idx_historique_abonnement_boutique_date ON historique_abonnement(boutique_id,id,cree_le)");
            verifierRefusSansEcriture(c);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE boutique SET abonnement_debut=NULL WHERE id=1",
            "UPDATE boutique SET abonnement_fin=abonnement_debut WHERE id=1",
            "UPDATE historique_abonnement SET action='PAIEMENT' WHERE id=1",
            "UPDATE historique_abonnement SET jeton_action='invalid' WHERE id=1",
            "UPDATE historique_abonnement SET jeton_action=UPPER(jeton_action) WHERE id=1",
            "UPDATE historique_abonnement SET acteur_nom=' ' WHERE id=1",
            "UPDATE historique_abonnement SET acteur_nom=' Nom ' WHERE id=1",
            "UPDATE historique_abonnement SET quantite=0 WHERE id=1",
            "UPDATE historique_abonnement SET quantite=3651 WHERE id=1",
            "UPDATE historique_abonnement SET quantite=8 WHERE id=1",
            "UPDATE historique_abonnement SET quantite=2 WHERE id=2",
            "UPDATE historique_abonnement SET quantite=NULL WHERE id=1",
            "UPDATE historique_abonnement SET periode_fin=periode_debut WHERE id=1",
            "UPDATE historique_abonnement SET periode_debut=NULL WHERE id=1",
            "UPDATE historique_abonnement SET ancienne_fin='2026-01-01' WHERE id=1",
            "UPDATE historique_abonnement SET quantite=1 WHERE id=3",
            "UPDATE historique_abonnement SET ancienne_fin='2025-01-01' WHERE id=3",
            "UPDATE historique_abonnement SET motif='' WHERE id=1"})
    void lesDonneesIncoherentesSontRefuseesSansCorrectionNiSuppression(String modification) throws Exception {
        try (var c = base(true)) { AbonnementMigration.migrer(c, true); remplir(c); sql(c, modification); verifierRefusSansEcriture(c); }
    }

    @Test
    void uneDateIsoleeEstRefuseeSansInventerDeFin() throws Exception {
        try (var c = base(true)) {
            sql(c, "ALTER TABLE boutique ADD COLUMN abonnement_debut TIMESTAMP(6) NULL");
            sql(c, "UPDATE boutique SET abonnement_debut='2025-01-01' WHERE id=1");
            verifierRefusSansEcriture(c);
        }
    }

    @Test
    void leJetonUniqueEtLesReferencesProtegentLeJournal() throws Exception {
        try (var c = base(true)) {
            AbonnementMigration.migrer(c, true); inserer(c, "ACCES_OFFERT", null, false, 1);
            assertThatThrownBy(() -> inserer(c, "ACCES_OFFERT", null, false, 1)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> sql(c, "UPDATE historique_abonnement SET boutique_id=999 WHERE id=1")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> sql(c, "UPDATE historique_abonnement SET acteur_id=999 WHERE id=1")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> sql(c, "DELETE FROM utilisateur WHERE id=1")).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void uneBaseVideNeRecoitNiAbonnementNiCompteNiHistorique() throws Exception {
        try (var c = base(false)) {
            AbonnementMigration.migrer(c, true); AbonnementMigration.migrer(c, false);
            assertThat(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique")).isZero();
            assertThat(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM utilisateur")).isZero();
            assertThat(AbonnementMigration.donnees(c)).isEmpty();
        }
    }

    @Test
    void uneTransactionOuverteEstRefuseeSansDDL() throws Exception {
        try (var c = base(true)) {
            var tables = BoutiquesMigration.tables(c); c.setAutoCommit(false);
            assertThatThrownBy(() -> AbonnementMigration.migrer(c, true)).hasMessageContaining("transaction ouverte");
            c.rollback(); c.setAutoCommit(true); assertThat(BoutiquesMigration.tables(c)).isEqualTo(tables);
        }
    }

    private static void verifierRefusSansEcriture(Connection c) throws Exception {
        var schema = BoutiquesMigration.tables(c); var boutique = InscriptionEssaiMigration.colonnes(c, "boutique");
        var historiqueSchema = InscriptionEssaiMigration.colonnes(c, AbonnementMigration.TABLE);
        var avant = AbonnementMigration.photographie(c); var periodes = AbonnementMigration.periodes(c); var historique = AbonnementMigration.donnees(c);
        assertThatThrownBy(() -> AbonnementMigration.migrer(c, false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AbonnementMigration.migrer(c, true)).isInstanceOf(IllegalStateException.class);
        assertThat(BoutiquesMigration.tables(c)).isEqualTo(schema);
        assertThat(InscriptionEssaiMigration.colonnes(c, "boutique")).isEqualTo(boutique);
        assertThat(InscriptionEssaiMigration.colonnes(c, AbonnementMigration.TABLE)).isEqualTo(historiqueSchema);
        assertThat(AbonnementMigration.photographie(c)).isEqualTo(avant);
        assertThat(AbonnementMigration.periodes(c)).isEqualTo(periodes);
        assertThat(AbonnementMigration.donnees(c)).isEqualTo(historique);
    }
    static Connection base(boolean remplir) throws SQLException {
        var c = InscriptionEssaiMigrationTests.base(remplir); InscriptionEssaiMigration.migrer(c, true);
        if (remplir) InscriptionEssaiMigrationTests.remplir(c); return c;
    }
    static void remplirPeriode(Connection c) throws SQLException {
        sql(c, "UPDATE boutique SET abonnement_debut='2025-02-01 00:00:00.123456',abonnement_fin='2025-03-01 00:00:00.123456' WHERE id=1");
    }
    static void remplir(Connection c) throws SQLException {
        remplirPeriode(c);
        int id = 1;
        for (String action : List.of("PROLONGATION_ESSAI", "ACTIVATION_ABONNEMENT", "ACCES_OFFERT", "RETRAIT_ACCES_OFFERT", "SUSPENSION_BOUTIQUE", "REACTIVATION_BOUTIQUE"))
            inserer(c, action, action.equals("PROLONGATION_ESSAI") ? Integer.valueOf(7) : action.equals("ACTIVATION_ABONNEMENT") ? Integer.valueOf(1) : null,
                    Set.of("PROLONGATION_ESSAI", "ACTIVATION_ABONNEMENT").contains(action), id++);
    }
    static void inserer(Connection c, String action, Integer quantite, boolean periode, int id) throws SQLException {
        try (var s = c.prepareStatement("INSERT INTO historique_abonnement(id,boutique_id,acteur_id,acteur_nom,action,cree_le,periode_debut,periode_fin,ancienne_fin,motif,jeton_action,quantite) VALUES(?,2,1,'Administrateur',?,'2025-01-01 00:00:00.123456',?,?,?,?,?,?)")) {
            s.setInt(1, id); s.setString(2, action);
            s.setTimestamp(3, periode ? Timestamp.valueOf("2025-01-01 00:00:00.123456") : null);
            s.setTimestamp(4, periode ? Timestamp.valueOf(action.equals("PROLONGATION_ESSAI") ? "2025-01-08 00:00:00.123456" : "2025-02-01 00:00:00.123456") : null);
            s.setTimestamp(5, periode ? Timestamp.valueOf("2025-01-01 00:00:00.123456") : null);
            s.setString(6, "Action manuelle"); s.setString(7, "abcdef01-1234-4567-89ab-" + String.format("%012d", id));
            if (quantite == null) s.setNull(8, Types.INTEGER); else s.setInt(8, quantite); s.executeUpdate();
        }
    }
    private static void sql(Connection c, String instruction) throws SQLException { c.setReadOnly(false); BoutiquesMigration.executer(c, instruction); }
}
