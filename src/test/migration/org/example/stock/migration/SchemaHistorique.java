package org.example.stock.migration;

import java.util.List;
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
    private SchemaHistorique() {}

    public static String sansLot1(String schema) {
        String resultat = schema;
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
