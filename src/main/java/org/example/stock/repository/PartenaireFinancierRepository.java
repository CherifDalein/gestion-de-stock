package org.example.stock.repository;

import org.example.stock.model.PartenaireFinancier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PartenaireFinancierRepository extends JpaRepository<PartenaireFinancier, Long> {
    Optional<PartenaireFinancier> findByIdAndBoutiqueId(Long id, Long boutiqueId);

    /** Partenaires actifs d'abord, puis par nom. */
    @Query("select p from PartenaireFinancier p where p.boutique.id = :boutique order by p.actif desc, p.nomCle, p.id")
    List<PartenaireFinancier> lister(@Param("boutique") Long boutiqueId);

    @Query("select p from PartenaireFinancier p where p.boutique.id = :boutique and p.actif = true order by p.nomCle, p.id")
    List<PartenaireFinancier> listerActifs(@Param("boutique") Long boutiqueId);

    boolean existsByBoutiqueIdAndNomCle(Long boutiqueId, String nomCle);

    boolean existsByBoutiqueIdAndNomCleAndIdNot(Long boutiqueId, String nomCle, Long id);
}
