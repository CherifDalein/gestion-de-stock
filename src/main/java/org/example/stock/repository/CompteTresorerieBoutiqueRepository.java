package org.example.stock.repository;

import jakarta.persistence.LockModeType;
import org.example.stock.enums.CompteTresorerie;
import org.example.stock.model.CompteTresorerieBoutique;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CompteTresorerieBoutiqueRepository extends JpaRepository<CompteTresorerieBoutique, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CompteTresorerieBoutique c where c.boutique.id = :boutique and c.compte = :compte")
    Optional<CompteTresorerieBoutique> verrouiller(@Param("boutique") Long boutiqueId, @Param("compte") CompteTresorerie compte);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from CompteTresorerieBoutique c where c.boutique.id = :boutique and c.compte = :compte")
    Optional<CompteTresorerieBoutique> verrouillerLecture(@Param("boutique") Long boutiqueId, @Param("compte") CompteTresorerie compte);
}
