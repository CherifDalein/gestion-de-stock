package org.example.stock.repository;

import org.example.stock.model.Achat;
import org.example.stock.model.Vente;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Factures non soldées d'UN partenaire, pour le détail des justificatifs de solde (lot 4, tâche D). Même condition que
 * {@code EtatsRepository.ventesNonSoldees} et que le reste à payer de {@code SoldePartenaireRepository} : reste = total − retours −
 * versé − avance utilisée, strictement positif. Toujours filtré par la boutique du compte connecté.
 */
public interface JustificatifSoldeRepository extends Repository<Vente, Long> {

    @Query("SELECT v FROM Vente v WHERE v.boutique.id = :boutiqueId AND v.client.id = :clientId AND "
            + "COALESCE(v.montantTotal, 0) - COALESCE(v.montantRetours, 0) - COALESCE(v.montantVerse, 0) - COALESCE(v.avanceUtilisee, 0) > 0 "
            + "ORDER BY v.id")
    List<Vente> ventesNonSoldeesDuClient(@Param("boutiqueId") Long boutiqueId, @Param("clientId") Long clientId, Pageable limite);

    @Query("SELECT a FROM Achat a WHERE a.boutique.id = :boutiqueId AND a.fournisseur.id = :fournisseurId AND "
            + "COALESCE(a.montantTotal, 0) - COALESCE(a.montantRetours, 0) - COALESCE(a.montantVerse, 0) - COALESCE(a.avanceUtilisee, 0) > 0 "
            + "ORDER BY a.id")
    List<Achat> achatsNonSoldesDuFournisseur(@Param("boutiqueId") Long boutiqueId, @Param("fournisseurId") Long fournisseurId, Pageable limite);
}
