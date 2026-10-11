package org.example.stock.service;

import org.example.stock.model.Categorie;
import org.example.stock.repository.CategorieRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class CategorieService {

    @Autowired
    private CategorieRepository categorieRepository;

    @Autowired
    private BoutiqueService boutiqueService;

    public List<Categorie> listerToutes() {
        return categorieRepository.findAllByBoutiqueId(boutiqueService.idBoutiqueCourante());
    }

    @Transactional
    public void ajouterCategorie(Categorie categorie) {
        if (categorie.getId() != null) throw new IllegalArgumentException("Une création ne peut pas contenir d'identifiant");
        categorie.setBoutique(boutiqueService.boutiqueCourante());
        categorieRepository.save(categorie);
    }

    @Transactional
    public void modifierCategorie(Long id, Categorie modifications) {
        Categorie categorie = trouverParId(id);
        categorie.setNom(modifications.getNom());
        categorieRepository.save(categorie);
    }

    public Categorie trouverParId(Long id) {
        return categorieRepository.findByIdAndBoutiqueId(id, boutiqueService.idBoutiqueCourante())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Catégorie introuvable"));
    }

    public boolean existeDeja(String nom) {
        return categorieRepository.findByNomAndBoutiqueId(nom, boutiqueService.idBoutiqueCourante()).isPresent();
    }

    @Transactional
    public void supprimer(Long id) {
        categorieRepository.delete(trouverParId(id));
    }
}
