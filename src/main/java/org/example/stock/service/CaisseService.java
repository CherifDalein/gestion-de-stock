package org.example.stock.service;

import org.example.stock.model.MouvementCaisse;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.MouvementCaisseRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class CaisseService {

    @Autowired
    private MouvementCaisseRepository mouvementRepo;

    public Double getSoldeActuel() {
        return normaliser(mouvementRepo.calculerSoldeTotal());
    }

    public Double getSoldeOuverture(LocalDate date) {
        return normaliser(mouvementRepo.calculerSoldeAvant(date.atStartOfDay()));
    }

    public Double getEntreesDuJour(LocalDate date) {
        return normaliser(mouvementRepo.calculerEntreesDepuis(date.atStartOfDay()));
    }

    public Double getSortiesDuJour(LocalDate date) {
        return Math.abs(normaliser(mouvementRepo.calculerSortiesDepuis(date.atStartOfDay())));
    }

    public Double getNetDuJour(LocalDate date) {
        return normaliser(mouvementRepo.calculerFluxTotalDepuis(date.atStartOfDay()));
    }

    public Double getSoldeCloture(LocalDate date) {
        return getSoldeOuverture(date) + getNetDuJour(date);
    }

    public Double getCaisseDuJour(LocalDate date) {
        return getEntreesDuJour(date) - getSortiesDuJour(date);
    }

    public List<MouvementCaisse> getMouvementsDuJour(LocalDate date) {
        return mouvementRepo.findByDateMouvementGreaterThanEqualOrderByDateMouvementDesc(date.atStartOfDay());
    }

    @Transactional
    public void enregistrerEntree(Double montant, String motif, String source, Utilisateur utilisateur) {
        if (montant == null || montant == 0) {
            return;
        }
        if (montant < 0) {
            throw new IllegalArgumentException("Le montant d'une entree de caisse doit etre positif.");
        }

        MouvementCaisse mouvement = new MouvementCaisse();
        mouvement.setDateMouvement(LocalDateTime.now());
        mouvement.setMontant(Math.abs(montant));
        mouvement.setType("ENTREE");
        mouvement.setMotif(motif);
        mouvement.setSource(source);
        mouvement.setUtilisateur(utilisateur);

        mouvementRepo.save(mouvement);
    }

    @Transactional
    public void enregistrerSortie(Double montant, String motif, String source, Utilisateur utilisateur) {
        if (montant == null || montant == 0) {
            return;
        }

        MouvementCaisse mouvement = new MouvementCaisse();
        mouvement.setDateMouvement(LocalDateTime.now());

        if (montant > 0) {
            mouvement.setMontant(-Math.abs(montant));
            mouvement.setType("SORTIE");
        } else {
            mouvement.setMontant(Math.abs(montant));
            mouvement.setType("CORRECTION_ENTREE");
        }

        mouvement.setMotif(motif);
        mouvement.setSource(source);
        mouvement.setUtilisateur(utilisateur);

        mouvementRepo.save(mouvement);
    }

    private Double normaliser(Double valeur) {
        return valeur != null ? valeur : 0.0;
    }
}
