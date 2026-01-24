package org.example.stock.service;

import org.example.stock.model.Produit;
import org.example.stock.repository.ProduitRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProduitService {
    @Autowired private ProduitRepository produitRepository;

    public ProduitService(ProduitRepository produitRepository) {}

    public List<Produit> listerTous() {
        return produitRepository.findAll();
    }

    public Produit trouverParId(Long id) {
        return produitRepository.findById(id).get();
    }

    public Produit ajouterProduit(Produit produit) {
        return produitRepository.save(produit);
    }

    public void supprimerProduit(Long id) {
        produitRepository.deleteById(id);
    }
}
