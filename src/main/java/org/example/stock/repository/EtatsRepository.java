package org.example.stock.repository;

import org.example.stock.enums.NatureProduit;
import org.example.stock.enums.TypeOperationTresorerie;
import org.example.stock.model.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Requêtes des états du gérant (lot 4, tâche C). Chaque requête reçoit l'identifiant de la boutique du compte connecté
 * et, pour une période, des bornes semi-ouvertes [début, fin) comme celles du tableau de bord. Les chiffres communs avec
 * le tableau de bord (chiffre d'affaires, bénéfices, dépenses, créances, dettes, stock) ne sont PAS recalculés ici : les
 * états appellent {@code TableauDeBordService} et {@code SoldePartenaireService}. Ce dépôt ne fournit que ce qu'ils n'ont
 * pas : flux de trésorerie par compte, lignes de détail, listes.
 */
public interface EtatsRepository extends Repository<Vente, Long> {

    // ---- Trésorerie -------------------------------------------------------------------------------------------

    /**
     * Flux de la période par compte et par source : [identifiant du compte, source, Σ des entrées (montants positifs), Σ des sorties
     * (montants négatifs, en négatif)]. Un montant vide compte pour 0.
     */
    @Query("SELECT m.compte.id, m.source, COALESCE(SUM(CASE WHEN m.montant > 0 THEN m.montant ELSE 0 END), 0), "
            + "COALESCE(SUM(CASE WHEN m.montant < 0 THEN m.montant ELSE 0 END), 0) FROM MouvementCaisse m "
            + "WHERE m.boutique.id = :boutiqueId AND m.dateMouvement >= :debut AND m.dateMouvement < :fin GROUP BY m.compte.id, m.source")
    List<Object[]> fluxParCompteEtSource(@Param("boutiqueId") Long boutiqueId,
                                         @Param("debut") LocalDateTime debut, @Param("fin") LocalDateTime fin);

    /**
     * Solde de chaque compte juste avant {@code date} : [identifiant du compte, Σ des montants]. Un mouvement sans date (donnée ancienne)
     * est compté comme antérieur à tout, pour que le solde de fin égale toujours le solde du journal.
     */
    @Query("SELECT m.compte.id, COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.boutique.id = :boutiqueId "
            + "AND (m.dateMouvement IS NULL OR m.dateMouvement < :date) GROUP BY m.compte.id")
    List<Object[]> soldesAvant(@Param("boutiqueId") Long boutiqueId, @Param("date") LocalDateTime date);

    /** Encaissements de la période (montants positifs hors les sources exclues), avec leurs documents, par date puis identifiant. */
    @Query("SELECT m FROM MouvementCaisse m LEFT JOIN FETCH m.vente v LEFT JOIN FETCH v.client LEFT JOIN FETCH m.client "
            + "LEFT JOIN FETCH m.fournisseur LEFT JOIN FETCH m.retour r LEFT JOIN FETCH r.fournisseur LEFT JOIN FETCH m.utilisateur "
            + "WHERE m.boutique.id = :boutiqueId AND m.dateMouvement >= :debut AND m.dateMouvement < :fin AND m.montant > 0 "
            + "AND (m.source IS NULL OR m.source NOT IN :exclues) ORDER BY m.dateMouvement, m.id")
    List<MouvementCaisse> encaissements(@Param("boutiqueId") Long boutiqueId, @Param("debut") LocalDateTime debut,
                                        @Param("fin") LocalDateTime fin, @Param("exclues") Collection<String> exclues, Pageable limite);

    /** Annulations de la période qui retirent de l'argent (annulation d'un encaissement), avec le mouvement annulé. */
    @Query("SELECT a FROM MouvementCaisse a JOIN FETCH a.annulationDe o LEFT JOIN FETCH o.vente v LEFT JOIN FETCH v.client "
            + "LEFT JOIN FETCH o.client LEFT JOIN FETCH o.fournisseur LEFT JOIN FETCH a.utilisateur "
            + "WHERE a.boutique.id = :boutiqueId AND a.dateMouvement >= :debut AND a.dateMouvement < :fin AND a.montant < 0 "
            + "ORDER BY a.dateMouvement, a.id")
    List<MouvementCaisse> annulationsDEncaissements(@Param("boutiqueId") Long boutiqueId, @Param("debut") LocalDateTime debut,
                                                    @Param("fin") LocalDateTime fin, Pageable limite);

    /** Annulations de la période qui rendent de l'argent (annulation d'un paiement) : [nombre, Σ montants]. */
    @Query("SELECT COUNT(a), COALESCE(SUM(a.montant), 0) FROM MouvementCaisse a WHERE a.boutique.id = :boutiqueId "
            + "AND a.annulationDe IS NOT NULL AND a.dateMouvement >= :debut AND a.dateMouvement < :fin AND a.montant > 0")
    List<Object[]> annulationsDePaiements(@Param("boutiqueId") Long boutiqueId, @Param("debut") LocalDateTime debut,
                                          @Param("fin") LocalDateTime fin);

    // ---- Dépenses ---------------------------------------------------------------------------------------------

    @Query("SELECT o FROM OperationTresorerie o JOIN FETCH o.utilisateur WHERE o.boutique.id = :boutiqueId AND o.type = :type "
            + "AND o.dateOperation >= :debut AND o.dateOperation < :fin ORDER BY o.dateOperation, o.id")
    List<OperationTresorerie> operations(@Param("boutiqueId") Long boutiqueId, @Param("type") TypeOperationTresorerie type,
                                         @Param("debut") LocalDateTime debut, @Param("fin") LocalDateTime fin, Pageable limite);

    /** Annulations (par mouvement inverse) des écritures des opérations de la période : l'opération annulée est celle du mouvement d'origine. */
    @Query("SELECT a FROM MouvementCaisse a JOIN FETCH a.annulationDe o JOIN FETCH o.operationTresorerie op WHERE a.boutique.id = :boutiqueId "
            + "AND op.type = :type AND op.dateOperation >= :debut AND op.dateOperation < :fin")
    List<MouvementCaisse> annulationsDOperations(@Param("boutiqueId") Long boutiqueId, @Param("type") TypeOperationTresorerie type,
                                                 @Param("debut") LocalDateTime debut, @Param("fin") LocalDateTime fin);

    // ---- Créances et dettes : factures non soldées ------------------------------------------------------------

    /** Ventes dont le reste à payer est positif (même condition que le reste de {@code SoldePartenaireRepository}), par identifiant. */
    @Query("SELECT v FROM Vente v LEFT JOIN FETCH v.client WHERE v.boutique.id = :boutiqueId AND "
            + "COALESCE(v.montantTotal, 0) - COALESCE(v.montantRetours, 0) - COALESCE(v.montantVerse, 0) - COALESCE(v.avanceUtilisee, 0) > 0 "
            + "ORDER BY v.id")
    List<Vente> ventesNonSoldees(@Param("boutiqueId") Long boutiqueId, Pageable limite);

    @Query("SELECT a FROM Achat a LEFT JOIN FETCH a.fournisseur WHERE a.boutique.id = :boutiqueId AND "
            + "COALESCE(a.montantTotal, 0) - COALESCE(a.montantRetours, 0) - COALESCE(a.montantVerse, 0) - COALESCE(a.avanceUtilisee, 0) > 0 "
            + "ORDER BY a.id")
    List<Achat> achatsNonSoldes(@Param("boutiqueId") Long boutiqueId, Pageable limite);

    // ---- Relevé d'un partenaire --------------------------------------------------------------------------------

    @Query("SELECT v FROM Vente v WHERE v.boutique.id = :boutiqueId AND v.client.id = :clientId ORDER BY v.dateVente, v.id")
    List<Vente> ventesDuClient(@Param("boutiqueId") Long boutiqueId, @Param("clientId") Long clientId, Pageable limite);

    @Query("SELECT a FROM Achat a WHERE a.boutique.id = :boutiqueId AND a.fournisseur.id = :fournisseurId ORDER BY a.dateAchat, a.id")
    List<Achat> achatsDuFournisseur(@Param("boutiqueId") Long boutiqueId, @Param("fournisseurId") Long fournisseurId, Pageable limite);

    @Query("SELECT r FROM Retour r LEFT JOIN FETCH r.vente WHERE r.boutique.id = :boutiqueId AND r.type = org.example.stock.enums.TypeRetour.VENTE "
            + "AND r.client.id = :clientId ORDER BY r.dateRetour, r.id")
    List<Retour> retoursDuClient(@Param("boutiqueId") Long boutiqueId, @Param("clientId") Long clientId, Pageable limite);

    @Query("SELECT r FROM Retour r LEFT JOIN FETCH r.achat WHERE r.boutique.id = :boutiqueId AND r.type = org.example.stock.enums.TypeRetour.ACHAT "
            + "AND r.fournisseur.id = :fournisseurId ORDER BY r.dateRetour, r.id")
    List<Retour> retoursDuFournisseur(@Param("boutiqueId") Long boutiqueId, @Param("fournisseurId") Long fournisseurId, Pageable limite);

    /**
     * Mouvements de trésorerie d'un client : ceux de ses ventes (paiement à la création, règlements, leurs annulations) et ceux
     * de son solde de départ (règlements et leurs annulations). Les remboursements de retour sont portés par le retour lui-même.
     */
    @Query("SELECT m FROM MouvementCaisse m LEFT JOIN FETCH m.vente v LEFT JOIN FETCH m.utilisateur WHERE m.boutique.id = :boutiqueId AND "
            + "(v.client.id = :clientId OR (m.client.id = :clientId AND m.source IN ('SOLDE_CLIENT', 'ANNULATION'))) ORDER BY m.dateMouvement, m.id")
    List<MouvementCaisse> mouvementsDuClient(@Param("boutiqueId") Long boutiqueId, @Param("clientId") Long clientId, Pageable limite);

    @Query("SELECT m FROM MouvementCaisse m LEFT JOIN FETCH m.achat a LEFT JOIN FETCH m.utilisateur WHERE m.boutique.id = :boutiqueId AND "
            + "(a.fournisseur.id = :fournisseurId OR (m.fournisseur.id = :fournisseurId AND m.source IN ('SOLDE_FOURNISSEUR', 'ANNULATION'))) "
            + "ORDER BY m.dateMouvement, m.id")
    List<MouvementCaisse> mouvementsDuFournisseur(@Param("boutiqueId") Long boutiqueId, @Param("fournisseurId") Long fournisseurId, Pageable limite);

    // ---- Stock ------------------------------------------------------------------------------------------------

    @Query("SELECT p FROM Produit p LEFT JOIN FETCH p.categorie c WHERE p.boutique.id = :boutiqueId AND p.nature = :nature ORDER BY p.id")
    List<Produit> produitsEnStock(@Param("boutiqueId") Long boutiqueId, @Param("nature") NatureProduit nature, Pageable limite);
}
