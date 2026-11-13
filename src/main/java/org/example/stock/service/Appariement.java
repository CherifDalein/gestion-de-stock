package org.example.stock.service;

import org.example.stock.model.LigneReleve;
import org.example.stock.model.MouvementCaisse;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Propositions automatiques de rapprochement : une ligne du relevé et un mouvement de l'appli de <b>même montant exact</b> et dont les dates
 * diffèrent d'au plus {@value #TOLERANCE_JOURS} jours. Un mouvement n'est jamais proposé deux fois, ni une ligne.
 *
 * <p>Quand il y a plusieurs candidats, on retient d'abord les paires sans concurrence (la ligne n'a qu'un candidat et le mouvement n'est
 * candidat que d'une ligne), puis la date la plus proche l'emporte ; à égalité de distance, l'ordre des dates puis des identifiants rend le
 * résultat déterministe. Une paire déjà refusée n'est pas reproposée. Classe pure : aucun accès à la base.</p>
 */
final class Appariement {
    static final int TOLERANCE_JOURS = 3;

    record Proposition(LigneReleve ligne, MouvementCaisse mouvement, int ecartJours, boolean unique) { }

    private record Paire(LigneReleve ligne, MouvementCaisse mouvement, int distance) { }

    private Appariement() { }

    /**
     * @param exclus   identifiants des mouvements qui ne se rapprochent pas seuls (annulés, contre-passations)
     * @param refusees paires déjà refusées, sous la forme {@code ligneId + ":" + mouvementId}
     */
    static List<Proposition> proposer(List<LigneReleve> lignes, List<MouvementCaisse> mouvements, Set<Long> exclus, Set<String> refusees) {
        Map<String, List<MouvementCaisse>> parMontant = new HashMap<>();
        for (MouvementCaisse m : mouvements) {
            if (exclus.contains(m.getId()) || m.getMontant() == null) continue;
            parMontant.computeIfAbsent(cle(m.getMontant()), k -> new ArrayList<>()).add(m);
        }
        List<Paire> paires = new ArrayList<>();
        Map<Long, Integer> candidatsLigne = new HashMap<>(), candidatsMouvement = new HashMap<>();
        for (LigneReleve l : lignes) {
            for (MouvementCaisse m : parMontant.getOrDefault(cle(l.getMontant()), List.of())) {
                int distance = (int) Math.abs(ChronoUnit.DAYS.between(l.getDateOperation(), jour(m)));
                if (distance > TOLERANCE_JOURS || refusees.contains(l.getId() + ":" + m.getId())) continue;
                paires.add(new Paire(l, m, distance));
                candidatsLigne.merge(l.getId(), 1, Integer::sum);
                candidatsMouvement.merge(m.getId(), 1, Integer::sum);
            }
        }
        Comparator<Paire> ordre = Comparator
                .comparingInt((Paire p) -> candidatsLigne.get(p.ligne().getId()) == 1 && candidatsMouvement.get(p.mouvement().getId()) == 1 ? 0 : 1)
                .thenComparingInt(Paire::distance)
                .thenComparing(p -> p.ligne().getDateOperation())
                .thenComparing(p -> p.ligne().getId())
                .thenComparing(p -> p.mouvement().getDateMouvement())
                .thenComparing(p -> p.mouvement().getId());
        paires.sort(ordre);
        Set<Long> lignesPrises = new HashSet<>(), mouvementsPris = new HashSet<>();
        List<Proposition> resultat = new ArrayList<>();
        for (Paire p : paires) {
            if (lignesPrises.contains(p.ligne().getId()) || mouvementsPris.contains(p.mouvement().getId())) continue;
            lignesPrises.add(p.ligne().getId()); mouvementsPris.add(p.mouvement().getId());
            boolean unique = candidatsLigne.get(p.ligne().getId()) == 1 && candidatsMouvement.get(p.mouvement().getId()) == 1;
            resultat.add(new Proposition(p.ligne(), p.mouvement(), p.distance(), unique));
        }
        resultat.sort(Comparator.comparing((Proposition p) -> p.ligne().getDateOperation()).thenComparing(p -> p.ligne().getId()));
        return resultat;
    }

    private static LocalDate jour(MouvementCaisse m) { return m.getDateMouvement().toLocalDate(); }

    private static String cle(java.math.BigDecimal montant) { return montant.stripTrailingZeros().toPlainString(); }
}
