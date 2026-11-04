package org.example.stock.migration;

import java.sql.*;
import java.util.*;

/** Comparaisons déterministes : clés physiques et contenu des BLOB, jamais identité d'un objet JDBC. */
final class PhotographieJdbc {
    private PhotographieJdbc() {}

    static String ordre(Connection c, String nom) throws SQLException {
        String physique = BoutiquesMigration.tables(c).get(nom);
        var cles = new TreeMap<Integer, String>();
        try (var r = c.getMetaData().getPrimaryKeys(c.getCatalog(), c.getSchema(), physique)) {
            while (r.next()) cles.put(r.getInt("KEY_SEQ"), identifiant(c, r.getString("COLUMN_NAME")));
        }
        if (cles.isEmpty()) try (var r = c.getMetaData().getColumns(c.getCatalog(), c.getSchema(), physique, null)) {
            if (!r.next()) throw new IllegalStateException("Table de photographie sans colonne.");
            cles.put(1, identifiant(c, r.getString("COLUMN_NAME")));
        }
        return String.join(",", cles.values());
    }

    static Object valeur(ResultSet r, int index) throws SQLException {
        if (Set.of(Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY).contains(r.getMetaData().getColumnType(index))) {
            byte[] contenu = r.getBytes(index);
            return contenu == null ? null : HexFormat.of().formatHex(contenu);
        }
        return r.getObject(index);
    }

    private static String identifiant(Connection c, String nom) throws SQLException {
        if (nom == null || !nom.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("Identifiant de photographie inattendu.");
        String q = c.getMetaData().getIdentifierQuoteString().strip();
        return q + nom + q;
    }
}
