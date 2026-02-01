package org.example.stock.service;

import org.example.stock.model.Achat;
import org.example.stock.model.DetailAchat;
import org.example.stock.model.Produit;
import org.example.stock.repository.AchatRepository;
import org.example.stock.repository.ProduitRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AchatService {

    @Autowired
    private AchatRepository achatRepository;

    @Autowired
    private ProduitRepository produitRepository;


    @Transactional
    public Achat enregistrerAchat(Achat achat) {
        if (achat.getDateAchat() == null) {
            achat.setDateAchat(LocalDateTime.now());
        }

        // SECURITÉ : Si le montant versé n'est pas saisi, on considère
        // que l'achat est payé en totalité (Cash).
        if (achat.getMontantVerse() == null) {
            achat.setMontantVerse(achat.getMontantTotal());
        }

        for (DetailAchat ligne : achat.getLignes()) {
            if (ligne.getProduit() == null || ligne.getProduit().getId() == null) {
                continue;
            }

            Long produitId = ligne.getProduit().getId();
            Produit produitBdd = produitRepository.findById(produitId)
                    .orElseThrow(() -> new RuntimeException("Produit non trouvé ID : " + produitId));

            // Mise à jour du stock
            Long nouveauStock = produitBdd.getQuantite() + ligne.getQuantite();
            produitBdd.setQuantite(nouveauStock);

            // Mise à jour du prix d'achat dans la fiche produit (Dernier prix d'achat)
            produitBdd.setPrixAchat(ligne.getPrixAchatUnitaire());

            ligne.setAchat(achat);
            produitRepository.save(produitBdd);
        }

        Achat achatEnregistre = achatRepository.save(achat);

        // TODO: Ici on enregistrera la SORTIE d'argent :
        // caisseService.enregistrerSortie(achatEnregistre.getMontantVerse());

        return achatEnregistre;
    }

    public List<Achat> listerTous() {
        return achatRepository.findAll();
    }

    @Transactional
    public void modifierAchat(Long id, Achat achatModifie) {
        Achat ancienAchat = achatRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Achat introuvable"));

        // 1. Annuler l'impact de l'ancien stock
        for (DetailAchat ancienneLigne : ancienAchat.getLignes()) {
            Produit p = ancienneLigne.getProduit();
            p.setQuantite(p.getQuantite() - ancienneLigne.getQuantite());
            // On ne fait pas forcément de save ici, l'aspect Transactional s'en chargera à la fin
        }

        // 2. Mise à jour des informations de base
        ancienAchat.setFournisseur(achatModifie.getFournisseur());
        ancienAchat.setMontantTotal(achatModifie.getMontantTotal());
        ancienAchat.setMontantVerse(achatModifie.getMontantVerse());

        // On garde la date originale si la nouvelle est nulle
        if(achatModifie.getDateAchat() != null) {
            ancienAchat.setDateAchat(achatModifie.getDateAchat());
        }

        // 3. REMPLACER les lignes proprement
        // Au lieu de clear(), on peut réassocier les nouvelles lignes
        ancienAchat.getLignes().clear();
        // Note: Assure-toi que dans Achat.java, l'annotation est @OneToMany(mappedBy = "achat", cascade = CascadeType.ALL, orphanRemoval = true)

        for (DetailAchat nouvelleLigne : achatModifie.getLignes()) {
            Produit p = produitRepository.findById(nouvelleLigne.getProduit().getId())
                    .orElseThrow(() -> new RuntimeException("Produit non trouvé"));

            // Appliquer le nouvel impact
            p.setQuantite(p.getQuantite() + nouvelleLigne.getQuantite());
            p.setPrixAchat(nouvelleLigne.getPrixAchatUnitaire());

            nouvelleLigne.setAchat(ancienAchat);
            ancienAchat.getLignes().add(nouvelleLigne);
        }

        achatRepository.save(ancienAchat);
    }

    public Achat trouverParId(Long id) {
        return achatRepository.findById(id).orElse(null);
    }
}