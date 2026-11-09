package org.example.stock.repository;

import org.example.stock.model.Categorie;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface CategorieRepository extends JpaRepository<Categorie, Long> {
    List<Categorie> findAllByBoutiqueId(Long boutiqueId);

    /** Pour l'export : [identifiant, nom, nombre de produits] de chaque catégorie de la boutique, lus par page (la limite d'export). */
    @Query("SELECT c.id, c.nom, COUNT(p.id) FROM Categorie c LEFT JOIN c.produits p WHERE c.boutique.id = :boutiqueId GROUP BY c.id, c.nom ORDER BY c.id")
    List<Object[]> compterProduitsParCategorie(@Param("boutiqueId") Long boutiqueId, Pageable page);

    Optional<Categorie> findByIdAndBoutiqueId(Long id, Long boutiqueId);
    boolean existsByIdAndBoutiqueId(Long id, Long boutiqueId);
    Optional<Categorie> findByNomAndBoutiqueId(String nom, Long boutiqueId);
}
