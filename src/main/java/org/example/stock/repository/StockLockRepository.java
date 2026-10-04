package org.example.stock.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.example.stock.model.Achat;
import org.example.stock.model.Produit;
import org.springframework.stereotype.Repository;
import org.springframework.dao.EmptyResultDataAccessException;
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

    public StockLockRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Map<Long, Produit> verrouillerProduits(Collection<Long> ids) {
        Map<Long, Produit> produits = new LinkedHashMap<>();
        // Même ordre pour tous les paniers, y compris les anciennes et nouvelles lignes d'un achat.
        for (Long id : new TreeSet<>(ids)) {
            Produit produit = entityManager.find(Produit.class, id);
            if (produit == null) throw new IllegalArgumentException("Produit introuvable");
            // Recharge aussi une entité déjà présente dans le contexte JPA (relations / OpenEntityManagerInView).
            entityManager.refresh(produit, LockModeType.PESSIMISTIC_WRITE);
            produits.put(id, produit);
        }
        return produits;
    }

    public Achat verrouillerAchat(Long id) {
        Achat achat = entityManager.find(Achat.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (achat == null) throw new EmptyResultDataAccessException("Achat introuvable", 1);
        // Verrouiller le document avant de rafraîchir ses lignes (cascade REFRESH), puis ses produits.
        entityManager.refresh(achat, LockModeType.PESSIMISTIC_WRITE);
        return achat;
    }
}
