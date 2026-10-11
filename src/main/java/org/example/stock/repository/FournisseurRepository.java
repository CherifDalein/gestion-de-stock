package org.example.stock.repository;

import org.example.stock.model.Fournisseur;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

// Repository
public interface FournisseurRepository extends JpaRepository<Fournisseur, Long> {
    List<Fournisseur> findAllByBoutiqueId(Long boutiqueId);
    Optional<Fournisseur> findByIdAndBoutiqueId(Long id, Long boutiqueId);
    boolean existsByIdAndBoutiqueId(Long id, Long boutiqueId);
}

