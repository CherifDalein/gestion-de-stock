package org.example.stock.repository;

import org.example.stock.model.Achat;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AchatRepository extends JpaRepository<Achat, Long> {
    Optional<Achat> findByIdAndBoutiqueId(Long id, Long boutiqueId);
    @EntityGraph(attributePaths = {"utilisateur", "retours"})
    List<Achat> findByBoutiqueIdOrderByDateAchatDesc(Long boutiqueId);
    List<Achat> findByDateAchatAfterAndBoutiqueId(LocalDateTime date, Long boutiqueId);
    List<Achat> findByFournisseurIdAndBoutiqueIdOrderByDateAchatDesc(Long fournisseurId, Long boutiqueId);
    List<Achat> findByFournisseurIdAndDateAchatAfterAndBoutiqueIdOrderByDateAchatDesc(Long fournisseurId, LocalDateTime date, Long boutiqueId);

    /**
     * Échus non soldés : reste à payer positif (total − versé − avance utilisée, valeurs vides comptées zéro)
     * et échéance strictement antérieure au jour.
     */
    @Query("SELECT a FROM Achat a WHERE a.boutique.id = :boutiqueId AND a.dateEcheance < :aujourdHui"
            + " AND COALESCE(a.montantTotal, 0) - COALESCE(a.montantRetours, 0) - COALESCE(a.montantVerse, 0) - COALESCE(a.avanceUtilisee, 0) > 0 ORDER BY a.dateEcheance ASC, a.id ASC")
    List<Achat> findEchusNonSoldes(@Param("boutiqueId") Long boutiqueId, @Param("aujourdHui") LocalDate aujourdHui);

    /** Pour l'export : les achats de la boutique, du plus récent au plus ancien, lus par page (la limite d'export), fournisseur et auteur inclus. */
    @EntityGraph(attributePaths = {"utilisateur", "fournisseur"})
    @Query("SELECT a FROM Achat a WHERE a.boutique.id = :boutiqueId ORDER BY a.dateAchat DESC, a.id DESC")
    List<Achat> findPourExport(@Param("boutiqueId") Long boutiqueId, Pageable page);

    /** Pour l'export : mêmes achats échus non soldés que {@link #findEchusNonSoldes}, lus par page. */
    @EntityGraph(attributePaths = {"utilisateur", "fournisseur"})
    @Query("SELECT a FROM Achat a WHERE a.boutique.id = :boutiqueId AND a.dateEcheance < :aujourdHui"
            + " AND COALESCE(a.montantTotal, 0) - COALESCE(a.montantRetours, 0) - COALESCE(a.montantVerse, 0) - COALESCE(a.avanceUtilisee, 0) > 0 ORDER BY a.dateEcheance ASC, a.id ASC")
    List<Achat> findEchusNonSoldesPourExport(@Param("boutiqueId") Long boutiqueId, @Param("aujourdHui") LocalDate aujourdHui, Pageable page);

    List<Achat> findAllByOrderByDateAchatDesc();
    List<Achat> findByDateAchatAfter(LocalDateTime date);
    List<Achat> findByFournisseurIdOrderByDateAchatDesc(Long fournisseurId);
    List<Achat> findByFournisseurIdAndDateAchatAfterOrderByDateAchatDesc(Long fournisseurId, LocalDateTime date);
}
