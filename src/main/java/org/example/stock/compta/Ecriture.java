package org.example.stock.compta;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Écriture comptable calculée à partir d'une opération de l'application (jamais stockée) : une date, un journal, un numéro de pièce, un
 * libellé et des lignes qui s'équilibrent. {@code origine} identifie l'opération source (par exemple {@code VENTE:12}) ; {@code contrePassation}
 * est vrai pour l'écriture inverse d'une opération annulée.
 */
public record Ecriture(LocalDateTime date, Journal journal, String piece, String libelle, List<Ligne> lignes, String origine, boolean contrePassation) {
    public Ecriture { lignes = List.copyOf(lignes); }

    /** Tiers d'une ligne : un client, un fournisseur ou un partenaire financier. */
    public enum TypeTiers { CLIENT, FOURNISSEUR, PARTENAIRE }

    /** Une ligne : un compte, un montant au débit ou au crédit (l'autre vaut zéro) et un tiers éventuel. */
    public record Ligne(String compte, BigDecimal debit, BigDecimal credit, TypeTiers tiersType, Long tiersId, String tiersNom) {
        public BigDecimal solde() { return debit.subtract(credit); }
    }

    public BigDecimal totalDebit() { return lignes.stream().map(Ligne::debit).reduce(BigDecimal.ZERO, BigDecimal::add); }

    public BigDecimal totalCredit() { return lignes.stream().map(Ligne::credit).reduce(BigDecimal.ZERO, BigDecimal::add); }

    public boolean estEquilibree() { return totalDebit().compareTo(totalCredit()) == 0; }
}
