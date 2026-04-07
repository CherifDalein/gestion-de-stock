package org.example.stock.repository;

import org.example.stock.model.MouvementCaisse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MouvementCaisseRepository extends JpaRepository<MouvementCaisse, Long> {

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m")
    Double calculerSoldeTotal();

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.dateMouvement >= :date")
    Double calculerFluxTotalDepuis(@Param("date") LocalDateTime date);

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.dateMouvement < :date")
    Double calculerSoldeAvant(@Param("date") LocalDateTime date);

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.dateMouvement >= :date AND m.montant > 0")
    Double calculerEntreesDepuis(@Param("date") LocalDateTime date);

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.dateMouvement >= :date AND m.montant < 0")
    Double calculerSortiesDepuis(@Param("date") LocalDateTime date);

    List<MouvementCaisse> findAllByOrderByDateMouvementDesc();
    List<MouvementCaisse> findBySource(String source);
    List<MouvementCaisse> findByDateMouvementGreaterThanEqualOrderByDateMouvementDesc(LocalDateTime date);
}
