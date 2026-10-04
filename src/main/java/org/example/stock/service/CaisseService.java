package org.example.stock.service;

import org.example.stock.model.MouvementCaisse;
import org.example.stock.model.Utilisateur;
import org.example.stock.model.Achat;
import org.example.stock.model.Vente;
import org.example.stock.model.Montants;
import org.example.stock.repository.MouvementCaisseRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class CaisseService {

    @Autowired
    private MouvementCaisseRepository mouvementRepo;

    public BigDecimal getSoldeActuel() {
        return normaliser(mouvementRepo.calculerSoldeTotal());
    }

    public BigDecimal getSoldeOuverture(LocalDate date) {
        return normaliser(mouvementRepo.calculerSoldeAvant(date.atStartOfDay()));
    }

    public BigDecimal getEntreesDuJour(LocalDate date) {
        return normaliser(mouvementRepo.calculerEntreesDepuis(date.atStartOfDay()));
    }

    public BigDecimal getSortiesDuJour(LocalDate date) {
        return normaliser(mouvementRepo.calculerSortiesDepuis(date.atStartOfDay())).abs();
    }

    public BigDecimal getNetDuJour(LocalDate date) {
        return normaliser(mouvementRepo.calculerFluxTotalDepuis(date.atStartOfDay()));
    }

    public BigDecimal getSoldeCloture(LocalDate date) {
        return getSoldeOuverture(date).add(getNetDuJour(date));
    }

    public BigDecimal getCaisseDuJour(LocalDate date) {
        return getEntreesDuJour(date).subtract(getSortiesDuJour(date));
    }

    public List<MouvementCaisse> getMouvementsDuJour(LocalDate date) {
        return mouvementRepo.findByDateMouvementGreaterThanEqualOrderByDateMouvementDesc(date.atStartOfDay());
    }

    @Transactional
    public void enregistrerEntree(BigDecimal montant, String motif, String source, Utilisateur utilisateur) {
        enregistrerEntree(montant, motif, source, utilisateur, null);
    }

    @Transactional
    public void enregistrerEntreeVente(BigDecimal montant, String motif, Vente vente, Utilisateur utilisateur) {
        enregistrerEntree(montant, motif, "VENTE", utilisateur, vente);
    }

    public List<MouvementCaisse> listerReglementsVente(Long venteId) {
        return mouvementRepo.findByVenteIdOrderByDateMouvementDescIdDesc(venteId);
    }

    private void enregistrerEntree(BigDecimal montant, String motif, String source, Utilisateur utilisateur, Vente vente) {
        if (montant == null || montant.signum() == 0) {
            return;
        }
        if (montant.signum() < 0) {
            throw new IllegalArgumentException("Le montant d'une entree de caisse doit etre positif.");
        }

        montant = Montants.valider(montant, "Le montant de caisse");
        MouvementCaisse mouvement = new MouvementCaisse();
        mouvement.setDateMouvement(LocalDateTime.now());
        mouvement.setMontant(montant.abs());
        mouvement.setType("ENTREE");
        mouvement.setMotif(motif);
        mouvement.setSource(source);
        mouvement.setUtilisateur(utilisateur);
        mouvement.setVente(vente);

        mouvementRepo.save(mouvement);
    }

    @Transactional
    public void enregistrerSortie(BigDecimal montant, String motif, String source, Utilisateur utilisateur) {
        enregistrerSortie(montant, motif, source, utilisateur, null);
    }

    @Transactional
    public void enregistrerSortieAchat(BigDecimal montant, String motif, Achat achat, Utilisateur utilisateur) {
        enregistrerSortie(montant, motif, "ACHAT", utilisateur, achat);
    }

    public List<MouvementCaisse> listerReglementsAchat(Long achatId) {
        return mouvementRepo.findByAchatIdOrderByDateMouvementDescIdDesc(achatId);
    }

    private void enregistrerSortie(BigDecimal montant, String motif, String source, Utilisateur utilisateur, Achat achat) {
        if (montant == null || montant.signum() == 0) {
            return;
        }

        montant = Montants.valider(montant, "Le montant de caisse");
        MouvementCaisse mouvement = new MouvementCaisse();
        mouvement.setDateMouvement(LocalDateTime.now());

        if (montant.signum() > 0) {
            mouvement.setMontant(montant.abs().negate());
            mouvement.setType("SORTIE");
        } else {
            mouvement.setMontant(montant.abs());
            mouvement.setType("CORRECTION_ENTREE");
        }

        mouvement.setMotif(motif);
        mouvement.setSource(source);
        mouvement.setUtilisateur(utilisateur);
        mouvement.setAchat(achat);

        mouvementRepo.save(mouvement);
    }

    private BigDecimal normaliser(BigDecimal valeur) {
        return Montants.ouZero(valeur);
    }
}
