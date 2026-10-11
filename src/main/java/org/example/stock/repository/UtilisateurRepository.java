package org.example.stock.repository;

import org.example.stock.model.Utilisateur;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import java.util.Optional;

public interface UtilisateurRepository extends JpaRepository<Utilisateur, Long> {
    @EntityGraph(attributePaths = "boutique")
    Optional<Utilisateur> findByEmail(String email);
    boolean existsByEmail(String email);
}
