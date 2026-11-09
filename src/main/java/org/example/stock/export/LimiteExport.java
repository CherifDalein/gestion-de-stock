package org.example.stock.export;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limite de sécurité commune à tous les exports de listes (mémoire du serveur, taille du fichier) : 20 000 lignes par
 * défaut, réglable par {@code stock.export.limite-lignes}. Un service d'export lit au plus {@link #lignes()} + 1 lignes
 * puis appelle {@link #verifier(int)}.
 */
@Component
public class LimiteExport {
    public static final int PAR_DEFAUT = 20_000;
    private final int lignes;

    public LimiteExport(@Value("${stock.export.limite-lignes:" + PAR_DEFAUT + "}") int lignes) {
        if (lignes < 1) throw new IllegalArgumentException("stock.export.limite-lignes doit être positif");
        this.lignes = lignes;
    }

    public int lignes() { return lignes; }

    /** @throws ExportTropVolumineuxException si {@code nombre} dépasse la limite */
    public void verifier(int nombre) {
        if (nombre > lignes) throw new ExportTropVolumineuxException(lignes);
    }
}
