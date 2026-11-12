package org.example.stock.repository;

import org.example.stock.model.CompteTresorerieAutorise;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CompteTresorerieAutoriseRepository extends JpaRepository<CompteTresorerieAutorise, Long> {
    /** Identifiants des comptes explicitement autorisés à un utilisateur (vide : repli sur les comptes principaux). */
    @Query("select a.compte.id from CompteTresorerieAutorise a where a.utilisateur.id = :utilisateur")
    List<Long> idsComptes(@Param("utilisateur") Long utilisateurId);

    @Query("select a from CompteTresorerieAutorise a where a.utilisateur.boutique.id = :boutique")
    List<CompteTresorerieAutorise> deLaBoutique(@Param("boutique") Long boutiqueId);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from CompteTresorerieAutorise a where a.utilisateur.id = :utilisateur")
    void supprimerDeUtilisateur(@Param("utilisateur") Long utilisateurId);
}
