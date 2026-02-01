package org.example.stock.repository;

import org.example.stock.model.Achat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AchatRepository extends JpaRepository<Achat,Long> {
}
