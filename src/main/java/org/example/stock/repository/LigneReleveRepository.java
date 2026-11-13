package org.example.stock.repository;

import org.example.stock.model.LigneReleve;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LigneReleveRepository extends JpaRepository<LigneReleve, Long> {
    @Query("select l from LigneReleve l where l.releve.id = :releve order by l.dateOperation, l.id")
    List<LigneReleve> duReleve(@Param("releve") Long releveId);

    @Query("select l from LigneReleve l where l.releve.id = :releve and l.rapprochement is null order by l.dateOperation, l.id")
    List<LigneReleve> nonRapprochees(@Param("releve") Long releveId);

    Optional<LigneReleve> findByIdAndReleveId(Long id, Long releveId);

    @Query("select l from LigneReleve l where l.releve.id = :releve and l.id in :ids")
    List<LigneReleve> duReleveParIds(@Param("releve") Long releveId, @Param("ids") Collection<Long> ids);

    long countByReleveId(Long releveId);

    @Query("select coalesce(sum(l.montant), 0) from LigneReleve l where l.releve.id = :releve")
    BigDecimal somme(@Param("releve") Long releveId);

    /** Lignes non rapprochées de tous les relevés du compte finissant au plus tard à {@code fin} (état cumulé). */
    @Query("select coalesce(sum(l.montant), 0) from LigneReleve l where l.releve.compte.id = :compte and l.releve.dateFin <= :fin and l.rapprochement is null")
    BigDecimal sommeNonRapprocheesJusqua(@Param("compte") Long compteId, @Param("fin") LocalDate fin);

    @Query("select l from LigneReleve l join fetch l.releve r where r.compte.id = :compte and r.id <> :exclu and r.dateFin <= :fin and l.rapprochement is null order by l.dateOperation, l.id")
    List<LigneReleve> nonRapprocheesAnterieures(@Param("compte") Long compteId, @Param("exclu") Long exclu, @Param("fin") LocalDate fin);

    /** Empreintes déjà présentes sur le compte, avec leur nombre : [empreinte, nombre]. */
    @Query("select l.empreinte, count(l) from LigneReleve l where l.releve.compte.id = :compte and l.empreinte in :empreintes group by l.empreinte")
    List<Object[]> compterEmpreintes(@Param("compte") Long compteId, @Param("empreintes") Collection<String> empreintes);

    @Modifying
    @Query("update LigneReleve l set l.rapprochement = null where l.releve.id = :releve")
    void detacherDuReleve(@Param("releve") Long releveId);

    @Modifying
    @Query("delete from LigneReleve l where l.releve.id = :releve")
    void supprimerDuReleve(@Param("releve") Long releveId);
}
