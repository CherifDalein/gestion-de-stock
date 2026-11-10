package org.example.stock.recu;

/**
 * Ce que montre le reçu d'un règlement (encaissement d'une vente, paiement d'un achat, règlement d'un solde de départ),
 * déjà mis en forme pour le gabarit.
 *
 * @param nature             nature du règlement (« Règlement d'une vente »…)
 * @param reference          numéro du règlement, identique à celui du journal de trésorerie
 * @param document           document concerné (« Vente FV-000012 du 03/11/2026 », « Solde de départ »…)
 * @param partenaire         client ou fournisseur, ou null
 * @param encaissement       vrai quand la boutique reçoit l'argent (vente, solde d'un client), faux quand elle le paie
 * @param reste              ce qui restait à régler sur le document juste APRÈS ce règlement
 * @param annuleLe           « ANNULÉ le 09/11/2026 14:05 » quand le règlement a été annulé ensuite, sinon null
 * @param annulationMotif    motif saisi par le gérant, ou null
 * @param annulationAuteur   auteur de l'annulation, ou null
 */
public record RecuReglement(String nature, String reference, String date, String document, String partenaire, boolean encaissement,
                            String montant, String compte, String reste, boolean soldeApres, String auteur, String annuleLe,
                            String annulationMotif, String annulationAuteur, String mention) {

    public boolean annule() { return annuleLe != null; }
}
