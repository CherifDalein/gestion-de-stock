package org.example.stock.compta;

import org.example.stock.compta.Ecriture.TypeTiers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Report à nouveau issu de la clôture d'un exercice (étape 5c) : les soldes des comptes de bilan à la date de clôture (le résultat net est
 * dans le compte 13), la valeur du stock à cette date et les affectations du résultat saisies depuis. Quand un calcul part d'une reprise, les
 * écritures de l'exercice clos ne sont plus recalculées : les soldes enregistrés font foi, puis seules les opérations qui suivent sont mises en écritures.
 *
 * @param exerciceId  exercice clôturé dont les soldes sont issus
 * @param finExercice dernier jour de cet exercice
 * @param soldes      soldes d'ouverture (débit moins crédit), par compte et par tiers
 * @param valeurStock valeur du stock à la clôture (le compte 31 y est égal)
 * @param stockDepart valeur des entrées « stock initial » jusqu'à la clôture : celles qui viennent après sont des stocks de départ ajoutés (report, pas un résultat)
 * @param plafondClients       plus grand identifiant de client à la clôture : un client plus récent a son solde de départ reporté après la clôture
 * @param plafondFournisseurs  même chose pour les fournisseurs
 * @param affectations affectations du résultat de l'exercice clôturé, datées dans l'exercice suivant
 */
public record Reprise(Long exerciceId, LocalDate finExercice, String libelle, List<Solde> soldes, BigDecimal valeurStock, BigDecimal stockDepart, long plafondClients, long plafondFournisseurs, List<Affectation> affectations) {
    public Reprise { soldes = List.copyOf(soldes); affectations = List.copyOf(affectations); }

    public record Solde(String compte, TypeTiers tiersType, Long tiersId, String tiersNom, BigDecimal solde) {}

    /** Affectation du résultat : {@code montant} positif pour un bénéfice (131 soldé), négatif pour une perte (139 soldée). */
    public record Affectation(Long id, LocalDateTime date, String compteDestination, BigDecimal montant, String libelle) {}

    /** Instant des écritures de report : la toute fin du dernier jour de l'exercice clos. Tout ce qui est daté après vient de l'exercice suivant. */
    public LocalDateTime limite() { return finExercice.atTime(LocalTime.MAX); }
}
