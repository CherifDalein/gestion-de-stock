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

    @Autowired
    private org.example.stock.repository.StockLockRepository verrous;

    @Autowired
    private CorbeilleService corbeille;

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

    /**
     * Place la catégorie dans la corbeille (lot 6). Comportement d'avant la corbeille : une catégorie qui contient encore des produits
     * est refusée ({@link IllegalArgumentException}) ; les produits ne sont jamais détachés ni déplacés.
     */
    @Transactional
    public CorbeilleService.ElementSupprime supprimer(Long id) {
        // La boutique d'abord : le contrôle des produits ne doit pas croiser la création d'un produit de cette catégorie.
        verrous.verrouillerBoutiqueCourante();
        Categorie categorie = trouverParId(id);
        corbeille.exigerSansLien(TypeCorbeille.CATEGORIE, id);
        var supprime = corbeille.placer(TypeCorbeille.CATEGORIE, categorie);
        categorieRepository.saveAndFlush(categorie);
        return supprime;
    }
}
