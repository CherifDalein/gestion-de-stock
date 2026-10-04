package org.example.stock.service;

import org.example.stock.model.Produit;
import org.example.stock.repository.ProduitRepository;
import org.example.stock.repository.CategorieRepository;
import org.example.stock.repository.FournisseurRepository;
import org.example.stock.repository.StockLockRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProduitService {
    @Autowired private ProduitRepository produitRepository;
    @Autowired private CategorieRepository categorieRepository;
    @Autowired private FournisseurRepository fournisseurRepository;
    @Autowired private StockLockRepository stockLockRepository;

    public List<Produit> listerTous() {
        return produitRepository.findAll();
    }

    public Produit trouverParId(Long id) {
        return produitRepository.findById(id).get();
    }

    @Transactional
    public Produit ajouterProduit(Produit produit) {
        if (produit.getId() != null) throw new IllegalArgumentException("Une création ne peut pas contenir d'identifiant");
        if (produit.getVersion() != null) throw new IllegalArgumentException("Une création ne peut pas contenir de version");
        chargerRelations(produit);
        return produitRepository.save(produit);
    }

    @Transactional
    public Produit modifierProduit(Long id, Produit modifications) {
        if (modifications.getVersion() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Version du produit obligatoire");
        }
        if (!produitRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Produit introuvable");
        }
        Produit produit = stockLockRepository.verrouillerProduits(List.of(id)).get(id);
        if (!modifications.getVersion().equals(produit.getVersion())) throw new ProduitModifieException();
        chargerRelations(modifications);
        produit.setNom(modifications.getNom());
        produit.setReference(modifications.getReference());
        produit.setPrixAchat(modifications.getPrixAchat());
        produit.setPrixVente(modifications.getPrixVente());
        produit.setQuantite(modifications.getQuantite());
        produit.setCategorie(modifications.getCategorie());
        produit.setFournisseur(modifications.getFournisseur());
        return produitRepository.save(produit);
    }

    private void chargerRelations(Produit produit) {
        if (produit.getCategorie() == null || produit.getCategorie().getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Catégorie obligatoire");
        }
        produit.setCategorie(categorieRepository.findById(produit.getCategorie().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Catégorie introuvable")));
        if (produit.getFournisseur() != null) {
            if (produit.getFournisseur().getId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fournisseur invalide");
            }
            produit.setFournisseur(fournisseurRepository.findById(produit.getFournisseur().getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fournisseur introuvable")));
        }
    }

    public void supprimerProduit(Long id) {
        produitRepository.deleteById(id);
    }
}
