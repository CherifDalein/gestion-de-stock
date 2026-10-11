package org.example.stock.repository;

import org.example.stock.model.Produit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProduitRepository extends JpaRepository<Produit, Long> {
    List<Produit> findAllByBoutiqueId(Long boutiqueId);
    Optional<Produit> findByIdAndBoutiqueId(Long id, Long boutiqueId);
    boolean existsByIdAndBoutiqueId(Long id, Long boutiqueId);
}
