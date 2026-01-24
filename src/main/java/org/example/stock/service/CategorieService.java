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

    public void ajouterCategorie(Categorie categorie) {
        categorieRepository.save(categorie);
    }

    public Categorie trouverParId(Long id) {
        return categorieRepository.findById(id).orElse(null);
    }

    public boolean existeDeja(String nom) {
        return categorieRepository.findByNom(nom).isPresent();
    }

    public void supprimer(Long id) {
        categorieRepository.deleteById(id);
    }
}