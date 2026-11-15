package org.example.stock.compta;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.example.stock.compta.DonneesComptables.Achat;
import org.example.stock.compta.DonneesComptables.Ajustement;
import org.example.stock.compta.DonneesComptables.Mouvement;
import org.example.stock.compta.DonneesComptables.Operation;
import org.example.stock.compta.DonneesComptables.Partenaire;
import org.example.stock.compta.DonneesComptables.Retour;
import org.example.stock.compta.DonneesComptables.ValeurStock;
import org.example.stock.compta.DonneesComptables.Vente;
import org.example.stock.enums.TypeOperationTresorerie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lit les opérations d'une boutique jusqu'à une date, avec un nombre fixe de requêtes groupées (jamais une requête par opération), pour
 * {@link ConstructeurEcritures}. Les clients, fournisseurs et produits de la corbeille sont lus aussi : leurs opérations restent dans les livres.
 */
@Service
public class DonneesComptablesService {
    private final EntityManager em;

    public DonneesComptablesService(EntityManager em) { this.em = em; }

    /** Données jusqu'à {@code fin} (comprise) ; le stock est régularisé à cette date si {@code regulariser}. */
    @Transactional(readOnly = true)
    public DonneesComptables charger(Long boutiqueId, LocalDateTime fin, LocalDateTime maintenant, boolean regulariser) {
        return charger(boutiqueId, fin, maintenant, regulariser, null, null);
    }

