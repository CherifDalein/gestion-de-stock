package org.example.stock.repository;

import org.example.stock.enums.TypeRetour;
import org.example.stock.model.Retour;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RetourRepository extends JpaRepository<Retour, Long> {
    Optional<Retour> findByIdAndBoutiqueId(Long id, Long boutiqueId);

    @EntityGraph(attributePaths = {"vente", "client", "utilisateur"})
    List<Retour> findByBoutiqueIdAndTypeOrderByDateRetourDescIdDesc(Long boutiqueId, TypeRetour type);

    long countByVenteId(Long venteId);

    /** Quantités déjà retournées d'une vente : [identifiant de la ligne de vente, Σ quantité]. */
    @Query("SELECT d.detailVente.id, SUM(d.quantite) FROM DetailRetour d WHERE d.retour.vente.id = :venteId GROUP BY d.detailVente.id")
    List<Object[]> quantitesRetournees(@Param("venteId") Long venteId);
}
