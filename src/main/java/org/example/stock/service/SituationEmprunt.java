package org.example.stock.service;

import org.example.stock.model.EcheanceEmprunt;
import org.example.stock.model.Emprunt;
import org.example.stock.model.ImputationEcheance;
import org.example.stock.model.Montants;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * État calculé d'un emprunt ou d'un prêt : pour chaque échéance, ce qui est payé (imputations des remboursements non annulés) et
 * ce qui reste. Rien n'est stocké : le capital restant dû, les retards et la clôture se déduisent de l'échéancier et des imputations.
 */
public final class SituationEmprunt {
    /** Une échéance et ce qui en a été payé ou remis. */
    public record LigneEcheance(EcheanceEmprunt echeance, BigDecimal capitalPaye, BigDecimal interetPaye, BigDecimal interetRemis) {
        public int rang() { return echeance.getRang(); }
        public LocalDate date() { return echeance.getDateEcheance(); }
        public BigDecimal capitalRestant() { return echeance.getCapital().subtract(capitalPaye); }
        public BigDecimal interetRestant() { return echeance.getInteret().subtract(interetPaye).subtract(interetRemis); }
        public BigDecimal resteTotal() { return capitalRestant().add(interetRestant()); }
        public BigDecimal totalPrevu() { return echeance.getCapital().add(echeance.getInteret()); }
        public boolean soldee() { return resteTotal().signum() <= 0; }
        public boolean enRetard(LocalDate aujourdhui) { return !soldee() && date().isBefore(aujourdhui); }
        public boolean partielle() { return !soldee() && (capitalPaye.signum() > 0 || interetPaye.signum() > 0); }
        public long joursDeRetard(LocalDate aujourdhui) { return enRetard(aujourdhui) ? ChronoUnit.DAYS.between(date(), aujourdhui) : 0; }
    }

    /** Part d'un remboursement imputée sur une échéance. */
    public record Part(EcheanceEmprunt echeance, BigDecimal capital, BigDecimal interet, BigDecimal interetRemis) { }

    private final Emprunt emprunt;
    private final List<LigneEcheance> lignes;
    private final LocalDate aujourdhui;

    private SituationEmprunt(Emprunt emprunt, List<LigneEcheance> lignes, LocalDate aujourdhui) {
        this.emprunt = emprunt;
        this.lignes = lignes;
        this.aujourdhui = aujourdhui;
    }

    public static SituationEmprunt de(Emprunt emprunt, List<EcheanceEmprunt> echeances, List<ImputationEcheance> imputationsActives, LocalDate aujourdhui) {
        Map<Long, BigDecimal[]> sommes = new HashMap<>();
        for (ImputationEcheance i : imputationsActives) {
            BigDecimal[] s = sommes.computeIfAbsent(i.getEcheance().getId(), id -> new BigDecimal[]{Montants.ZERO, Montants.ZERO, Montants.ZERO});
            s[0] = s[0].add(i.getCapital()); s[1] = s[1].add(i.getInteret()); s[2] = s[2].add(i.getInteretRemis());
        }
        List<LigneEcheance> lignes = new ArrayList<>();
        for (EcheanceEmprunt e : echeances) {
            BigDecimal[] s = sommes.getOrDefault(e.getId(), new BigDecimal[]{Montants.ZERO, Montants.ZERO, Montants.ZERO});
            lignes.add(new LigneEcheance(e, s[0], s[1], s[2]));
        }
        lignes.sort(java.util.Comparator.comparingInt(LigneEcheance::rang));
        return new SituationEmprunt(emprunt, List.copyOf(lignes), aujourdhui);
    }

    public Emprunt emprunt() { return emprunt; }
    public List<LigneEcheance> lignes() { return lignes; }
    public LocalDate aujourdhui() { return aujourdhui; }

    public BigDecimal capitalPaye() { return somme(LigneEcheance::capitalPaye); }
    public BigDecimal capitalRestantDu() { return somme(LigneEcheance::capitalRestant); }
    public BigDecimal interetsPayes() { return somme(LigneEcheance::interetPaye); }
    public BigDecimal interetsRemis() { return somme(LigneEcheance::interetRemis); }
    public BigDecimal interetsRestants() { return somme(LigneEcheance::interetRestant); }
    public BigDecimal interetsPrevus() { return somme(l -> l.echeance().getInteret()); }
    public BigDecimal totalRestantDu() { return somme(LigneEcheance::resteTotal); }