    /**
     * Comme {@link #charger(Long, LocalDateTime, LocalDateTime, boolean)}, avec le report à nouveau d'un exercice clôturé ({@code reprise}, ou {@code null}) et la
     * valeur du stock figée à la fin d'un exercice clôturé ({@code stockFige}, ou {@code null} : le stock est alors valorisé comme avant).
     */
    @Transactional(readOnly = true)
    public DonneesComptables charger(Long boutiqueId, LocalDateTime fin, LocalDateTime maintenant, boolean regulariser, Reprise reprise, BigDecimal stockFige) {
        Map<Long, Partenaire> clients = partenaires("client", boutiqueId);
        Map<Long, Partenaire> fournisseurs = partenaires("fournisseur", boutiqueId);
        Map<Long, BigDecimal> servicesVentes = servicesParDocument("DetailVente d join d.vente x join d.produit p", "x", boutiqueId, "x.dateVente", fin, "d.prixUnitaire");
        Map<Long, BigDecimal> servicesRetours = servicesParDocument("DetailRetour d join d.retour x join d.produit p", "x", boutiqueId, "x.dateRetour", fin, "d.prixUnitaire");

        List<Vente> ventes = new ArrayList<>();
        for (Object[] r : lignes("select v.id, v.numero, v.dateVente, v.montantTotal, v.montantVerse, v.avanceUtilisee, v.client.id from Vente v "
                + "where v.boutique.id = :b and v.dateVente <= :fin", boutiqueId, fin)) {
            Long cid = id(r[6]);
            ventes.add(new Vente(id(r[0]), (String) r[1], date(r[2]), nb(r[3]), nb(r[4]), nb(r[5]), cid, cid == null || clients.get(cid) == null ? null : clients.get(cid).nom(),
                    servicesVentes.getOrDefault(id(r[0]), BigDecimal.ZERO)));
        }
        List<Achat> achats = new ArrayList<>();
        for (Object[] r : lignes("select a.id, a.numero, a.dateAchat, a.montantTotal, a.montantVerse, a.avanceUtilisee, a.fournisseur.id from Achat a "
                + "where a.boutique.id = :b and a.dateAchat <= :fin", boutiqueId, fin)) {
            Long fid = id(r[6]);
            achats.add(new Achat(id(r[0]), (String) r[1], date(r[2]), nb(r[3]), nb(r[4]), nb(r[5]), fid, fid == null || fournisseurs.get(fid) == null ? null : fournisseurs.get(fid).nom()));
        }
        List<Retour> retours = new ArrayList<>();
        for (Object[] r : lignes("select r.id, r.numero, r.type, r.dateRetour, r.montantTotal, r.montantRembourse, r.montantAvoir, r.client.id, r.fournisseur.id from Retour r "
                + "where r.boutique.id = :b and r.dateRetour <= :fin", boutiqueId, fin)) {
            boolean vente = "VENTE".equals(String.valueOf(r[2]));
            Long tid = vente ? id(r[7]) : id(r[8]);
            Map<Long, Partenaire> source = vente ? clients : fournisseurs;
            retours.add(new Retour(id(r[0]), (String) r[1], vente, date(r[3]), nb(r[4]), nb(r[5]), nb(r[6]), tid, tid == null || source.get(tid) == null ? null : source.get(tid).nom(),
                    vente ? servicesRetours.getOrDefault(id(r[0]), BigDecimal.ZERO) : BigDecimal.ZERO));
        }
        List<Mouvement> mouvements = new ArrayList<>();
        for (Object[] r : lignes("select m.id, m.dateMouvement, m.compte.id, m.montant, m.source, m.motif, m.vente.id, m.achat.id, m.client.id, m.fournisseur.id, m.retour.id, "
                + "m.operationTresorerie.id, m.comptageCaisse.id, m.annulationDe.id from MouvementCaisse m where m.boutique.id = :b and m.dateMouvement <= :fin", boutiqueId, fin)) {
            mouvements.add(new Mouvement(id(r[0]), date(r[1]), id(r[2]), nb(r[3]), (String) r[4], (String) r[5], id(r[6]), id(r[7]), id(r[8]), id(r[9]), id(r[10]), id(r[11]), id(r[12]), id(r[13])));
        }
        Map<Long, Operation> operations = new LinkedHashMap<>();
        for (Object[] r : lignes("select o.id, o.type, o.motif, p.id, p.nom, e.numero from OperationTresorerie o left join o.partenaireFinancier p left join o.emprunt e "
                + "where o.boutique.id = :b and o.dateOperation <= :fin", boutiqueId, fin)) {
            operations.put(id(r[0]), new Operation(id(r[0]), (TypeOperationTresorerie) r[1], (String) r[2], id(r[3]), (String) r[4], (String) r[5]));
        }
        List<Ajustement> ajustements = new ArrayList<>();
        for (Object[] r : lignes("select a.id, a.dateAjustement, a.client.id, a.fournisseur.id, a.ancienSolde, a.nouveauSolde, a.motif from AjustementSoldeDepart a "
                + "where a.boutique.id = :b and a.dateAjustement <= :fin", boutiqueId, fin)) {
            ajustements.add(new Ajustement(id(r[0]), date(r[1]), id(r[2]), id(r[3]), nb(r[4]), nb(r[5]), (String) r[6]));
        }
        // Les ajustements après la date ne comptent pas, mais le solde d'origine d'un partenaire ajusté plus tard reste celui d'avant le premier ajustement.
        Map<Long, BigDecimal> origineClients = originesAjustees(boutiqueId, true);
        Map<Long, BigDecimal> origineFournisseurs = originesAjustees(boutiqueId, false);
        List<Partenaire> listeClients = reporter(clients, origineClients);
        List<Partenaire> listeFournisseurs = reporter(fournisseurs, origineFournisseurs);

        LocalDateTime ouverture = ouverture(ventes, achats, retours, mouvements, ajustements);
        BigDecimal stockDepart = stockDepart(boutiqueId, fin);
        List<ValeurStock> valeurs = regulariser ? List.of(new ValeurStock(fin, stockFige != null ? stockFige : valeurStock(boutiqueId, fin, maintenant))) : List.of();
        Map<Long, Operation> ops = operations;
        return new DonneesComptables(ouverture, ventes, achats, retours, mouvements, ops, listeClients, listeFournisseurs, ajustements, stockDepart, valeurs, reprise);
    }

