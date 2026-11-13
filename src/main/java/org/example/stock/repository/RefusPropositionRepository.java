package org.example.stock.repository;

import org.example.stock.model.RefusProposition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RefusPropositionRepository extends JpaRepository<RefusProposition, Long> {
    @Query("select r from RefusProposition r where r.ligne.releve.id = :releve")
    List<RefusProposition> duReleve(@Param("releve") Long releveId);

    boolean existsByLigneIdAndMouvementId(Long ligneId, Long mouvementId);

    @Modifying
    @Query("delete from RefusProposition r where r.ligne.releve.id = :releve")
    void supprimerDuReleve(@Param("releve") Long releveId);

    @Modifying
    @Query("delete from RefusProposition r where r.ligne.id = :ligne")
    void supprimerDeLaLigne(@Param("ligne") Long ligneId);
}
