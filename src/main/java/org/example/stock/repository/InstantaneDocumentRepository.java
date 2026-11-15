package org.example.stock.repository;

import org.example.stock.model.InstantaneDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InstantaneDocumentRepository extends JpaRepository<InstantaneDocument, Long> {
    List<InstantaneDocument> findByInstantaneIdOrderById(Long instantaneId);

    Optional<InstantaneDocument> findByInstantaneIdAndCode(Long instantaneId, String code);
}
