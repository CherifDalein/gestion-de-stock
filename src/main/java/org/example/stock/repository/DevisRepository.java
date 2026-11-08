package org.example.stock.repository;

import org.example.stock.enums.StatutDevis;
import org.example.stock.model.Devis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DevisRepository extends JpaRepository<Devis, Long> {
    Optional<Devis> findByIdAndBoutiqueId(Long id, Long boutiqueId);

    @Query("SELECT d FROM Devis d LEFT JOIN FETCH d.client WHERE d.boutique.id = :boutiqueId ORDER BY d.dateDevis DESC, d.id DESC")
    List<Devis> findTous(@Param("boutiqueId") Long boutiqueId);

    @Query("SELECT d FROM Devis d LEFT JOIN FETCH d.client WHERE d.boutique.id = :boutiqueId AND d.statut = :statut ORDER BY d.dateDevis DESC, d.id DESC")
    List<Devis> findParStatut(@Param("boutiqueId") Long boutiqueId, @Param("statut") StatutDevis statut);

    /** En cours et encore valables : la validité n'est pas strictement antérieure au jour. */
    @Query("SELECT d FROM Devis d LEFT JOIN FETCH d.client WHERE d.boutique.id = :boutiqueId AND d.statut = org.example.stock.enums.StatutDevis.EN_COURS"
            + " AND d.dateValidite >= :aujourdHui ORDER BY d.dateDevis DESC, d.id DESC")
    List<Devis> findEnCoursValables(@Param("boutiqueId") Long boutiqueId, @Param("aujourdHui") LocalDate aujourdHui);

    /** En cours dont la validité est strictement antérieure au jour. */
    @Query("SELECT d FROM Devis d LEFT JOIN FETCH d.client WHERE d.boutique.id = :boutiqueId AND d.statut = org.example.stock.enums.StatutDevis.EN_COURS"
            + " AND d.dateValidite < :aujourdHui ORDER BY d.dateDevis DESC, d.id DESC")
    List<Devis> findExpires(@Param("boutiqueId") Long boutiqueId, @Param("aujourdHui") LocalDate aujourdHui);
}
