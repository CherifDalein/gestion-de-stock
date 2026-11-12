package org.example.stock.repository;

import org.example.stock.model.InventaireCreances;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Inventaires des créances et dettes ; toutes les lectures sont bornées à la boutique du compte connecté. */
public interface InventaireCreancesRepository extends JpaRepository<InventaireCreances, Long> {
    Optional<InventaireCreances> findByBoutiqueIdAndJeton(Long boutiqueId, String jeton);

    @EntityGraph(attributePaths = {"utilisateur"})
    Optional<InventaireCreances> findByIdAndBoutiqueId(Long id, Long boutiqueId);

    @EntityGraph(attributePaths = {"utilisateur"})
    List<InventaireCreances> findTop200ByBoutiqueIdOrderByDateInventaireDescIdDesc(Long boutiqueId);

    /** Nombre de lignes avec écart de chaque inventaire de la boutique : [identifiant, nombre de lignes, nombre d'écarts]. */
    @Query("SELECT l.inventaire.id, COUNT(l), SUM(CASE WHEN l.ecart <> 0 THEN 1 ELSE 0 END) FROM LigneInventaire l WHERE l.inventaire.boutique.id = :boutiqueId GROUP BY l.inventaire.id")
    List<Object[]> comptesParInventaire(@Param("boutiqueId") Long boutiqueId);
}
