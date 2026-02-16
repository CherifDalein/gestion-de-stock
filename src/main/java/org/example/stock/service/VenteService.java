package org.example.stock.service;

import org.example.stock.model.DetailVente;
import org.example.stock.model.Produit;
import org.example.stock.model.Vente;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.ProduitRepository;
import org.example.stock.repository.VenteRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class VenteService {

    @Autowired private VenteRepository venteRepository;
    @Autowired private ProduitRepository produitRepository;
    @Autowired private UtilisateurRepository utilisateurRepository;
    @Autowired private CaisseService caisseService;

    @Transactional
    public Vente effectuerVente(Vente vente) {
        if (vente.getLignes() == null || vente.getLignes().isEmpty()) {
            throw new RuntimeException("Impossible d'enregistrer une vente vide.");
        }

        if (vente.getMontantVerse() == null) {
            vente.setMontantVerse(vente.getMontantTotal());
        }

        // 1. Mise à jour des stocks et préparation des lignes
        for (DetailVente detail : vente.getLignes()) {
            Produit produit = produitRepository.findById(detail.getProduit().getId())
                    .orElseThrow(() -> new RuntimeException("Produit non trouvé"));

            if (produit.getQuantite() < detail.getQuantite()) {
                throw new RuntimeException("Stock insuffisant pour " + produit.getNom());
            }

            produit.setQuantite(produit.getQuantite() - detail.getQuantite());
            produitRepository.save(produit);

            detail.setPrixUnitaire(produit.getPrixVente());
            detail.setVente(vente);
        }

        vente.setDateVente(LocalDateTime.now());
        Vente venteEnregistree = venteRepository.save(vente);

        // 2. Récupération de l'utilisateur connecté
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Utilisateur actuel = utilisateurRepository.findByEmail(email).orElse(null);

        // 3. Enregistrement en Caisse
        String motif = "Vente #" + venteEnregistree.getId() + " - Client: " +
                (venteEnregistree.getClient() != null ? venteEnregistree.getClient().getNom() : "Passant");

        caisseService.enregistrerEntree(venteEnregistree.getMontantVerse(), motif, "VENTE", actuel);

        return venteEnregistree;
    }

    public List<Vente> listerToutes() {
        return venteRepository.findAll();
    }
}