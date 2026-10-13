package org.example.stock.service;

import org.example.stock.model.Produit;
import org.example.stock.model.Montants;
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
    @Autowired private BoutiqueService boutiqueService;
    @Autowired private org.example.stock.repository.MouvementStockRepository mouvementsStock;

    public List<Produit> listerTous() {
        return produitRepository.findAllByBoutiqueId(boutiqueService.idBoutiqueCourante());
    }

    public Produit trouverParId(Long id) {
        return produitRepository.findByIdAndBoutiqueId(id, boutiqueService.idBoutiqueCourante())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Produit introuvable"));
    }

    @Transactional
    public Produit ajouterProduit(Produit produit) {
        if (produit.getId() != null) throw new IllegalArgumentException("Une création ne peut pas contenir d'identifiant");
        if (produit.getVersion() != null) throw new IllegalArgumentException("Une création ne peut pas contenir de version");
        produit.setQuantite(0L);
        produit.setPrixAchat(Montants.ZERO);
        produit.setPrixVente(Montants.ZERO);
        produit.setPrixDefinis(false);
        produit.setBoutique(boutiqueService.boutiqueCourante());
        chargerRelations(produit);
        return produitRepository.save(produit);
    }

    @Transactional
    public Produit modifierProduit(Long id, Produit modifications) {
        if (modifications.getVersion() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Version du produit obligatoire");
        }
        if (!produitRepository.existsByIdAndBoutiqueId(id, boutiqueService.idBoutiqueCourante())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Produit introuvable");
        }
        Produit produit = stockLockRepository.verrouillerProduits(List.of(id)).get(id);
        if (!modifications.getVersion().equals(produit.getVersion())) throw new ProduitModifieException();
        if (produit.isPrixDefinis() && modifications.getPrixVente() == null) {
            throw new IllegalArgumentException("Le prix de vente est obligatoire pour modifier une fiche dont les tarifs sont déjà définis.");
        }
        if (!produit.isPrixDefinis() && modifications.getPrixVente() != null && modifications.getPrixVente().signum() != 0) {
            throw new IllegalArgumentException("Définissez les prix lors d'un achat ou de l'enregistrement du stock initial.");
        }
        if (!produit.isPrixDefinis()) modifications.setPrixVente(Montants.ZERO);
        chargerRelations(modifications);
        produit.setNom(modifications.getNom());
        produit.setReference(modifications.getReference());
        if (produit.isPrixDefinis()) produit.setPrixVente(modifications.getPrixVente());
        produit.setCategorie(modifications.getCategorie());
        produit.setFournisseur(modifications.getFournisseur());
        return produitRepository.save(produit);
    }

    private void chargerRelations(Produit produit) {
        produit.setPrixAchat(Montants.positifOuNul(produit.getPrixAchat(), "Le prix d'achat"));
        produit.setPrixVente(Montants.positifOuNul(produit.getPrixVente(), "Le prix de vente"));
        if (produit.getCategorie() == null || produit.getCategorie().getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Catégorie obligatoire");
        }
        Long boutiqueId = boutiqueService.idBoutiqueCourante();
        produit.setCategorie(categorieRepository.findByIdAndBoutiqueId(produit.getCategorie().getId(), boutiqueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Catégorie introuvable")));
        if (produit.getFournisseur() != null) {
            if (produit.getFournisseur().getId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fournisseur invalide");
            }
            produit.setFournisseur(fournisseurRepository.findByIdAndBoutiqueId(produit.getFournisseur().getId(), boutiqueId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fournisseur introuvable")));
        }
    }

    @Transactional
    public void supprimerProduit(Long id) {
        trouverParId(id);
        if (mouvementsStock.existsByProduitId(id)) throw new IllegalArgumentException("Ce produit possède un historique d’inventaire et doit être conservé.");
        produitRepository.delete(trouverParId(id));
    }
}
