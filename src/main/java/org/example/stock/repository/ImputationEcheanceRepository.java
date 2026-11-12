package org.example.stock.repository;

import org.example.stock.model.ImputationEcheance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ImputationEcheanceRepository extends JpaRepository<ImputationEcheance, Long> {
    /** Imputations d'un emprunt dont le remboursement n'est pas annulé (aucun mouvement d'annulation sur son opération). */
    @Query("select i from ImputationEcheance i join fetch i.echeance e where e.emprunt.id = :emprunt and not exists "
            + "(select 1 from MouvementCaisse m where m.operationTresorerie = i.operation and m.source = 'ANNULATION')")
    List<ImputationEcheance> activesDeLEmprunt(@Param("emprunt") Long empruntId);

    @Query("select i from ImputationEcheance i join fetch i.echeance e join e.emprunt p where p.boutique.id = :boutique and not exists "
            + "(select 1 from MouvementCaisse m where m.operationTresorerie = i.operation and m.source = 'ANNULATION')")
    List<ImputationEcheance> activesDeLaBoutique(@Param("boutique") Long boutiqueId);

    @Query("select count(i) > 0 from ImputationEcheance i where i.echeance.emprunt.id = :emprunt and not exists "
            + "(select 1 from MouvementCaisse m where m.operationTresorerie = i.operation and m.source = 'ANNULATION')")
    boolean existeActive(@Param("emprunt") Long empruntId);

    @Query("select i from ImputationEcheance i join fetch i.echeance e where i.operation.id in :operations order by e.rang, i.id")
    List<ImputationEcheance> deLOperation(@Param("operations") java.util.Collection<Long> operations);
}
