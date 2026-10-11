package org.example.stock.repository;

import org.example.stock.model.Boutique;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BoutiqueRepository extends JpaRepository<Boutique, Long> {
    List<Boutique> findAllByOrderByDateCreationDescIdDesc();
}