    /** Solde de départ des partenaires ajustés après la date : le solde enregistré est postérieur à l'ajustement, on revient à l'ancien. */
    private List<Partenaire> reporter(Map<Long, Partenaire> partenaires, Map<Long, BigDecimal> origines) {
        List<Partenaire> r = new ArrayList<>();
        for (Partenaire p : partenaires.values()) r.add(origines.containsKey(p.id()) ? new Partenaire(p.id(), p.nom(), origines.get(p.id())) : p);
        return r;
    }

    private Map<Long, BigDecimal> originesAjustees(Long boutiqueId, boolean client) {
        Map<Long, BigDecimal> m = new HashMap<>();
        String champ = client ? "client" : "fournisseur";
        for (Object[] r : lignes("select a." + champ + ".id, a.ancienSolde from AjustementSoldeDepart a where a.boutique.id = :b and a." + champ + ".id is not null "
                + "order by a.dateAjustement desc, a.id desc", boutiqueId, null)) m.put(id(r[0]), nb(r[1]));
        return m;
    }

    /** Début du jour de la plus ancienne opération ; {@code null} s'il n'y en a aucune avant la date (les livres sont alors vides). */
    private static LocalDateTime ouverture(List<Vente> v, List<Achat> a, List<Retour> r, List<Mouvement> m, List<Ajustement> aj) {
        LocalDateTime min = null;
        for (Vente x : v) if (min == null || x.date().isBefore(min)) min = x.date();
        for (Achat x : a) if (min == null || x.date().isBefore(min)) min = x.date();
        for (Retour x : r) if (min == null || x.date().isBefore(min)) min = x.date();
        for (Mouvement x : m) if (min == null || x.date().isBefore(min)) min = x.date();
        for (Ajustement x : aj) if (min == null || x.date().isBefore(min)) min = x.date();
        return min == null ? null : min.toLocalDate().atStartOfDay();
    }

    // ---- Stock ----------------------------------------------------------------------------------------------------

    /** Valeur du stock de départ : somme des entrées « stock initial » au coût unitaire enregistré. */
    BigDecimal stockDepart(Long boutiqueId, LocalDateTime fin) {
        Object o = em.createQuery("select coalesce(sum((s.quantiteApres - s.quantiteAvant) * coalesce(s.coutUnitaire, 0)), 0) from MouvementStock s "
                        + "where s.boutique.id = :b and s.type = org.example.stock.enums.TypeMouvementStock.INITIAL and s.creeLe <= :fin")
                .setParameter("b", boutiqueId).setParameter("fin", fin).getSingleResult();
        return nb(o);
    }

