package org.example.stock.service;

import org.example.stock.model.Achat;
import org.example.stock.model.DetailAchat;
import org.example.stock.model.Fournisseur;
import org.example.stock.model.Produit;
import org.example.stock.model.Utilisateur;
import org.example.stock.model.Montants;
import org.example.stock.enums.CompteTresorerie;
import org.example.stock.repository.AchatRepository;
import org.example.stock.repository.FournisseurRepository;
import org.example.stock.repository.RetourRepository;
import org.example.stock.repository.StockLockRepository;
import org.example.stock.repository.UtilisateurRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Stream;

@Service
public class AchatService {

    @Autowired private AchatRepository achatRepository;
    @Autowired private StockLockRepository stockLockRepository;
    @Autowired private UtilisateurRepository utilisateurRepository;
    @Autowired private CaisseService caisseService;
    @Autowired private FournisseurRepository fournisseurRepository;
    @Autowired private BoutiqueService boutiqueService;
    @Autowired private InitialisationBoutiqueService initialisation;
    @Autowired private CoutMoyenService coutMoyen;
    @Autowired private NumerotationFactureService numerotation;
    @Autowired private EcheanceService echeances;
    @Autowired private SoldePartenaireService soldes;
    @Autowired private EntityManager entityManager;
    @Autowired private RetourRepository retourRepository;

    @Transactional
    public Achat enregistrerAchat(Achat achat) {
        if (achat.getComptePaiement() == null) throw new IllegalArgumentException("Choisissez le compte utilisé pour le paiement.");
        if (achat.getId() != null) throw new IllegalArgumentException("Une création ne peut pas contenir d'identifiant");
        // Ordre global des verrous : la boutique d'abord (docs/lot-2.md, « Ordre des verrous »), puis le fournisseur et ses
        // factures, puis les produits.
        stockLockRepository.verrouillerBoutiqueCourante();
        validerAchat(achat);
        achat.setNumero(NumerotationFactureService.validerSaisie(achat.getNumero()));
        initialisation.verrouillerEtClorePourActivite();
        String numero = numerotation.attribuerNumeroAchat(achat.getNumero());
        achat.setBoutique(boutiqueService.boutiqueCourante());
        // Fiche du fournisseur verrouillée ensuite, avant les produits : une avance n'est jamais consommée deux fois.
        Fournisseur fournisseur = stockLockRepository.verrouillerFournisseur(achat.getFournisseur().getId());
        Map<Long, Produit> produits = stockLockRepository.verrouillerProduits(achat.getLignes().stream()
                .map(ligne -> ligne.getProduit().getId()).toList());
        refuserServices(produits);
        // Daté sous le verrou des produits, comme une vente : l'ordre des dates est celui du coût moyen.
        achat.setDateAchat(LocalDateTime.now());

        BigDecimal montantTotalCalcule = calculerMontantTotal(achat);

        for (DetailAchat ligne : achat.getLignes()) {
            Produit produitBdd = produits.get(ligne.getProduit().getId());

            coutMoyen.enregistrerEntree(produitBdd, ligne.getQuantite(), ligne.getPrixAchatUnitaire());
            produitBdd.setQuantite(produitBdd.getQuantite() + ligne.getQuantite());
            produitBdd.setPrixAchat(ligne.getPrixAchatUnitaire());
            produitBdd.setPrixVente(ligne.getPrixVenteUnitaire());
            produitBdd.setPrixDefinis(true);

            ligne.setAchat(achat);
            ligne.setProduit(produitBdd);
        }

        achat.setMontantTotal(montantTotalCalcule);
        // Un achat a toujours un fournisseur : le trop-payé est admis, le surplus devient une avance chez lui.
        BigDecimal verse = normaliserMontantVerse(achat.getMontantVerse(), montantTotalCalcule);
        achat.setMontantVerse(verse);
        achat.setAvanceUtilisee(verse.compareTo(montantTotalCalcule) >= 0 ? Montants.ZERO
                : Montants.valider(soldes.avanceDisponibleVerrouillee(fournisseur).min(montantTotalCalcule.subtract(verse)), "L'avance utilisée"));
        achat.setDateEcheance(echeances.pourCreation(achat.getDateAchat(), achat.getResteAPayer(), achat.getDateEcheance(),
                achat.getBoutique().getDelaiFournisseursJours()));
        achat.setNumero(numero);
        // L'auteur est le compte connecté, lu côté serveur ; une modification ultérieure ne le change pas.
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Utilisateur actuel = utilisateurRepository.findByEmail(email).orElse(null);
        achat.setUtilisateur(actuel);
        Achat achatEnregistre = achatRepository.save(achat);

        String motif = "Achat #" + achatEnregistre.getId() + " - Fournisseur: " + achatEnregistre.getFournisseur().getNom();
        caisseService.enregistrerSortieAchat(achatEnregistre.getMontantVerse(), motif, achatEnregistre, actuel, achat.getComptePaiement());

        return achatEnregistre;
    }

