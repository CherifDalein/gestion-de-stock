package org.example.stock.repository;

import org.example.stock.model.Fournisseur;
import org.springframework.data.jpa.repository.JpaRepository;

// Repository
public interface FournisseurRepository extends JpaRepository<Fournisseur, Long> {

}

