package org.example.stock.compta;

import org.example.stock.compta.Ecriture.Ligne;
import org.example.stock.compta.Ecriture.TypeTiers;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Soldes (débit moins crédit) d'un ensemble d'écritures, par compte et par tiers. Le classement des comptes à double sens au bilan se fait
 * <b>par compte de tiers</b> (un client, un fournisseur, un associé) : le texte du Système comptable interdit de compenser les clients à solde
 * débiteur avec ceux à solde créditeur. Les lignes sans tiers d'un même compte forment un seul solde.
 */
public final class SoldesComptables {
    /** Un compte, et le tiers qui le porte (ou {@code null}). */
    public record Cle(String compte, TypeTiers tiersType, Long tiersId) {}

    private final Map<Cle, BigDecimal> soldes = new LinkedHashMap<>();

    public static SoldesComptables de(Collection<Ecriture> ecritures) {
        SoldesComptables s = new SoldesComptables();
        for (Ecriture e : ecritures) s.ajouter(e);
        return s;
    }

    public SoldesComptables ajouter(Ecriture e) {
        for (Ligne l : e.lignes()) soldes.merge(new Cle(l.compte(), l.tiersType(), l.tiersId()), l.solde(), BigDecimal::add);
        return this;
    }

    /** Soldes non nuls, par compte et tiers. */
    public Map<Cle, BigDecimal> atomes() {
        Map<Cle, BigDecimal> r = new LinkedHashMap<>();
        soldes.forEach((k, v) -> { if (v.signum() != 0) r.put(k, v); });
        return r;
    }

    /** Soldes non nuls, par compte (tous tiers confondus), du plus petit numéro au plus grand. */
    public Map<String, BigDecimal> parCompte() {
        Map<String, BigDecimal> r = new TreeMap<>();
        soldes.forEach((k, v) -> r.merge(k.compte(), v, BigDecimal::add));
        r.values().removeIf(v -> v.signum() == 0);
        return r;
    }

    /** Somme des soldes des comptes qui commencent par l'un des préfixes. */
    public BigDecimal solde(String... prefixes) {
        BigDecimal t = BigDecimal.ZERO;
        for (var e : soldes.entrySet()) for (String p : prefixes) if (e.getKey().compte().startsWith(p)) { t = t.add(e.getValue()); break; }
        return t;
    }

    public SoldesComptables filtrer(Predicate<String> compte) {
        SoldesComptables r = new SoldesComptables();
        soldes.forEach((k, v) -> { if (compte.test(k.compte())) r.soldes.put(k, v); });
        return r;
    }

    /** Ces soldes moins ceux de {@code autre}, compte par compte et tiers par tiers. */
    public SoldesComptables moins(SoldesComptables autre) {
        SoldesComptables r = new SoldesComptables();
        r.soldes.putAll(soldes);
        autre.soldes.forEach((k, v) -> r.soldes.merge(k, v.negate(), BigDecimal::add));
        return r;
    }

    public SoldesComptables plus(SoldesComptables autre) {
        SoldesComptables r = new SoldesComptables();
        r.soldes.putAll(soldes);
        autre.soldes.forEach((k, v) -> r.soldes.merge(k, v, BigDecimal::add));
        return r;
    }

    /** Vrai pour un compte de gestion (classes 6, 7 et 8) : il alimente le compte de résultat, pas le bilan. */
    public static boolean estGestion(String compte) { return compte.startsWith("6") || compte.startsWith("7") || compte.startsWith("8"); }

    /** Vrai pour un compte de bilan (classes 1 à 5). */
    public static boolean estBilan(String compte) { return !compte.isEmpty() && compte.charAt(0) >= '1' && compte.charAt(0) <= '5'; }
}
