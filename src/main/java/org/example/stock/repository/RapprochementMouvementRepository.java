package org.example.stock.repository;

import jakarta.persistence.LockModeType;
import org.example.stock.model.MouvementCaisse;
import org.example.stock.model.RapprochementMouvement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RapprochementMouvementRepository extends JpaRepository<RapprochementMouvement, Long> {
    @Query("select rm from RapprochementMouvement rm join fetch rm.mouvement m join fetch m.compte where rm.rapprochement.releve.id = :releve order by m.dateMouvement, m.id")
    List<RapprochementMouvement> duReleve(@Param("releve") Long releveId);

    @Query("select rm from RapprochementMouvement rm join fetch rm.rapprochement where rm.mouvement.id in :ids")
    List<RapprochementMouvement> deMouvements(@Param("ids") Collection<Long> mouvementIds);

    /** Relevé qui a rapproché ce mouvement, s'il y en a un (lecture simple : l'appelant tient déjà le verrou de la boutique). */
    @Query("select rm.rapprochement.releve from RapprochementMouvement rm where rm.mouvement.id = :id")
    Optional<org.example.stock.model.ReleveBancaire> releveDuMouvement(@Param("id") Long mouvementId);

    @Query("select coalesce(sum(m.montant), 0) from RapprochementMouvement rm join rm.mouvement m where rm.rapprochement.id = :rapprochement")
    java.math.BigDecimal sommeDuRapprochement(@Param("rapprochement") Long rapprochementId);

    /** Somme des mouvements rapprochés (relevés du compte finissant au plus tard à {@code fin}) et datés à partir de {@code apres}. */
    @Query("select coalesce(sum(m.montant), 0) from RapprochementMouvement rm join rm.mouvement m where m.compte.id = :compte and rm.rapprochement.releve.dateFin <= :fin and m.dateMouvement >= :apres")
    java.math.BigDecimal sommeRapprochesApres(@Param("compte") Long compteId, @Param("fin") java.time.LocalDate fin, @Param("apres") LocalDateTime apres);

    @Query("select count(rm) from RapprochementMouvement rm where rm.rapprochement.releve.id = :releve")
    long compterDuReleve(@Param("releve") Long releveId);

    /** Mouvements d'un relevé verrouillés en écriture, par identifiant croissant (dernier verrou de la validation). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MouvementCaisse m where m.id in :ids order by m.id")
    List<MouvementCaisse> verrouillerMouvements(@Param("ids") Collection<Long> ids);

    @Modifying
    @Query("delete from RapprochementMouvement rm where rm.rapprochement.id = :rapprochement")
    void supprimerDuRapprochement(@Param("rapprochement") Long rapprochementId);

    @Modifying
    @Query("delete from RapprochementMouvement rm where rm.rapprochement.releve.id = :releve")
    void supprimerDuReleve(@Param("releve") Long releveId);
}
