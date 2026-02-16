package org.example.stock.service;

import org.example.stock.model.Achat;
import org.example.stock.model.DetailAchat;
import org.example.stock.model.Produit;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.AchatRepository;
import org.example.stock.repository.ProduitRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AchatService {

    @Autowired private AchatRepository achatRepository;
    @Autowired private ProduitRepository produitRepository;
    @Autowired private UtilisateurRepository utilisateurRepository;
    @Autowired private CaisseService caisseService;

    @Transactional
    public Achat enregistrerAchat(Achat achat) {
        if (achat.getDateAchat() == null) {
            achat.setDateAchat(LocalDateTime.now());
        }

        if (achat.getMontantVerse() == null) {
            achat.setMontantVerse(achat.getMontantTotal());
        }

        // Mise à jour stock
        for (DetailAchat ligne : achat.getLignes()) {
            Produit produitBdd = produitRepository.findById(ligne.getProduit().getId())
                    .orElseThrow(() -> new RuntimeException("Produit non trouvé"));

            produitBdd.setQuantite(produitBdd.getQuantite() + ligne.getQuantite());
            produitBdd.setPrixAchat(ligne.getPrixAchatUnitaire());

            ligne.setAchat(achat);
            produitRepository.save(produitBdd);
        }

        Achat achatEnregistre = achatRepository.save(achat);

        // Enregistrement Sortie Caisse
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Utilisateur actuel = utilisateurRepository.findByEmail(email).orElse(null);

        String motif = "Achat #" + achatEnregistre.getId() + " - Fournisseur: " + achatEnregistre.getFournisseur().getNom();
        caisseService.enregistrerSortie(achatEnregistre.getMontantVerse(), motif, "ACHAT", actuel);

        return achatEnregistre;
    }

    @Transactional
    public void modifierAchat(Long id, Achat achatModifie) {
        Achat ancienAchat = achatRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Achat introuvable"));

        Double difference = achatModifie.getMontantVerse() - ancienAchat.getMontantVerse();

        // 1. Restaurer l'ancien stock
        for (DetailAchat ancienneLigne : ancienAchat.getLignes()) {
            Produit p = ancienneLigne.getProduit();
            p.setQuantite(p.getQuantite() - ancienneLigne.getQuantite());
        }

        // 2. Mettre à jour les infos
        ancienAchat.setFournisseur(achatModifie.getFournisseur());
        ancienAchat.setMontantTotal(achatModifie.getMontantTotal());
        ancienAchat.setMontantVerse(achatModifie.getMontantVerse());

        ancienAchat.getLignes().clear();
        for (DetailAchat nouvelleLigne : achatModifie.getLignes()) {
            Produit p = produitRepository.findById(nouvelleLigne.getProduit().getId()).orElseThrow();
            p.setQuantite(p.getQuantite() + nouvelleLigne.getQuantite());
            p.setPrixAchat(nouvelleLigne.getPrixAchatUnitaire());

            nouvelleLigne.setAchat(ancienAchat);
            ancienAchat.getLignes().add(nouvelleLigne);
        }

        achatRepository.save(ancienAchat);

        // 3. Ajustement Caisse si le montant versé a changé
        if (difference != 0) {
            String email = SecurityContextHolder.getContext().getAuthentication().getName();
            Utilisateur actuel = utilisateurRepository.findByEmail(email).orElse(null);

            String motif = "Correction Achat #" + id + " (Ajustement paiement)";
            caisseService.enregistrerSortie(difference, motif, "ACHAT", actuel);
        }
    }

    public List<Achat> listerTous() { return achatRepository.findAll(); }
    public Achat trouverParId(Long id) { return achatRepository.findById(id).orElse(null); }
}