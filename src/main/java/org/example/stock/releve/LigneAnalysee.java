package org.example.stock.releve;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Une ligne du fichier après application de la correspondance : valide (date, libellé, montant) ou en erreur (avec la raison). */
public record LigneAnalysee(int numeroLigne, LocalDate date, String libelle, String reference, BigDecimal montant, String erreur, boolean doublon) {
    public boolean valide() { return erreur == null; }

    public LigneAnalysee comme(boolean doublon) { return new LigneAnalysee(numeroLigne, date, libelle, reference, montant, erreur, doublon); }

    /** Empreinte de comparaison : voir {@link Empreinte}. Seulement pour une ligne valide. */
    public String empreinte() { return Empreinte.calculer(date, montant, libelle, reference); }
}