    /**
     * Modifie les lignes d'un achat sans retour. Ordre des verrous : boutique, achat, fournisseur, produits (docs/lot-2.md,
     * « Ordre des verrous »). Isolation READ COMMITTED, comme le retour d'achat : la lecture verrouillée des retours de l'achat
     * ne pose aucun verrou de plage (voir « Isolation » dans la même section).
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void modifierAchat(Long id, Achat achatModifie) {
        if (achatModifie.getMontantVerse() != null) {
            throw new IllegalArgumentException("Utilisez le formulaire de règlement pour enregistrer un versement.");
        }
        stockLockRepository.verrouillerBoutiqueCourante();
        initialisation.verrouillerEtClorePourActivite();
        // Même ordre que le retour d'achat : boutique, achat, fiche du fournisseur, produits.
        Achat ancienAchat = stockLockRepository.verrouillerAchat(id);
        // Lecture verrouillée sous le verrou de l'achat : un retour d'achat validé à l'instant est vu.
        if (!entityManager.createQuery("select r.id from Retour r where r.achat.id = :achat", Long.class)
                .setParameter("achat", id).setLockMode(LockModeType.PESSIMISTIC_READ).setMaxResults(1).getResultList().isEmpty()) {
            throw new ModificationAchatRefuseeException();
        }
        Fournisseur fournisseurVerrouille = ancienAchat.getFournisseur() == null ? null
                : stockLockRepository.verrouillerFournisseur(ancienAchat.getFournisseur().getId());

        validerAchat(achatModifie);
        Map<Long, Produit> produits = stockLockRepository.verrouillerProduits(Stream.concat(
                ancienAchat.getLignes().stream(), achatModifie.getLignes().stream())
                .map(ligne -> ligne.getProduit().getId()).toList());
        refuserServices(produits);

        BigDecimal montantTotalCalcule = calculerMontantTotal(achatModifie);
        // Le montant déjà payé comprend l'avance utilisée : le nouveau total ne peut pas descendre en dessous.
        BigDecimal montantDejaVerse = Montants.ouZero(ancienAchat.getMontantVerse()).add(Montants.ouZero(ancienAchat.getAvanceUtilisee()));
        if (montantTotalCalcule.compareTo(montantDejaVerse) < 0) {
            throw new IllegalArgumentException("Le total modifié ne peut pas être inférieur au montant déjà payé"
                    + (Montants.ouZero(ancienAchat.getAvanceUtilisee()).signum() > 0 ? " (avance utilisée comprise)." : "."));
        }
        verifierAvanceConservee(ancienAchat, achatModifie, fournisseurVerrouille, montantTotalCalcule);

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
        // Le numéro ne change jamais ; l'échéance suit le nouveau reste à payer.
        ancienAchat.setDateEcheance(echeances.apresChangement(ancienAchat.getDateAchat(), ancienAchat.getResteAPayer(),
                achatModifie.getDateEcheance(), ancienAchat.getDateEcheance(), boutiqueService.boutiqueCourante().getDelaiFournisseursJours()));

        ancienAchat.getLignes().clear();
        for (DetailAchat nouvelleLigne : achatModifie.getLignes()) {
            Produit produit = produits.get(nouvelleLigne.getProduit().getId());

            produit.setQuantite(produit.getQuantite() + nouvelleLigne.getQuantite());
            produit.setPrixAchat(nouvelleLigne.getPrixAchatUnitaire());
            produit.setPrixVente(nouvelleLigne.getPrixVenteUnitaire());
            produit.setPrixDefinis(true);

            nouvelleLigne.setAchat(ancienAchat);
            nouvelleLigne.setProduit(produit);
            ancienAchat.getLignes().add(nouvelleLigne);
        }

        achatRepository.save(ancienAchat);
        coutMoyen.recalculer(produits.values());
    }

    @Transactional
    public void reglerAchat(Long id, BigDecimal montant, BigDecimal montantVerseAttendu) {
        reglerAchat(id, montant, montantVerseAttendu, CompteTresorerie.CAISSE);
    }

    @Transactional
    public void reglerAchat(Long id, BigDecimal montant, BigDecimal montantVerseAttendu, CompteTresorerie comptePaiement) {
        reglerAchat(id, montant, montantVerseAttendu, comptePaiement, null);
    }

    /** Le versement peut aussi reporter l'échéance du reste ; un achat soldé garde son échéance en historique. */
    @Transactional
    public void reglerAchat(Long id, BigDecimal montant, BigDecimal montantVerseAttendu, CompteTresorerie comptePaiement,
                            LocalDate nouvelleEcheance) {
        if (comptePaiement == null) throw new IllegalArgumentException("Choisissez le compte utilisé pour le versement.");
        montant = Montants.positifOuNul(montant, "Le versement");
        if (montant.signum() == 0) {
            throw new IllegalArgumentException("Le versement doit être positif et comporter au maximum 2 décimales.");
        }
        if (montantVerseAttendu == null || montantVerseAttendu.signum() < 0) {
            throw new IllegalArgumentException("Rechargez le formulaire de règlement.");
        }
        stockLockRepository.verrouillerBoutiqueCourante();
        initialisation.verrouillerEtClorePourActivite();
        Achat achat = stockLockRepository.verrouillerAchat(id);
        BigDecimal dejaVerse = Montants.positifOuNul(Montants.ouZero(achat.getMontantVerse()), "Le montant déjà versé");
        BigDecimal total = Montants.positifOuNul(achat.getMontantTotal(), "Le total de l'achat");
        // Un trop-payé à la création est permis (avance chez le fournisseur) ; seule une avance supérieure au total est incohérente.
        if (Montants.ouZero(achat.getAvanceUtilisee()).compareTo(total) > 0) {
            throw new IllegalArgumentException("Les montants de cet achat sont invalides. Vérifiez la facture.");
        }
        if (montantVerseAttendu.compareTo(dejaVerse) != 0) throw new ReglementAchatObsoleteException();
        BigDecimal nouveauVerse = dejaVerse.add(montant);
        if (montant.compareTo(achat.getResteAPayer()) > 0) {
            throw new IllegalArgumentException("Le versement ne peut pas dépasser le reste à payer.");
        }
        achat.setMontantVerse(Montants.valider(nouveauVerse, "Le cumul des versements"));
        if (nouvelleEcheance != null && achat.getResteAPayer().signum() > 0) {
            EcheanceService.exigerValide(nouvelleEcheance, achat.getDateAchat());
            achat.setDateEcheance(nouvelleEcheance);
        }

        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Utilisateur actuel = utilisateurRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Utilisateur introuvable pour enregistrer le versement."));
        String motif = "Règlement Achat #" + id + " - Fournisseur: " + achat.getFournisseur().getNom();
        caisseService.enregistrerSortieAchat(montant, motif, achat, actuel, comptePaiement);
    }

