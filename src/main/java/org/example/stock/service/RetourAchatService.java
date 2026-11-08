package org.example.stock.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.example.stock.enums.CompteTresorerie;
import org.example.stock.enums.ModeRemboursement;
import org.example.stock.enums.TypeRetour;
import org.example.stock.form.RetourAchatForm;
import org.example.stock.model.Achat;
import org.example.stock.model.DetailAchat;
import org.example.stock.model.DetailRetour;
import org.example.stock.model.Fournisseur;
import org.example.stock.model.Montants;
import org.example.stock.model.Produit;
import org.example.stock.model.Retour;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.AchatRepository;
import org.example.stock.repository.RetourRepository;
import org.example.stock.repository.StockLockRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Retour d'achat : la boutique rend au fournisseur de la marchandise qu'il lui a livrée. Réservé au gérant. C'est le
 * symétrique du {@link RetourVenteService}.
 *
 * <p>Pour un retour de montant R sur un achat dont le reste à payer juste avant est D, la partie imputée sur la dette
 * est {@code min(R, D)} (elle réduit ce que la boutique doit au fournisseur, sans argent) et l'excédent
 * {@code E = R − min(R, D)} (marchandise déjà payée) est soit rendu en argent par le fournisseur (mouvement de
 * trésorerie entrant), soit gardé en avoir chez lui (avance de la boutique auprès de ce fournisseur). Le stock diminue de
 * la quantité retournée ; le coût moyen est recalculé au prix de la ligne d'achat ({@link CoutMoyenService}).</p>
 *
 * <p><b>Ordre des verrous</b> : ligne de la <b>boutique</b> en premier (c'est aussi elle qui porte le numéro {@code RA-}),
 * puis l'<b>achat</b>, la fiche du <b>fournisseur</b>, les <b>produits</b> (par identifiant croissant) et les comptes de
 * trésorerie (lecture) pour l'argent rendu. C'est l'ordre commun à toutes les écritures métier, décrit dans
 * {@code docs/lot-2.md}, « Ordre des verrous ».</p>
 */
@Service
public class RetourAchatService {
    public static final int MOTIF_LONGUEUR_MAXIMALE = 255;

    private final RetourRepository retours;
    private final AchatRepository achats;
    private final StockLockRepository verrous;
    private final NumerotationFactureService numerotation;
    private final InitialisationBoutiqueService initialisation;
    private final CoutMoyenService coutMoyen;
    private final CaisseService caisse;
    private final BoutiqueService boutiques;
    private final UtilisateurRepository utilisateurs;
    private final EntityManager em;

    public RetourAchatService(RetourRepository retours, AchatRepository achats, StockLockRepository verrous,
                              NumerotationFactureService numerotation, InitialisationBoutiqueService initialisation,
                              CoutMoyenService coutMoyen, CaisseService caisse, BoutiqueService boutiques,
                              UtilisateurRepository utilisateurs, EntityManager em) {
        this.retours = retours;
        this.achats = achats;
        this.verrous = verrous;
        this.numerotation = numerotation;
        this.initialisation = initialisation;
        this.coutMoyen = coutMoyen;
        this.caisse = caisse;
        this.boutiques = boutiques;
        this.utilisateurs = utilisateurs;
        this.em = em;
    }

    /**
     * Une ligne de l'achat telle que le formulaire la montre. Le maximum est le plus petit de ce qui reste à retourner
     * sur la ligne et du stock actuel du produit.
     */
    public record LigneRetournable(Long detailAchatId, String produit, String unite, int quantiteAchetee, int dejaRetournee,
                                   int resteARetourner, long stockActuel, int maximum, BigDecimal prixUnitaire) {}

    /** Données du formulaire : l'achat, ses lignes et le nombre de retours déjà enregistrés. */
    public record Formulaire(Achat achat, List<LigneRetournable> lignes, long retoursConnus, BigDecimal resteAPayer, boolean avecFournisseur) {
        public boolean peutRetourner() {
            return lignes.stream().anyMatch(ligne -> ligne.maximum() > 0);
        }
    }

    // ----- Consultation -----

    /** Lit l'achat de la boutique courante (404 sinon) avec ce qui peut encore être retourné. */
    @Transactional(readOnly = true)
    public Formulaire preparer(Long achatId) {
        exigerGerant();
        Achat achat = achats.findByIdAndBoutiqueId(achatId, boutiques.idBoutiqueCourante())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Achat introuvable"));
        Map<Long, Long> deja = new HashMap<>();
        for (Object[] ligne : retours.quantitesRetourneesAchat(achatId)) deja.put(((Number) ligne[0]).longValue(), ((Number) ligne[1]).longValue());
        List<LigneRetournable> lignes = new ArrayList<>();
        for (DetailAchat detail : achat.getLignes()) {
            int achetee = detail.getQuantite() == null ? 0 : detail.getQuantite();
            int retournee = deja.getOrDefault(detail.getId(), 0L).intValue();
            Produit produit = detail.getProduit();
            long stock = produit.getQuantite() == null ? 0 : produit.getQuantite();
            int reste = Math.max(0, achetee - retournee);
            lignes.add(new LigneRetournable(detail.getId(), produit.getNom(), produit.getUnite(), achetee, retournee, reste, stock,
                    (int) Math.max(0, Math.min(reste, stock)), Montants.ouZero(detail.getPrixAchatUnitaire())));
        }
        return new Formulaire(achat, lignes, retours.countByAchatId(achatId), achat.getResteAPayer(), achat.getFournisseur() != null);
    }

    /** Retours d'achat de la boutique courante, du plus récent au plus ancien. */
    @Transactional(readOnly = true)
    public List<Retour> lister() {
        exigerGerant();
        return retours.retoursAchat(boutiques.idBoutiqueCourante());
    }

    /** Un retour d'achat de la boutique courante ; un retour d'une autre boutique ou de vente est introuvable (404). */
    @Transactional(readOnly = true)
    public Retour trouver(Long id) {
        exigerGerant();
        Retour retour = retours.findByIdAndBoutiqueId(id, boutiques.idBoutiqueCourante())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Retour introuvable"));
        if (retour.getType() != TypeRetour.ACHAT) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Retour introuvable");
        retour.getLignes().size();
        return retour;
    }

    // ----- Enregistrement -----

    /**
     * Enregistre un retour sur l'achat donné. Tout est écrit dans une seule transaction : le retour et ses lignes, le
     * total des retours de l'achat, le stock et le coût moyen, l'argent rendu éventuel. Un refus annule tout et rend le
     * numéro.
     * <p>
     * Isolation READ COMMITTED, comme le retour de vente : sous REPEATABLE READ, les lectures verrouillées des retours d'un
     * achat posent des verrous de plage (« gap »), qui peuvent croiser l'insertion d'un retour d'une autre boutique. Les
     * écritures restent protégées par les verrous de ligne (boutique, achat, fournisseur, produits).
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Retour enregistrer(Long achatId, RetourAchatForm form) {
        exigerGerant();
        if (form == null) throw new IllegalArgumentException("Indiquez au moins une quantité à retourner.");
        // 1. La boutique d'abord (ordre global des verrous), 2. l'achat : deux retours simultanés sur le même achat passent
        //    l'un après l'autre, et un règlement de cet achat aussi.
        verrous.verrouillerBoutiqueCourante();
        initialisation.verrouillerEtClorePourActivite();
        Achat achat = verrous.verrouillerAchat(achatId);
        Map<Long, Integer> demandees = quantitesDemandees(form);
        controlerFormulaireNonPerime(achat, form);
        // 3. Le fournisseur, 4. les produits ; le numéro lit le compteur de la boutique, déjà verrouillée.
        String numero = numerotation.attribuerNumeroRetourAchat();
        Fournisseur fournisseur = achat.getFournisseur() == null ? null : verrous.verrouillerFournisseur(achat.getFournisseur().getId());

        Map<Long, DetailAchat> lignesAchat = new LinkedHashMap<>();
        for (DetailAchat detail : achat.getLignes()) lignesAchat.put(detail.getId(), detail);
        Map<Long, Long> dejaRetournees = dejaRetourneesVerrouillees(achat.getId());
        List<Long> produitsAVerrouiller = new ArrayList<>();
        for (Map.Entry<Long, Integer> demande : demandees.entrySet()) {
            DetailAchat detail = lignesAchat.get(demande.getKey());
            if (detail == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ligne d'achat introuvable");
            long restant = (detail.getQuantite() == null ? 0 : detail.getQuantite()) - dejaRetournees.getOrDefault(detail.getId(), 0L);
            if (demande.getValue() > restant) {
                throw new IllegalArgumentException("La quantité retournée de « " + detail.getProduit().getNom() + " » ("
                        + demande.getValue() + ") dépasse ce qui reste à retourner (" + Math.max(0, restant) + ").");
            }
            produitsAVerrouiller.add(detail.getProduit().getId());
        }
        Map<Long, Produit> produits = verrous.verrouillerProduits(produitsAVerrouiller);

        // Plafond du stock actuel, relu sous le verrou des produits : on ne renvoie pas au fournisseur ce qui a déjà été
        // vendu. Plusieurs lignes d'un même produit se cumulent.
        Map<Long, Long> parProduit = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> demande : demandees.entrySet()) {
            parProduit.merge(lignesAchat.get(demande.getKey()).getProduit().getId(), (long) demande.getValue(), Long::sum);
        }
        for (Map.Entry<Long, Long> total : parProduit.entrySet()) {
            Produit produit = produits.get(total.getKey());
            long stock = produit.getQuantite() == null ? 0 : produit.getQuantite();
            if (total.getValue() > stock) {
                throw new IllegalArgumentException("La quantité retournée de « " + produit.getNom() + " » (" + total.getValue()
                        + ") dépasse le stock actuel (" + Math.max(0, stock) + ") : on ne renvoie pas au fournisseur ce qui a déjà été vendu.");
            }
        }

        // Calcul : R = Σ quantité × prix de la ligne d'achat ; D = reste à payer juste avant ; E = R − min(R, D).
        BigDecimal montant = Montants.ZERO;
        List<DetailRetour> lignes = new ArrayList<>();
        Retour retour = new Retour();
        for (Map.Entry<Long, Integer> demande : demandees.entrySet()) {
            DetailAchat detail = lignesAchat.get(demande.getKey());
            int quantite = demande.getValue();
            DetailRetour ligne = new DetailRetour();
            ligne.setRetour(retour);
            ligne.setProduit(detail.getProduit());
            ligne.setDetailAchat(detail);
            ligne.setQuantite(quantite);
            ligne.setPrixUnitaire(Montants.positifOuNul(detail.getPrixAchatUnitaire(), "Le prix de la ligne d'achat"));
            lignes.add(ligne);
            montant = Montants.valider(montant.add(ligne.getPrixUnitaire().multiply(BigDecimal.valueOf(quantite))), "Le montant du retour");
        }
        BigDecimal resteAvant = achat.getResteAPayer();
        BigDecimal imputee = montant.min(resteAvant);
        BigDecimal excedent = montant.subtract(imputee);

        ModeRemboursement mode = null;
        CompteTresorerie compte = null;
        if (excedent.signum() > 0) {
            mode = form.getModeRemboursement();
            if (mode == null) {
                throw new IllegalArgumentException("Il reste " + excedent.toPlainString() + " GNF déjà payés au fournisseur : choisissez qu'il vous rembourse ou de les garder en avoir chez lui.");
            }
            if (mode == ModeRemboursement.AVOIR && fournisseur == null) {
                throw new IllegalArgumentException("Un achat sans fournisseur ne peut être remboursé qu'en argent : un avoir demande un fournisseur chez qui le garder.");
            }
            if (mode == ModeRemboursement.ARGENT) {
                compte = form.getComptePaiement();
                if (compte == null) throw new IllegalArgumentException("Choisissez le compte qui reçoit l'argent (caisse, Orange Money ou banque).");
            }
        }

        Utilisateur acteur = utilisateurActuel();
        retour.setBoutique(boutiques.boutiqueCourante());
        retour.setNumero(numero);
        retour.setType(TypeRetour.ACHAT);
        retour.setAchat(achat);
        retour.setFournisseur(fournisseur);
        retour.setUtilisateur(acteur);
        retour.setDateRetour(LocalDateTime.now());
        retour.setMontantTotal(montant);
        retour.setMontantRembourse(mode == ModeRemboursement.ARGENT ? excedent : Montants.ZERO);
        retour.setCompteTresorerie(mode == ModeRemboursement.ARGENT ? compte : null);
        retour.setMontantAvoir(mode == ModeRemboursement.AVOIR ? excedent : Montants.ZERO);
        retour.setMotif(normaliserMotif(form.getMotif()));
        retour.setLignes(lignes);

        // Stock et coût moyen : une sortie au prix de la ligne d'achat, calculée avant de retirer la quantité. Le coût
        // moyen d'avant est gardé sur la ligne, à titre d'information.
        for (DetailRetour ligne : lignes) {
            Produit produit = produits.get(ligne.getProduit().getId());
            ligne.setCoutUnitaire(Montants.ouZero(produit.getCoutMoyen()));
            coutMoyen.enregistrerRetourAchat(produit, ligne.getQuantite(), ligne.getPrixUnitaire());
            produit.setQuantite((produit.getQuantite() == null ? 0 : produit.getQuantite()) - ligne.getQuantite());
        }
        achat.setMontantRetours(Montants.valider(Montants.ouZero(achat.getMontantRetours()).add(montant), "Le total des retours de l'achat"));
        Retour enregistre = retours.save(retour);

        if (mode == ModeRemboursement.ARGENT) {
            String motif = "Remboursement fournisseur retour " + numero + " - achat " + (achat.getNumero() != null ? achat.getNumero() : "#" + achat.getId())
                    + " - Fournisseur: " + (fournisseur != null ? fournisseur.getNom() : "Non renseigné");
            caisse.enregistrerEntreeRetourAchat(excedent, motif, enregistre, acteur, compte);
        }
        return enregistre;
    }

    // ----- Contrôles -----

    /** Quantités > 0 par ligne d'achat, dans l'ordre de saisie ; au moins une, sans doublon ni valeur négative. */
    private static Map<Long, Integer> quantitesDemandees(RetourAchatForm form) {
        Map<Long, Integer> demandees = new LinkedHashMap<>();
        Set<Long> vues = new HashSet<>();
        if (form.getLignes() == null) form.setLignes(new ArrayList<>());
        for (RetourAchatForm.Ligne saisie : form.getLignes()) {
            if (saisie == null || saisie.getDetailAchatId() == null) throw new IllegalArgumentException("Une ligne du retour ne désigne aucune ligne de l'achat.");
            if (!vues.add(saisie.getDetailAchatId())) throw new IllegalArgumentException("Une même ligne de l'achat ne peut apparaître qu'une fois dans le retour.");
            int quantite = saisie.getQuantite() == null ? 0 : saisie.getQuantite();
            if (quantite < 0) throw new IllegalArgumentException("La quantité retournée ne peut pas être négative.");
            if (quantite > 0) demandees.put(saisie.getDetailAchatId(), quantite);
        }
        if (demandees.isEmpty()) throw new IllegalArgumentException("Indiquez au moins une quantité à retourner.");
        return demandees;
    }

    /**
     * Le formulaire doit avoir été ouvert après le dernier retour de l'achat. Un second envoi du même formulaire (double
     * clic, rechargement) ou un formulaire ouvert avant le retour d'un autre compte est refusé : rien n'est enregistré
     * deux fois et les quantités restantes affichées étaient périmées. Lecture verrouillée, donc à jour même sous
     * REPEATABLE READ, l'achat étant déjà verrouillé.
     */
    private void controlerFormulaireNonPerime(Achat achat, RetourAchatForm form) {
        List<String> numeros = em.createQuery("select r.numero from Retour r where r.achat.id = :achat order by r.id", String.class)
                .setParameter("achat", achat.getId()).setLockMode(LockModeType.PESSIMISTIC_READ).getResultList();
        if (form.getRetoursConnus() == null || form.getRetoursConnus() != numeros.size()) {
            throw new RetourAchatObsoleteException(numeros.isEmpty()
                    ? "Ce formulaire n'est plus valide. Rouvrez le retour depuis l'achat."
                    : "Un retour a déjà été enregistré sur cet achat depuis l'ouverture de ce formulaire (retours : " + String.join(", ", numeros)
                    + "). Rien n'a été enregistré une seconde fois. Rouvrez le formulaire pour vérifier les quantités restantes.");
        }
    }

    private Map<Long, Long> dejaRetourneesVerrouillees(Long achatId) {
        Map<Long, Long> deja = new HashMap<>();
        // Lecture verrouillée : voit les retours validés après l'instantané de la transaction.
        for (Object[] ligne : em.createQuery("select d.detailAchat.id, d.quantite from DetailRetour d join d.retour r where r.achat.id = :achat", Object[].class)
                .setParameter("achat", achatId).setLockMode(LockModeType.PESSIMISTIC_READ).getResultList()) {
            deja.merge(((Number) ligne[0]).longValue(), ((Number) ligne[1]).longValue(), Long::sum);
        }
        return deja;
    }

    private static String normaliserMotif(String motif) {
        if (motif == null || motif.isBlank()) return null;
        String propre = motif.strip();
        if (propre.length() > MOTIF_LONGUEUR_MAXIMALE) {
            throw new IllegalArgumentException("Le motif accepte au maximum " + MOTIF_LONGUEUR_MAXIMALE + " caractères.");
        }
        if (propre.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Le motif contient un caractère non autorisé.");
        return propre;
    }

    private Utilisateur utilisateurActuel() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return utilisateurs.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Retour d'achat réservé au gérant de la boutique"));
    }

    /** Le retour d'achat est réservé au gérant ; la configuration de sécurité le garantit déjà, ce contrôle double la règle. */
    private static void exigerGerant() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Retour d'achat réservé au gérant de la boutique");
        }
    }
}
