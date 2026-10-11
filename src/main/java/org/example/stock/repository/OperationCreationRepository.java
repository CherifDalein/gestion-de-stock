package org.example.stock.repository;

import jakarta.persistence.LockModeType;
import org.example.stock.model.OperationCreation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface OperationCreationRepository extends JpaRepository<OperationCreation, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select operation from OperationCreation operation where operation.jeton = :jeton AND operation.boutique.id = :boutiqueId")
    Optional<OperationCreation> verrouiller(@Param("jeton") String jeton, @Param("boutiqueId") Long boutiqueId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select operation from OperationCreation operation where operation.jeton = :jeton")
    Optional<OperationCreation> verrouiller(@Param("jeton") String jeton);
}
