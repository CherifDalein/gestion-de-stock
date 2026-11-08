package org.example.stock.repository;

import org.example.stock.model.Devis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DevisRepository extends JpaRepository<Devis, Long> {
    Optional<Devis> findByIdAndBoutiqueId(Long id, Long boutiqueId);
}
