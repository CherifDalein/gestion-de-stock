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

        for (DetailAchat ligne : achat.getLignes()) {
            if (ligne.getProduit() == null || ligne.getProduit().getId() == null) {
                continue;
            }

            Long produitId = ligne.getProduit().getId();
            Produit produitBdd = produitRepository.findById(produitId)
                    .orElseThrow(() -> new RuntimeException("Produit non trouvé avec l'ID : " + produitId));

            Long nouveauStock = produitBdd.getQuantite() + ligne.getQuantite();
            produitBdd.setQuantite(nouveauStock);

            produitBdd.setPrixAchat(ligne.getPrixAchatUnitaire());

            ligne.setAchat(achat);
            produitRepository.save(produitBdd);
        }

        return achatRepository.save(achat);
    }

    public List<Achat> listerTous() {
        return achatRepository.findAll();
    }

    public Achat trouverParId(Long id) {
        return achatRepository.findById(id).orElse(null);
    }
}