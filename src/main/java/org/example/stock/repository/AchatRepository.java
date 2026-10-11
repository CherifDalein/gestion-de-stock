package org.example.stock.repository;

import org.example.stock.model.Achat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AchatRepository extends JpaRepository<Achat, Long> {
    Optional<Achat> findByIdAndBoutiqueId(Long id, Long boutiqueId);
    List<Achat> findByBoutiqueIdOrderByDateAchatDesc(Long boutiqueId);
    List<Achat> findByDateAchatAfterAndBoutiqueId(LocalDateTime date, Long boutiqueId);
    List<Achat> findByFournisseurIdAndBoutiqueIdOrderByDateAchatDesc(Long fournisseurId, Long boutiqueId);
    List<Achat> findByFournisseurIdAndDateAchatAfterAndBoutiqueIdOrderByDateAchatDesc(Long fournisseurId, LocalDateTime date, Long boutiqueId);
    List<Achat> findAllByOrderByDateAchatDesc();
    List<Achat> findByDateAchatAfter(LocalDateTime date);
    List<Achat> findByFournisseurIdOrderByDateAchatDesc(Long fournisseurId);
    List<Achat> findByFournisseurIdAndDateAchatAfterOrderByDateAchatDesc(Long fournisseurId, LocalDateTime date);
}
