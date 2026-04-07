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
import java.util.Objects;

@Service
public class AchatService {

    @Autowired private AchatRepository achatRepository;
    @Autowired private ProduitRepository produitRepository;
    @Autowired private UtilisateurRepository utilisateurRepository;
    @Autowired private CaisseService caisseService;

    @Transactional
    public Achat enregistrerAchat(Achat achat) {
        validerAchat(achat);

        if (achat.getDateAchat() == null) {
            achat.setDateAchat(LocalDateTime.now());
        }

        double montantTotalCalcule = 0.0;

        for (DetailAchat ligne : achat.getLignes()) {
            Produit produitBdd = produitRepository.findById(ligne.getProduit().getId())
                    .orElseThrow(() -> new RuntimeException("Produit non trouve"));

            produitBdd.setQuantite(produitBdd.getQuantite() + ligne.getQuantite());
            produitBdd.setPrixAchat(ligne.getPrixAchatUnitaire());

            ligne.setAchat(achat);
            produitRepository.save(produitBdd);
            montantTotalCalcule += ligne.getPrixAchatUnitaire() * ligne.getQuantite();
        }

        achat.setMontantTotal(montantTotalCalcule);
        achat.setMontantVerse(normaliserMontantVerse(achat.getMontantVerse(), montantTotalCalcule));
        Achat achatEnregistre = achatRepository.save(achat);

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

        validerAchat(achatModifie);

        double montantTotalCalcule = calculerMontantTotal(achatModifie);
        Double nouveauMontantVerse = normaliserMontantVerse(achatModifie.getMontantVerse(), montantTotalCalcule);
        Double difference = nouveauMontantVerse - Objects.requireNonNullElse(ancienAchat.getMontantVerse(), 0.0);

        for (DetailAchat ancienneLigne : ancienAchat.getLignes()) {
            Produit produit = produitRepository.findById(ancienneLigne.getProduit().getId())
                    .orElseThrow(() -> new RuntimeException("Produit non trouve"));

            if (produit.getQuantite() < ancienneLigne.getQuantite()) {
                throw new RuntimeException(
                        "Modification impossible pour " + produit.getNom() + " : une partie du stock a deja ete consommee."
                );
            }

            produit.setQuantite(produit.getQuantite() - ancienneLigne.getQuantite());
        }

        ancienAchat.setFournisseur(achatModifie.getFournisseur());
        ancienAchat.setMontantTotal(montantTotalCalcule);
        ancienAchat.setMontantVerse(nouveauMontantVerse);

        ancienAchat.getLignes().clear();
        for (DetailAchat nouvelleLigne : achatModifie.getLignes()) {
            Produit produit = produitRepository.findById(nouvelleLigne.getProduit().getId())
                    .orElseThrow(() -> new RuntimeException("Produit non trouve"));

            produit.setQuantite(produit.getQuantite() + nouvelleLigne.getQuantite());
            produit.setPrixAchat(nouvelleLigne.getPrixAchatUnitaire());

            nouvelleLigne.setAchat(ancienAchat);
            ancienAchat.getLignes().add(nouvelleLigne);
        }

        achatRepository.save(ancienAchat);

        if (Math.abs(difference) > 0.000001d) {
            String email = SecurityContextHolder.getContext().getAuthentication().getName();
            Utilisateur actuel = utilisateurRepository.findByEmail(email).orElse(null);

            String motif = "Correction Achat #" + id + " (Ajustement paiement)";
            caisseService.enregistrerSortie(difference, motif, "ACHAT", actuel);
        }
    }

    public List<Achat> listerTous() {
        return achatRepository.findAll();
    }

    public Achat trouverParId(Long id) {
        return achatRepository.findById(id).orElse(null);
    }

    private void validerAchat(Achat achat) {
        if (achat.getFournisseur() == null || achat.getFournisseur().getId() == null) {
            throw new RuntimeException("Veuillez selectionner un fournisseur.");
        }
        if (achat.getLignes() == null || achat.getLignes().isEmpty()) {
            throw new RuntimeException("Impossible d'enregistrer un achat sans produit.");
        }

        for (DetailAchat ligne : achat.getLignes()) {
            if (ligne.getProduit() == null || ligne.getProduit().getId() == null) {
                throw new RuntimeException("Chaque ligne d'achat doit contenir un produit.");
            }
            if (ligne.getQuantite() == null || ligne.getQuantite() <= 0) {
                throw new RuntimeException("La quantite achetee doit etre superieure a 0.");
            }
            if (ligne.getPrixAchatUnitaire() == null || ligne.getPrixAchatUnitaire() < 0) {
                throw new RuntimeException("Le prix d'achat unitaire doit etre valide.");
            }
        }
    }

    private double calculerMontantTotal(Achat achat) {
        double montantTotalCalcule = 0.0;
        for (DetailAchat ligne : achat.getLignes()) {
            montantTotalCalcule += ligne.getPrixAchatUnitaire() * ligne.getQuantite();
        }
        return montantTotalCalcule;
    }

    private Double normaliserMontantVerse(Double montantVerse, double montantTotal) {
        double montant = Objects.requireNonNullElse(montantVerse, montantTotal);

        if (montant < 0) {
            throw new RuntimeException("Le montant verse ne peut pas etre negatif.");
        }
        if (montant > montantTotal) {
            throw new RuntimeException("Le montant verse ne peut pas depasser le total de l'achat.");
        }

        return montant;
    }
}
