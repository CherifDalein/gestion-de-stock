package org.example.stock.repository;

import org.example.stock.model.SousCompteBoutique;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SousCompteBoutiqueRepository extends JpaRepository<SousCompteBoutique, Long> {
    /** Sous-comptes de la boutique, par numéro, avec leur compte de trésorerie (une seule requête). */
    @Query("SELECT s FROM SousCompteBoutique s LEFT JOIN FETCH s.compteTresorerie WHERE s.boutique.id = :boutiqueId ORDER BY s.numero")
    List<SousCompteBoutique> deLaBoutique(@Param("boutiqueId") Long boutiqueId);

    Optional<SousCompteBoutique> findByIdAndBoutiqueId(Long id, Long boutiqueId);

    boolean existsByBoutiqueIdAndNumero(Long boutiqueId, String numero);

    long countByBoutiqueId(Long boutiqueId);
}
