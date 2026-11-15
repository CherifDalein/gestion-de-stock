package org.example.stock.repository;

import org.example.stock.model.ReglageComptableBoutique;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReglageComptableBoutiqueRepository extends JpaRepository<ReglageComptableBoutique, Long> {
    Optional<ReglageComptableBoutique> findByBoutiqueId(Long boutiqueId);
}
