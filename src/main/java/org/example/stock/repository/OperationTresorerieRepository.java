package org.example.stock.repository;

import org.example.stock.model.OperationTresorerie;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OperationTresorerieRepository extends JpaRepository<OperationTresorerie, Long> {
    Optional<OperationTresorerie> findByJeton(String jeton);
}
