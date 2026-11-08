package org.example.stock.repository;

import org.example.stock.model.Retour;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RetourRepository extends JpaRepository<Retour, Long> {
    Optional<Retour> findByIdAndBoutiqueId(Long id, Long boutiqueId);
}
