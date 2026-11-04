package org.example.stock.migration;

/** Les scénarios des migrations antérieures reconstruisent leur schéma d'origine. */
public final class SchemaHistorique {
    private SchemaHistorique() {}

    public static String sansIdentiteBoutique(String schema) {
        return schema.replaceAll("(?s)CREATE TABLE IF NOT EXISTS logo_boutique\\s*\\(.*?;", "")
                .replaceAll("(?m)^\\s*telephone_secondaire VARCHAR\\(50\\) NULL,\\s*$", "");
    }

    public static String sansTresorerie(String schema) {
        return sansIdentiteBoutique(schema).replaceAll("(?s)CREATE TABLE IF NOT EXISTS (?:compte_tresorerie_boutique|operation_tresorerie)\\s*\\(.*?;", "")
                .replaceAll("(?m)^\\s*compte VARCHAR\\(32\\) NOT NULL DEFAULT 'CAISSE',\\s*$", "")
                .replaceAll("(?m)^\\s*operation_tresorerie_id BIGINT NULL,\\s*$", "")
                .replaceAll("(?m)^\\s*CONSTRAINT fk_mouvement_operation_tresorerie\\s*\\R\\s*FOREIGN KEY \\(operation_tresorerie_id\\) REFERENCES operation_tresorerie \\(id\\),\\s*\\R", "");
    }
}
