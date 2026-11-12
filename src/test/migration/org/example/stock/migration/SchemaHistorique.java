package org.example.stock.migration;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Les scénarios des migrations antérieures reconstruisent leur schéma d'origine. */
public final class SchemaHistorique {
    private static final List<String> LIGNES_LOT1 = List.of(
            "delai_clients_jours INT NOT NULL DEFAULT 30,", "delai_fournisseurs_jours INT NOT NULL DEFAULT 30,",
            "prochain_numero_vente BIGINT NOT NULL DEFAULT 1,", "prochain_numero_achat BIGINT NOT NULL DEFAULT 1,",
            "solde_initial DECIMAL(17,2) NOT NULL DEFAULT 0,", "echeance_solde_initial DATE NULL,",
            "nature VARCHAR(16) NOT NULL DEFAULT 'STOCK',", "unite VARCHAR(30) NULL,", "seuil_alerte BIGINT NOT NULL DEFAULT 5,",
            "cout_moyen DECIMAL(17,2) NOT NULL DEFAULT 0,", "numero VARCHAR(30) NULL,", "date_echeance DATE NULL,",
            "avance_utilisee DECIMAL(17,2) NOT NULL DEFAULT 0,",
            "client_id BIGINT NULL,", "fournisseur_id BIGINT NULL,",
            "CONSTRAINT uk_vente_boutique_numero UNIQUE (boutique_id, numero),", "CONSTRAINT uk_achat_boutique_numero UNIQUE (boutique_id, numero),");
    private static final List<String> LIGNES_LOT2 = List.of(
            "prochain_numero_devis BIGINT NOT NULL DEFAULT 1,", "prochain_numero_retour_vente BIGINT NOT NULL DEFAULT 1,", "prochain_numero_retour_achat BIGINT NOT NULL DEFAULT 1,",
            "montant_retours DECIMAL(17,2) NOT NULL DEFAULT 0,", "vendeur_id BIGINT NULL,", "retour_id BIGINT NULL,", "annulation_de_id BIGINT NULL,",
            "CONSTRAINT uk_mouvement_caisse_annulation UNIQUE (annulation_de_id),");
    private static final List<String> LIGNES_LOT4 = List.of(
            "format_recu VARCHAR(16) NOT NULL DEFAULT 'A4',", "proposer_impression_recu BOOLEAN NOT NULL DEFAULT TRUE,");
    private static final String COMPTES_AVANT_LOT3 = """
            CREATE TABLE IF NOT EXISTS compte_tresorerie_boutique (
                id BIGINT NOT NULL AUTO_INCREMENT,
                boutique_id BIGINT NOT NULL,
                compte VARCHAR(32) NOT NULL,
                PRIMARY KEY (id),
                CONSTRAINT uk_compte_tresorerie_boutique UNIQUE (boutique_id, compte),
                CONSTRAINT fk_compte_tresorerie_boutique
                    FOREIGN KEY (boutique_id) REFERENCES boutique (id)
            ) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

            """;
    private SchemaHistorique() {}

    /**
     * Schéma antérieur au lot 3 (comptes de trésorerie multiples) : un compte par type désigné par une valeur d'énumération
     * ({@code CAISSE}, {@code ORANGE_MONEY}, {@code BANQUE}) dans {@code compte}, {@code compte_source}, {@code compte_destination}
     * et {@code retour.compte_tresorerie}, sans table des comptes autorisés aux caissiers.
     */
    public static String sansLot3(String schema) {
        String resultat = sansFinancements(schema).replaceAll("(?m)^-- Lot 3 : [^\\r\\n]*\\R(?:-- [^\\r\\n]*\\R)*", "");
        resultat = resultat.replaceAll("(?s)CREATE TABLE IF NOT EXISTS compte_tresorerie_boutique \\(.*?;[ \\t]*\\R(?:[ \\t]*\\R)?", Matcher.quoteReplacement(COMPTES_AVANT_LOT3))
                .replaceAll("(?s)CREATE TABLE IF NOT EXISTS compte_tresorerie_autorise \\(.*?;[ \\t]*\\R(?:[ \\t]*\\R)?", "");
        resultat = resultat.replace("    compte_source_id BIGINT NOT NULL,\n    compte_destination_id BIGINT NULL,\n", "    compte_source VARCHAR(32) NOT NULL,\n    compte_destination VARCHAR(32) NULL,\n")
                .replace("    compte_tresorerie_id BIGINT NULL,\n", "    compte_tresorerie VARCHAR(16) NULL,\n")
                .replace("    compte_id BIGINT NOT NULL,\n", "    compte VARCHAR(32) NOT NULL DEFAULT 'CAISSE',\n");
        return resultat.replaceAll("(?m)^[ \\t]*CONSTRAINT fk_(?:operation_tresorerie_source|operation_tresorerie_destination|retour_compte_tresorerie|mouvement_caisse_compte)\\R[ \\t]*FOREIGN KEY \\([a-z_]+\\) REFERENCES [a-z_]+ \\(id\\),[ \\t]*\\R", "");
    }

