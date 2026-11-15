package org.example.stock.repository;

import org.example.stock.model.SoldeOuverture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SoldeOuvertureRepository extends JpaRepository<SoldeOuverture, Long> {
    List<SoldeOuverture> findByExerciceIdOrderByCompteAscTiersTypeAscTiersIdAsc(Long exerciceId);

    long countByExerciceId(Long exerciceId);

    @Modifying
    @Query("delete from SoldeOuverture s where s.exercice.id = :exerciceId")
    int supprimerDe(@Param("exerciceId") Long exerciceId);
}
