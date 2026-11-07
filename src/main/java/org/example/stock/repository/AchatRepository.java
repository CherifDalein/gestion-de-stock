package org.example.stock.repository;

import org.example.stock.model.Achat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AchatRepository extends JpaRepository<Achat, Long> {
    Optional<Achat> findByIdAndBoutiqueId(Long id, Long boutiqueId);
    List<Achat> findByBoutiqueIdOrderByDateAchatDesc(Long boutiqueId);
    List<Achat> findByDateAchatAfterAndBoutiqueId(LocalDateTime date, Long boutiqueId);
    List<Achat> findByFournisseurIdAndBoutiqueIdOrderByDateAchatDesc(Long fournisseurId, Long boutiqueId);
    List<Achat> findByFournisseurIdAndDateAchatAfterAndBoutiqueIdOrderByDateAchatDesc(Long fournisseurId, LocalDateTime date, Long boutiqueId);

    /** Échus non soldés : reste à payer (montant versé vide compté zéro) et échéance strictement antérieure au jour. */
    @Query("SELECT a FROM Achat a WHERE a.boutique.id = :boutiqueId AND a.dateEcheance < :aujourdHui"
            + " AND COALESCE(a.montantTotal, 0) > COALESCE(a.montantVerse, 0) ORDER BY a.dateEcheance ASC, a.id ASC")
    List<Achat> findEchusNonSoldes(@Param("boutiqueId") Long boutiqueId, @Param("aujourdHui") LocalDate aujourdHui);
    List<Achat> findAllByOrderByDateAchatDesc();
    List<Achat> findByDateAchatAfter(LocalDateTime date);
    List<Achat> findByFournisseurIdOrderByDateAchatDesc(Long fournisseurId);
    List<Achat> findByFournisseurIdAndDateAchatAfterOrderByDateAchatDesc(Long fournisseurId, LocalDateTime date);
}
