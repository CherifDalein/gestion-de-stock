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

    @Autowired
    private BoutiqueService boutiqueService;

    public List<Fournisseur> listerTous() {
        return fournisseurRepository.findAllByBoutiqueId(boutiqueService.idBoutiqueCourante());
    }

    @Transactional
    public Fournisseur enregistrer(Fournisseur f) {
        if (f.getId() != null) throw new IllegalArgumentException("Une création ne peut pas contenir d'identifiant");
        f.setBoutique(boutiqueService.boutiqueCourante());
        return fournisseurRepository.save(f);
    }

    @Transactional
    public Fournisseur modifierFournisseur(Long id, Fournisseur modifications) {
        Fournisseur fournisseur = trouverParId(id);
        fournisseur.setNom(modifications.getNom());
        fournisseur.setTelephone(modifications.getTelephone());
        fournisseur.setEmail(modifications.getEmail());
        fournisseur.setAdresse(modifications.getAdresse());
        return fournisseurRepository.save(fournisseur);
    }

    public Fournisseur trouverParId(Long id) {
        return fournisseurRepository.findByIdAndBoutiqueId(id, boutiqueService.idBoutiqueCourante())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fournisseur introuvable"));
    }

    @Transactional
    public void supprimerFournisseur(Long id) {
        fournisseurRepository.delete(trouverParId(id));
    }
}
