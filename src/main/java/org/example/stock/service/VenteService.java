package org.example.stock.service;

import org.example.stock.model.DetailVente;
import org.example.stock.model.Produit;
import org.example.stock.model.Utilisateur;
import org.example.stock.model.Vente;
import org.example.stock.repository.StockLockRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.example.stock.repository.VenteRepository;
import org.example.stock.repository.ClientRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Map;

@Service
public class VenteService {

    @Autowired private VenteRepository venteRepository;
    @Autowired private StockLockRepository stockLockRepository;
    @Autowired private UtilisateurRepository utilisateurRepository;
    @Autowired private CaisseService caisseService;
    @Autowired private ClientRepository clientRepository;

    @Transactional
    public Vente effectuerVente(Vente vente) {
        if (vente.getId() != null) throw new IllegalArgumentException("Une création ne peut pas contenir d'identifiant");
        if (vente.getLignes() == null || vente.getLignes().isEmpty()) {
            throw new RuntimeException("Impossible d'enregistrer une vente vide.");
        }
        if (vente.getClient() != null) {
            if (vente.getClient().getId() == null) throw new IllegalArgumentException("Client invalide");
            vente.setClient(clientRepository.findById(vente.getClient().getId())
                    .orElseThrow(() -> new IllegalArgumentException("Client introuvable")));
        }

        for (DetailVente detail : vente.getLignes()) {
            if (detail == null || detail.getId() != null) {
                throw new IllegalArgumentException("Une ligne de vente doit être nouvelle et sans identifiant");
            }
            if (detail.getProduit() == null || detail.getProduit().getId() == null) {
                throw new RuntimeException("Chaque ligne de vente doit contenir un produit.");
            }
            if (detail.getQuantite() == null || detail.getQuantite() <= 0) {
                throw new RuntimeException("La quantite vendue doit etre superieure a 0.");
            }
        }

        Map<Long, Produit> produits = stockLockRepository.verrouillerProduits(vente.getLignes().stream()
                .map(ligne -> ligne.getProduit().getId()).toList());
        double montantTotalCalcule = 0.0;

        for (DetailVente detail : vente.getLignes()) {
            Produit produit = produits.get(detail.getProduit().getId());

            if (produit.getQuantite() < detail.getQuantite()) {
                throw new RuntimeException("Stock insuffisant pour " + produit.getNom());
            }

            produit.setQuantite(produit.getQuantite() - detail.getQuantite());

            detail.setPrixUnitaire(produit.getPrixVente());
            detail.setVente(vente);
            detail.setProduit(produit);
            montantTotalCalcule += produit.getPrixVente() * detail.getQuantite();
        }

        vente.setMontantTotal(montantTotalCalcule);
        vente.setMontantVerse(normaliserMontantVerse(vente.getMontantVerse(), montantTotalCalcule));
        vente.setDateVente(LocalDateTime.now());
        Vente venteEnregistree = venteRepository.save(vente);

        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Utilisateur actuel = utilisateurRepository.findByEmail(email).orElse(null);

        String motif = "Vente #" + venteEnregistree.getId() + " - Client: " +
                (venteEnregistree.getClient() != null ? venteEnregistree.getClient().getNom() : "Passant");

        caisseService.enregistrerEntree(venteEnregistree.getMontantVerse(), motif, "VENTE", actuel);

        return venteEnregistree;
    }

    public List<Vente> listerToutes() {
        return venteRepository.findAll();
    }

    private Double normaliserMontantVerse(Double montantVerse, double montantTotal) {
        double montant = Objects.requireNonNullElse(montantVerse, montantTotal);

        if (montant < 0) {
            throw new RuntimeException("Le montant verse ne peut pas etre negatif.");
        }
        if (montant > montantTotal) {
            throw new RuntimeException("Le montant verse ne peut pas depasser le total de la vente.");
        }

        return montant;
    }
}
