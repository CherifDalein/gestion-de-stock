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

    /**
     * Table des factures, colonne du partenaire, source des mouvements de caisse qui règlent son solde de départ et type
     * des retours dont les avoirs sont une avance (retours de vente pour un client, retours d'achat pour un fournisseur ;
     * ils portent le partenaire dans la même colonne que les factures).
     */
    public enum Partenaire {
        CLIENT("client", "vente", "client_id", "SOLDE_CLIENT", "VENTE"),
        FOURNISSEUR("fournisseur", "achat", "fournisseur_id", "SOLDE_FOURNISSEUR", "ACHAT");

        final String table, factures, colonne, source, typeRetour;

        Partenaire(String table, String factures, String colonne, String source, String typeRetour) {
            this.table = table; this.factures = factures; this.colonne = colonne; this.source = source; this.typeRetour = typeRetour;
        }
    }

    /**
     * Somme par partenaire des trop-payés (calculés avec le total d'origine), des avances utilisées, des restes à payer
     * de ses factures (calculés avec le total effectif, retours déduits) et des avoirs de ses retours (de vente ou d'achat).
     */
    public record Factures(BigDecimal surplus, BigDecimal avanceUtilisee, BigDecimal reste, BigDecimal avoirs) {
        public static final Factures VIDE = new Factures(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        public Factures(BigDecimal surplus, BigDecimal avanceUtilisee, BigDecimal reste) {
            this(surplus, avanceUtilisee, reste, BigDecimal.ZERO);
        }
    }

    /**
     * Règlements du solde de départ d'un partenaire, en valeur absolue : {@code net} = règlements − leurs annulations (ce qui
     * reste réglé, base du reste à régler) ; {@code brut} = Σ des règlements enregistrés, annulés ou non (un solde de
     * départ qui a servi une fois reste figé, même après annulation).
     */
    public record Reglements(BigDecimal net, BigDecimal brut) {
        public static final Reglements AUCUN = new Reglements(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** Total et part dont l'échéance est strictement antérieure au jour donné. */
    public record Totaux(BigDecimal total, BigDecimal enRetard) {}

    /** Montant en retard d'un partenaire de la boutique et plus ancienne échéance dépassée. */
    public record RetardPartenaire(Long id, String nom, BigDecimal montant, LocalDate plusAncienneEcheance) {}

    /**
     * Un règlement du solde de départ compte pour son montant ; son annulation (source {@code ANNULATION}, rattachée au même
     * partenaire par la même colonne) le retranche. Les annulations des règlements de factures ne portent jamais cette colonne.
     */
    private static final String NET_REGLEMENT = "CASE WHEN source = 'ANNULATION' THEN -ABS(montant) ELSE ABS(montant) END";
    private static final String NET_REGLEMENT_M = "CASE WHEN m.source = 'ANNULATION' THEN -ABS(m.montant) ELSE ABS(m.montant) END";
    private static final String BRUT_REGLEMENT = "CASE WHEN source = 'ANNULATION' THEN 0 ELSE ABS(montant) END";

    /** Reste à payer = max(0, total effectif − versé − avance utilisée), avec total effectif = total − retours. */
    private static final String RESTE = "GREATEST(COALESCE(montant_total,0) - COALESCE(montant_retours,0) - COALESCE(montant_verse,0) - COALESCE(avance_utilisee,0), 0)";
    private static final String RESTE_F = "GREATEST(COALESCE(f.montant_total,0) - COALESCE(f.montant_retours,0) - COALESCE(f.montant_verse,0) - COALESCE(f.avance_utilisee,0), 0)";
    /** Trop-payé d'origine : calculé avec le total d'origine, jamais avec le total effectif (l'excédent d'un retour est traité par le retour). */
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
        // Avoirs des retours du partenaire : une avance, en plus des trop-payés.
        var avoirs = entityManager.createNativeQuery("SELECT " + type.colonne + ", COALESCE(SUM(montant_avoir),0) FROM retour WHERE boutique_id = :boutique AND type = :typeRetour AND "
                        + type.colonne + " IS NOT NULL" + (partenaireId == null ? "" : " AND " + type.colonne + " = :partenaire") + " GROUP BY " + type.colonne)
                .setParameter("boutique", boutiqueId).setParameter("typeRetour", type.typeRetour);
        if (partenaireId != null) avoirs.setParameter("partenaire", partenaireId);
        for (Object ligne : avoirs.getResultList()) {
            Object[] colonnes = (Object[]) ligne;
            Factures actuel = resultat.getOrDefault(((Number) colonnes[0]).longValue(), Factures.VIDE);
            resultat.put(((Number) colonnes[0]).longValue(), new Factures(actuel.surplus(), actuel.avanceUtilisee(), actuel.reste(), montant(colonnes[1])));
        }
        return resultat;
    }

    /** Règlements du solde de départ (nets de leurs annulations, et bruts), par partenaire de la boutique. */
    public Map<Long, Reglements> reglementsSoldeDepart(Partenaire type, Long boutiqueId) {
        return lireReglements(type, boutiqueId, null);
    }

    public Reglements reglementsSoldeDepart(Partenaire type, Long boutiqueId, Long partenaireId) {
        return lireReglements(type, boutiqueId, partenaireId).getOrDefault(partenaireId, Reglements.AUCUN);
    }

    private Map<Long, Reglements> lireReglements(Partenaire type, Long boutiqueId, Long partenaireId) {
        var requete = entityManager.createNativeQuery("SELECT " + type.colonne + ", COALESCE(SUM(" + NET_REGLEMENT + "),0), COALESCE(SUM(" + BRUT_REGLEMENT
                + "),0) FROM mouvement_caisse WHERE boutique_id = :boutique AND source IN (:source, 'ANNULATION') AND "
                + type.colonne + " IS NOT NULL" + (partenaireId == null ? "" : " AND " + type.colonne + " = :partenaire") + " GROUP BY " + type.colonne)
                .setParameter("boutique", boutiqueId).setParameter("source", type.source);
        if (partenaireId != null) requete.setParameter("partenaire", partenaireId);
        Map<Long, Reglements> resultat = new LinkedHashMap<>();
        for (Object ligne : requete.getResultList()) {
            Object[] colonnes = (Object[]) ligne;
            resultat.put(((Number) colonnes[0]).longValue(), new Reglements(montant(colonnes[1]), montant(colonnes[2])));
        }
        return resultat;
    }

    /**
     * Lecture verrouillée des seules factures qui portent une avance (trop-payé ou avance utilisée) d'un partenaire,
     * et des avoirs de ses retours (de vente ou d'achat). Le trop-payé reste calculé avec le total d'origine.
     */
    public Factures facturesVerrouillees(Partenaire type, Long boutiqueId, Long partenaireId) {
        List<?> lignes = entityManager.createNativeQuery("SELECT COALESCE(montant_verse,0), COALESCE(montant_total,0), COALESCE(avance_utilisee,0) FROM " + type.factures
                        + " WHERE boutique_id = :boutique AND " + type.colonne + " = :partenaire AND (COALESCE(montant_verse,0) > COALESCE(montant_total,0) OR COALESCE(avance_utilisee,0) > 0) FOR UPDATE")
                .setParameter("boutique", boutiqueId).setParameter("partenaire", partenaireId).getResultList();
        BigDecimal surplus = BigDecimal.ZERO, utilisee = BigDecimal.ZERO, avoirs = BigDecimal.ZERO;
        for (Object ligne : new ArrayList<>(lignes)) {
            Object[] colonnes = (Object[]) ligne;
            BigDecimal verse = montant(colonnes[0]), total = montant(colonnes[1]);
            if (verse.compareTo(total) > 0) surplus = surplus.add(verse.subtract(total));
            utilisee = utilisee.add(montant(colonnes[2]));
        }
        for (Object montantAvoir : entityManager.createNativeQuery("SELECT montant_avoir FROM retour WHERE boutique_id = :boutique AND type = :typeRetour AND "
                        + type.colonne + " = :partenaire AND montant_avoir > 0 FOR UPDATE")
                .setParameter("boutique", boutiqueId).setParameter("typeRetour", type.typeRetour).setParameter("partenaire", partenaireId).getResultList()) {
            avoirs = avoirs.add(montant(montantAvoir));
        }
        return new Factures(surplus, utilisee, BigDecimal.ZERO, avoirs);
    }

    /**
     * Vrai si une facture de ce partenaire dans cette boutique (vente d'un client, achat d'un fournisseur) a consommé de
     * l'avance ({@code avance_utilisee > 0}). Lecture verrouillée, à n'utiliser qu'après le verrou de la fiche.
     */
    public boolean avanceUtiliseeVerrouillee(Partenaire type, Long boutiqueId, Long partenaireId) {
        return !entityManager.createNativeQuery("SELECT id FROM " + type.factures + " WHERE boutique_id = :boutique AND " + type.colonne
                        + " = :partenaire AND COALESCE(avance_utilisee,0) > 0 FOR UPDATE")
                .setParameter("boutique", boutiqueId).setParameter("partenaire", partenaireId).getResultList().isEmpty();
    }

    /** Lecture verrouillée des règlements du solde de départ d'un partenaire et de leurs annulations. */
    public Reglements reglementsVerrouilles(Partenaire type, Long boutiqueId, Long partenaireId) {
        List<?> lignes = entityManager.createNativeQuery("SELECT montant, source FROM mouvement_caisse WHERE boutique_id = :boutique AND source IN (:source, 'ANNULATION') AND "
                        + type.colonne + " = :partenaire FOR UPDATE")
                .setParameter("boutique", boutiqueId).setParameter("source", type.source).setParameter("partenaire", partenaireId).getResultList();
        BigDecimal net = BigDecimal.ZERO, brut = BigDecimal.ZERO;
        for (Object ligne : lignes) {
            Object[] colonnes = (Object[]) ligne;
            BigDecimal valeur = montant(colonnes[0]).abs();
            if ("ANNULATION".equals(colonnes[1])) net = net.subtract(valeur);
            else { net = net.add(valeur); brut = brut.add(valeur); }
        }
        return new Reglements(net, brut);
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
        String du = type == Partenaire.CLIENT ? "GREATEST(p.solde_initial, 0)" : "GREATEST(-p.solde_initial, 0)";
        // Reste par partenaire, jamais négatif : un solde ajusté sous ce qui a été réglé laisse un trop-réglé (une avance), pas un reste négatif.
        String reste = "GREATEST(" + du + " - COALESCE((SELECT SUM(" + NET_REGLEMENT_M + ") FROM mouvement_caisse m WHERE m." + type.colonne
                + " = p.id AND m.boutique_id = :boutique AND m.source IN (:source, 'ANNULATION')),0), 0)";
        Object[] ligne = (Object[]) entityManager.createNativeQuery("SELECT COALESCE(SUM(" + reste + "),0), COALESCE(SUM(CASE WHEN p.echeance_solde_initial < :jour THEN " + reste
                        + " ELSE 0 END),0) FROM " + type.table + " p WHERE p.boutique_id = :boutique")
                .setParameter("jour", aujourdHui).setParameter("boutique", boutiqueId).setParameter("source", type.source).getSingleResult();
        return new Totaux(montant(ligne[0]), montant(ligne[1]));
    }

    /**
     * Montants en retard par partenaire (factures dont l'échéance est strictement antérieure au jour donné, plus reste du
     * solde de départ échu), avec la plus ancienne échéance dépassée. Deux requêtes agrégées ; seuls les partenaires
     * dont le montant en retard est positif sont rendus, sans ordre.
     */
    public List<RetardPartenaire> retardsParPartenaire(Partenaire type, Long boutiqueId, LocalDate aujourdHui) {
        Map<Long, String> noms = new LinkedHashMap<>();
        Map<Long, BigDecimal> montants = new LinkedHashMap<>();
        Map<Long, LocalDate> echeances = new LinkedHashMap<>();
        for (Object ligne : entityManager.createNativeQuery("SELECT f." + type.colonne + ", p.nom, SUM(" + RESTE_F + "), MIN(f.date_echeance) FROM " + type.factures
                        + " f JOIN " + type.table + " p ON p.id = f." + type.colonne + " WHERE f.boutique_id = :boutique AND p.boutique_id = :boutique AND f.date_echeance < :jour AND "
                        + RESTE_F + " > 0 GROUP BY f." + type.colonne + ", p.nom")
                .setParameter("boutique", boutiqueId).setParameter("jour", aujourdHui).getResultList()) {
            Object[] c = (Object[]) ligne;
            long id = ((Number) c[0]).longValue();
            noms.put(id, String.valueOf(c[1])); montants.put(id, montant(c[2])); echeances.put(id, date(c[3]));
        }
        String du = type == Partenaire.CLIENT ? "GREATEST(p.solde_initial, 0)" : "GREATEST(-p.solde_initial, 0)";
        for (Object ligne : entityManager.createNativeQuery("SELECT p.id, p.nom, " + du + " - COALESCE(SUM(" + NET_REGLEMENT_M + "),0), p.echeance_solde_initial FROM " + type.table
                        + " p LEFT JOIN mouvement_caisse m ON m." + type.colonne + " = p.id AND m.boutique_id = :boutique AND m.source IN (:source, 'ANNULATION')"
                        + " WHERE p.boutique_id = :boutique AND p.echeance_solde_initial < :jour AND " + du + " > 0"
                        + " GROUP BY p.id, p.nom, p.solde_initial, p.echeance_solde_initial")
                .setParameter("boutique", boutiqueId).setParameter("jour", aujourdHui).setParameter("source", type.source).getResultList()) {
            Object[] c = (Object[]) ligne;
            BigDecimal reste = montant(c[2]);
            if (reste.signum() <= 0) continue;
            long id = ((Number) c[0]).longValue();
            noms.put(id, String.valueOf(c[1]));
            montants.merge(id, reste, BigDecimal::add);
            LocalDate echeance = date(c[3]);
            echeances.merge(id, echeance, (a, b) -> a.isBefore(b) ? a : b);
        }
        List<RetardPartenaire> resultat = new ArrayList<>();
        montants.forEach((id, montant) -> resultat.add(new RetardPartenaire(id, noms.get(id), montant, echeances.get(id))));
        return resultat;
    }

    /** Σ des restes en retard des factures sans partenaire (vente sans client, achat sans fournisseur). */
    public BigDecimal retardFacturesSansPartenaire(Partenaire type, Long boutiqueId, LocalDate aujourdHui) {
        return montant(entityManager.createNativeQuery("SELECT COALESCE(SUM(" + RESTE + "),0) FROM " + type.factures + " WHERE boutique_id = :boutique AND "
                        + type.colonne + " IS NULL AND date_echeance < :jour")
                .setParameter("boutique", boutiqueId).setParameter("jour", aujourdHui).getSingleResult());
    }

    private static LocalDate date(Object valeur) {
        return valeur instanceof java.sql.Date d ? d.toLocalDate() : valeur instanceof LocalDate l ? l : LocalDate.parse(valeur.toString().substring(0, 10));
    }

    private static BigDecimal montant(Object valeur) {
        return valeur == null ? BigDecimal.ZERO : valeur instanceof BigDecimal b ? b : new BigDecimal(valeur.toString());
    }
}