    /**
     * Un achat qui porte une avance (trop-payé ou avance utilisée) garde son fournisseur, et son trop-payé ne peut disparaître
     * que si l'avance correspondante n'a pas déjà été utilisée ailleurs.
     */
    private void verifierAvanceConservee(Achat ancien, Achat modifie, Fournisseur fournisseur, BigDecimal nouveauTotal) {
        BigDecimal surplusPerdu = ancien.getSurplus().subtract(Montants.ouZero(ancien.getMontantVerse()).subtract(nouveauTotal).max(Montants.ZERO));
        boolean porteAvance = ancien.getSurplus().signum() > 0 || Montants.ouZero(ancien.getAvanceUtilisee()).signum() > 0;
        if (porteAvance && modifie.getFournisseur() != null && fournisseur != null
                && !modifie.getFournisseur().getId().equals(fournisseur.getId())) {
            throw new IllegalArgumentException("Cet achat porte une avance (trop-payé ou avance utilisée) : son fournisseur ne peut plus être changé.");
        }
        if (surplusPerdu.signum() > 0 && fournisseur != null
                && soldes.avanceDisponibleVerrouillee(fournisseur).compareTo(surplusPerdu) < 0) {
            throw new IllegalArgumentException("Le trop-payé de cet achat est déjà en partie utilisé comme avance : le total ne peut pas être augmenté.");
        }
    }

