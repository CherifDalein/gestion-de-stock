package org.example.stock.repository;

import org.example.stock.model.InscriptionBoutique;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InscriptionBoutiqueRepository extends JpaRepository<InscriptionBoutique, String> {
    Optional<InscriptionBoutique> findByEmpreinteJeton(String empreinteJeton);
}
