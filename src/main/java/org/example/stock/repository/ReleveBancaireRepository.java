package org.example.stock.repository;

import org.example.stock.enums.StatutReleve;
import org.example.stock.model.ReleveBancaire;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ReleveBancaireRepository extends JpaRepository<ReleveBancaire, Long> {
    Optional<ReleveBancaire> findByIdAndBoutiqueId(Long id, Long boutiqueId);

    Optional<ReleveBancaire> findByBoutiqueIdAndJeton(Long boutiqueId, String jeton);

    boolean existsByBoutiqueIdAndNumero(Long boutiqueId, String numero);

    @Query("select r from ReleveBancaire r join fetch r.compte where r.boutique.id = :boutique order by r.dateFin desc, r.id desc")
    List<ReleveBancaire> lister(@Param("boutique") Long boutiqueId);

    @Query("select r from ReleveBancaire r where r.compte.id = :compte order by r.dateDebut, r.id")
    List<ReleveBancaire> duCompte(@Param("compte") Long compteId);

    /** Un autre relevé du compte dont la période recoupe [debut, fin] (bornes incluses). */
    @Query("select count(r) > 0 from ReleveBancaire r where r.compte.id = :compte and r.id <> :exclu and r.dateDebut <= :fin and r.dateFin >= :debut")
    boolean chevauche(@Param("compte") Long compteId, @Param("exclu") Long exclu, @Param("debut") LocalDate debut, @Param("fin") LocalDate fin);

    /** Relevés du compte dont la période finit avant {@code debut}, le plus récent d'abord. */
    @Query("select r from ReleveBancaire r where r.compte.id = :compte and r.id <> :exclu and r.dateFin < :debut order by r.dateFin desc, r.id desc")
    List<ReleveBancaire> precedents(@Param("compte") Long compteId, @Param("exclu") Long exclu, @Param("debut") LocalDate debut);

    /** Relevés du compte dont la période commence après {@code fin}, le plus ancien d'abord. */
    @Query("select r from ReleveBancaire r where r.compte.id = :compte and r.id <> :exclu and r.dateDebut > :fin order by r.dateDebut, r.id")
    List<ReleveBancaire> suivants(@Param("compte") Long compteId, @Param("exclu") Long exclu, @Param("fin") LocalDate fin);

    /** Dernier relevé validé du compte (période la plus récente). */
    @Query("select r from ReleveBancaire r where r.compte.id = :compte and r.statut = :statut order by r.dateFin desc, r.id desc")
    List<ReleveBancaire> parStatut(@Param("compte") Long compteId, @Param("statut") StatutReleve statut);

    @Query("select min(r.dateDebut) from ReleveBancaire r where r.compte.id = :compte")
    LocalDate premierDebut(@Param("compte") Long compteId);

    /** Dernière date de clôture validée de chaque compte de la boutique : [identifiant du compte, date de fin]. */
    @Query("select r.compte.id, max(r.dateFin) from ReleveBancaire r where r.boutique.id = :boutique and r.statut = org.example.stock.enums.StatutReleve.VALIDE group by r.compte.id")
    List<Object[]> dernieresValidations(@Param("boutique") Long boutiqueId);

    long countByCompteId(Long compteId);
}