    /** Première échéance non soldée, ou {@code null} si l'emprunt est clos. */
    public LigneEcheance prochaine() {
        for (LigneEcheance l : lignes) if (!l.soldee()) return l;
        return null;
    }

    public boolean clos() { return prochaine() == null; }
    public boolean aUnRemboursement() { return capitalPaye().signum() > 0 || interetsPayes().signum() > 0 || interetsRemis().signum() > 0; }

    public List<LigneEcheance> enRetard() { return lignes.stream().filter(l -> l.enRetard(aujourdhui)).toList(); }
    public int nombreRetards() { return enRetard().size(); }
    public boolean enRetardMaintenant() { return nombreRetards() > 0; }
    public BigDecimal montantEnRetard() { return enRetard().stream().map(LigneEcheance::resteTotal).reduce(Montants.ZERO, BigDecimal::add); }
    public long plusGrandRetardEnJours() { return enRetard().stream().mapToLong(l -> l.joursDeRetard(aujourdhui)).max().orElse(0); }

    /** Intérêt des échéances échues (date au plus égale à aujourd'hui) non encore payé : ce qui est dû en plus du capital restant. */
    public BigDecimal interetsEchusImpayes() {
        return lignes.stream().filter(l -> !l.date().isAfter(aujourdhui)).map(LigneEcheance::interetRestant).reduce(Montants.ZERO, BigDecimal::add);
    }

    /** Montant du remboursement anticipé du solde : tout le capital restant et les intérêts des échéances échues ; les intérêts à venir sont remis. */
    public BigDecimal soldeAnticipe() { return capitalRestantDu().add(interetsEchusImpayes()); }

    /** Intérêts des échéances à venir que le remboursement anticipé du solde abandonne. */
    public BigDecimal interetsRemisParAnticipation() {
        return lignes.stream().filter(l -> l.date().isAfter(aujourdhui)).map(LigneEcheance::interetRestant).reduce(Montants.ZERO, BigDecimal::add);
    }

    /** Statut affiché : Clos, En retard ou En cours. */
    public String statut() { return clos() ? "Clos" : enRetardMaintenant() ? "En retard" : "En cours"; }

    /**
     * Imputation d'un paiement : échéance par échéance dans l'ordre des rangs, intérêts d'abord puis capital de chaque échéance.
     * Le montant ne peut pas dépasser ce qui reste dû.
     */
    public List<Part> imputer(BigDecimal montant) {
        if (montant == null || montant.signum() <= 0) throw new IllegalArgumentException("Le montant doit être supérieur à zéro.");
        if (montant.compareTo(totalRestantDu()) > 0) throw new IllegalArgumentException("Le montant dépasse ce qui reste à rembourser (" + totalRestantDu().toPlainString() + ").");
        List<Part> parts = new ArrayList<>();
        BigDecimal disponible = montant;
        for (LigneEcheance l : lignes) {
            if (disponible.signum() == 0) break;
            if (l.soldee()) continue;
            BigDecimal interet = disponible.min(l.interetRestant());
            disponible = disponible.subtract(interet);
            BigDecimal capital = disponible.min(l.capitalRestant());
            disponible = disponible.subtract(capital);
            if (interet.signum() > 0 || capital.signum() > 0) parts.add(new Part(l.echeance(), capital, interet, Montants.ZERO));
        }
        return parts;
    }

    /** Imputation du remboursement anticipé du solde : tout le capital, les intérêts échus, et la remise des intérêts à venir. */
    public List<Part> imputerAnticipe() {
        List<Part> parts = new ArrayList<>();
        for (LigneEcheance l : lignes) {
            if (l.soldee()) continue;
            boolean echue = !l.date().isAfter(aujourdhui);
            BigDecimal interet = echue ? l.interetRestant() : Montants.ZERO;
            BigDecimal remis = echue ? Montants.ZERO : l.interetRestant();
            parts.add(new Part(l.echeance(), l.capitalRestant(), interet, remis));
        }
        return parts;
    }

    private BigDecimal somme(java.util.function.Function<LigneEcheance, BigDecimal> f) {
        BigDecimal total = Montants.ZERO;
        for (LigneEcheance l : lignes) total = total.add(f.apply(l));
        return total;
    }
}
