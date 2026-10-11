package org.example.stock.repository;

import org.example.stock.model.Utilisateur;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface UtilisateurRepository extends JpaRepository<Utilisateur, Long> {
    @EntityGraph(attributePaths = "boutique")
    Optional<Utilisateur> findByEmail(String email);
    boolean existsByEmail(String email);

    List<Utilisateur> findAllByBoutiqueIdOrderByNomAscIdAsc(Long boutiqueId);

    @Query("select u.boutique.id as boutiqueId, count(u.id) as nombre from Utilisateur u group by u.boutique.id")
    List<NombreParBoutique> compterParBoutique();

    interface NombreParBoutique {
        Long getBoutiqueId();
        Long getNombre();
    }
}
