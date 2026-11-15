package org.example.stock.compta;

import org.example.stock.enums.TypeOperationTresorerie;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Les opérations d'une boutique jusqu'à une date, telles que l'application les a enregistrées, prêtes à être mises en écritures par
 * {@link ConstructeurEcritures}. Ce sont des lectures : aucune écriture n'est conservée.
 *
 * @param ouverture      date des écritures de report (soldes de départ des partenaires, stock de départ)
 * @param stockDepart    valeur du stock de départ (avant toute opération) au coût moyen pondéré
 * @param valeursStock   valeur du stock aux dates de régularisation demandées, du plus ancien au plus récent
 * @param reprise        report à nouveau d'un exercice clôturé (étape 5c) ; {@code null} quand on calcule depuis l'origine
 */
public record DonneesComptables(LocalDateTime ouverture, List<Vente> ventes, List<Achat> achats, List<Retour> retours, List<Mouvement> mouvements,
                                Map<Long, Operation> operations, List<Partenaire> clients, List<Partenaire> fournisseurs, List<Ajustement> ajustements,
                                BigDecimal stockDepart, List<ValeurStock> valeursStock, Reprise reprise) {

    /** Données sans report à nouveau : les écritures se calculent depuis l'origine. */
    public DonneesComptables(LocalDateTime ouverture, List<Vente> ventes, List<Achat> achats, List<Retour> retours, List<Mouvement> mouvements,
                             Map<Long, Operation> operations, List<Partenaire> clients, List<Partenaire> fournisseurs, List<Ajustement> ajustements,
                             BigDecimal stockDepart, List<ValeurStock> valeursStock) {
        this(ouverture, ventes, achats, retours, mouvements, operations, clients, fournisseurs, ajustements, stockDepart, valeursStock, null);
    }

    /** Vente : total, montant versé (le surplus sur le total est une avance du client), avance utilisée, et part des services (le reste du total est de la marchandise). */
    public record Vente(Long id, String numero, LocalDateTime date, BigDecimal total, BigDecimal verse, BigDecimal avanceUtilisee, Long clientId, String clientNom, BigDecimal services) {}

    public record Achat(Long id, String numero, LocalDateTime date, BigDecimal total, BigDecimal verse, BigDecimal avanceUtilisee, Long fournisseurId, String fournisseurNom) {}

    /** Retour de vente ({@code vente = true}) ou d'achat ; {@code services} : part de services d'un retour de vente. */
    public record Retour(Long id, String numero, boolean vente, LocalDateTime date, BigDecimal total, BigDecimal rembourse, BigDecimal avoir,
                         Long tiersId, String tiersNom, BigDecimal services) {}

    /**
     * Mouvement de trésorerie ; {@code montant} est positif pour une entrée. Les références sont celles de l'application ; {@code annulationDeId}
     * désigne le mouvement que celui-ci annule.
     */
    public record Mouvement(Long id, LocalDateTime date, Long compteId, BigDecimal montant, String source, String motif, Long venteId, Long achatId,
                            Long clientId, Long fournisseurId, Long retourId, Long operationId, Long comptageId, Long annulationDeId) {}

    /** En-tête d'une opération de trésorerie (dépense, transfert, financement). */
    public record Operation(Long id, TypeOperationTresorerie type, String motif, Long partenaireId, String partenaireNom, String empruntNumero) {}

    /** Client ou fournisseur : solde de départ signé actuel (positif : il me doit) et ses noms. */
    public record Partenaire(Long id, String nom, BigDecimal soldeInitial) {}

    /** Ajustement d'un solde de départ : ancien et nouveau soldes signés. */
    public record Ajustement(Long id, LocalDateTime date, Long clientId, Long fournisseurId, BigDecimal ancienSolde, BigDecimal nouveauSolde, String motif) {}

    public record ValeurStock(LocalDateTime date, BigDecimal valeur) {}
}
