package org.example.stock.repository;

import org.example.stock.model.Vente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface VenteRepository extends JpaRepository<Vente, Long> {
    List<Vente> findByDateVenteAfter(LocalDateTime date);
    List<Vente> findAllByOrderByDateVenteDesc();
    List<Vente> findByClientIdAndDateVenteAfterOrderByDateVenteDesc(Long clientId, LocalDateTime date);
    List<Vente> findByClientIdOrderByDateVenteDesc(Long clientId);

    @Query("SELECT COALESCE(SUM(v.montantVerse), 0) FROM Vente v WHERE v.dateVente >= :date")
    Double calculerTotalVentesDepuis(@Param("date") LocalDateTime date);
}
