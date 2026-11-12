package org.example.stock.service;

import org.example.stock.model.Client;
import org.example.stock.model.Devis;
import org.example.stock.model.DetailVente;
import org.example.stock.model.Produit;
import org.example.stock.model.Utilisateur;
import org.example.stock.model.Vente;
import org.example.stock.model.Montants;
import org.example.stock.enums.NatureProduit;
import org.example.stock.repository.ProduitRepository;
import org.example.stock.repository.StockLockRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.example.stock.repository.VenteRepository;
import org.example.stock.repository.ClientRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
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
    @Autowired private BoutiqueService boutiqueService;
    @Autowired private InitialisationBoutiqueService initialisation;
    @Autowired private ProduitRepository produitRepository;
    @Autowired private CoutMoyenService coutMoyen;
    @Autowired private NumerotationFactureService numerotation;
    @Autowired private EcheanceService echeances;
    @Autowired private SoldePartenaireService soldes;
    @Autowired private DevisService devisService;
    @Autowired private ComptesTresorerieService comptes;

    @Transactional
    public Vente effectuerVente(Vente vente) {
        return effectuerVente(vente, null);
    }

    /**
     * Vente, éventuellement issue d'un devis : le devis est alors verrouillé, vérifié (en cours, non expiré, mêmes lignes
     * et quantités) puis passé à TRANSFORME dans cette transaction ; les prix des lignes sont ceux du devis.
     * Un refus (stock insuffisant, devis périmé…) annule tout et laisse le devis en cours.
     */
    @Transactional
    public Vente effectuerVente(Vente vente, Long devisId) {
        if (vente.getComptePaiement() == null || vente.getComptePaiement().isBlank()) throw new IllegalArgumentException("Choisissez le compte qui reçoit le paiement.");
        // Compte de la boutique, actif et autorisé au vendeur : contrôlé même à crédit ; sinon 400 et rien n'est enregistré.
        Long compteId = comptes.resoudreId(vente.getComptePaiement());
        if (vente.getId() != null) throw new IllegalArgumentException("Une création ne peut pas contenir d'identifiant");
        if (vente.getLignes() == null || vente.getLignes().isEmpty()) {
            throw new RuntimeException("Impossible d'enregistrer une vente vide.");
        }
        // Ordre global des verrous : la boutique d'abord (docs/lot-2.md, « Ordre des verrous »), puis le devis, le client et
        // ses factures, puis les produits.
        stockLockRepository.verrouillerBoutiqueCourante();
        if (vente.getClient() != null) {
            if (vente.getClient().getId() == null) throw new IllegalArgumentException("Client invalide");
            vente.setClient(clientRepository.findByIdAndBoutiqueId(vente.getClient().getId(), boutiqueService.idBoutiqueCourante())
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

        vente.setNumero(NumerotationFactureService.validerSaisie(vente.getNumero()));
        initialisation.verrouillerEtClorePourActivite();
        String numero = numerotation.attribuerNumeroVente(vente.getNumero());
        // Devis verrouillé après la boutique, avant le client et les produits ; ses lignes remplacent celles du navigateur.
        Devis devis = devisId == null ? null : devisService.verrouillerPourTransformation(devisId, vente.getLignes());
        if (devis != null) vente.setLignes(devisService.lignesDeVente(devis));
        // Fiche du client verrouillée ensuite, avant les produits : deux ventes du même client ne consomment jamais deux fois la même avance.
        Client client = vente.getClient() == null ? null : stockLockRepository.verrouillerClient(vente.getClient().getId());
        Map<Long, Produit> produits = chargerProduits(vente.getLignes().stream()
                .map(ligne -> ligne.getProduit().getId()).toList());
        vente.setBoutique(boutiqueService.boutiqueCourante());
        BigDecimal montantTotalCalcule = Montants.ZERO;

        for (Produit produit : produits.values()) {
            if (!produit.isPrixDefinis()) {
                throw new IllegalArgumentException("Les prix de " + produit.getNom() + " doivent être définis lors d'un achat ou du stock initial avant toute vente.");
            }
        }

        for (DetailVente detail : vente.getLignes()) {
            Produit produit = produits.get(detail.getProduit().getId());

            // Un service se vend sans stock : ni contrôle, ni déduction de quantité.
            if (!produit.estService()) {
                if (produit.getQuantite() < detail.getQuantite()) {
                    throw new RuntimeException("Stock insuffisant pour " + produit.getNom());
                }

                produit.setQuantite(produit.getQuantite() - detail.getQuantite());
            }

            detail.setPrixUnitaire(devis != null ? Montants.positifOuNul(detail.getPrixUnitaire(), "Le prix du devis")
                    : Montants.positifOuNul(produit.getPrixVente(), "Le prix de vente"));
            detail.setCoutUnitaire(coutMoyen.coutUnitaireVente(produit));
            detail.setVente(vente);
            detail.setProduit(produit);
            montantTotalCalcule = montantTotalCalcule.add(detail.getPrixUnitaire().multiply(BigDecimal.valueOf(detail.getQuantite())));
        }

        vente.setMontantTotal(Montants.valider(montantTotalCalcule, "Le total de la vente"));
        // Le trop-payé n'est admis qu'avec un client : le surplus devient son avance. Toute la somme versée entre en trésorerie.
        BigDecimal verse = normaliserMontantVerse(vente.getMontantVerse(), montantTotalCalcule, client != null);
        vente.setMontantVerse(verse);
        vente.setAvanceUtilisee(avanceAUtiliser(client, montantTotalCalcule, verse));
        vente.setDateVente(LocalDateTime.now());
        vente.setDateEcheance(echeances.pourCreation(vente.getDateVente(), vente.getResteAPayer(), vente.getDateEcheance(),
                vente.getBoutique().getDelaiClientsJours()));
        vente.setNumero(numero);
        // Le vendeur est le compte connecté, toujours lu côté serveur : jamais une valeur du formulaire.
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Utilisateur actuel = utilisateurRepository.findByEmail(email).orElse(null);
        vente.setVendeur(actuel);
        Vente venteEnregistree = venteRepository.save(vente);
        if (devis != null) devisService.marquerTransforme(devis, venteEnregistree);

        String motif = "Vente #" + venteEnregistree.getId() + " - Client: " +
                (venteEnregistree.getClient() != null ? venteEnregistree.getClient().getNom() : "Passant");

        caisseService.enregistrerEntreeVente(venteEnregistree.getMontantVerse(), motif, venteEnregistree, actuel, compteId);

        return venteEnregistree;
    }

    /**
     * Verrouille uniquement les articles en stock. Les services de la boutique sont lus sans verrou :
     * leur nature ne change jamais après création et leur quantité n'est pas utilisée.
     */
    private Map<Long, Produit> chargerProduits(List<Long> ids) {
        Map<Long, Produit> services = new HashMap<>();
        produitRepository.findAllByIdInAndBoutiqueIdAndNature(ids, boutiqueService.idBoutiqueCourante(), NatureProduit.SERVICE)
                .forEach(service -> services.put(service.getId(), service));
        Map<Long, Produit> produits = new HashMap<>(stockLockRepository.verrouillerProduits(
                ids.stream().filter(id -> !services.containsKey(id)).toList()));
        produits.putAll(services);
        return produits;
    }

    public List<Vente> listerToutes() {
        return venteRepository.findByBoutiqueIdOrderByDateVenteDesc(boutiqueService.idBoutiqueCourante());
    }

    /**
     * Ventes de la boutique courante selon les filtres de l'écran /ventes : c'est aussi la source des exports Excel et PDF.
     * Tri : les plus récentes d'abord ; pour « échues non soldées », échéance la plus ancienne d'abord.
     */
    public List<Vente> rechercher(FiltreVentes filtre) {
        return venteRepository.findAll(specification(filtre), tri(filtre));
    }

    /** Comme {@link #rechercher(FiltreVentes)} mais lit au plus {@code max + 1} lignes : plus de {@code max} résultats signale un dépassement. */
    public List<Vente> rechercher(FiltreVentes filtre, int max) {
        return venteRepository.findAll(specification(filtre), PageRequest.of(0, max + 1, tri(filtre))).getContent();
    }

    private static Sort tri(FiltreVentes filtre) {
        return filtre.echues() ? Sort.by(Sort.Order.asc("dateEcheance"), Sort.Order.asc("id"))
                : Sort.by(Sort.Order.desc("dateVente"), Sort.Order.desc("id"));
    }

    private Specification<Vente> specification(FiltreVentes filtre) {
        Long boutiqueId = boutiqueService.idBoutiqueCourante();
        LocalDate aujourdHui = echeances.aujourdHui();
        return (vente, requete, cb) -> {
            if (requete.getResultType() != Long.class && requete.getResultType() != long.class) {
                vente.fetch("vendeur", JoinType.LEFT);
                vente.fetch("client", JoinType.LEFT);
            }
            List<Predicate> conditions = new ArrayList<>();
            conditions.add(cb.equal(vente.get("boutique").get("id"), boutiqueId));
            if (filtre.vendeurId() != null) conditions.add(cb.equal(vente.get("vendeur").get("id"), filtre.vendeurId()));
            if (filtre.du() != null) conditions.add(cb.greaterThanOrEqualTo(vente.<LocalDateTime>get("dateVente"), filtre.du().atStartOfDay()));
            if (filtre.au() != null) conditions.add(cb.lessThan(vente.<LocalDateTime>get("dateVente"), filtre.au().plusDays(1).atStartOfDay()));
            if (filtre.echues()) {
                conditions.add(cb.lessThan(vente.<LocalDate>get("dateEcheance"), aujourdHui));
                var reste = cb.diff(cb.diff(cb.diff(cb.coalesce(vente.<BigDecimal>get("montantTotal"), BigDecimal.ZERO),
                                cb.coalesce(vente.<BigDecimal>get("montantRetours"), BigDecimal.ZERO)),
                        cb.coalesce(vente.<BigDecimal>get("montantVerse"), BigDecimal.ZERO)),
                        cb.coalesce(vente.<BigDecimal>get("avanceUtilisee"), BigDecimal.ZERO));
                conditions.add(cb.greaterThan(reste, BigDecimal.ZERO));
            }
            return cb.and(conditions.toArray(Predicate[]::new));
        };
    }

    /** Comptes de la boutique courante, désactivés compris (ils ont pu saisir des ventes), proposés par le filtre de vendeur. */
    public List<Utilisateur> listerVendeursPossibles() {
        return utilisateurRepository.findAllByBoutiqueIdOrderByNomAscIdAsc(boutiqueService.idBoutiqueCourante());
    }

    /** Ventes saisies par un vendeur ; l'identifiant doit avoir été validé comme compte de la boutique. */
    public List<Vente> listerDuVendeur(Long vendeurId) {
        return venteRepository.findByBoutiqueIdAndVendeurIdOrderByDateVenteDesc(boutiqueService.idBoutiqueCourante(), vendeurId);
    }

    /** Ventes échues non soldées : reste à payer et échéance antérieure au jour. */
    public List<Vente> listerEchuesNonSoldees() {
        return venteRepository.findEchuesNonSoldees(boutiqueService.idBoutiqueCourante(), echeances.aujourdHui());
    }

    public List<Vente> listerEchuesNonSoldeesDuVendeur(Long vendeurId) {
        return venteRepository.findEchuesNonSoldeesDuVendeur(boutiqueService.idBoutiqueCourante(), vendeurId, echeances.aujourdHui());
    }

    public Vente trouverParId(Long id) {
        return venteRepository.findByIdAndBoutiqueId(id, boutiqueService.idBoutiqueCourante()).orElse(null);
    }

    @Transactional
    public Long reglerVente(Long id, BigDecimal montant, BigDecimal montantVerseAttendu) {
        return reglerVente(id, montant, montantVerseAttendu, "ESPECES");
    }

    @Transactional
    public Long reglerVente(Long id, BigDecimal montant, BigDecimal montantVerseAttendu, String comptePaiement) {
        return reglerVente(id, montant, montantVerseAttendu, comptePaiement, null);
    }

    /**
     * Le versement peut aussi reporter l'échéance du reste ; une vente soldée garde son échéance en historique.
     * Retourne l'identifiant du mouvement de caisse du règlement, qui désigne son reçu.
     */
    @Transactional
    public Long reglerVente(Long id, BigDecimal montant, BigDecimal montantVerseAttendu, String comptePaiement,
                            LocalDate nouvelleEcheance) {
        if (comptePaiement == null || comptePaiement.isBlank()) throw new IllegalArgumentException("Choisissez le compte qui reçoit le versement.");
        Long compteId = comptes.resoudreId(comptePaiement);
        montant = Montants.positifOuNul(montant, "Le versement");
        if (montant.signum() == 0) throw new IllegalArgumentException("Le versement doit être supérieur à zéro.");
        montantVerseAttendu = Montants.positifOuNul(montantVerseAttendu, "Le montant déjà versé attendu");
        stockLockRepository.verrouillerBoutiqueCourante();
        initialisation.verrouillerEtClorePourActivite();
        Vente vente = stockLockRepository.verrouillerVente(id);
        BigDecimal dejaVerse = Montants.positifOuNul(Montants.ouZero(vente.getMontantVerse()), "Le montant déjà versé");
        BigDecimal total = Montants.positifOuNul(vente.getMontantTotal(), "Le total de la vente");
        // Un trop-payé à la création est permis (avance du client) ; seule une avance supérieure au total est incohérente.
        if (Montants.ouZero(vente.getAvanceUtilisee()).compareTo(total) > 0) {
            throw new IllegalArgumentException("Les montants de cette vente sont invalides. Vérifiez la facture.");
        }
        if (montantVerseAttendu.compareTo(dejaVerse) != 0) throw new ReglementVenteObsoleteException();
        BigDecimal nouveauVerse = dejaVerse.add(montant);
        if (montant.compareTo(vente.getResteAPayer()) > 0) {
            throw new IllegalArgumentException("Le versement ne peut pas dépasser le reste à payer.");
        }
        vente.setMontantVerse(Montants.valider(nouveauVerse, "Le cumul des versements"));
        if (nouvelleEcheance != null && vente.getResteAPayer().signum() > 0) {
            EcheanceService.exigerValide(nouvelleEcheance, vente.getDateVente());
            vente.setDateEcheance(nouvelleEcheance);
        }
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Utilisateur actuel = utilisateurRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Utilisateur introuvable pour enregistrer le versement."));
        String motif = "Règlement Vente #" + id + " - Client: "
                + (vente.getClient() != null ? vente.getClient().getNom() : "Passant");
        return caisseService.enregistrerEntreeReglementVente(montant, motif, vente, actuel, compteId).getId();
    }

    /** Change seulement l'échéance d'une vente qui a encore un reste à payer. */
    @Transactional
    public void modifierEcheance(Long id, LocalDate echeance) {
        if (echeance == null) throw new IllegalArgumentException("Choisissez la nouvelle échéance.");
        stockLockRepository.verrouillerBoutiqueCourante();
        Vente vente = stockLockRepository.verrouillerVente(id);
        if (vente.getResteAPayer().signum() <= 0) {
            throw new IllegalArgumentException("Cette vente est soldée : son échéance n'est plus modifiable.");
        }
        EcheanceService.exigerValide(echeance, vente.getDateVente());
        vente.setDateEcheance(echeance);
    }

    private BigDecimal normaliserMontantVerse(BigDecimal montantVerse, BigDecimal montantTotal, boolean avecClient) {
        BigDecimal montant = Montants.positifOuNul(Objects.requireNonNullElse(montantVerse, montantTotal), "Le montant versé");
        if (!avecClient && montant.compareTo(montantTotal) > 0) {
            throw new RuntimeException("Le montant verse ne peut pas depasser le total de la vente.");
        }

        return montant;
    }

    /** min(avance disponible du client, total − versé), sans mouvement de trésorerie : l'argent est déjà entré. */
    private BigDecimal avanceAUtiliser(Client client, BigDecimal total, BigDecimal verse) {
        if (client == null || verse.compareTo(total) >= 0) return Montants.ZERO;
        return Montants.valider(soldes.avanceDisponibleVerrouillee(client).min(total.subtract(verse)), "L'avance utilisée");
    }
}
