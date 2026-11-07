package org.example.stock.repository;

import org.example.stock.enums.NatureProduit;
import org.example.stock.enums.TypeOperationTresorerie;
import org.example.stock.model.Produit;
import org.example.stock.model.Vente;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Requêtes agrégées du tableau de bord. Chaque requête reçoit l'identifiant de la boutique du
 * compte connecté et des bornes semi-ouvertes [debut, fin) ; aucune ligne n'est chargée en mémoire
 * lorsqu'une somme suffit.
 */
public interface TableauDeBordRepository extends Repository<Vente, Long> {

    /** Chiffre d'affaires : Σ montant total des ventes de la période. */
    @Query("SELECT COALESCE(SUM(v.montantTotal), 0) FROM Vente v WHERE v.boutique.id = :boutiqueId "
            + "AND v.dateVente >= :debut AND v.dateVente < :fin")
    BigDecimal chiffreAffaires(@Param("boutiqueId") Long boutiqueId,
                               @Param("debut") LocalDateTime debut, @Param("fin") LocalDateTime fin);

    /** Bénéfice brut : Σ (prix unitaire − coût unitaire) × quantité ; un coût absent compte pour 0. */
    @Query("SELECT COALESCE(SUM((COALESCE(d.prixUnitaire, 0) - COALESCE(d.coutUnitaire, 0)) * COALESCE(d.quantite, 0)), 0) "
            + "FROM DetailVente d JOIN d.vente v WHERE v.boutique.id = :boutiqueId "
            + "AND v.dateVente >= :debut AND v.dateVente < :fin")
    BigDecimal beneficeBrut(@Param("boutiqueId") Long boutiqueId,
                            @Param("debut") LocalDateTime debut, @Param("fin") LocalDateTime fin);

    /** Lignes de vente de la période dont le coût unitaire n'est pas connu. */
    @Query("SELECT COUNT(d) FROM DetailVente d JOIN d.vente v WHERE v.boutique.id = :boutiqueId "
            + "AND v.dateVente >= :debut AND v.dateVente < :fin AND d.coutUnitaire IS NULL")
    long lignesSansCout(@Param("boutiqueId") Long boutiqueId,
                        @Param("debut") LocalDateTime debut, @Param("fin") LocalDateTime fin);

    /** Dépenses : opérations de trésorerie de type DEPENSE (les achats et transferts n'en sont pas). */
    @Query("SELECT COALESCE(SUM(o.montant), 0) FROM OperationTresorerie o WHERE o.boutique.id = :boutiqueId "
            + "AND o.type = :type AND o.dateOperation >= :debut AND o.dateOperation < :fin")
    BigDecimal depenses(@Param("boutiqueId") Long boutiqueId, @Param("type") TypeOperationTresorerie type,
                        @Param("debut") LocalDateTime debut, @Param("fin") LocalDateTime fin);

    /** Ventes par mois : [mois (1-12), Σ montant total]. */
    @Query("SELECT EXTRACT(MONTH FROM v.dateVente), COALESCE(SUM(v.montantTotal), 0) FROM Vente v "
            + "WHERE v.boutique.id = :boutiqueId AND v.dateVente >= :debut AND v.dateVente < :fin "
            + "GROUP BY EXTRACT(MONTH FROM v.dateVente)")
    List<Object[]> chiffreAffairesParMois(@Param("boutiqueId") Long boutiqueId,
                                          @Param("debut") LocalDateTime debut, @Param("fin") LocalDateTime fin);

    /** Bénéfice brut par mois : [mois (1-12), Σ marge]. */
    @Query("SELECT EXTRACT(MONTH FROM v.dateVente), "
            + "COALESCE(SUM((COALESCE(d.prixUnitaire, 0) - COALESCE(d.coutUnitaire, 0)) * COALESCE(d.quantite, 0)), 0) "
            + "FROM DetailVente d JOIN d.vente v WHERE v.boutique.id = :boutiqueId "
            + "AND v.dateVente >= :debut AND v.dateVente < :fin GROUP BY EXTRACT(MONTH FROM v.dateVente)")
    List<Object[]> beneficeBrutParMois(@Param("boutiqueId") Long boutiqueId,
                                       @Param("debut") LocalDateTime debut, @Param("fin") LocalDateTime fin);

    /** Dépenses par mois : [mois (1-12), Σ montant]. */
    @Query("SELECT EXTRACT(MONTH FROM o.dateOperation), COALESCE(SUM(o.montant), 0) FROM OperationTresorerie o "
            + "WHERE o.boutique.id = :boutiqueId AND o.type = :type "
            + "AND o.dateOperation >= :debut AND o.dateOperation < :fin GROUP BY EXTRACT(MONTH FROM o.dateOperation)")
    List<Object[]> depensesParMois(@Param("boutiqueId") Long boutiqueId, @Param("type") TypeOperationTresorerie type,
                                   @Param("debut") LocalDateTime debut, @Param("fin") LocalDateTime fin);

    /** Valeur du stock : [Σ quantité × coût moyen, nombre de produits] des articles de la nature donnée. */
    @Query("SELECT COALESCE(SUM(COALESCE(p.quantite, 0) * COALESCE(p.coutMoyen, 0)), 0), COUNT(p) FROM Produit p "
            + "WHERE p.boutique.id = :boutiqueId AND p.nature = :nature")
    List<Object[]> valeurStock(@Param("boutiqueId") Long boutiqueId, @Param("nature") NatureProduit nature);

    /**
     * Articles en rupture (quantité ≤ 0) ou au seuil (quantité ≤ seuil), comme {@code Produit.etatStock()} :
     * ruptures d'abord, puis les quantités les plus basses.
     */
    @Query("SELECT p FROM Produit p WHERE p.boutique.id = :boutiqueId AND p.nature = :nature "
            + "AND p.quantite IS NOT NULL AND (p.quantite <= 0 OR p.quantite <= COALESCE(p.seuilAlerte, :seuilParDefaut)) "
            + "ORDER BY CASE WHEN p.quantite <= 0 THEN 0 ELSE 1 END, p.quantite, p.nom, p.id")
    List<Produit> produitsASurveiller(@Param("boutiqueId") Long boutiqueId, @Param("nature") NatureProduit nature,
                                      @Param("seuilParDefaut") Long seuilParDefaut, Pageable page);

    @Query("SELECT COUNT(p) FROM Produit p WHERE p.boutique.id = :boutiqueId AND p.nature = :nature "
            + "AND p.quantite IS NOT NULL AND (p.quantite <= 0 OR p.quantite <= COALESCE(p.seuilAlerte, :seuilParDefaut))")
    long nombreProduitsASurveiller(@Param("boutiqueId") Long boutiqueId, @Param("nature") NatureProduit nature,
                                   @Param("seuilParDefaut") Long seuilParDefaut);
}
