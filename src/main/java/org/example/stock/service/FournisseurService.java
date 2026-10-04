package org.example.stock.service;

import org.example.stock.model.Fournisseur;
import org.example.stock.repository.FournisseurRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class FournisseurService {
    @Autowired
    private FournisseurRepository fournisseurRepository;

    public List<Fournisseur> listerTous() {
        return fournisseurRepository.findAll();
    }

    public Fournisseur enregistrer(Fournisseur f) {
        if (f.getId() != null) throw new IllegalArgumentException("Une création ne peut pas contenir d'identifiant");
        return fournisseurRepository.save(f);
    }

    @Transactional
    public Fournisseur modifierFournisseur(Long id, Fournisseur modifications) {
        Fournisseur fournisseur = fournisseurRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fournisseur introuvable"));
        fournisseur.setNom(modifications.getNom());
        fournisseur.setTelephone(modifications.getTelephone());
        fournisseur.setEmail(modifications.getEmail());
        fournisseur.setAdresse(modifications.getAdresse());
        return fournisseurRepository.save(fournisseur);
    }

    public Fournisseur trouverParId(Long id) {
        return fournisseurRepository.findById(id).orElseThrow(() -> new RuntimeException("Fournisseur introuvable"));
    }

    public void supprimerFournisseur(Long id) {
        fournisseurRepository.deleteById(id);
    }
}
