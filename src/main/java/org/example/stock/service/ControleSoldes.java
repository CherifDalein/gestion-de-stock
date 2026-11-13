package org.example.stock.service;

import java.math.BigDecimal;

/** Contrôle d'un relevé : solde d'ouverture + somme des lignes = solde de clôture. Un écart bloque la validation, jamais la saisie. */
public record ControleSoldes(BigDecimal ouverture, BigDecimal sommeLignes, BigDecimal cloture) {
    public BigDecimal attendu() { return ouverture.add(sommeLignes); }

    /** Clôture saisie moins clôture attendue : zéro quand le relevé est complet. */
    public BigDecimal ecart() { return cloture.subtract(attendu()); }

    public boolean coherent() { return ecart().signum() == 0; }
}
