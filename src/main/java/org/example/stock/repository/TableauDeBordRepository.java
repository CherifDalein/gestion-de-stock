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

    /**
     * Retours de vente de la période, comptés à LEUR date (date du retour) : Σ montant total. Ils se retranchent du
     * chiffre d'affaires. Les retours d'achat n'en font pas partie.
     */
    @Query("SELECT COALESCE(SUM(r.montantTotal), 0) FROM Retour r WHERE r.boutique.id = :boutiqueId "
            + "AND r.type = org.example.stock.enums.TypeRetour.VENTE AND r.dateRetour >= :debut AND r.dateRetour < :fin")
    BigDecimal retoursDeVente(@Param("boutiqueId") Long boutiqueId,
                              @Param("debut") LocalDateTime debut, @Param("fin") LocalDateTime fin);

    /** Marge des marchandises retournées : Σ (prix unitaire − coût figé) × quantité retournée, à retrancher du bénéfice brut. */
    @Query("SELECT COALESCE(SUM((COALESCE(d.prixUnitaire, 0) - COALESCE(d.coutUnitaire, 0)) * COALESCE(d.quantite, 0)), 0) "
            + "FROM DetailRetour d JOIN d.retour r WHERE r.boutique.id = :boutiqueId "
            + "AND r.type = org.example.stock.enums.TypeRetour.VENTE AND r.dateRetour >= :debut AND r.dateRetour < :fin")
    BigDecimal beneficeDesRetoursDeVente(@Param("boutiqueId") Long boutiqueId,
                                         @Param("debut") LocalDateTime debut, @Param("fin") LocalDateTime fin);

    /** Retours de vente par mois (date du retour) : [mois (1-12), Σ montant total]. */
    @Query("SELECT EXTRACT(MONTH FROM r.dateRetour), COALESCE(SUM(r.montantTotal), 0) FROM Retour r "
            + "WHERE r.boutique.id = :boutiqueId AND r.type = org.example.stock.enums.TypeRetour.VENTE "
            + "AND r.dateRetour >= :debut AND r.dateRetour < :fin GROUP BY EXTRACT(MONTH FROM r.dateRetour)")
    List<Object[]> retoursDeVenteParMois(@Param("boutiqueId") Long boutiqueId,
                                         @Param("debut") LocalDateTime debut, @Param("fin") LocalDateTime fin);

    /** Marge des retours de vente par mois (date du retour) : [mois (1-12), Σ marge]. */
    @Query("SELECT EXTRACT(MONTH FROM r.dateRetour), "
            + "COALESCE(SUM((COALESCE(d.prixUnitaire, 0) - COALESCE(d.coutUnitaire, 0)) * COALESCE(d.quantite, 0)), 0) "
            + "FROM DetailRetour d JOIN d.retour r WHERE r.boutique.id = :boutiqueId "
            + "AND r.type = org.example.stock.enums.TypeRetour.VENTE "
            + "AND r.dateRetour >= :debut AND r.dateRetour < :fin GROUP BY EXTRACT(MONTH FROM r.dateRetour)")
    List<Object[]> beneficeDesRetoursDeVenteParMois(@Param("boutiqueId") Long boutiqueId,
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

    /** Ventes par vendeur : [identifiant du vendeur (vide si non renseigné), nombre de ventes, Σ montant total]. */
    @Query("SELECT v.vendeur.id, COUNT(v), COALESCE(SUM(v.montantTotal), 0) FROM Vente v WHERE v.boutique.id = :boutiqueId "
            + "AND v.dateVente >= :debut AND v.dateVente < :fin GROUP BY v.vendeur.id")
    List<Object[]> ventesParVendeur(@Param("boutiqueId") Long boutiqueId,
                                    @Param("debut") LocalDateTime debut, @Param("fin") LocalDateTime fin);

    /**
     * Bénéfice brut par vendeur au coût figé : [identifiant du vendeur, Σ marge, lignes sans coût connu].
     * Un coût absent compte pour 0, comme dans le tableau de bord.
     */
    @Query("SELECT v.vendeur.id, "
            + "COALESCE(SUM((COALESCE(d.prixUnitaire, 0) - COALESCE(d.coutUnitaire, 0)) * COALESCE(d.quantite, 0)), 0), "
            + "COUNT(CASE WHEN d.coutUnitaire IS NULL THEN 1 END) "
            + "FROM DetailVente d JOIN d.vente v WHERE v.boutique.id = :boutiqueId "
            + "AND v.dateVente >= :debut AND v.dateVente < :fin GROUP BY v.vendeur.id")
    List<Object[]> beneficeBrutParVendeur(@Param("boutiqueId") Long boutiqueId,
                                          @Param("debut") LocalDateTime debut, @Param("fin") LocalDateTime fin);

    /**
     * Retours de vente par vendeur de la vente d'origine (date du retour) : [identifiant du vendeur (vide si non
     * renseigné), nombre de retours, Σ montant total].
     */
    @Query("SELECT v.vendeur.id, COUNT(r), COALESCE(SUM(r.montantTotal), 0) FROM Retour r JOIN r.vente v WHERE r.boutique.id = :boutiqueId "
            + "AND r.type = org.example.stock.enums.TypeRetour.VENTE AND r.dateRetour >= :debut AND r.dateRetour < :fin GROUP BY v.vendeur.id")
    List<Object[]> retoursDeVenteParVendeur(@Param("boutiqueId") Long boutiqueId,
                                            @Param("debut") LocalDateTime debut, @Param("fin") LocalDateTime fin);

    /** Marge des retours de vente par vendeur de la vente d'origine : [identifiant du vendeur, Σ marge]. */
    @Query("SELECT v.vendeur.id, "
            + "COALESCE(SUM((COALESCE(d.prixUnitaire, 0) - COALESCE(d.coutUnitaire, 0)) * COALESCE(d.quantite, 0)), 0) "
            + "FROM DetailRetour d JOIN d.retour r JOIN r.vente v WHERE r.boutique.id = :boutiqueId "
            + "AND r.type = org.example.stock.enums.TypeRetour.VENTE "
            + "AND r.dateRetour >= :debut AND r.dateRetour < :fin GROUP BY v.vendeur.id")
    List<Object[]> beneficeDesRetoursDeVenteParVendeur(@Param("boutiqueId") Long boutiqueId,
                                                       @Param("debut") LocalDateTime debut, @Param("fin") LocalDateTime fin);

    /**
     * Mouvements de trésorerie des sources de financement sur la période : [source, Σ des montants (signés)]. Avec
     * {@link #annulationsDesSources}, ils donnent chaque source nette de ses annulations.
     */
    @Query("SELECT m.source, COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.boutique.id = :boutiqueId AND m.source IN :sources "
            + "AND m.dateMouvement >= :debut AND m.dateMouvement < :fin GROUP BY m.source")
    List<Object[]> fluxDesSources(@Param("boutiqueId") Long boutiqueId, @Param("sources") java.util.Collection<String> sources,
                                  @Param("debut") LocalDateTime debut, @Param("fin") LocalDateTime fin);

    /** Annulations de la période, rattachées à la source du mouvement qu'elles annulent : [source d'origine, Σ des montants (signés)]. */
    @Query("SELECT o.source, COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m JOIN m.annulationDe o WHERE m.boutique.id = :boutiqueId "
            + "AND m.source = 'ANNULATION' AND o.source IN :sources AND m.dateMouvement >= :debut AND m.dateMouvement < :fin GROUP BY o.source")
    List<Object[]> annulationsDesSources(@Param("boutiqueId") Long boutiqueId, @Param("sources") java.util.Collection<String> sources,
                                         @Param("debut") LocalDateTime debut, @Param("fin") LocalDateTime fin);

    /** Comme {@link #fluxDesSources} par mois : [mois (1-12), source, Σ des montants]. */
    @Query("SELECT EXTRACT(MONTH FROM m.dateMouvement), m.source, COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.boutique.id = :boutiqueId "
            + "AND m.source IN :sources AND m.dateMouvement >= :debut AND m.dateMouvement < :fin GROUP BY EXTRACT(MONTH FROM m.dateMouvement), m.source")
    List<Object[]> fluxDesSourcesParMois(@Param("boutiqueId") Long boutiqueId, @Param("sources") java.util.Collection<String> sources,
                                         @Param("debut") LocalDateTime debut, @Param("fin") LocalDateTime fin);

    @Query("SELECT EXTRACT(MONTH FROM m.dateMouvement), o.source, COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m JOIN m.annulationDe o WHERE m.boutique.id = :boutiqueId "
            + "AND m.source = 'ANNULATION' AND o.source IN :sources AND m.dateMouvement >= :debut AND m.dateMouvement < :fin GROUP BY EXTRACT(MONTH FROM m.dateMouvement), o.source")
    List<Object[]> annulationsDesSourcesParMois(@Param("boutiqueId") Long boutiqueId, @Param("sources") java.util.Collection<String> sources,
                                                @Param("debut") LocalDateTime debut, @Param("fin") LocalDateTime fin);
}
