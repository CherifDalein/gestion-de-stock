package org.example.stock.service;

import org.example.stock.model.Achat;
import org.example.stock.model.DetailAchat;
import org.example.stock.model.Produit;
import org.example.stock.model.Utilisateur;
import org.example.stock.model.Montants;
import org.example.stock.repository.AchatRepository;
import org.example.stock.repository.FournisseurRepository;
import org.example.stock.repository.StockLockRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class AchatService {

    @Autowired private AchatRepository achatRepository;
    @Autowired private StockLockRepository stockLockRepository;
    @Autowired private UtilisateurRepository utilisateurRepository;
    @Autowired private CaisseService caisseService;
    @Autowired private FournisseurRepository fournisseurRepository;

    @Transactional
    public Achat enregistrerAchat(Achat achat) {
        if (achat.getId() != null) throw new IllegalArgumentException("Une création ne peut pas contenir d'identifiant");
        validerAchat(achat);
        achat.setDateAchat(LocalDateTime.now());
        Map<Long, Produit> produits = stockLockRepository.verrouillerProduits(achat.getLignes().stream()
                .map(ligne -> ligne.getProduit().getId()).toList());

        BigDecimal montantTotalCalcule = calculerMontantTotal(achat);

        for (DetailAchat ligne : achat.getLignes()) {
            Produit produitBdd = produits.get(ligne.getProduit().getId());

            produitBdd.setQuantite(produitBdd.getQuantite() + ligne.getQuantite());
            produitBdd.setPrixAchat(ligne.getPrixAchatUnitaire());

            ligne.setAchat(achat);
            ligne.setProduit(produitBdd);
        }

        achat.setMontantTotal(montantTotalCalcule);
        achat.setMontantVerse(normaliserMontantVerse(achat.getMontantVerse(), montantTotalCalcule));
        Achat achatEnregistre = achatRepository.save(achat);

        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Utilisateur actuel = utilisateurRepository.findByEmail(email).orElse(null);

        String motif = "Achat #" + achatEnregistre.getId() + " - Fournisseur: " + achatEnregistre.getFournisseur().getNom();
        caisseService.enregistrerSortieAchat(achatEnregistre.getMontantVerse(), motif, achatEnregistre, actuel);

        return achatEnregistre;
    }

    @Transactional
    public void modifierAchat(Long id, Achat achatModifie) {
        if (achatModifie.getMontantVerse() != null) {
            throw new IllegalArgumentException("Utilisez le formulaire de règlement pour enregistrer un versement.");
        }
        Achat ancienAchat = stockLockRepository.verrouillerAchat(id);

        validerAchat(achatModifie);
        Map<Long, Produit> produits = stockLockRepository.verrouillerProduits(Stream.concat(
                ancienAchat.getLignes().stream(), achatModifie.getLignes().stream())
                .map(ligne -> ligne.getProduit().getId()).toList());

        BigDecimal montantTotalCalcule = calculerMontantTotal(achatModifie);
        BigDecimal montantDejaVerse = Montants.ouZero(ancienAchat.getMontantVerse());
        if (montantTotalCalcule.compareTo(montantDejaVerse) < 0) {
            throw new IllegalArgumentException("Le total modifié ne peut pas être inférieur au montant déjà payé.");
        }

        for (DetailAchat ancienneLigne : ancienAchat.getLignes()) {
            Produit produit = produits.get(ancienneLigne.getProduit().getId());

            if (produit.getQuantite() < ancienneLigne.getQuantite()) {
                throw new RuntimeException(
                        "Modification impossible pour " + produit.getNom() + " : une partie du stock a deja ete consommee."
                );
            }

            produit.setQuantite(produit.getQuantite() - ancienneLigne.getQuantite());
        }

        ancienAchat.setFournisseur(achatModifie.getFournisseur());
        ancienAchat.setMontantTotal(montantTotalCalcule);

        ancienAchat.getLignes().clear();
        for (DetailAchat nouvelleLigne : achatModifie.getLignes()) {
            Produit produit = produits.get(nouvelleLigne.getProduit().getId());

            produit.setQuantite(produit.getQuantite() + nouvelleLigne.getQuantite());
            produit.setPrixAchat(nouvelleLigne.getPrixAchatUnitaire());

            nouvelleLigne.setAchat(ancienAchat);
            nouvelleLigne.setProduit(produit);
            ancienAchat.getLignes().add(nouvelleLigne);
        }

        achatRepository.save(ancienAchat);
    }

    @Transactional
    public void reglerAchat(Long id, BigDecimal montant, BigDecimal montantVerseAttendu) {
        montant = Montants.positifOuNul(montant, "Le versement");
        if (montant.signum() == 0) {
            throw new IllegalArgumentException("Le versement doit être positif et comporter au maximum 2 décimales.");
        }
        if (montantVerseAttendu == null || montantVerseAttendu.signum() < 0) {
            throw new IllegalArgumentException("Rechargez le formulaire de règlement.");
        }
        Achat achat = stockLockRepository.verrouillerAchat(id);
        BigDecimal dejaVerse = Montants.positifOuNul(Montants.ouZero(achat.getMontantVerse()), "Le montant déjà versé");
        BigDecimal total = Montants.positifOuNul(achat.getMontantTotal(), "Le total de l'achat");
        if (total.compareTo(dejaVerse) < 0) {
            throw new IllegalArgumentException("Les montants de cet achat sont invalides. Vérifiez la facture.");
        }
        if (montantVerseAttendu.compareTo(dejaVerse) != 0) throw new ReglementAchatObsoleteException();
        BigDecimal nouveauVerse = dejaVerse.add(montant);
        if (nouveauVerse.compareTo(total) > 0) {
            throw new IllegalArgumentException("Le versement ne peut pas dépasser le reste à payer.");
        }
        achat.setMontantVerse(Montants.valider(nouveauVerse, "Le cumul des versements"));

        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Utilisateur actuel = utilisateurRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Utilisateur introuvable pour enregistrer le versement."));
        String motif = "Règlement Achat #" + id + " - Fournisseur: " + achat.getFournisseur().getNom();
        caisseService.enregistrerSortieAchat(montant, motif, achat, actuel);
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
        achat.setFournisseur(fournisseurRepository.findById(achat.getFournisseur().getId())
                .orElseThrow(() -> new IllegalArgumentException("Fournisseur introuvable")));

        for (DetailAchat ligne : achat.getLignes()) {
            if (ligne == null || ligne.getId() != null) {
                throw new IllegalArgumentException("Une ligne d'achat doit être nouvelle et sans identifiant");
            }
            if (ligne.getProduit() == null || ligne.getProduit().getId() == null) {
                throw new RuntimeException("Chaque ligne d'achat doit contenir un produit.");
            }
            if (ligne.getQuantite() == null || ligne.getQuantite() <= 0) {
                throw new RuntimeException("La quantite achetee doit etre superieure a 0.");
            }
            ligne.setPrixAchatUnitaire(Montants.positifOuNul(ligne.getPrixAchatUnitaire(), "Le prix d'achat unitaire"));
        }
    }

    private BigDecimal calculerMontantTotal(Achat achat) {
        BigDecimal montantTotalCalcule = Montants.ZERO;
        for (DetailAchat ligne : achat.getLignes()) {
            montantTotalCalcule = montantTotalCalcule.add(ligne.getPrixAchatUnitaire().multiply(BigDecimal.valueOf(ligne.getQuantite())));
        }
        return Montants.valider(montantTotalCalcule, "Le total de l'achat");
    }

    private BigDecimal normaliserMontantVerse(BigDecimal montantVerse, BigDecimal montantTotal) {
        BigDecimal montant = Montants.positifOuNul(Objects.requireNonNullElse(montantVerse, montantTotal), "Le montant versé");
        if (montant.compareTo(montantTotal) > 0) {
            throw new RuntimeException("Le montant verse ne peut pas depasser le total de l'achat.");
        }

        return montant;
    }
}
