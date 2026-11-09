package org.example.stock.repository;

import org.example.stock.enums.NatureProduit;
import org.example.stock.model.Produit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProduitRepository extends JpaRepository<Produit, Long> {
    List<Produit> findAllByBoutiqueId(Long boutiqueId);

    /** Pour l'export : les produits de la boutique avec leur catégorie et leur fournisseur, lus par page (la limite d'export). */
    @EntityGraph(attributePaths = {"categorie", "fournisseur"})
    List<Produit> findPourExportByBoutiqueId(Long boutiqueId, Pageable page);
    Optional<Produit> findByIdAndBoutiqueId(Long id, Long boutiqueId);
    boolean existsByIdAndBoutiqueId(Long id, Long boutiqueId);
    List<Produit> findAllByIdInAndBoutiqueIdAndNature(Collection<Long> ids, Long boutiqueId, NatureProduit nature);
}
