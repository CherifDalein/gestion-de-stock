package org.example.stock.repository;

import org.example.stock.model.ReinitialisationMotDePasse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReinitialisationMotDePasseRepository extends JpaRepository<ReinitialisationMotDePasse, Long> {
    Optional<ReinitialisationMotDePasse> findByEmpreinteJeton(String empreinteJeton);
}
