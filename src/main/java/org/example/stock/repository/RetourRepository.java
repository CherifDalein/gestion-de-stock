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

    /** Retours d'achat de la boutique, du plus récent au plus ancien, avec l'achat, le fournisseur et l'auteur. */
    @EntityGraph(attributePaths = {"achat", "fournisseur", "utilisateur"})
    @Query("SELECT r FROM Retour r WHERE r.boutique.id = :boutiqueId AND r.type = org.example.stock.enums.TypeRetour.ACHAT ORDER BY r.dateRetour DESC, r.id DESC")
    List<Retour> retoursAchat(@Param("boutiqueId") Long boutiqueId);

    long countByVenteId(Long venteId);

    long countByAchatId(Long achatId);

    /** Quantités déjà retournées d'un achat : [identifiant de la ligne d'achat, Σ quantité]. */
    @Query("SELECT d.detailAchat.id, SUM(d.quantite) FROM DetailRetour d WHERE d.retour.achat.id = :achatId GROUP BY d.detailAchat.id")
    List<Object[]> quantitesRetourneesAchat(@Param("achatId") Long achatId);

    /** Quantités déjà retournées d'une vente : [identifiant de la ligne de vente, Σ quantité]. */
    @Query("SELECT d.detailVente.id, SUM(d.quantite) FROM DetailRetour d WHERE d.retour.vente.id = :venteId GROUP BY d.detailVente.id")
    List<Object[]> quantitesRetournees(@Param("venteId") Long venteId);
}
