package org.example.stock.repository;

import org.example.stock.model.HistoriqueAbonnement;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface HistoriqueAbonnementRepository extends JpaRepository<HistoriqueAbonnement, Long> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Transactional(readOnly = true)
    Optional<HistoriqueAbonnement> findByJetonAction(String jetonAction);

    Slice<HistoriqueAbonnement> findAllByBoutiqueIdOrderByCreeLeDescIdDesc(Long boutiqueId, Pageable pageable);
}
