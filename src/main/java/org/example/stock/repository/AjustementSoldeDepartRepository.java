package org.example.stock.repository;

import org.example.stock.model.AjustementSoldeDepart;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Historique des ajustements de solde de départ ; toutes les lectures sont bornées à la boutique du compte connecté. */
public interface AjustementSoldeDepartRepository extends JpaRepository<AjustementSoldeDepart, Long> {
    List<AjustementSoldeDepart> findByBoutiqueIdAndClientIdOrderByDateAjustementAscIdAsc(Long boutiqueId, Long clientId);

    List<AjustementSoldeDepart> findByBoutiqueIdAndFournisseurIdOrderByDateAjustementAscIdAsc(Long boutiqueId, Long fournisseurId);

    boolean existsByBoutiqueIdAndClientId(Long boutiqueId, Long clientId);

    boolean existsByBoutiqueIdAndFournisseurId(Long boutiqueId, Long fournisseurId);

    Optional<AjustementSoldeDepart> findByBoutiqueIdAndJeton(Long boutiqueId, String jeton);
}
