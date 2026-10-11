package org.example.stock.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.example.stock.model.Achat;
import org.example.stock.model.Vente;
import org.example.stock.model.Produit;
import org.example.stock.service.BoutiqueService;
import org.springframework.stereotype.Repository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/** Les verrous restent détenus jusqu'à la fin de la transaction métier. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class StockLockRepository {
    private final EntityManager entityManager;
    private final BoutiqueService boutiques;

    public StockLockRepository(EntityManager entityManager, BoutiqueService boutiques) {
        this.entityManager = entityManager;
        this.boutiques = boutiques;
    }

    public Map<Long, Produit> verrouillerProduits(Collection<Long> ids) {
        Map<Long, Produit> produits = new LinkedHashMap<>();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        // Même ordre pour tous les paniers, y compris les anciennes et nouvelles lignes d'un achat.
        for (Long id : new TreeSet<>(ids)) {
            verifierIdentifiant("Produit", id, boutiqueId);
            Produit produit = entityManager.find(Produit.class, id);
            if (produit == null) throw introuvable("Produit");
            // Recharge aussi une entité déjà présente dans le contexte JPA (relations / OpenEntityManagerInView).
            entityManager.refresh(produit, LockModeType.PESSIMISTIC_WRITE);
            boutiques.exigerAppartenance(produit.getBoutique());
            produits.put(id, produit);
        }
        return produits;
    }

    public Achat verrouillerAchat(Long id) {
        verifierIdentifiant("Achat", id, boutiques.idBoutiqueCourante());
        Achat achat = entityManager.find(Achat.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (achat == null) throw introuvable("Achat");
        // Verrouiller le document avant de rafraîchir ses lignes (cascade REFRESH), puis ses produits.
        entityManager.refresh(achat, LockModeType.PESSIMISTIC_WRITE);
        boutiques.exigerAppartenance(achat.getBoutique());
        return achat;
    }

    public Vente verrouillerVente(Long id) {
        verifierIdentifiant("Vente", id, boutiques.idBoutiqueCourante());
        Vente vente = entityManager.find(Vente.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (vente == null) throw introuvable("Vente");
        // Relire le cumul sous verrou, même si la vente est déjà dans le contexte JPA.
        entityManager.refresh(vente, LockModeType.PESSIMISTIC_WRITE);
        boutiques.exigerAppartenance(vente.getBoutique());
        return vente;
    }

    private void verifierIdentifiant(String entite, Long id, Long boutiqueId) {
        // Une projection évite de verrouiller une @Version périmée du contexte JPA.
        // Le document est ensuite relu sous verrou, puis son appartenance est revérifiée.
        if (entityManager.createQuery("select e.id from " + entite
                        + " e where e.id = :id and e.boutique.id = :boutiqueId", Long.class)
                .setParameter("id", id).setParameter("boutiqueId", boutiqueId)
                .getResultList().isEmpty()) throw introuvable(entite);
    }

    private static ResponseStatusException introuvable(String document) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, document + " introuvable");
    }
}
