package org.example.stock.repository;

import org.example.stock.model.MouvementStock;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MouvementStockRepository extends JpaRepository<MouvementStock, Long> {
    boolean existsByProduitId(Long produitId);
    Slice<MouvementStock> findByBoutiqueIdAndProduitIdOrderByCreeLeDescIdDesc(Long boutiqueId, Long produitId, Pageable pageable);
}
