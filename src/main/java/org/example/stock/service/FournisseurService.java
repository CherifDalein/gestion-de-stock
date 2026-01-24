package org.example.stock.service;

import org.example.stock.model.Fournisseur;
import org.example.stock.repository.FournisseurRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FournisseurService {
    @Autowired
    private FournisseurRepository fournisseurRepository;

    public List<Fournisseur> listerTous() {
        return fournisseurRepository.findAll();
    }

    public Fournisseur enregistrer(Fournisseur f) {
        return fournisseurRepository.save(f);
    }

    public Fournisseur trouverParId(Long id) {
        return fournisseurRepository.findById(id).orElseThrow(() -> new RuntimeException("Fournisseur introuvable"));
    }

    public void supprimerFournisseur(Long id) {
        fournisseurRepository.deleteById(id);
    }
}
