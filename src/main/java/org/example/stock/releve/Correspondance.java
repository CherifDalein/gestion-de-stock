package org.example.stock.releve;

import org.example.stock.enums.FormatDateReleve;
import org.example.stock.enums.SeparateurDecimal;

/**
 * Choix du gérant pour lire un fichier : numéro (à partir de 1) de la ligne d'en-tête (0 : pas d'en-tête, les données commencent à la première
 * ligne), numéros de colonnes (à partir de 1), format de date et séparateur décimal. Soit {@code colonneMontant} (montant signé), soit
 * {@code colonneDebit} et {@code colonneCredit}.
 */
public record Correspondance(int ligneEntete, int colonneDate, int colonneLibelle, Integer colonneReference, Integer colonneMontant,
                             Integer colonneDebit, Integer colonneCredit, FormatDateReleve formatDate, SeparateurDecimal separateur) {

    public boolean montantSigne() { return colonneMontant != null; }

    /** Refuse une correspondance incomplète, incohérente ou hors du fichier ({@code colonnes} colonnes, {@code lignes} lignes). */
    public void controler(int colonnes, int lignes) {
        if (ligneEntete < 0 || ligneEntete > lignes) throw new ImportReleveException("La ligne d'en-tête choisie n'existe pas dans ce fichier.");
        if (formatDate == null || separateur == null) throw new ImportReleveException("Choisissez le format de date et le séparateur décimal.");
        if (colonneDate < 1 || colonneLibelle < 1) throw new ImportReleveException("Choisissez la colonne de la date et celle du libellé.");
        boolean signe = colonneMontant != null, partage = colonneDebit != null || colonneCredit != null;
        if (signe == partage) throw new ImportReleveException("Choisissez soit une colonne de montant signé, soit une colonne de débit et une colonne de crédit.");
        if (partage && (colonneDebit == null || colonneCredit == null)) throw new ImportReleveException("Choisissez à la fois la colonne de débit et la colonne de crédit.");
        java.util.List<Integer> choisies = new java.util.ArrayList<>(java.util.List.of(colonneDate, colonneLibelle));
        for (Integer c : new Integer[]{colonneReference, colonneMontant, colonneDebit, colonneCredit}) if (c != null) choisies.add(c);
        for (int c : choisies) if (c < 1 || c > colonnes) throw new ImportReleveException("Une colonne choisie n'existe pas dans ce fichier.");
        if (choisies.stream().distinct().count() != choisies.size()) throw new ImportReleveException("Chaque colonne ne peut servir qu'à un seul usage.");
    }
}
