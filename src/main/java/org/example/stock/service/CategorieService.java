package org.example.stock.service;

import org.example.stock.model.Categorie;
import org.example.stock.repository.CategorieRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CategorieService {

    @Autowired
    private CategorieRepository categorieRepository;

    public List<Categorie> listerToutes() {
        return categorieRepository.findAll();
    }

    public void enregistrer(Categorie categorie) {
        categorieRepository.save(categorie);
    }

    public Categorie trouverParId(Long id) {
        return categorieRepository.findById(id).orElse(null);
    }

    public boolean existeDeja(String nom) {
        return categorieRepository.findByNom(nom).isPresent();
    }

    public void supprimer(Long id) {
        // La règle métier dit : suppression bloquée si utilisée.
        // JPA s'en chargera via une exception si des produits y sont liés.
        categorieRepository.deleteById(id);
    }
}