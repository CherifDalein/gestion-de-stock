package org.example.stock.repository;

import org.example.stock.enums.StatutInstantane;
import org.example.stock.model.InstantaneExercice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InstantaneExerciceRepository extends JpaRepository<InstantaneExercice, Long> {
    List<InstantaneExercice> findByBoutiqueIdOrderByDateDebutAscVersionAsc(Long boutiqueId);

    Optional<InstantaneExercice> findByIdAndBoutiqueId(Long id, Long boutiqueId);

    Optional<InstantaneExercice> findByExerciceIdAndStatut(Long exerciceId, StatutInstantane statut);

    int countByExerciceId(Long exerciceId);
}
