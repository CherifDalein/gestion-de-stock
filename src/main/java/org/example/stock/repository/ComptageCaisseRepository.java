package org.example.stock.repository;

import org.example.stock.enums.StatutComptage;
import org.example.stock.model.ComptageCaisse;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/** Comptages de caisse ; toutes les lectures sont bornées à la boutique du compte connecté. */
public interface ComptageCaisseRepository extends JpaRepository<ComptageCaisse, Long>, JpaSpecificationExecutor<ComptageCaisse> {
    Optional<ComptageCaisse> findByBoutiqueIdAndJeton(Long boutiqueId, String jeton);

    @EntityGraph(attributePaths = {"compte", "utilisateur", "decideur", "annulePar"})
    Optional<ComptageCaisse> findByIdAndBoutiqueId(Long id, Long boutiqueId);

    @EntityGraph(attributePaths = {"compte", "utilisateur", "decideur", "annulePar"})
    List<ComptageCaisse> findByBoutiqueIdAndStatutOrderByDateComptageAscIdAsc(Long boutiqueId, StatutComptage statut);

    long countByBoutiqueIdAndStatut(Long boutiqueId, StatutComptage statut);
}
