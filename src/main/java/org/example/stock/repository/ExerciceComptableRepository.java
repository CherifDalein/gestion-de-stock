package org.example.stock.repository;

import org.example.stock.enums.StatutExercice;
import org.example.stock.model.ExerciceComptable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ExerciceComptableRepository extends JpaRepository<ExerciceComptable, Long> {
    List<ExerciceComptable> findByBoutiqueIdOrderByDateDebut(Long boutiqueId);

    Optional<ExerciceComptable> findByBoutiqueIdAndDateDebut(Long boutiqueId, LocalDate dateDebut);

    Optional<ExerciceComptable> findByIdAndBoutiqueId(Long id, Long boutiqueId);

    List<ExerciceComptable> findByBoutiqueIdAndStatutOrderByDateDebut(Long boutiqueId, StatutExercice statut);

    /**
     * Le dernier exercice clôturé, relu <b>sous verrou partagé</b> : une lecture verrouillante voit toujours la dernière valeur validée, même dans une
     * transaction dont l'instantané de lecture (REPEATABLE READ) est plus ancien. C'est ce qui empêche une écriture qui attendait la clôture de passer ensuite.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
    Optional<ExerciceComptable> findFirstByBoutiqueIdAndStatutOrderByDateFinDesc(Long boutiqueId, StatutExercice statut);

    /** Dernière date couverte par un exercice clôturé : les exercices clôturés se suivent depuis le premier, donc toute date jusque-là est figée. */
    @Query("select max(e.dateFin) from ExerciceComptable e where e.boutique.id = :boutiqueId and e.statut = org.example.stock.enums.StatutExercice.CLOTURE")
    LocalDate derniereDateCloturee(@Param("boutiqueId") Long boutiqueId);
}
