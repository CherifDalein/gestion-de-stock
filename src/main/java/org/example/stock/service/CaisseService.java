package org.example.stock.service;

import org.example.stock.model.MouvementCaisse;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.MouvementCaisseRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class CaisseService {

    @Autowired
    private MouvementCaisseRepository mouvementRepo;

    /**
     * Calcule le solde total actuel de la caisse.
     */
    public Double getSoldeActuel() {
        return mouvementRepo.calculerSoldeTotal();
    }

    /**
     * Enregistre une rentrée d'argent (Ventes, apports, etc.)
     */
    @Transactional
    public void enregistrerEntree(Double montant, String motif, String source, Utilisateur utilisateur) {
        if (montant == null || montant == 0) return;

        MouvementCaisse m = new MouvementCaisse();
        m.setDateMouvement(LocalDateTime.now());
        m.setMontant(Math.abs(montant)); // Forcer la valeur positive pour une entrée
        m.setType("ENTRÉE");
        m.setMotif(motif);
        m.setSource(source);
        m.setUtilisateur(utilisateur);

        mouvementRepo.save(m);
    }

    /**
     * Enregistre une sortie d'argent (Achats, dépenses, etc.)
     * Si le montant est négatif, cela sera traité comme une correction (rentrée).
     */
    @Transactional
    public void enregistrerSortie(Double montant, String motif, String source, Utilisateur utilisateur) {
        if (montant == null || montant == 0) return;

        MouvementCaisse m = new MouvementCaisse();
        m.setDateMouvement(LocalDateTime.now());

        // Logique : On enregistre l'inverse du montant versé
        // Un achat de 1000 devient -1000 en caisse.
        m.setMontant(-montant);

        // Détermination automatique du type pour le journal
        if (montant > 0) {
            m.setType("SORTIE");
        } else {
            m.setType("CORRECTION (ENTRÉE)");
        }

        m.setMotif(motif);
        m.setSource(source);
        m.setUtilisateur(utilisateur);

        mouvementRepo.save(m);
    }
}