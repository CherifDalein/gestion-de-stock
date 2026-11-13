package org.example.stock.repository;

import org.example.stock.model.EvenementReleve;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EvenementReleveRepository extends JpaRepository<EvenementReleve, Long> {
    List<EvenementReleve> findByReleveIdOrderByIdAsc(Long releveId);

    @Modifying
    @Query("delete from EvenementReleve e where e.releve.id = :releve")
    void supprimerDuReleve(@Param("releve") Long releveId);
}
