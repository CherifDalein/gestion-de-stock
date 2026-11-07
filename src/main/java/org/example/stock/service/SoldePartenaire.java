package org.example.stock.service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Situation d'un client ou d'un fournisseur, du point de vue de la boutique.
 *
 * <p>Le solde net est signé : positif quand le partenaire doit de l'argent à la boutique, négatif quand la boutique
 * lui en doit. Pour un client, net = reste du solde de départ + restes des factures − avance disponible.
 * Pour un fournisseur, net = avance disponible − reste du solde de départ − restes des factures.</p>
 *
 * @param soldeDepart       montant signé enregistré sur la fiche (positif : il me doit ; négatif : je lui dois)
 * @param soldeDepartRegle  somme des règlements du solde de départ, en valeur absolue
 * @param resteSoldeDepart  ce qui reste à régler du solde de départ par celui qui le doit (client ou boutique)
 * @param echeanceSoldeDepart échéance du solde de départ, vide si elle n'a pas été saisie
 * @param resteFactures     somme des restes à payer de ses factures
 * @param avanceDisponible  avance non encore utilisée, jamais négative
 * @param soldeNet          solde global signé
 */
public record SoldePartenaire(BigDecimal soldeDepart, BigDecimal soldeDepartRegle, BigDecimal resteSoldeDepart,
                              LocalDate echeanceSoldeDepart, BigDecimal resteFactures, BigDecimal avanceDisponible,
                              BigDecimal soldeNet) {
    /** Le solde de départ ne se modifie plus dès qu'un règlement a été enregistré. */
    public boolean soldeDepartModifiable() {
        return soldeDepartRegle.signum() == 0;
    }

    public boolean soldeDepartAReglerPossible() {
        return resteSoldeDepart.signum() > 0;
    }
}
