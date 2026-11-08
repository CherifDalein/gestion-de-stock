package org.example.stock.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.example.stock.enums.NatureProduit;
import org.example.stock.enums.TypeMouvementStock;
import org.example.stock.model.Montants;
import org.example.stock.model.Produit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Coût moyen pondéré (CMP) des produits en stock.
 *
 * <p>Toutes les méthodes qui écrivent s'exécutent dans la transaction de l'opération commerciale,
 * après le verrou pessimiste du produit pris par {@code StockLockRepository} : un achat, une vente
 * ou une correction d'achat sur le même produit lisent et écrivent donc le CMP l'un après l'autre.
 * Les lignes de vente déjà enregistrées gardent leur coût figé ; ce service ne les réécrit jamais.</p>
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CoutMoyenService {
    private final EntityManager em;

    public CoutMoyenService(EntityManager em) {
        this.em = em;
    }

    /**
     * Applique une entrée (achat ou stock initial) au CMP. À appeler sous le verrou du produit,
     * AVANT d'ajouter la quantité entrée au stock.
     */
    public void enregistrerEntree(Produit produit, long quantite, BigDecimal prixUnitaire) {
        // Le coût d'un service est fixe et géré avec sa fiche : une entrée ne le pondère pas.
        if (produit.getNature() == NatureProduit.SERVICE) return;
        long stockAvant = produit.getQuantite() == null ? 0 : produit.getQuantite();
        produit.setCoutMoyen(nouveauCout(stockAvant, produit.getCoutMoyen(), quantite, prixUnitaire));
    }

    /**
     * Retour de vente : la marchandise rentre en stock comme une entrée valorisée au coût unitaire figé de la ligne de
     * vente d'origine, jamais au CMP du jour ni au prix de vente. À appeler sous le verrou du produit, AVANT d'ajouter
     * la quantité retournée au stock. Une ligne dont le coût figé est inconnu (vente migrée sans coût) remet la
     * quantité en stock sans changer le CMP.
     */
    public void enregistrerRetourVente(Produit produit, long quantite, BigDecimal coutFige) {
        if (coutFige == null) return;
        enregistrerEntree(produit, quantite, coutFige);
    }

    /**
     * Retour d'achat : la marchandise sort du stock, valorisée au prix de la ligne d'achat retournée (jamais au CMP du jour).
     * À appeler sous le verrou du produit, AVANT de retirer la quantité retournée du stock. Un service n'a pas de stock :
     * il n'est pas concerné.
     */
    public void enregistrerRetourAchat(Produit produit, long quantite, BigDecimal prixLigneAchat) {
        if (produit.getNature() == NatureProduit.SERVICE) return;
        long stockAvant = produit.getQuantite() == null ? 0 : produit.getQuantite();
        produit.setCoutMoyen(coutApresRetourAchat(stockAvant, produit.getCoutMoyen(), quantite, prixLigneAchat));
    }

    /** Coût à figer sur une nouvelle ligne de vente : le CMP lu sous le verrou du produit. */
    @Transactional(propagation = Propagation.SUPPORTS)
    public BigDecimal coutUnitaireVente(Produit produit) {
        return Montants.ouZero(produit.getCoutMoyen());
    }

    /**
     * Recalcule le CMP de chaque produit à partir de tout son historique, après la correction d'un
     * ancien achat. Les produits doivent être verrouillés et leur quantité déjà à jour.
     */
    public void recalculer(Collection<Produit> produits) {
        // Les lignes corrigées doivent être visibles des requêtes d'historique.
        em.flush();
        for (Produit produit : produits) {
            if (produit.getNature() == NatureProduit.SERVICE) continue;
            produit.setCoutMoyen(rejouer(produit.getQuantite() == null ? 0 : produit.getQuantite(),
                    produit.getCoutMoyen(), historique(produit)));
        }
    }

    /**
     * Formule du CMP : (stock avant × CMP + quantité × prix) / (stock avant + quantité), calculée
     * exactement puis arrondie une seule fois au centime. Sans stock positif avant l'entrée, ou sans
     * CMP connu, le CMP devient le prix de l'entrée. Un CMP à zéro avec du stock reste une vraie valeur.
     */
    static BigDecimal nouveauCout(long stockAvant, BigDecimal coutActuel, long quantite, BigDecimal prixUnitaire) {
        BigDecimal prix = Montants.ouZero(prixUnitaire);
        if (quantite <= 0) return coutActuel;
        if (stockAvant <= 0 || coutActuel == null) return prix.setScale(2, RoundingMode.HALF_UP);
        BigDecimal valeur = coutActuel.multiply(BigDecimal.valueOf(stockAvant))
                .add(prix.multiply(BigDecimal.valueOf(quantite)));
        return Montants.diviser(valeur, stockAvant + quantite);
    }

    /**
     * CMP après un retour d'achat de {@code quantite} unités valorisées {@code prix}, avec S le stock avant le retour :
     * <ul>
     *   <li>S − q &gt; 0 : (S × CMP − q × prix) / (S − q), calculé exactement puis arrondi une seule fois ;</li>
     *   <li>S − q = 0 (le stock est vidé) : le CMP reste inchangé, il n'y a plus rien à valoriser ;</li>
     *   <li>résultat négatif (on rend plus cher que le coût moyen du reste) : le CMP est ramené à 0, jamais négatif.</li>
     * </ul>
     */
    static BigDecimal coutApresRetourAchat(long stockAvant, BigDecimal coutActuel, long quantite, BigDecimal prix) {
        if (quantite <= 0 || coutActuel == null) return coutActuel;
        long restant = stockAvant - quantite;
        if (restant <= 0) return coutActuel;
        BigDecimal valeur = coutActuel.multiply(BigDecimal.valueOf(stockAvant))
                .subtract(Montants.ouZero(prix).multiply(BigDecimal.valueOf(quantite)));
        if (valeur.signum() < 0) return Montants.ZERO;
        return Montants.diviser(valeur, restant);
    }

    /**
     * Rejoue l'historique trié (achats, retours de vente, retours d'achat, ajustements, ventes). Le stock que l'historique n'explique pas (antérieur au journal
     * d'inventaire) est placé au début, valorisé au premier coût d'entrée connu ; un écart négatif
     * ne change que la quantité et n'a donc pas d'effet sur le CMP.
     */
    static BigDecimal rejouer(long quantiteActuelle, BigDecimal coutActuel, List<Evenement> evenements) {
        List<Evenement> tries = new ArrayList<>(evenements);
        tries.sort(Evenement.ORDRE);
        long explique = tries.stream().mapToLong(Evenement::variation).sum();
        BigDecimal premierPrix = tries.stream().filter(Evenement::estEntree).map(Evenement::prixUnitaire)
                .findFirst().orElse(null);
        if (premierPrix == null) return coutActuel;
        long stock = 0;
        BigDecimal cout = null;
        long ouverture = quantiteActuelle - explique;
        if (ouverture > 0) {
            stock = ouverture;
            cout = Montants.ouZero(premierPrix).setScale(2, RoundingMode.HALF_UP);
        }
        for (Evenement evenement : tries) {
            if (evenement.estEntree()) cout = nouveauCout(stock, cout, evenement.variation(), evenement.prixUnitaire());
            else if (evenement.estRetourAchat()) cout = coutApresRetourAchat(stock, cout, -evenement.variation(), evenement.prixUnitaire());
            stock += evenement.variation();
        }
        return cout == null ? coutActuel : cout;
    }

    private List<Evenement> historique(Produit produit) {
        Long produitId = produit.getId();
        Long boutiqueId = produit.getBoutique().getId();
        List<Evenement> evenements = new ArrayList<>();
        // Lectures verrouillées : sous REPEATABLE READ, elles voient aussi les opérations validées
        // après l'instantané de la transaction, avant que celle-ci obtienne le verrou du produit.
        for (Object[] m : lire("select m.type, m.creeLe, m.id, m.quantiteAvant, m.quantiteApres, m.coutUnitaire"
                + " from MouvementStock m where m.produit.id = :produit and m.boutique.id = :boutique", produitId, boutiqueId)) {
            TypeMouvementStock type = (TypeMouvementStock) m[0];
            long variation = (Long) m[4] - (Long) m[3];
            if (type == TypeMouvementStock.INITIAL) {
                evenements.add(new Evenement(Rang.INITIAL, null, Ordre.ENTREE, (Long) m[2], 0, variation,
                        Montants.ouZero((BigDecimal) m[5])));
            } else {
                // Le journal d'inventaire est daté en UTC, les achats et ventes à l'heure du serveur.
                LocalDateTime date = m[1] == null ? null : ((LocalDateTime) m[1]).atOffset(ZoneOffset.UTC)
                        .atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
                evenements.add(new Evenement(Rang.OPERATION, date, Ordre.AJUSTEMENT, (Long) m[2], 0, variation, null));
            }
        }
        for (Object[] l : lire("select a.dateAchat, a.id, d.id, d.quantite, d.prixAchatUnitaire from DetailAchat d join d.achat a"
                + " where d.produit.id = :produit and a.boutique.id = :boutique", produitId, boutiqueId)) {
            evenements.add(new Evenement(Rang.OPERATION, (LocalDateTime) l[0], Ordre.ENTREE, (Long) l[1], (Long) l[2],
                    quantite(l[3]), Montants.ouZero((BigDecimal) l[4])));
        }
        // Retours de vente : entrées datées de la date du retour, au coût figé de la ligne ; sans coût connu, la quantité seule.
        for (Object[] l : lire("select r.dateRetour, r.id, d.id, d.quantite, d.coutUnitaire from DetailRetour d join d.retour r"
                + " where d.produit.id = :produit and r.boutique.id = :boutique and r.type = org.example.stock.enums.TypeRetour.VENTE", produitId, boutiqueId)) {
            evenements.add(new Evenement(Rang.OPERATION, (LocalDateTime) l[0], Ordre.ENTREE, (Long) l[1], (Long) l[2],
                    quantite(l[3]), l[4] == null ? null : Montants.ouZero((BigDecimal) l[4])));
        }
        // Retours d'achat : sorties datées de la date du retour, valorisées au prix de la ligne d'achat retournée.
        for (Object[] l : lire("select r.dateRetour, r.id, d.id, d.quantite, d.prixUnitaire from DetailRetour d join d.retour r"
                + " where d.produit.id = :produit and r.boutique.id = :boutique and r.type = org.example.stock.enums.TypeRetour.ACHAT", produitId, boutiqueId)) {
            evenements.add(new Evenement(Rang.OPERATION, (LocalDateTime) l[0], Ordre.RETOUR_ACHAT, (Long) l[1], (Long) l[2],
                    -quantite(l[3]), Montants.ouZero((BigDecimal) l[4])));
        }
        for (Object[] l : lire("select v.dateVente, v.id, d.id, d.quantite from DetailVente d join d.vente v"
                + " where d.produit.id = :produit and v.boutique.id = :boutique", produitId, boutiqueId)) {
            evenements.add(new Evenement(Rang.OPERATION, (LocalDateTime) l[0], Ordre.SORTIE, (Long) l[1], (Long) l[2],
                    -quantite(l[3]), null));
        }
        return evenements;
    }

    private List<Object[]> lire(String jpql, Long produitId, Long boutiqueId) {
        return em.createQuery(jpql, Object[].class).setParameter("produit", produitId).setParameter("boutique", boutiqueId)
                .setLockMode(LockModeType.PESSIMISTIC_READ).getResultList();
    }

    private static long quantite(Object valeur) {
        return valeur == null ? 0 : ((Number) valeur).longValue();
    }

    /** Le stock initial précède toujours les opérations : il est réservé au démarrage de la boutique. */
    enum Rang { INITIAL, OPERATION }

    /** À date égale, les entrées passent avant les ajustements, puis les sorties, puis les retours au fournisseur. */
    enum Ordre { ENTREE, AJUSTEMENT, SORTIE, RETOUR_ACHAT }

    /**
     * Une variation de stock datée. {@code prixUnitaire} n'est renseigné que pour une entrée valorisée ou un retour d'achat.
     * Une date absente (document historique) est placée avant les dates connues.
     */
    record Evenement(Rang rang, LocalDateTime date, Ordre ordre, long documentId, long ligneId, long variation,
                     BigDecimal prixUnitaire) {
        static final Comparator<Evenement> ORDRE = Comparator.comparing(Evenement::rang)
                .thenComparing(Evenement::date, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Evenement::ordre)
                .thenComparingLong(Evenement::documentId)
                .thenComparingLong(Evenement::ligneId);

        boolean estEntree() {
            return prixUnitaire != null && ordre != Ordre.RETOUR_ACHAT;
        }

        /** Sortie valorisée au prix de la ligne d'achat retournée : elle fait baisser le CMP quand elle rend plus cher que lui. */
        boolean estRetourAchat() {
            return ordre == Ordre.RETOUR_ACHAT;
        }
    }
}
