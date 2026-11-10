package org.example.stock.recu;

import java.util.List;

/**
 * Ce que montre le reçu d'une vente, déjà mis en forme pour le gabarit. Ne contient jamais un coût, une marge ou un prix
 * d'achat : seuls le prix de vente, les quantités et les montants de la facture y figurent.
 *
 * @param retours         montant des marchandises retournées, ou null s'il n'y en a pas
 * @param retoursNumeros  numéros des retours enregistrés sur la vente, ou null
 * @param avance          avance du client utilisée, ou null si nulle
 * @param surplus         trop-perçu devenu avance, ou null si nul
 * @param compte          compte qui a reçu le paiement saisi à la vente, ou null s'il est inconnu ou s'il n'y en a pas eu
 */
public record RecuVente(String numero, String date, String vendeur, String client, List<Ligne> lignes, String total, String retours,
                        String retoursNumeros, String totalNet, String verse, String avance, String surplus, String reste,
                        boolean solde, String compte, String mention) {

    /** Une ligne de vente : désignation, quantité avec son unité, prix unitaire et total de la ligne. */
    public record Ligne(String designation, String quantite, String prix, String total) { }
}