    public List<Achat> listerTous() {
        return achatRepository.findByBoutiqueIdOrderByDateAchatDesc(boutiqueService.idBoutiqueCourante());
    }

    /** Achats échus non soldés : reste à payer et échéance antérieure au jour. */
    public List<Achat> listerEchusNonSoldes() {
        return achatRepository.findEchusNonSoldes(boutiqueService.idBoutiqueCourante(), echeances.aujourdHui());
    }

    /** Change seulement l'échéance d'un achat qui a encore un reste à payer. */
    @Transactional
    public void modifierEcheance(Long id, LocalDate echeance) {
        if (echeance == null) throw new IllegalArgumentException("Choisissez la nouvelle échéance.");
        stockLockRepository.verrouillerBoutiqueCourante();
        Achat achat = stockLockRepository.verrouillerAchat(id);
        if (achat.getResteAPayer().signum() <= 0) {
            throw new IllegalArgumentException("Cet achat est soldé : son échéance n'est plus modifiable.");
        }
        EcheanceService.exigerValide(echeance, achat.getDateAchat());
        achat.setDateEcheance(echeance);
    }

    /** Vrai si l'achat de la boutique courante a au moins un retour d'achat (il est alors figé). Lecture simple, sans verrou. */
    @Transactional(readOnly = true)
    public boolean aDesRetours(Long id) {
        return retourRepository.countByAchatId(id) > 0;
    }

    public Achat trouverParId(Long id) {
        return achatRepository.findByIdAndBoutiqueId(id, boutiqueService.idBoutiqueCourante()).orElse(null);
    }

    private void validerAchat(Achat achat) {
        if (achat.getFournisseur() == null || achat.getFournisseur().getId() == null) {
            throw new RuntimeException("Veuillez selectionner un fournisseur.");
        }
        if (achat.getLignes() == null || achat.getLignes().isEmpty()) {
            throw new RuntimeException("Impossible d'enregistrer un achat sans produit.");
        }
        achat.setFournisseur(fournisseurRepository.findByIdAndBoutiqueId(achat.getFournisseur().getId(), boutiqueService.idBoutiqueCourante())
                .orElseThrow(() -> new IllegalArgumentException("Fournisseur introuvable")));

        Map<Long, BigDecimal> tarifsVente = new HashMap<>();
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
            if (ligne.getPrixVenteUnitaire() == null) {
                throw new IllegalArgumentException("Le prix de vente unitaire est obligatoire pour chaque ligne d'achat.");
            }
            ligne.setPrixVenteUnitaire(Montants.positifOuNul(ligne.getPrixVenteUnitaire(), "Le prix de vente unitaire"));
            BigDecimal tarifPrecedent = tarifsVente.putIfAbsent(ligne.getProduit().getId(), ligne.getPrixVenteUnitaire());
            if (tarifPrecedent != null && tarifPrecedent.compareTo(ligne.getPrixVenteUnitaire()) != 0) {
                throw new IllegalArgumentException("Les lignes d'un même produit doivent avoir le même prix de vente.");
            }
        }
    }

    /** Un service n'a pas de stock : il ne peut jamais entrer par un achat. */
    private void refuserServices(Map<Long, Produit> produits) {
        for (Produit produit : produits.values()) {
            if (produit.estService()) {
                throw new IllegalArgumentException("« " + produit.getNom() + " » est un service : il se vend sans stock et ne peut pas figurer dans un achat.");
            }
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
        return Montants.positifOuNul(Objects.requireNonNullElse(montantVerse, montantTotal), "Le montant versé");
    }
}
