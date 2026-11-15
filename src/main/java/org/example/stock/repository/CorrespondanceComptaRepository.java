package org.example.stock.repository;

import org.example.stock.model.CorrespondanceCompta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CorrespondanceComptaRepository extends JpaRepository<CorrespondanceCompta, Long> {
    List<CorrespondanceCompta> findByBoutiqueId(Long boutiqueId);

    Optional<CorrespondanceCompta> findByBoutiqueIdAndCle(Long boutiqueId, String cle);

    boolean existsByBoutiqueIdAndCompteNumero(Long boutiqueId, String compteNumero);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM CorrespondanceCompta c WHERE c.boutique.id = :boutiqueId")
    int supprimerDeLaBoutique(@Param("boutiqueId") Long boutiqueId);
}
