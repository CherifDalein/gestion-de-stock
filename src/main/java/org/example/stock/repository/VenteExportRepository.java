package org.example.stock.repository;

import org.example.stock.model.Vente;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Marges par vente pour l'export des ventes (gérant seulement). Le coût est celui figé sur la ligne au moment de la vente ;
 * un coût absent compte pour 0, comme dans le tableau de bord. Les identifiants viennent de ventes déjà filtrées par boutique.
 */
public interface VenteExportRepository extends Repository<Vente, Long> {

    /** [identifiant de la vente, Σ (prix − coût) × quantité, nombre de lignes sans coût connu]. */
    @Query("SELECT d.vente.id, COALESCE(SUM((COALESCE(d.prixUnitaire, 0) - COALESCE(d.coutUnitaire, 0)) * COALESCE(d.quantite, 0)), 0), "
            + "COUNT(CASE WHEN d.coutUnitaire IS NULL THEN 1 END) FROM DetailVente d WHERE d.vente.id IN :ids GROUP BY d.vente.id")
    List<Object[]> margesDesVentes(@Param("ids") Collection<Long> ids);

    /** [identifiant de la vente d'origine, Σ marge des marchandises retournées], à retrancher de la marge. */
    @Query("SELECT r.vente.id, COALESCE(SUM((COALESCE(d.prixUnitaire, 0) - COALESCE(d.coutUnitaire, 0)) * COALESCE(d.quantite, 0)), 0) "
            + "FROM DetailRetour d JOIN d.retour r WHERE r.type = org.example.stock.enums.TypeRetour.VENTE AND r.vente.id IN :ids GROUP BY r.vente.id")
    List<Object[]> margesDesRetours(@Param("ids") Collection<Long> ids);
}
