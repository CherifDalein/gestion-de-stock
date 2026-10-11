package org.example.stock.repository;

import org.example.stock.model.Categorie;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CategorieRepository extends JpaRepository<Categorie, Long> {
    List<Categorie> findAllByBoutiqueId(Long boutiqueId);
    Optional<Categorie> findByIdAndBoutiqueId(Long id, Long boutiqueId);
    boolean existsByIdAndBoutiqueId(Long id, Long boutiqueId);
    Optional<Categorie> findByNomAndBoutiqueId(String nom, Long boutiqueId);
}
