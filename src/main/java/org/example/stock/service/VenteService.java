package org.example.stock.service;

import org.example.stock.model.DetailVente;
import org.example.stock.model.Produit;
import org.example.stock.model.Vente;
import org.example.stock.repository.ProduitRepository;
import org.example.stock.repository.VenteRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class VenteService {

    @Autowired private VenteRepository venteRepository;
    @Autowired
    private ProduitRepository produitRepository;

    @Transactional
    public Vente effectuerVente(Vente vente) {
        if (vente.getLignes() == null || vente.getLignes().isEmpty()) {
            throw new RuntimeException("Impossible d'enregistrer une vente vide.");
        }

        // Le montant total est déjà envoyé par l'input hidden du formulaire
        // On s'assure juste que montantVerse n'est pas null
        if (vente.getMontantVerse() == null) {
            vente.setMontantVerse(vente.getMontantTotal());
        }

        for (DetailVente detail : vente.getLignes()) {
            Produit produit = produitRepository.findById(detail.getProduit().getId())
                    .orElseThrow(() -> new RuntimeException("Produit non trouvé"));

            // Vérification de sécurité côté serveur (même si JS le fait déjà)
            if (produit.getQuantite() < detail.getQuantite()) {
                throw new RuntimeException("Stock insuffisant pour " + produit.getNom());
            }

            // Mise à jour du stock
            produit.setQuantite(produit.getQuantite() - detail.getQuantite());
            produitRepository.save(produit);

            // On fixe les données de la ligne
            detail.setPrixUnitaire(produit.getPrixVente());
            detail.setVente(vente);
        }

        vente.setDateVente(LocalDateTime.now());

        // On enregistre la vente
        Vente venteEnregistree = venteRepository.save(vente);

        // TODO: Ici on appellera caisseService.enregistrerEntree(vente.getMontantVerse())

        return venteEnregistree;
    }

    public List<Vente> listerToutes() {
        return venteRepository.findAll();
    }
}