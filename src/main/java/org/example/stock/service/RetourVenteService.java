package org.example.stock.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.example.stock.enums.CompteTresorerie;
import org.example.stock.enums.ModeRemboursement;
import org.example.stock.enums.TypeRetour;
import org.example.stock.form.RetourVenteForm;
import org.example.stock.model.Client;
import org.example.stock.model.DetailRetour;
import org.example.stock.model.DetailVente;
import org.example.stock.model.Montants;
import org.example.stock.model.Produit;
import org.example.stock.model.Retour;
import org.example.stock.model.Utilisateur;
import org.example.stock.model.Vente;
import org.example.stock.repository.RetourRepository;
import org.example.stock.repository.StockLockRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.example.stock.repository.VenteRepository;
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
 * Retour de vente : le client rend de la marchandise vendue. Réservé au gérant.
 *
 * <p>Pour un retour de montant R sur une vente dont le reste à payer juste avant est D, la partie imputée sur la créance
 * est {@code min(R, D)} (elle réduit ce que le client doit, sans argent) et l'excédent {@code E = R − min(R, D)} est soit
 * rendu en argent (mouvement de trésorerie sortant), soit gardé en avoir (avance du client). Le stock revient pour les
 * articles en stock, valorisé au coût figé de la ligne vendue.</p>
 *
 * <p><b>Ordre des verrous</b> : ligne de la <b>boutique</b> en premier (c'est aussi elle qui porte le numéro {@code RV-}),
 * puis la <b>vente</b>, la fiche du <b>client</b>, les <b>produits</b> (par identifiant croissant) et les comptes de
 * trésorerie (lecture) pour le remboursement. C'est l'ordre commun à toutes les écritures métier, décrit dans
 * {@code docs/lot-2.md}, « Ordre des verrous ».</p>
 */
@Service
public class RetourVenteService {
    public static final int MOTIF_LONGUEUR_MAXIMALE = 255;

    private final RetourRepository retours;
    private final VenteRepository ventes;
    private final StockLockRepository verrous;
    private final NumerotationFactureService numerotation;
    private final InitialisationBoutiqueService initialisation;
    private final CoutMoyenService coutMoyen;
    private final CaisseService caisse;
    private final BoutiqueService boutiques;
    private final UtilisateurRepository utilisateurs;
    private final EntityManager em;

    public RetourVenteService(RetourRepository retours, VenteRepository ventes, StockLockRepository verrous,
                              NumerotationFactureService numerotation, InitialisationBoutiqueService initialisation,
                              CoutMoyenService coutMoyen, CaisseService caisse, BoutiqueService boutiques,
                              UtilisateurRepository utilisateurs, EntityManager em) {
        this.retours = retours;
        this.ventes = ventes;
        this.verrous = verrous;
        this.numerotation = numerotation;
        this.initialisation = initialisation;
        this.coutMoyen = coutMoyen;
        this.caisse = caisse;
        this.boutiques = boutiques;
        this.utilisateurs = utilisateurs;
        this.em = em;
    }

    /** Une ligne de la vente telle que le formulaire la montre. */
    public record LigneRetournable(Long detailVenteId, String produit, String unite, boolean service, int quantiteVendue,
                                   int dejaRetournee, int maximum, BigDecimal prixUnitaire) {}

    /** Données du formulaire : la vente, ses lignes et le nombre de retours déjà enregistrés. */
    public record Formulaire(Vente vente, List<LigneRetournable> lignes, long retoursConnus, BigDecimal resteAPayer, boolean avecClient) {
        public boolean peutRetourner() {
            return lignes.stream().anyMatch(ligne -> ligne.maximum() > 0);
        }
    }

    // ----- Consultation -----

    /** Lit la vente de la boutique courante (404 sinon) avec ce qui peut encore être retourné. */
    @Transactional(readOnly = true)
    public Formulaire preparer(Long venteId) {
        exigerGerant();
        Vente vente = ventes.findByIdAndBoutiqueId(venteId, boutiques.idBoutiqueCourante())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vente introuvable"));
        Map<Long, Long> deja = new HashMap<>();
        for (Object[] ligne : retours.quantitesRetournees(venteId)) deja.put(((Number) ligne[0]).longValue(), ((Number) ligne[1]).longValue());
        List<LigneRetournable> lignes = new ArrayList<>();
        for (DetailVente detail : vente.getLignes()) {
            int vendue = detail.getQuantite() == null ? 0 : detail.getQuantite();
            int retournee = deja.getOrDefault(detail.getId(), 0L).intValue();
            Produit produit = detail.getProduit();
            lignes.add(new LigneRetournable(detail.getId(), produit.getNom(), produit.getUnite(), produit.estService(), vendue, retournee,
                    Math.max(0, vendue - retournee), Montants.ouZero(detail.getPrixUnitaire())));
        }
        return new Formulaire(vente, lignes, retours.countByVenteId(venteId), vente.getResteAPayer(), vente.getClient() != null);
    }

    /** Retours de vente de la boutique courante, du plus récent au plus ancien. */
    @Transactional(readOnly = true)
    public List<Retour> lister() {
        exigerGerant();
        return retours.findByBoutiqueIdAndTypeOrderByDateRetourDescIdDesc(boutiques.idBoutiqueCourante(), TypeRetour.VENTE);
    }

    /** Comme {@link #lister()} mais lit au plus {@code max + 1} retours (export : plus de {@code max} signale un dépassement). */
    @Transactional(readOnly = true)
    public List<Retour> lister(int max) {
        exigerGerant();
        return retours.findByBoutiqueIdAndTypeOrderByDateRetourDescIdDesc(boutiques.idBoutiqueCourante(), TypeRetour.VENTE,
                org.springframework.data.domain.PageRequest.of(0, max + 1));
    }

    /** Un retour de vente de la boutique courante ; un retour d'une autre boutique ou d'achat est introuvable (404). */
    @Transactional(readOnly = true)
    public Retour trouver(Long id) {
        exigerGerant();
        Retour retour = retours.findByIdAndBoutiqueId(id, boutiques.idBoutiqueCourante())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Retour introuvable"));
        if (retour.getType() != TypeRetour.VENTE) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Retour introuvable");
        retour.getLignes().size();
        return retour;
    }

    // ----- Enregistrement -----

    /**
     * Enregistre un retour sur la vente donnée. Tout est écrit dans une seule transaction : le retour et ses lignes, le
     * total des retours de la vente, le stock et le coût moyen, le remboursement éventuel. Un refus annule tout et rend
     * le numéro.
     * <p>
     * Isolation READ COMMITTED : sous REPEATABLE READ, les lectures verrouillées des retours d'une vente posent des verrous
     * de plage (« gap ») qui peuvent croiser l'insertion d'un retour d'une autre boutique. En READ COMMITTED, il n'y a pas de
     * verrou de plage et chaque lecture voit les retours déjà validés ; les écritures restent protégées par les verrous de
     * ligne (boutique, vente, client, produits).
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Retour enregistrer(Long venteId, RetourVenteForm form) {
        exigerGerant();
        if (form == null) throw new IllegalArgumentException("Indiquez au moins une quantité à retourner.");
        // 1. La boutique d'abord (ordre global des verrous), 2. la vente : deux retours simultanés sur la même vente passent
        //    l'un après l'autre, et un règlement de cette vente aussi.
        verrous.verrouillerBoutiqueCourante();
        initialisation.verrouillerEtClorePourActivite();
        Vente vente = verrous.verrouillerVente(venteId);
        Map<Long, Integer> demandees = quantitesDemandees(form);
        controlerFormulaireNonPerime(vente, form);
        // 3. Le client, 4. les produits ; le numéro lit le compteur de la boutique, déjà verrouillée.
        String numero = numerotation.attribuerNumeroRetourVente();
        Client client = vente.getClient() == null ? null : verrous.verrouillerClient(vente.getClient().getId());

        Map<Long, DetailVente> lignesVente = new LinkedHashMap<>();
        for (DetailVente detail : vente.getLignes()) lignesVente.put(detail.getId(), detail);
        Map<Long, Long> dejaRetournees = dejaRetourneesVerrouillees(vente.getId());
        List<Long> produitsAVerrouiller = new ArrayList<>();
        for (Map.Entry<Long, Integer> demande : demandees.entrySet()) {
            DetailVente detail = lignesVente.get(demande.getKey());
            if (detail == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ligne de vente introuvable");
            long restant = (detail.getQuantite() == null ? 0 : detail.getQuantite()) - dejaRetournees.getOrDefault(detail.getId(), 0L);
            if (demande.getValue() > restant) {
                throw new IllegalArgumentException("La quantité retournée de « " + detail.getProduit().getNom() + " » ("
                        + demande.getValue() + ") dépasse ce qui reste à retourner (" + Math.max(0, restant) + ").");
            }
            if (!detail.getProduit().estService()) produitsAVerrouiller.add(detail.getProduit().getId());
        }
        Map<Long, Produit> produits = verrous.verrouillerProduits(produitsAVerrouiller);

        // Calcul : R = Σ quantité × prix de la ligne vendue ; D = reste à payer juste avant ; E = R − min(R, D).
        BigDecimal montant = Montants.ZERO;
        List<DetailRetour> lignes = new ArrayList<>();
        Retour retour = new Retour();
        for (Map.Entry<Long, Integer> demande : demandees.entrySet()) {
            DetailVente detail = lignesVente.get(demande.getKey());
            int quantite = demande.getValue();
            DetailRetour ligne = new DetailRetour();
            ligne.setRetour(retour);
            ligne.setProduit(detail.getProduit());
            ligne.setDetailVente(detail);
            ligne.setQuantite(quantite);
            ligne.setPrixUnitaire(Montants.positifOuNul(detail.getPrixUnitaire(), "Le prix de la ligne vendue"));
            ligne.setCoutUnitaire(detail.getCoutUnitaire());
            lignes.add(ligne);
            montant = Montants.valider(montant.add(ligne.getPrixUnitaire().multiply(BigDecimal.valueOf(quantite))), "Le montant du retour");
        }
        BigDecimal resteAvant = vente.getResteAPayer();
        BigDecimal imputee = montant.min(resteAvant);
        BigDecimal excedent = montant.subtract(imputee);

        ModeRemboursement mode = null;
        CompteTresorerie compte = null;
        if (excedent.signum() > 0) {
            mode = form.getModeRemboursement();
            if (mode == null) {
                throw new IllegalArgumentException("Il reste " + excedent.toPlainString() + " GNF à rembourser : choisissez de rendre l'argent ou de le garder en avoir.");
            }
            if (mode == ModeRemboursement.AVOIR && client == null) {
                throw new IllegalArgumentException("Une vente sans client ne peut être remboursée qu'en argent : un avoir demande un client pour le garder.");
            }
            if (mode == ModeRemboursement.ARGENT) {
                compte = form.getComptePaiement();
                if (compte == null) throw new IllegalArgumentException("Choisissez le compte qui rend l'argent (caisse, Orange Money ou banque).");
            }
        }

        Utilisateur acteur = utilisateurActuel();
        retour.setBoutique(boutiques.boutiqueCourante());
        retour.setNumero(numero);
        retour.setType(TypeRetour.VENTE);
        retour.setVente(vente);
        retour.setClient(client);
        retour.setUtilisateur(acteur);
        retour.setDateRetour(LocalDateTime.now());
        retour.setMontantTotal(montant);
        retour.setMontantRembourse(mode == ModeRemboursement.ARGENT ? excedent : Montants.ZERO);
        retour.setCompteTresorerie(mode == ModeRemboursement.ARGENT ? compte : null);
        retour.setMontantAvoir(mode == ModeRemboursement.AVOIR ? excedent : Montants.ZERO);
        retour.setMotif(normaliserMotif(form.getMotif()));
        retour.setLignes(lignes);

        // Stock et coût moyen : une entrée au coût figé de la ligne, calculée avant d'ajouter la quantité.
        for (DetailRetour ligne : lignes) {
            Produit produit = produits.get(ligne.getProduit().getId());
            if (produit == null) continue; // un service ne touche ni stock ni coût
            coutMoyen.enregistrerRetourVente(produit, ligne.getQuantite(), ligne.getCoutUnitaire());
            produit.setQuantite((produit.getQuantite() == null ? 0 : produit.getQuantite()) + ligne.getQuantite());
        }
        vente.setMontantRetours(Montants.valider(Montants.ouZero(vente.getMontantRetours()).add(montant), "Le total des retours de la vente"));
        Retour enregistre = retours.save(retour);

        if (mode == ModeRemboursement.ARGENT) {
            String motif = "Remboursement retour " + numero + " - vente " + (vente.getNumero() != null ? vente.getNumero() : "#" + vente.getId())
                    + " - Client: " + (client != null ? client.getNom() : "Passant");
            caisse.enregistrerSortieRetourVente(excedent, motif, enregistre, acteur, compte);
        }
        return enregistre;
    }

    // ----- Contrôles -----

    /** Quantités > 0 par ligne de vente, dans l'ordre de saisie ; au moins une, sans doublon ni valeur négative. */
    private static Map<Long, Integer> quantitesDemandees(RetourVenteForm form) {
        Map<Long, Integer> demandees = new LinkedHashMap<>();
        Set<Long> vues = new HashSet<>();
        if (form.getLignes() == null) form.setLignes(new ArrayList<>());
        for (RetourVenteForm.Ligne saisie : form.getLignes()) {
            if (saisie == null || saisie.getDetailVenteId() == null) throw new IllegalArgumentException("Une ligne du retour ne désigne aucune ligne de la vente.");
            if (!vues.add(saisie.getDetailVenteId())) throw new IllegalArgumentException("Une même ligne de la vente ne peut apparaître qu'une fois dans le retour.");
            int quantite = saisie.getQuantite() == null ? 0 : saisie.getQuantite();
            if (quantite < 0) throw new IllegalArgumentException("La quantité retournée ne peut pas être négative.");
            if (quantite > 0) demandees.put(saisie.getDetailVenteId(), quantite);
        }
        if (demandees.isEmpty()) throw new IllegalArgumentException("Indiquez au moins une quantité à retourner.");
        return demandees;
    }

    /**
     * Le formulaire doit avoir été ouvert après le dernier retour de la vente. Un second envoi du même formulaire (double
     * clic, rechargement) ou un formulaire ouvert avant le retour d'un autre compte est refusé : rien n'est enregistré
     * deux fois et les quantités restantes affichées étaient périmées. Lecture verrouillée, donc à jour même sous
     * REPEATABLE READ, la vente étant déjà verrouillée.
     */
    private void controlerFormulaireNonPerime(Vente vente, RetourVenteForm form) {
        List<String> numeros = em.createQuery("select r.numero from Retour r where r.vente.id = :vente order by r.id", String.class)
                .setParameter("vente", vente.getId()).setLockMode(LockModeType.PESSIMISTIC_READ).getResultList();
        if (form.getRetoursConnus() == null || form.getRetoursConnus() != numeros.size()) {
            throw new RetourVenteObsoleteException(numeros.isEmpty()
                    ? "Ce formulaire n'est plus valide. Rouvrez le retour depuis la vente."
                    : "Un retour a déjà été enregistré sur cette vente depuis l'ouverture de ce formulaire (retours : " + String.join(", ", numeros)
                    + "). Rien n'a été enregistré une seconde fois. Rouvrez le formulaire pour vérifier les quantités restantes.");
        }
    }

    private Map<Long, Long> dejaRetourneesVerrouillees(Long venteId) {
        Map<Long, Long> deja = new HashMap<>();
        // Lecture verrouillée : voit les retours validés après l'instantané de la transaction.
        for (Object[] ligne : em.createQuery("select d.detailVente.id, d.quantite from DetailRetour d join d.retour r where r.vente.id = :vente", Object[].class)
                .setParameter("vente", venteId).setLockMode(LockModeType.PESSIMISTIC_READ).getResultList()) {
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Retour de vente réservé au gérant de la boutique"));
    }

    /** Le retour de vente est réservé au gérant ; la configuration de sécurité le garantit déjà, ce contrôle double la règle. */
    private static void exigerGerant() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Retour de vente réservé au gérant de la boutique");
        }
    }
}
