package org.example.stock.repository;

import org.example.stock.model.Client;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClientRepository extends JpaRepository<Client, Long> {
    List<Client> findAllByBoutiqueId(Long boutiqueId);
    Optional<Client> findByIdAndBoutiqueId(Long id, Long boutiqueId);
    boolean existsByIdAndBoutiqueId(Long id, Long boutiqueId);
}
