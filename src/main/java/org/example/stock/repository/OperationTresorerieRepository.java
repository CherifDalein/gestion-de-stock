package org.example.stock.repository;

import org.example.stock.enums.TypeOperationTresorerie;
import org.example.stock.model.OperationTresorerie;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OperationTresorerieRepository extends JpaRepository<OperationTresorerie, Long> {
    Optional<OperationTresorerie> findByJeton(String jeton);

    Optional<OperationTresorerie> findByIdAndBoutiqueId(Long id, Long boutiqueId);

    /** Opérations d'un partenaire financier, de la plus récente à la plus ancienne. */
    @Query("select o from OperationTresorerie o join fetch o.compteSource where o.boutique.id = :boutique and o.partenaireFinancier.id = :partenaire "
            + "order by o.dateOperation desc, o.id desc")
    List<OperationTresorerie> duPartenaire(@Param("boutique") Long boutiqueId, @Param("partenaire") Long partenaireId);

    /** Opérations de financement d'un emprunt (déblocage, frais, remboursements), de la plus récente à la plus ancienne. */
    @Query("select o from OperationTresorerie o join fetch o.compteSource where o.boutique.id = :boutique and o.emprunt.id = :emprunt "
            + "order by o.dateOperation desc, o.id desc")
    List<OperationTresorerie> deLEmprunt(@Param("boutique") Long boutiqueId, @Param("emprunt") Long empruntId);

    /** Opérations d'une nature donnée (apports, produits et charges...), de la plus récente à la plus ancienne. */
    @Query("select o from OperationTresorerie o join fetch o.compteSource left join fetch o.partenaireFinancier where o.boutique.id = :boutique and o.type in :types "
            + "order by o.dateOperation desc, o.id desc")
    List<OperationTresorerie> deTypes(@Param("boutique") Long boutiqueId, @Param("types") Collection<TypeOperationTresorerie> types);

    /** Identifiants, parmi ceux donnés, des opérations dont un mouvement d'annulation existe : elles sont annulées. */
    @Query("select distinct m.operationTresorerie.id from MouvementCaisse m where m.source = 'ANNULATION' and m.operationTresorerie.id in :ids")
    List<Long> annulees(@Param("ids") Collection<Long> ids);

    /** Somme par partenaire et par nature des apports et retraits non annulés (capital et compte courant). */
    @Query("select o.partenaireFinancier.id, o.type, sum(o.montant) from OperationTresorerie o where o.boutique.id = :boutique and o.type in :types "
            + "and o.partenaireFinancier is not null and not exists (select 1 from MouvementCaisse m where m.operationTresorerie = o and m.source = 'ANNULATION') "
            + "group by o.partenaireFinancier.id, o.type")
    List<Object[]> sommesParPartenaire(@Param("boutique") Long boutiqueId, @Param("types") Collection<TypeOperationTresorerie> types);
}
