package org.example.stock.repository;

import org.example.stock.model.Rapprochement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RapprochementRepository extends JpaRepository<Rapprochement, Long> {
    Optional<Rapprochement> findByIdAndReleveId(Long id, Long releveId);

    @Query("select r from Rapprochement r join fetch r.utilisateur where r.releve.id = :releve order by r.id")
    List<Rapprochement> duReleve(@Param("releve") Long releveId);

    @Modifying
    @Query("delete from Rapprochement r where r.releve.id = :releve")
    void supprimerDuReleve(@Param("releve") Long releveId);
}
