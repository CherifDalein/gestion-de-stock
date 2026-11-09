package org.example.stock.repository;

import org.example.stock.model.Vente;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface VenteRepository extends JpaRepository<Vente, Long>, JpaSpecificationExecutor<Vente> {
    Optional<Vente> findByIdAndBoutiqueId(Long id, Long boutiqueId);
    @EntityGraph(attributePaths = "vendeur")
    List<Vente> findByBoutiqueIdOrderByDateVenteDesc(Long boutiqueId);
    /** Ventes d'un vendeur de la boutique : l'identifiant du vendeur est validé par l'appelant. */
    @EntityGraph(attributePaths = "vendeur")
    List<Vente> findByBoutiqueIdAndVendeurIdOrderByDateVenteDesc(Long boutiqueId, Long vendeurId);
    List<Vente> findByDateVenteAfterAndBoutiqueId(LocalDateTime date, Long boutiqueId);
    List<Vente> findByClientIdAndBoutiqueIdOrderByDateVenteDesc(Long clientId, Long boutiqueId);
    List<Vente> findByClientIdAndDateVenteAfterAndBoutiqueIdOrderByDateVenteDesc(Long clientId, LocalDateTime date, Long boutiqueId);

    /**
     * Échues non soldées : reste à payer positif (total − retours − versé − avance utilisée, valeurs vides comptées zéro)
     * et échéance strictement antérieure au jour.
     */
    @Query("SELECT v FROM Vente v WHERE v.boutique.id = :boutiqueId AND v.dateEcheance < :aujourdHui"
            + " AND COALESCE(v.montantTotal, 0) - COALESCE(v.montantRetours, 0) - COALESCE(v.montantVerse, 0) - COALESCE(v.avanceUtilisee, 0) > 0 ORDER BY v.dateEcheance ASC, v.id ASC")
    List<Vente> findEchuesNonSoldees(@Param("boutiqueId") Long boutiqueId, @Param("aujourdHui") LocalDate aujourdHui);

    /** Mêmes ventes échues non soldées, limitées à un vendeur. */
    @Query("SELECT v FROM Vente v WHERE v.boutique.id = :boutiqueId AND v.vendeur.id = :vendeurId AND v.dateEcheance < :aujourdHui"
            + " AND COALESCE(v.montantTotal, 0) - COALESCE(v.montantRetours, 0) - COALESCE(v.montantVerse, 0) - COALESCE(v.avanceUtilisee, 0) > 0 ORDER BY v.dateEcheance ASC, v.id ASC")
    List<Vente> findEchuesNonSoldeesDuVendeur(@Param("boutiqueId") Long boutiqueId, @Param("vendeurId") Long vendeurId,
                                              @Param("aujourdHui") LocalDate aujourdHui);

    /** Réglé sur les ventes depuis la date : versé + avance utilisée, plafonné au total effectif (total − retours ; le surplus est une avance ou un remboursement). */
    @Query("SELECT COALESCE(SUM(CASE WHEN COALESCE(v.montantVerse, 0) + COALESCE(v.avanceUtilisee, 0) > COALESCE(v.montantTotal, 0) - COALESCE(v.montantRetours, 0) THEN COALESCE(v.montantTotal, 0) - COALESCE(v.montantRetours, 0) ELSE COALESCE(v.montantVerse, 0) + COALESCE(v.avanceUtilisee, 0) END), 0) FROM Vente v WHERE v.dateVente >= :date AND v.boutique.id = :boutiqueId")
    BigDecimal calculerTotalVentesDepuis(@Param("date") LocalDateTime date, @Param("boutiqueId") Long boutiqueId);
    List<Vente> findByDateVenteAfter(LocalDateTime date);
    List<Vente> findAllByOrderByDateVenteDesc();
    List<Vente> findByClientIdAndDateVenteAfterOrderByDateVenteDesc(Long clientId, LocalDateTime date);
    List<Vente> findByClientIdOrderByDateVenteDesc(Long clientId);

    @Query("SELECT COALESCE(SUM(CASE WHEN COALESCE(v.montantVerse, 0) + COALESCE(v.avanceUtilisee, 0) > COALESCE(v.montantTotal, 0) - COALESCE(v.montantRetours, 0) THEN COALESCE(v.montantTotal, 0) - COALESCE(v.montantRetours, 0) ELSE COALESCE(v.montantVerse, 0) + COALESCE(v.avanceUtilisee, 0) END), 0) FROM Vente v WHERE v.dateVente >= :date")
    BigDecimal calculerTotalVentesDepuis(@Param("date") LocalDateTime date);
}
