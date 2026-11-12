package org.example.stock.repository;

import jakarta.persistence.LockModeType;
import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.model.CompteTresorerieBoutique;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CompteTresorerieBoutiqueRepository extends JpaRepository<CompteTresorerieBoutique, Long> {
    /** Verrou exclusif d'un compte de la boutique (transferts, annulations, archivage) ; jamais le compte d'une autre boutique. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CompteTresorerieBoutique c where c.boutique.id = :boutique and c.id = :id")
    Optional<CompteTresorerieBoutique> verrouiller(@Param("boutique") Long boutiqueId, @Param("id") Long id);

    /** Verrou partagé : les encaissements et paiements s'additionnent, mais attendent un transfert ou un archivage en cours. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from CompteTresorerieBoutique c where c.boutique.id = :boutique and c.id = :id")
    Optional<CompteTresorerieBoutique> verrouillerLecture(@Param("boutique") Long boutiqueId, @Param("id") Long id);

    Optional<CompteTresorerieBoutique> findByIdAndBoutiqueId(Long id, Long boutiqueId);

    @Query("select c from CompteTresorerieBoutique c where c.boutique.id = :boutique order by case c.type when org.example.stock.enums.TypeCompteTresorerie.ESPECES then 0 when org.example.stock.enums.TypeCompteTresorerie.ORANGE_MONEY then 1 else 2 end, c.ordre, c.id")
    List<CompteTresorerieBoutique> lister(@Param("boutique") Long boutiqueId);

    @Query("select c from CompteTresorerieBoutique c where c.boutique.id = :boutique and c.actif = true order by case c.type when org.example.stock.enums.TypeCompteTresorerie.ESPECES then 0 when org.example.stock.enums.TypeCompteTresorerie.ORANGE_MONEY then 1 else 2 end, c.ordre, c.id")
    List<CompteTresorerieBoutique> listerActifs(@Param("boutique") Long boutiqueId);

    @Query("select c from CompteTresorerieBoutique c where c.boutique.id = :boutique and c.type = :type and c.principalType is not null")
    Optional<CompteTresorerieBoutique> principal(@Param("boutique") Long boutiqueId, @Param("type") TypeCompteTresorerie type);

    long countByBoutiqueId(Long boutiqueId);

    boolean existsByBoutiqueIdAndNomCle(Long boutiqueId, String nomCle);

    boolean existsByBoutiqueIdAndNomCleAndIdNot(Long boutiqueId, String nomCle, Long id);

    @Query("select coalesce(max(c.ordre), -1) from CompteTresorerieBoutique c where c.boutique.id = :boutique and c.type = :type")
    int dernierOrdre(@Param("boutique") Long boutiqueId, @Param("type") TypeCompteTresorerie type);
}
