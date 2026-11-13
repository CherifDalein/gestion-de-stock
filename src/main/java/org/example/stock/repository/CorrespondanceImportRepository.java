package org.example.stock.repository;

import org.example.stock.model.CorrespondanceImport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CorrespondanceImportRepository extends JpaRepository<CorrespondanceImport, Long> {
    Optional<CorrespondanceImport> findByCompteId(Long compteId);
}
