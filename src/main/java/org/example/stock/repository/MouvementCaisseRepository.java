package org.example.stock.repository;

import org.example.stock.model.MouvementCaisse;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;

@Repository
public interface MouvementCaisseRepository extends JpaRepository<MouvementCaisse, Long>, JpaSpecificationExecutor<MouvementCaisse> {
    boolean existsByBoutiqueId(Long boutiqueId);
    boolean existsByBoutiqueIdAndCompteId(Long boutiqueId, Long compteId);

    /** Vrai s'il existe un mouvement du compte autre que son solde de départ : le solde de départ est alors figé. */
    @Query("SELECT COUNT(m) > 0 FROM MouvementCaisse m WHERE m.compte.id = :compteId AND (m.source IS NULL OR m.source <> 'SOLDE_INITIAL')")
    boolean existeMouvementAutreQueSoldeDepart(@Param("compteId") Long compteId);

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.boutique.id = :boutiqueId AND m.compte.id = :compteId")
    BigDecimal calculerSoldeCompte(@Param("boutiqueId") Long boutiqueId, @Param("compteId") Long compteId);

    /** Solde de chaque compte de la boutique : [identifiant du compte, Σ des montants] ; un compte sans mouvement n'a pas de ligne. */
    @Query("SELECT m.compte.id, COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.boutique.id = :boutiqueId GROUP BY m.compte.id")
    List<Object[]> soldesParCompte(@Param("boutiqueId") Long boutiqueId);

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.dateMouvement >= :date AND m.dateMouvement < :fin AND m.source = 'SOLDE_INITIAL' AND m.boutique.id = :boutiqueId")
    BigDecimal calculerInitialDuJour(@Param("date") LocalDateTime date, @Param("fin") LocalDateTime fin, @Param("boutiqueId") Long boutiqueId);

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.boutique.id = :boutiqueId")
    BigDecimal calculerSoldeTotal(@Param("boutiqueId") Long boutiqueId);

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.dateMouvement >= :date AND (m.source IS NULL OR m.source <> 'SOLDE_INITIAL') AND m.boutique.id = :boutiqueId")
    BigDecimal calculerFluxTotalDepuis(@Param("date") LocalDateTime date, @Param("boutiqueId") Long boutiqueId);

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.dateMouvement < :date AND m.boutique.id = :boutiqueId")
    BigDecimal calculerSoldeAvant(@Param("date") LocalDateTime date, @Param("boutiqueId") Long boutiqueId);

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.dateMouvement >= :date AND m.montant > 0 AND (m.source IS NULL OR m.source NOT IN ('SOLDE_INITIAL', 'TRANSFERT')) AND m.boutique.id = :boutiqueId")
    BigDecimal calculerEntreesDepuis(@Param("date") LocalDateTime date, @Param("boutiqueId") Long boutiqueId);

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.dateMouvement >= :date AND m.montant < 0 AND (m.source IS NULL OR m.source <> 'TRANSFERT') AND m.boutique.id = :boutiqueId")
    BigDecimal calculerSortiesDepuis(@Param("date") LocalDateTime date, @Param("boutiqueId") Long boutiqueId);

    @EntityGraph(attributePaths = "annulationDe")
    List<MouvementCaisse> findByBoutiqueIdOrderByDateMouvementDesc(Long boutiqueId);
    List<MouvementCaisse> findBySourceAndBoutiqueId(String source, Long boutiqueId);
    @EntityGraph(attributePaths = "annulationDe")
    List<MouvementCaisse> findByAchatIdAndBoutiqueIdOrderByDateMouvementDescIdDesc(Long achatId, Long boutiqueId);
    @EntityGraph(attributePaths = "annulationDe")
    List<MouvementCaisse> findByVenteIdAndBoutiqueIdOrderByDateMouvementDescIdDesc(Long venteId, Long boutiqueId);
    List<MouvementCaisse> findByClientIdAndSourceAndBoutiqueIdOrderByDateMouvementDescIdDesc(Long clientId, String source, Long boutiqueId);
    List<MouvementCaisse> findByFournisseurIdAndSourceAndBoutiqueIdOrderByDateMouvementDescIdDesc(Long fournisseurId, String source, Long boutiqueId);
    @EntityGraph(attributePaths = "annulationDe")
    List<MouvementCaisse> findByClientIdAndSourceInAndBoutiqueIdOrderByDateMouvementDescIdDesc(Long clientId, java.util.Collection<String> sources, Long boutiqueId);
    @EntityGraph(attributePaths = "annulationDe")
    List<MouvementCaisse> findByFournisseurIdAndSourceInAndBoutiqueIdOrderByDateMouvementDescIdDesc(Long fournisseurId, java.util.Collection<String> sources, Long boutiqueId);
    @EntityGraph(attributePaths = "annulationDe")
    List<MouvementCaisse> findByDateMouvementGreaterThanEqualAndBoutiqueIdOrderByDateMouvementDesc(LocalDateTime date, Long boutiqueId);

    /** Annulations (avec leur auteur) des mouvements donnés, dans la boutique : au plus une par mouvement. */
    @Query("SELECT a FROM MouvementCaisse a LEFT JOIN FETCH a.utilisateur WHERE a.boutique.id = :boutiqueId AND a.annulationDe.id IN :ids")
    List<MouvementCaisse> findAnnulationsDe(@Param("boutiqueId") Long boutiqueId, @Param("ids") java.util.Collection<Long> ids);

    /** Toutes les annulations de la boutique (journal de caisse) : une requête, quel que soit le nombre de mouvements. */
    @Query("SELECT a FROM MouvementCaisse a LEFT JOIN FETCH a.utilisateur WHERE a.boutique.id = :boutiqueId AND a.annulationDe IS NOT NULL")
    List<MouvementCaisse> findAnnulationsDeLaBoutique(@Param("boutiqueId") Long boutiqueId);

    boolean existsByAnnulationDeId(Long id);

    /** Mouvements d'une opération de trésorerie (transfert, dépense ou financement), dans l'ordre de leur création. */
    List<MouvementCaisse> findByOperationTresorerieIdOrderByIdAsc(Long operationId);

    java.util.Optional<MouvementCaisse> findByIdAndBoutiqueId(Long id, Long boutiqueId);

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m")
    BigDecimal calculerSoldeTotal();

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.dateMouvement >= :date")
    BigDecimal calculerFluxTotalDepuis(@Param("date") LocalDateTime date);

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.dateMouvement < :date")
    BigDecimal calculerSoldeAvant(@Param("date") LocalDateTime date);

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.dateMouvement >= :date AND m.montant > 0")
    BigDecimal calculerEntreesDepuis(@Param("date") LocalDateTime date);

    @Query("SELECT COALESCE(SUM(m.montant), 0) FROM MouvementCaisse m WHERE m.dateMouvement >= :date AND m.montant < 0")
    BigDecimal calculerSortiesDepuis(@Param("date") LocalDateTime date);

    List<MouvementCaisse> findAllByOrderByDateMouvementDesc();
    List<MouvementCaisse> findBySource(String source);
    List<MouvementCaisse> findByAchatIdOrderByDateMouvementDescIdDesc(Long achatId);
    List<MouvementCaisse> findByVenteIdOrderByDateMouvementDescIdDesc(Long venteId);
    List<MouvementCaisse> findByDateMouvementGreaterThanEqualOrderByDateMouvementDesc(LocalDateTime date);
}
