package org.example.stock.repository;

import org.example.stock.model.AffectationResultat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AffectationResultatRepository extends JpaRepository<AffectationResultat, Long> {
    Optional<AffectationResultat> findByExerciceId(Long exerciceId);

    List<AffectationResultat> findByBoutiqueId(Long boutiqueId);
}
