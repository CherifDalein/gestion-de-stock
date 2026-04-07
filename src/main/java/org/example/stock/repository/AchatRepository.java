package org.example.stock.repository;

import org.example.stock.model.Achat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AchatRepository extends JpaRepository<Achat, Long> {
    List<Achat> findAllByOrderByDateAchatDesc();
    List<Achat> findByDateAchatAfter(LocalDateTime date);
    List<Achat> findByFournisseurIdOrderByDateAchatDesc(Long fournisseurId);
    List<Achat> findByFournisseurIdAndDateAchatAfterOrderByDateAchatDesc(Long fournisseurId, LocalDateTime date);
}
