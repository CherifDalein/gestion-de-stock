package org.example.stock.repository;

import org.example.stock.model.MouvementCaisse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MouvementCaisseRepository extends JpaRepository<MouvementCaisse, Long> {

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m")
    Double calculerSoldeTotal();

    List<MouvementCaisse> findAllByOrderByDateMouvementDesc();
    List<MouvementCaisse> findBySource(String source);
}