    /**
     * Schéma de l'étape 3a seule (comptes multiples), sans les financements de l'étape 3b1 : ni les quatre tables, ni les compteurs EM-/PR-
     * de la boutique, ni le partenaire et l'emprunt des opérations de trésorerie.
     */
    public static String sansFinancements(String schema) {
        String resultat = schema.replaceAll("(?s)-- Lot 3b1 :.*?-- Fin lot 3b1\\R(?:[ \\t]*\\R)?", "");
        for (String ligne : List.of("prochain_numero_emprunt BIGINT NOT NULL DEFAULT 1,", "prochain_numero_pret BIGINT NOT NULL DEFAULT 1,",
                "partenaire_financier_id BIGINT NULL,", "emprunt_id BIGINT NULL,"))
            resultat = resultat.replaceAll("(?m)^[ \\t]*" + Pattern.quote(ligne) + "[ \\t]*\\R", "");
        return resultat.replaceAll("(?m)^[ \\t]*CONSTRAINT fk_operation_tresorerie_(?:partenaire|emprunt)\\R[ \\t]*FOREIGN KEY \\([a-z_]+\\) REFERENCES [a-z_]+ \\(id\\),[ \\t]*\\R", "");
    }

    /** Schéma antérieur au lot 4 : sans le format des reçus ni le réglage du bouton « Imprimer le reçu » de la boutique. */
    public static String sansLot4(String schema) {
        String resultat = sansLot3(schema);
        for (String ligne : LIGNES_LOT4) resultat = resultat.replaceAll("(?m)^[ \\t]*" + Pattern.quote(ligne) + "[ \\t]*\\R", "");
        return resultat;
    }

    /** Schéma antérieur au lot 2 : sans devis, retours, vendeur des ventes, auteur des achats ni annulation des règlements. */
    public static String sansLot2(String schema) {
        String resultat = sansLot4(schema).replaceAll("(?s)CREATE TABLE IF NOT EXISTS (?:devis|detail_devis|retour|detail_retour) \\(.*?;[ \\t]*\\R(?:[ \\t]*\\R)?", "")
                .replaceAll("(?m)^-- Lot 2 : [^\\r\\n]*\\R", "");
        for (String ligne : LIGNES_LOT2) resultat = resultat.replaceAll("(?m)^[ \\t]*" + Pattern.quote(ligne) + "[ \\t]*\\R", "");
        // achat possède une colonne utilisateur_id facultative propre au lot 2 ; les autres tables gardent la leur.
        resultat = resultat.replaceAll("(?s)(CREATE TABLE IF NOT EXISTS achat \\(.*?)\\R[ \\t]*utilisateur_id BIGINT NULL,", "$1");
        // Les clés de vente et d'achat terminent leur table : la virgule de la clé précédente disparaît avec elles.
        resultat = resultat.replaceAll(",[ \\t]*\\R[ \\t]*CONSTRAINT fk_(?:vente_vendeur|achat_utilisateur)\\R[ \\t]*FOREIGN KEY \\([a-z_]+\\) REFERENCES [a-z_]+ \\(id\\)(?=[ \\t]*\\R\\))", "");
        return resultat.replaceAll("(?m)^[ \\t]*CONSTRAINT fk_mouvement_caisse_(?:retour|annulation)\\R[ \\t]*FOREIGN KEY \\([a-z_]+\\) REFERENCES [a-z_]+ \\(id\\),[ \\t]*\\R", "");
    }

    public static String sansLot1(String schema) {
        String resultat = sansLot2(schema);
        for (String ligne : LIGNES_LOT1) resultat = resultat.replaceAll("(?m)^[ \\t]*" + Pattern.quote(ligne) + "[ \\t]*\\R", "");
        resultat = resultat.replaceAll("(?m)^[ \\t]*CONSTRAINT fk_mouvement_caisse_(?:client|fournisseur)\\R[ \\t]*FOREIGN KEY \\([a-z_]+\\) REFERENCES [a-z_]+ \\(id\\),[ \\t]*\\R", "");
        // mouvement_stock possède aussi un cout_unitaire : seul celui de detail_vente appartient au lot 1.
        return resultat.replaceAll("(?s)(CREATE TABLE IF NOT EXISTS detail_vente \\(.*?)\\R[ \\t]*cout_unitaire DECIMAL\\(17,2\\) NULL,", "$1");
    }

    public static String sansIdentiteBoutique(String schema) {
        return sansLot1(schema).replaceAll("(?s)CREATE TABLE IF NOT EXISTS logo_boutique\\s*\\(.*?;", "")
                .replaceAll("(?m)^\\s*telephone_secondaire VARCHAR\\(50\\) NULL,\\s*$", "");
    }

    public static String sansTresorerie(String schema) {
        return sansIdentiteBoutique(schema).replaceAll("(?s)CREATE TABLE IF NOT EXISTS (?:compte_tresorerie_boutique|operation_tresorerie)\\s*\\(.*?;", "")
                .replaceAll("(?m)^\\s*compte VARCHAR\\(32\\) NOT NULL DEFAULT 'CAISSE',\\s*$", "")
                .replaceAll("(?m)^\\s*operation_tresorerie_id BIGINT NULL,\\s*$", "")
                .replaceAll("(?m)^\\s*CONSTRAINT fk_mouvement_operation_tresorerie\\s*\\R\\s*FOREIGN KEY \\(operation_tresorerie_id\\) REFERENCES operation_tresorerie \\(id\\),\\s*\\R", "");
    }
}