    /**
     * Valeur du stock à une date : quantités actuelles remontées en arrière par les ventes, achats, retours et ajustements postérieurs, valorisées au
     * coût moyen pondéré actuel (le coût de la date n'est pas conservé : limite documentée).
     */
    BigDecimal valeurStock(Long boutiqueId, LocalDateTime fin, LocalDateTime maintenant) {
        Map<Long, BigDecimal[]> produits = new HashMap<>();
        for (Object o : em.createNativeQuery("select id, quantite, cout_moyen from produit where boutique_id = :b and (nature is null or nature = 'STOCK')")
                .setParameter("b", boutiqueId).getResultList()) {
            Object[] r = (Object[]) o;
            produits.put(id(r[0]), new BigDecimal[] {nb(r[1]), nb(r[2])});
        }
        if (fin.isBefore(maintenant)) {
            ajuster(produits, quantites("select d.produit.id, sum(d.quantite) from DetailVente d where d.vente.boutique.id = :b and d.vente.dateVente > :fin group by d.produit.id", boutiqueId, fin), 1);
            ajuster(produits, quantites("select d.produit.id, sum(d.quantite) from DetailAchat d where d.achat.boutique.id = :b and d.achat.dateAchat > :fin group by d.produit.id", boutiqueId, fin), -1);
            ajuster(produits, quantites("select d.produit.id, sum(d.quantite) from DetailRetour d where d.retour.boutique.id = :b and d.retour.type = org.example.stock.enums.TypeRetour.VENTE and d.retour.dateRetour > :fin group by d.produit.id", boutiqueId, fin), -1);
            ajuster(produits, quantites("select d.produit.id, sum(d.quantite) from DetailRetour d where d.retour.boutique.id = :b and d.retour.type = org.example.stock.enums.TypeRetour.ACHAT and d.retour.dateRetour > :fin group by d.produit.id", boutiqueId, fin), 1);
            ajuster(produits, quantites("select s.produit.id, sum(s.quantiteApres - s.quantiteAvant) from MouvementStock s where s.boutique.id = :b and s.creeLe > :fin group by s.produit.id", boutiqueId, fin), -1);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal[] p : produits.values()) if (p[0].signum() > 0) total = total.add(p[0].multiply(p[1]));
        return total.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static void ajuster(Map<Long, BigDecimal[]> produits, Map<Long, BigDecimal> delta, int signe) {
        delta.forEach((pid, q) -> { BigDecimal[] p = produits.get(pid); if (p != null) p[0] = p[0].add(q.multiply(BigDecimal.valueOf(signe))); });
    }

    private Map<Long, BigDecimal> quantites(String jpql, Long boutiqueId, LocalDateTime fin) {
        Map<Long, BigDecimal> m = new HashMap<>();
        for (Object[] r : lignes(jpql, boutiqueId, fin)) m.put(id(r[0]), nb(r[1]));
        return m;
    }

    /** Valeur du stock de l'application aujourd'hui, lue sans les mouvements : sert au contrôle du stock. */
    @Transactional(readOnly = true)
    public BigDecimal valeurStockAppli(Long boutiqueId) {
        Object o = em.createNativeQuery("select coalesce(sum(quantite * cout_moyen), 0) from produit where boutique_id = :b and (nature is null or nature = 'STOCK') and quantite > 0")
                .setParameter("b", boutiqueId).getSingleResult();
        return nb(o).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    // ---- Outils ---------------------------------------------------------------------------------------------------

    private Map<Long, Partenaire> partenaires(String table, Long boutiqueId) {
        Map<Long, Partenaire> m = new LinkedHashMap<>();
        for (Object o : em.createNativeQuery("select id, nom, coalesce(solde_initial, 0) from " + table + " where boutique_id = :b order by id").setParameter("b", boutiqueId).getResultList()) {
            Object[] r = (Object[]) o;
            m.put(id(r[0]), new Partenaire(id(r[0]), (String) r[1], nb(r[2])));
        }
        return m;
    }

    private Map<Long, BigDecimal> servicesParDocument(String from, String alias, Long boutiqueId, String champDate, LocalDateTime fin, String prix) {
        Map<Long, BigDecimal> m = new HashMap<>();
        for (Object[] r : lignes("select " + alias + ".id, sum(d.quantite * " + prix + ") from " + from + " where " + alias + ".boutique.id = :b and " + champDate
                + " <= :fin and p.nature = org.example.stock.enums.NatureProduit.SERVICE group by " + alias + ".id", boutiqueId, fin)) m.put(id(r[0]), nb(r[1]));
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> lignes(String jpql, Long boutiqueId, LocalDateTime fin) {
        Query q = em.createQuery(jpql).setParameter("b", boutiqueId);
        if (jpql.contains(":fin")) q.setParameter("fin", fin);
        List<Object[]> r = new ArrayList<>();
        for (Object o : q.getResultList()) r.add(o instanceof Object[] t ? t : new Object[] {o});
        return r;
    }

    private static Long id(Object o) { return o == null ? null : ((Number) o).longValue(); }

    private static BigDecimal nb(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        return new BigDecimal(o.toString());
    }

    private static LocalDateTime date(Object o) {
        if (o instanceof LocalDateTime d) return d;
        if (o instanceof Timestamp t) return t.toLocalDateTime();
        return (LocalDateTime) o;
    }
}
