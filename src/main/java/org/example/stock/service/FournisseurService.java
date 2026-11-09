package org.example.stock.service;

import org.example.stock.model.Fournisseur;
import org.example.stock.model.Montants;
import org.example.stock.repository.FournisseurRepository;
import org.example.stock.repository.StockLockRepository;
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

    @Autowired
    private SoldeDepartService soldeDepart;

    @Autowired
    private StockLockRepository verrous;

    public List<Fournisseur> listerTous() {
        return fournisseurRepository.findAllByBoutiqueId(boutiqueService.idBoutiqueCourante());
    }

    /** Comme {@link #listerTous()} mais lit au plus {@code max + 1} fiches (export : plus de {@code max} signale un dépassement). */
    public List<Fournisseur> listerPourExport(int max) {
        return fournisseurRepository.findAllByBoutiqueId(boutiqueService.idBoutiqueCourante(), org.springframework.data.domain.PageRequest.of(0, max + 1, org.springframework.data.domain.Sort.by("id")));
    }

    @Transactional
    public Fournisseur enregistrer(Fournisseur f) {
        if (f.getId() != null) throw new IllegalArgumentException("Une création ne peut pas contenir d'identifiant");
        f.setBoutique(boutiqueService.boutiqueCourante());
        // Le solde enregistré ne vient jamais du navigateur : il est recalculé, pour le gérant seulement.
        f.setSoldeInitial(Montants.ZERO);
        f.setEcheanceSoldeInitial(f.isSoldeDepartSoumis() ? f.getEcheanceSoldeInitial() : null);
        soldeDepart.appliquerALaCreation(f);
        return fournisseurRepository.save(f);
    }

    @Transactional
    public Fournisseur modifierFournisseur(Long id, Fournisseur modifications) {
        soldeDepart.appliquerALaModification(id, modifications);
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
        // La suppression contrôle les factures du fournisseur : la boutique d'abord (docs/lot-2.md, « Ordre des verrous »).
        verrous.verrouillerBoutiqueCourante();
        fournisseurRepository.delete(trouverParId(id));
    }
}
