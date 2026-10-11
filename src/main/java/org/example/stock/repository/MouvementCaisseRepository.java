package org.example.stock.repository;

import org.example.stock.model.MouvementCaisse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;

@Repository
public interface MouvementCaisseRepository extends JpaRepository<MouvementCaisse, Long> {

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.boutique.id = :boutiqueId")
    BigDecimal calculerSoldeTotal(@Param("boutiqueId") Long boutiqueId);

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.dateMouvement >= :date AND m.boutique.id = :boutiqueId")
    BigDecimal calculerFluxTotalDepuis(@Param("date") LocalDateTime date, @Param("boutiqueId") Long boutiqueId);

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.dateMouvement < :date AND m.boutique.id = :boutiqueId")
    BigDecimal calculerSoldeAvant(@Param("date") LocalDateTime date, @Param("boutiqueId") Long boutiqueId);

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.dateMouvement >= :date AND m.montant > 0 AND m.boutique.id = :boutiqueId")
    BigDecimal calculerEntreesDepuis(@Param("date") LocalDateTime date, @Param("boutiqueId") Long boutiqueId);

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.dateMouvement >= :date AND m.montant < 0 AND m.boutique.id = :boutiqueId")
    BigDecimal calculerSortiesDepuis(@Param("date") LocalDateTime date, @Param("boutiqueId") Long boutiqueId);

    List<MouvementCaisse> findByBoutiqueIdOrderByDateMouvementDesc(Long boutiqueId);
    List<MouvementCaisse> findBySourceAndBoutiqueId(String source, Long boutiqueId);
    List<MouvementCaisse> findByAchatIdAndBoutiqueIdOrderByDateMouvementDescIdDesc(Long achatId, Long boutiqueId);
    List<MouvementCaisse> findByVenteIdAndBoutiqueIdOrderByDateMouvementDescIdDesc(Long venteId, Long boutiqueId);
    List<MouvementCaisse> findByDateMouvementGreaterThanEqualAndBoutiqueIdOrderByDateMouvementDesc(LocalDateTime date, Long boutiqueId);

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m")
    BigDecimal calculerSoldeTotal();

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.dateMouvement >= :date")
    BigDecimal calculerFluxTotalDepuis(@Param("date") LocalDateTime date);

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.dateMouvement < :date")
    BigDecimal calculerSoldeAvant(@Param("date") LocalDateTime date);

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.dateMouvement >= :date AND m.montant > 0")
    BigDecimal calculerEntreesDepuis(@Param("date") LocalDateTime date);

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.dateMouvement >= :date AND m.montant < 0")
    BigDecimal calculerSortiesDepuis(@Param("date") LocalDateTime date);

    List<MouvementCaisse> findAllByOrderByDateMouvementDesc();
    List<MouvementCaisse> findBySource(String source);
    List<MouvementCaisse> findByAchatIdOrderByDateMouvementDescIdDesc(Long achatId);
    List<MouvementCaisse> findByVenteIdOrderByDateMouvementDescIdDesc(Long venteId);
    List<MouvementCaisse> findByDateMouvementGreaterThanEqualOrderByDateMouvementDesc(LocalDateTime date);
}
