package org.example.stock.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agrégats des soldes des partenaires. Toutes les requêtes reçoivent la boutique du compte connecté.
 *
 * <p>Les requêtes « verrouillées » lisent les lignes avec {@code FOR UPDATE} : elles voient les documents validés
 * après le début de la transaction (REPEATABLE READ) et attendent ceux d'une transaction en cours. Elles ne doivent
 * servir qu'après le verrou de la ligne du partenaire. Le SQL natif est construit uniquement avec les constantes
 * de {@link Partenaire} ; les valeurs sont toujours des paramètres.</p>
 */
@Repository
public class SoldePartenaireRepository {

    /** Table des factures, colonne du partenaire et source des mouvements de caisse qui règlent son solde de départ. */
    public enum Partenaire {
        CLIENT("client", "vente", "client_id", "SOLDE_CLIENT"),
        FOURNISSEUR("fournisseur", "achat", "fournisseur_id", "SOLDE_FOURNISSEUR");

        final String table, factures, colonne, source;

        Partenaire(String table, String factures, String colonne, String source) {
            this.table = table; this.factures = factures; this.colonne = colonne; this.source = source;
        }
    }

    /** Somme par partenaire des trop-payés, des avances utilisées et des restes à payer de ses factures. */
    public record Factures(BigDecimal surplus, BigDecimal avanceUtilisee, BigDecimal reste) {
        public static final Factures VIDE = new Factures(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** Total et part dont l'échéance est strictement antérieure au jour donné. */
    public record Totaux(BigDecimal total, BigDecimal enRetard) {}

    private static final String RESTE = "GREATEST(COALESCE(montant_total,0) - COALESCE(montant_verse,0) - COALESCE(avance_utilisee,0), 0)";
    private static final String SURPLUS = "GREATEST(COALESCE(montant_verse,0) - COALESCE(montant_total,0), 0)";

    private final EntityManager entityManager;

    public SoldePartenaireRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Factures de tous les partenaires de la boutique, par identifiant de partenaire. */
    public Map<Long, Factures> factures(Partenaire type, Long boutiqueId) {
        return lireFactures(type, boutiqueId, null);
    }

    public Factures factures(Partenaire type, Long boutiqueId, Long partenaireId) {
        return lireFactures(type, boutiqueId, partenaireId).getOrDefault(partenaireId, Factures.VIDE);
    }

    private Map<Long, Factures> lireFactures(Partenaire type, Long boutiqueId, Long partenaireId) {
        var requete = entityManager.createNativeQuery("SELECT " + type.colonne + ", COALESCE(SUM(" + SURPLUS + "),0), COALESCE(SUM(COALESCE(avance_utilisee,0)),0), COALESCE(SUM(" + RESTE
                + "),0) FROM " + type.factures + " WHERE boutique_id = :boutique AND " + type.colonne + " IS NOT NULL"
                + (partenaireId == null ? "" : " AND " + type.colonne + " = :partenaire") + " GROUP BY " + type.colonne)
                .setParameter("boutique", boutiqueId);
        if (partenaireId != null) requete.setParameter("partenaire", partenaireId);
        Map<Long, Factures> resultat = new LinkedHashMap<>();
        for (Object ligne : requete.getResultList()) {
            Object[] colonnes = (Object[]) ligne;
            resultat.put(((Number) colonnes[0]).longValue(), new Factures(montant(colonnes[1]), montant(colonnes[2]), montant(colonnes[3])));
        }
        return resultat;
    }

    /** Σ des règlements du solde de départ, en valeur absolue, par partenaire de la boutique. */
    public Map<Long, BigDecimal> reglementsSoldeDepart(Partenaire type, Long boutiqueId) {
        return lireReglements(type, boutiqueId, null);
    }

    public BigDecimal reglementsSoldeDepart(Partenaire type, Long boutiqueId, Long partenaireId) {
        return lireReglements(type, boutiqueId, partenaireId).getOrDefault(partenaireId, BigDecimal.ZERO);
    }

    private Map<Long, BigDecimal> lireReglements(Partenaire type, Long boutiqueId, Long partenaireId) {
        var requete = entityManager.createNativeQuery("SELECT " + type.colonne + ", COALESCE(SUM(ABS(montant)),0) FROM mouvement_caisse WHERE boutique_id = :boutique AND source = :source AND "
                + type.colonne + " IS NOT NULL" + (partenaireId == null ? "" : " AND " + type.colonne + " = :partenaire") + " GROUP BY " + type.colonne)
                .setParameter("boutique", boutiqueId).setParameter("source", type.source);
        if (partenaireId != null) requete.setParameter("partenaire", partenaireId);
        Map<Long, BigDecimal> resultat = new LinkedHashMap<>();
        for (Object ligne : requete.getResultList()) {
            Object[] colonnes = (Object[]) ligne;
            resultat.put(((Number) colonnes[0]).longValue(), montant(colonnes[1]));
        }
        return resultat;
    }

    /**
     * Lecture verrouillée des seules factures qui portent une avance (trop-payé ou avance utilisée) d'un partenaire.
     * Chaque élément est {surplus, avanceUtilisee}.
     */
    public Factures facturesVerrouillees(Partenaire type, Long boutiqueId, Long partenaireId) {
        List<?> lignes = entityManager.createNativeQuery("SELECT COALESCE(montant_verse,0), COALESCE(montant_total,0), COALESCE(avance_utilisee,0) FROM " + type.factures
                        + " WHERE boutique_id = :boutique AND " + type.colonne + " = :partenaire AND (COALESCE(montant_verse,0) > COALESCE(montant_total,0) OR COALESCE(avance_utilisee,0) > 0) FOR UPDATE")
                .setParameter("boutique", boutiqueId).setParameter("partenaire", partenaireId).getResultList();
        BigDecimal surplus = BigDecimal.ZERO, utilisee = BigDecimal.ZERO;
        for (Object ligne : new ArrayList<>(lignes)) {
            Object[] colonnes = (Object[]) ligne;
            BigDecimal verse = montant(colonnes[0]), total = montant(colonnes[1]);
            if (verse.compareTo(total) > 0) surplus = surplus.add(verse.subtract(total));
            utilisee = utilisee.add(montant(colonnes[2]));
        }
        return new Factures(surplus, utilisee, BigDecimal.ZERO);
    }

    /** Lecture verrouillée des règlements du solde de départ d'un partenaire. */
    public BigDecimal reglementsVerrouilles(Partenaire type, Long boutiqueId, Long partenaireId) {
        List<?> lignes = entityManager.createNativeQuery("SELECT montant FROM mouvement_caisse WHERE boutique_id = :boutique AND source = :source AND "
                        + type.colonne + " = :partenaire FOR UPDATE")
                .setParameter("boutique", boutiqueId).setParameter("source", type.source).setParameter("partenaire", partenaireId).getResultList();
        BigDecimal total = BigDecimal.ZERO;
        for (Object ligne : lignes) total = total.add(montant(ligne).abs());
        return total;
    }

    /** Σ des restes à payer des factures de la boutique (avec ou sans partenaire) et part en retard. */
    public Totaux restesFactures(Partenaire type, Long boutiqueId, LocalDate aujourdHui) {
        Object[] ligne = (Object[]) entityManager.createNativeQuery("SELECT COALESCE(SUM(" + RESTE + "),0), COALESCE(SUM(CASE WHEN date_echeance < :jour THEN " + RESTE
                        + " ELSE 0 END),0) FROM " + type.factures + " WHERE boutique_id = :boutique")
                .setParameter("jour", aujourdHui).setParameter("boutique", boutiqueId).getSingleResult();
        return new Totaux(montant(ligne[0]), montant(ligne[1]));
    }

    /**
     * Σ des soldes de départ restant à régler (clients qui me doivent, ou fournisseurs à qui je dois) et part en retard :
     * les soldes de départ du bon sens, moins leurs règlements.
     */
    public Totaux restesSoldesDepart(Partenaire type, Long boutiqueId, LocalDate aujourdHui) {
        String du = type == Partenaire.CLIENT ? "GREATEST(solde_initial, 0)" : "GREATEST(-solde_initial, 0)";
        Object[] soldes = (Object[]) entityManager.createNativeQuery("SELECT COALESCE(SUM(" + du + "),0), COALESCE(SUM(CASE WHEN echeance_solde_initial < :jour THEN " + du
                        + " ELSE 0 END),0) FROM " + type.table + " WHERE boutique_id = :boutique")
                .setParameter("jour", aujourdHui).setParameter("boutique", boutiqueId).getSingleResult();
        Object[] regles = (Object[]) entityManager.createNativeQuery("SELECT COALESCE(SUM(ABS(m.montant)),0), COALESCE(SUM(CASE WHEN p.echeance_solde_initial < :jour THEN ABS(m.montant) ELSE 0 END),0)"
                        + " FROM mouvement_caisse m JOIN " + type.table + " p ON p.id = m." + type.colonne + " WHERE m.boutique_id = :boutique AND m.source = :source")
                .setParameter("jour", aujourdHui).setParameter("boutique", boutiqueId).setParameter("source", type.source).getSingleResult();
        return new Totaux(montant(soldes[0]).subtract(montant(regles[0])), montant(soldes[1]).subtract(montant(regles[1])));
    }

    private static BigDecimal montant(Object valeur) {
        return valeur == null ? BigDecimal.ZERO : valeur instanceof BigDecimal b ? b : new BigDecimal(valeur.toString());
    }
}
