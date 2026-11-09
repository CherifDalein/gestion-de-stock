package org.example.stock.export;

/** Un export dépasse la limite de lignes ({@link LimiteExport}) : le message est destiné à l'utilisateur. */
public class ExportTropVolumineuxException extends RuntimeException {
    public ExportTropVolumineuxException(int limite) {
        super("Cet export dépasse " + (limite >= 1000 ? (limite / 1000) + " " + String.format("%03d", limite % 1000) : String.valueOf(limite))
                + " lignes. Réduisez la période ou ajoutez un filtre, puis recommencez.");
    }
}
