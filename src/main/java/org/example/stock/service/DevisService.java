package org.example.stock.service;

import org.example.stock.enums.EtatDevis;
import org.example.stock.enums.StatutDevis;
import org.example.stock.form.DevisForm;
import org.example.stock.model.Client;
import org.example.stock.model.DetailDevis;
import org.example.stock.model.DetailVente;
import org.example.stock.model.Devis;
import org.example.stock.model.Montants;
import org.example.stock.model.Produit;
import org.example.stock.model.Vente;
import org.example.stock.repository.ClientRepository;
import org.example.stock.repository.DevisRepository;
import org.example.stock.repository.ProduitRepository;
import org.example.stock.repository.StockLockRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Devis : une proposition de prix sans aucun effet sur le stock, la trésorerie, les créances, les avances,
 * le tableau de bord ni le coût moyen. Seule la transformation en vente (dans la transaction de
 * {@link VenteService#effectuerVente(Vente, Long)}) produit des effets, ceux de la vente.
 *
 * <p>Le jour de référence est celui de {@link EcheanceService#aujourdHui()}, comme pour les échéances.</p>
 */
@Service
public class DevisService {
    /** Validité proposée à la création : date du jour + 30 jours. */
    public static final int VALIDITE_PAR_DEFAUT_JOURS = 30;
    public static final int NOTE_LONGUEUR_MAXIMALE = 255;
    private static final DateTimeFormatter AFFICHAGE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final DevisRepository devis;
    private final ClientRepository clients;
    private final ProduitRepository produits;
    private final UtilisateurRepository utilisateurs;
    private final StockLockRepository verrous;
    private final BoutiqueService boutiques;
    private final NumerotationFactureService numerotation;
    private final EcheanceService echeances;

    public DevisService(DevisRepository devis, ClientRepository clients, ProduitRepository produits,
                        UtilisateurRepository utilisateurs, StockLockRepository verrous, BoutiqueService boutiques,
                        NumerotationFactureService numerotation, EcheanceService echeances) {
        this.devis = devis;
        this.clients = clients;
        this.produits = produits;
        this.utilisateurs = utilisateurs;
        this.verrous = verrous;
        this.boutiques = boutiques;
        this.numerotation = numerotation;
        this.echeances = echeances;
    }

    public LocalDate validiteProposee() {
        return echeances.aujourdHui().plusDays(VALIDITE_PAR_DEFAUT_JOURS);
    }

    // ----- Création -----

    /**
     * Crée un devis EN_COURS. Le numéro {@code FD-} est tiré du compteur de la boutique sous le verrou de la ligne
     * boutique, dans cette transaction : une création refusée rend son numéro. La création ne ferme pas le démarrage
     * de la boutique et ne touche ni produit, ni client, ni caisse.
     */
    @Transactional
    public Devis creer(DevisForm form) {
        if (form.getLignes() == null || form.getLignes().isEmpty()) {
            throw new IllegalArgumentException("Ajoutez au moins une ligne au devis.");
        }
        // Ordre global des verrous : la boutique d'abord (elle porte aussi le compteur FD-), avant toute lecture ou insertion.
        verrous.verrouillerBoutiqueCourante();
        LocalDate jour = echeances.aujourdHui();
        LocalDate validite = form.getDateValidite() != null ? form.getDateValidite() : jour.plusDays(VALIDITE_PAR_DEFAUT_JOURS);
        exigerValiditeCreation(validite, jour);
        String note = normaliserNote(form.getNote());
        Long boutiqueId = boutiques.idBoutiqueCourante();

        Client client = null;
        if (form.getClient() != null) {
            Long clientId = form.getClient().getId();
            if (clientId == null) throw new IllegalArgumentException("Client invalide.");
            client = clients.findByIdAndBoutiqueId(clientId, boutiqueId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Client introuvable"));
        }

        Devis nouveau = new Devis();
        Map<Long, Produit> connus = new HashMap<>();
        BigDecimal total = Montants.ZERO;
        for (DevisForm.Ligne saisie : form.getLignes()) {
            if (saisie == null || saisie.getProduit() == null || saisie.getProduit().getId() == null) {
                throw new IllegalArgumentException("Chaque ligne du devis doit contenir un produit.");
            }
            if (saisie.getQuantite() == null || saisie.getQuantite() <= 0) {
                throw new IllegalArgumentException("La quantité d'une ligne du devis doit être un entier supérieur à 0.");
            }
            Produit produit = connus.get(saisie.getProduit().getId());
            if (produit == null) {
                produit = produits.findByIdAndBoutiqueId(saisie.getProduit().getId(), boutiqueId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Produit introuvable"));
                connus.put(produit.getId(), produit);
            }
            if (!produit.isPrixDefinis()) {
                throw new IllegalArgumentException("Les prix de " + produit.getNom() + " ne sont pas encore définis : "
                        + "définissez-les lors d'un achat avant de proposer ce produit dans un devis.");
            }
            if (saisie.getPrixUnitaire() == null) {
                throw new IllegalArgumentException("Saisissez le prix unitaire de " + produit.getNom() + ".");
            }
            DetailDevis ligne = new DetailDevis();
            ligne.setDevis(nouveau);
            ligne.setProduit(produit);
            ligne.setQuantite(saisie.getQuantite());
            ligne.setPrixUnitaire(Montants.positifOuNul(saisie.getPrixUnitaire(), "Le prix unitaire de " + produit.getNom()));
            nouveau.getLignes().add(ligne);
            total = Montants.valider(total.add(ligne.getPrixUnitaire().multiply(BigDecimal.valueOf(ligne.getQuantite()))), "Le total du devis");
        }

        // Le compteur est lu sous le verrou de la boutique, déjà tenu.
        nouveau.setNumero(numerotation.attribuerNumeroDevis());
        nouveau.setBoutique(boutiques.boutiqueCourante());
        nouveau.setClient(client);
        nouveau.setUtilisateur(utilisateurActuel());
        nouveau.setDateDevis(echeances.maintenant());
        nouveau.setDateValidite(validite);
        nouveau.setMontantTotal(Montants.valider(total, "Le total du devis"));
        nouveau.setStatut(StatutDevis.EN_COURS);
        nouveau.setNote(note);
        return devis.save(nouveau);
    }

    // ----- Consultation -----

    /** Devis de la boutique courante ; un devis d'une autre boutique est introuvable (404). */
    public Devis trouver(Long id) {
        return devis.findByIdAndBoutiqueId(id, boutiques.idBoutiqueCourante())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Devis introuvable"));
    }

    /** Liste de la boutique, du plus récent au plus ancien, filtrée par état affiché ({@code null} : tous). */
    public List<Devis> lister(EtatDevis etat) {
        return lister(etat, Pageable.unpaged());
    }

    /** Comme {@link #lister(EtatDevis)} mais lit au plus {@code max + 1} devis : plus de {@code max} résultats signale un dépassement. */
    public List<Devis> lister(EtatDevis etat, int max) {
        return lister(etat, PageRequest.of(0, max + 1));
    }

    private List<Devis> lister(EtatDevis etat, Pageable page) {
        Long boutiqueId = boutiques.idBoutiqueCourante();
        LocalDate jour = echeances.aujourdHui();
        if (etat == null) return devis.findTous(boutiqueId, page);
        return switch (etat) {
            case EN_COURS -> devis.findEnCoursValables(boutiqueId, jour, page);
            case EXPIRE -> devis.findExpires(boutiqueId, jour, page);
            case TRANSFORME -> devis.findParStatut(boutiqueId, StatutDevis.TRANSFORME, page);
            case ANNULE -> devis.findParStatut(boutiqueId, StatutDevis.ANNULE, page);
        };
    }

    // ----- Prolongation et annulation -----

    /** Nouvelle date de validité d'un devis en cours (expiré compris) : au jour même ou après, et après la validité actuelle. */
    @Transactional
    public void prolonger(Long id, LocalDate nouvelleDate) {
        if (nouvelleDate == null) throw new IllegalArgumentException("Choisissez la nouvelle date de validité.");
        verrous.verrouillerBoutiqueCourante();
        Devis verrouille = verrous.verrouillerDevis(id);
        exigerEnCours(verrouille);
        LocalDate jour = echeances.aujourdHui();
        exigerValiditeCreation(nouvelleDate, jour);
        if (!nouvelleDate.isAfter(verrouille.getDateValidite())) {
            throw new IllegalArgumentException("La nouvelle date de validité doit être postérieure à la validité actuelle ("
                    + AFFICHAGE.format(verrouille.getDateValidite()) + ").");
        }
        verrouille.setDateValidite(nouvelleDate);
    }

    /** Annule un devis en cours (expiré compris). Un devis transformé ne s'annule pas : la vente existe. */
    @Transactional
    public void annuler(Long id) {
        verrous.verrouillerBoutiqueCourante();
        Devis verrouille = verrous.verrouillerDevis(id);
        exigerEnCours(verrouille);
        verrouille.setStatut(StatutDevis.ANNULE);
    }

    // ----- Transformation en vente -----

    /** Raison pour laquelle le devis ne peut pas devenir une vente, ou {@code null} s'il le peut. */
    public String raisonRefusTransformation(Devis d) {
        return switch (d.getStatut()) {
            case TRANSFORME -> "Le devis " + d.getNumero() + " a déjà été transformé en vente.";
            case ANNULE -> "Le devis " + d.getNumero() + " est annulé : il ne peut plus être transformé en vente.";
            case EN_COURS -> d.estExpire(echeances.aujourdHui())
                    ? "Le devis " + d.getNumero() + " a expiré le " + AFFICHAGE.format(d.getDateValidite())
                            + " : prolongez sa validité avant de le transformer en vente."
                    : null;
        };
    }

    /**
     * À appeler dans la transaction de la vente, après le verrou de la boutique et avant ceux du client et des
     * produits. Verrouille le devis (404 s'il est d'une autre boutique), vérifie qu'il est toujours en cours et non
     * expiré, puis que les lignes envoyées par le formulaire sont exactement les siennes : mêmes produits, mêmes
     * quantités, ni retrait ni ajout.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Devis verrouillerPourTransformation(Long devisId, List<DetailVente> lignesSaisies) {
        Devis verrouille = verrous.verrouillerDevis(devisId);
        String refus = raisonRefusTransformation(verrouille);
        if (refus != null) throw new IllegalArgumentException(refus);
        List<String> attendues = new ArrayList<>();
        for (DetailDevis ligne : verrouille.getLignes()) attendues.add(ligne.getProduit().getId() + "x" + ligne.getQuantite());
        List<String> saisies = new ArrayList<>();
        for (DetailVente ligne : lignesSaisies) saisies.add(ligne.getProduit().getId() + "x" + ligne.getQuantite());
        attendues.sort(null);
        saisies.sort(null);
        if (!attendues.equals(saisies)) {
            throw new IllegalArgumentException("Une vente issue d'un devis doit reprendre exactement ses lignes et ses quantités. "
                    + "Pour changer le contenu, annulez le devis " + verrouille.getNumero() + " et établissez-en un autre.");
        }
        return verrouille;
    }

    /** Lignes de la vente reprises du devis : produits, quantités et prix du devis, jamais ceux du navigateur ni de la fiche produit. */
    public List<DetailVente> lignesDeVente(Devis d) {
        List<DetailVente> lignes = new ArrayList<>();
        for (DetailDevis source : d.getLignes()) {
            DetailVente ligne = new DetailVente();
            ligne.setProduit(source.getProduit());
            ligne.setQuantite(source.getQuantite());
            ligne.setPrixUnitaire(source.getPrixUnitaire());
            lignes.add(ligne);
        }
        return lignes;
    }

    /** Le devis passe à TRANSFORME avec sa vente (unique) dans la transaction de la vente. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void marquerTransforme(Devis d, Vente vente) {
        d.setStatut(StatutDevis.TRANSFORME);
        d.setVente(vente);
        devis.saveAndFlush(d);
    }

    // ----- Contrôles -----

    private void exigerEnCours(Devis d) {
        if (d.getStatut() == StatutDevis.TRANSFORME) {
            throw new IllegalArgumentException("Le devis " + d.getNumero() + " est transformé en vente : il ne peut plus être modifié.");
        }
        if (d.getStatut() == StatutDevis.ANNULE) {
            throw new IllegalArgumentException("Le devis " + d.getNumero() + " est annulé : il ne peut plus être modifié.");
        }
    }

    /** Une validité ne précède jamais le jour du devis. */
    private static void exigerValiditeCreation(LocalDate validite, LocalDate jour) {
        if (validite.getYear() > 9999) throw new IllegalArgumentException("La date de validité saisie est invalide.");
        if (validite.isBefore(jour)) {
            throw new IllegalArgumentException("La date de validité ne peut pas être antérieure à la date du devis ("
                    + AFFICHAGE.format(jour) + ").");
        }
    }

    private static String normaliserNote(String note) {
        if (note == null || note.isBlank()) return null;
        String propre = note.strip();
        if (propre.length() > NOTE_LONGUEUR_MAXIMALE) {
            throw new IllegalArgumentException("La note accepte au maximum " + NOTE_LONGUEUR_MAXIMALE + " caractères.");
        }
        if (propre.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("La note contient un caractère non autorisé.");
        }
        return propre;
    }

    private org.example.stock.model.Utilisateur utilisateurActuel() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) return null;
        return utilisateurs.findByEmail(authentication.getName()).orElse(null);
    }
}
