package org.example.stock.repository;

import org.example.stock.model.Vente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface VenteRepository extends JpaRepository<Vente, Long> {
    List<Vente> findByDateVenteAfter(LocalDateTime date);
    List<Vente> findAllByOrderByDateVenteDesc();
    // Filtre par client ET par date (après une certaine date)
    List<Vente> findByClientIdAndDateVenteAfterOrderByDateVenteDesc(Long clientId, LocalDateTime date);

    // Toutes les ventes d'un client
    List<Vente> findByClientIdOrderByDateVenteDesc(Long clientId);

    // Calcule la somme des montants versés depuis une date précise
    @Query("SELECT COALESCE(SUM(v.montantVerse), 0) FROM Vente v WHERE v.dateVente >= :date")
    Double calculerTotalVentesDepuis(LocalDateTime date);
}
