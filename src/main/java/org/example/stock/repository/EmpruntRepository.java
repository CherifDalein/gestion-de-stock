package org.example.stock.repository;

import org.example.stock.model.Emprunt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmpruntRepository extends JpaRepository<Emprunt, Long> {
    Optional<Emprunt> findByIdAndBoutiqueId(Long id, Long boutiqueId);

    Optional<Emprunt> findByJeton(String jeton);

    @Query("select e from Emprunt e join fetch e.partenaire where e.boutique.id = :boutique order by e.dateEmprunt desc, e.id desc")
    List<Emprunt> lister(@Param("boutique") Long boutiqueId);

    @Query("select e from Emprunt e join fetch e.partenaire where e.boutique.id = :boutique and e.partenaire.id = :partenaire order by e.dateEmprunt desc, e.id desc")
    List<Emprunt> listerDuPartenaire(@Param("boutique") Long boutiqueId, @Param("partenaire") Long partenaireId);
}
