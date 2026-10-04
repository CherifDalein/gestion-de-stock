package org.example.stock.service;

import org.example.stock.model.DetailVente;
import org.example.stock.model.Produit;
import org.example.stock.model.Utilisateur;
import org.example.stock.model.Vente;
import org.example.stock.model.Montants;
import org.example.stock.repository.StockLockRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.example.stock.repository.VenteRepository;
import org.example.stock.repository.ClientRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Map;

@Service
public class VenteService {

    @Autowired private VenteRepository venteRepository;
    @Autowired private StockLockRepository stockLockRepository;
    @Autowired private UtilisateurRepository utilisateurRepository;
    @Autowired private CaisseService caisseService;
    @Autowired private ClientRepository clientRepository;

    @Transactional
    public Vente effectuerVente(Vente vente) {
        if (vente.getId() != null) throw new IllegalArgumentException("Une création ne peut pas contenir d'identifiant");
        if (vente.getLignes() == null || vente.getLignes().isEmpty()) {
            throw new RuntimeException("Impossible d'enregistrer une vente vide.");
        }
        if (vente.getClient() != null) {
            if (vente.getClient().getId() == null) throw new IllegalArgumentException("Client invalide");
            vente.setClient(clientRepository.findById(vente.getClient().getId())
                    .orElseThrow(() -> new IllegalArgumentException("Client introuvable")));
        }

        for (DetailVente detail : vente.getLignes()) {
            if (detail == null || detail.getId() != null) {
                throw new IllegalArgumentException("Une ligne de vente doit être nouvelle et sans identifiant");
            }
            if (detail.getProduit() == null || detail.getProduit().getId() == null) {
                throw new RuntimeException("Chaque ligne de vente doit contenir un produit.");
            }
            if (detail.getQuantite() == null || detail.getQuantite() <= 0) {
                throw new RuntimeException("La quantite vendue doit etre superieure a 0.");
            }
        }

        Map<Long, Produit> produits = stockLockRepository.verrouillerProduits(vente.getLignes().stream()
                .map(ligne -> ligne.getProduit().getId()).toList());
        BigDecimal montantTotalCalcule = Montants.ZERO;

        for (DetailVente detail : vente.getLignes()) {
            Produit produit = produits.get(detail.getProduit().getId());

            if (produit.getQuantite() < detail.getQuantite()) {
                throw new RuntimeException("Stock insuffisant pour " + produit.getNom());
            }

            produit.setQuantite(produit.getQuantite() - detail.getQuantite());

            detail.setPrixUnitaire(Montants.positifOuNul(produit.getPrixVente(), "Le prix de vente"));
            detail.setVente(vente);
            detail.setProduit(produit);
            montantTotalCalcule = montantTotalCalcule.add(detail.getPrixUnitaire().multiply(BigDecimal.valueOf(detail.getQuantite())));
        }

        vente.setMontantTotal(Montants.valider(montantTotalCalcule, "Le total de la vente"));
        vente.setMontantVerse(normaliserMontantVerse(vente.getMontantVerse(), montantTotalCalcule));
        vente.setDateVente(LocalDateTime.now());
        Vente venteEnregistree = venteRepository.save(vente);

        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Utilisateur actuel = utilisateurRepository.findByEmail(email).orElse(null);

        String motif = "Vente #" + venteEnregistree.getId() + " - Client: " +
                (venteEnregistree.getClient() != null ? venteEnregistree.getClient().getNom() : "Passant");

        caisseService.enregistrerEntreeVente(venteEnregistree.getMontantVerse(), motif, venteEnregistree, actuel);

        return venteEnregistree;
    }

    public List<Vente> listerToutes() {
        return venteRepository.findAll();
    }

    public Vente trouverParId(Long id) {
        return venteRepository.findById(id).orElse(null);
    }

    @Transactional
    public void reglerVente(Long id, BigDecimal montant, BigDecimal montantVerseAttendu) {
        montant = Montants.positifOuNul(montant, "Le versement");
        if (montant.signum() == 0) throw new IllegalArgumentException("Le versement doit être supérieur à zéro.");
        montantVerseAttendu = Montants.positifOuNul(montantVerseAttendu, "Le montant déjà versé attendu");
        Vente vente = stockLockRepository.verrouillerVente(id);
        BigDecimal dejaVerse = Montants.positifOuNul(Montants.ouZero(vente.getMontantVerse()), "Le montant déjà versé");
        BigDecimal total = Montants.positifOuNul(vente.getMontantTotal(), "Le total de la vente");
        if (total.compareTo(dejaVerse) < 0) {
            throw new IllegalArgumentException("Les montants de cette vente sont invalides. Vérifiez la facture.");
        }
        if (montantVerseAttendu.compareTo(dejaVerse) != 0) throw new ReglementVenteObsoleteException();
        BigDecimal nouveauVerse = dejaVerse.add(montant);
        if (nouveauVerse.compareTo(total) > 0) {
            throw new IllegalArgumentException("Le versement ne peut pas dépasser le reste à payer.");
        }
        vente.setMontantVerse(Montants.valider(nouveauVerse, "Le cumul des versements"));
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Utilisateur actuel = utilisateurRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Utilisateur introuvable pour enregistrer le versement."));
        String motif = "Règlement Vente #" + id + " - Client: "
                + (vente.getClient() != null ? vente.getClient().getNom() : "Passant");
        caisseService.enregistrerEntreeVente(montant, motif, vente, actuel);
    }

    private BigDecimal normaliserMontantVerse(BigDecimal montantVerse, BigDecimal montantTotal) {
        BigDecimal montant = Montants.positifOuNul(Objects.requireNonNullElse(montantVerse, montantTotal), "Le montant versé");
        if (montant.compareTo(montantTotal) > 0) {
            throw new RuntimeException("Le montant verse ne peut pas depasser le total de la vente.");
        }

        return montant;
    }
}
