package org.example.stock.repository;

import org.example.stock.model.Vente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface VenteRepository extends JpaRepository<Vente, Long> {
    Optional<Vente> findByIdAndBoutiqueId(Long id, Long boutiqueId);
    List<Vente> findByBoutiqueIdOrderByDateVenteDesc(Long boutiqueId);
    List<Vente> findByDateVenteAfterAndBoutiqueId(LocalDateTime date, Long boutiqueId);
    List<Vente> findByClientIdAndBoutiqueIdOrderByDateVenteDesc(Long clientId, Long boutiqueId);
    List<Vente> findByClientIdAndDateVenteAfterAndBoutiqueIdOrderByDateVenteDesc(Long clientId, LocalDateTime date, Long boutiqueId);

    /** Échues non soldées : reste à payer (montant versé vide compté zéro) et échéance strictement antérieure au jour. */
    @Query("SELECT v FROM Vente v WHERE v.boutique.id = :boutiqueId AND v.dateEcheance < :aujourdHui"
            + " AND COALESCE(v.montantTotal, 0) > COALESCE(v.montantVerse, 0) ORDER BY v.dateEcheance ASC, v.id ASC")
    List<Vente> findEchuesNonSoldees(@Param("boutiqueId") Long boutiqueId, @Param("aujourdHui") LocalDate aujourdHui);

    @Query("SELECT COALESCE(SUM(v.montantVerse), 0) FROM Vente v WHERE v.dateVente >= :date AND v.boutique.id = :boutiqueId")
    BigDecimal calculerTotalVentesDepuis(@Param("date") LocalDateTime date, @Param("boutiqueId") Long boutiqueId);
    List<Vente> findByDateVenteAfter(LocalDateTime date);
    List<Vente> findAllByOrderByDateVenteDesc();
    List<Vente> findByClientIdAndDateVenteAfterOrderByDateVenteDesc(Long clientId, LocalDateTime date);
    List<Vente> findByClientIdOrderByDateVenteDesc(Long clientId);

    @Query("SELECT COALESCE(SUM(v.montantVerse), 0) FROM Vente v WHERE v.dateVente >= :date")
    BigDecimal calculerTotalVentesDepuis(@Param("date") LocalDateTime date);
}
