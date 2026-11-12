package org.example.stock.repository;

import org.example.stock.model.EcheanceEmprunt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EcheanceEmpruntRepository extends JpaRepository<EcheanceEmprunt, Long> {
    @Query("select e from EcheanceEmprunt e where e.emprunt.id = :emprunt order by e.rang")
    List<EcheanceEmprunt> deLEmprunt(@Param("emprunt") Long empruntId);

    @Query("select e from EcheanceEmprunt e join fetch e.emprunt m where m.boutique.id = :boutique order by m.id, e.rang")
    List<EcheanceEmprunt> deLaBoutique(@Param("boutique") Long boutiqueId);
}
