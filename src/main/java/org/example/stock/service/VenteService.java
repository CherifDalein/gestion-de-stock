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
        double montantTotalVente = 0.0;

        for (DetailVente detail : vente.getLignes()) {
            Produit produit = produitRepository.findById(detail.getProduit().getId())
                    .orElseThrow(() -> new RuntimeException("Produit non trouvé"));

            if (produit.getQuantite() < detail.getQuantite()) {
                throw new RuntimeException("Stock insuffisant pour le produit : " + produit.getNom()
                        + " (Disponible: " + produit.getQuantite() + ")");
            }

            produit.setQuantite(produit.getQuantite() - detail.getQuantite());
            produitRepository.save(produit);

            detail.setPrixUnitaire(produit.getPrixVente());
            detail.setVente(vente);

            montantTotalVente += detail.getPrixUnitaire() * detail.getQuantite();
        }

        vente.setMontantTotal(montantTotalVente);
        vente.setDateVente(LocalDateTime.now());

        return venteRepository.save(vente);
    }

    public List<Vente> listerToutes() {
        return venteRepository.findAll();
    }
}