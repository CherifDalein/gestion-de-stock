package org.example.stock.service;

import org.example.stock.enums.Periodicite;
import org.example.stock.model.EcheanceEmprunt;
import org.example.stock.model.Emprunt;
import org.example.stock.model.ImputationEcheance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Échéancier à capital constant, intérêts simples, et imputation des remboursements (calculs purs, sans base). */
class EcheancierCalculTests {
    static final LocalDate PREMIERE = LocalDate.of(2027, 1, 31);

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test
    void tauxZeroDonneDesInteretsNuls() {
        var lignes = EcheancierCalcul.generer(d("1200000"), BigDecimal.ZERO, 12, Periodicite.MENSUELLE, PREMIERE);
        assertThat(lignes).hasSize(12);
        assertThat(lignes).allSatisfy(l -> { assertThat(l.interet()).isEqualByComparingTo("0"); assertThat(l.capital()).isEqualByComparingTo("100000"); });
    }

    @Test
    void douzePourCentSurDouzeMoisDonneSoixanteDixHuitMilleD_interets() {
        var lignes = EcheancierCalcul.generer(d("1200000"), d("12"), 12, Periodicite.MENSUELLE, PREMIERE);
        assertThat(lignes.get(0).interet()).isEqualByComparingTo("12000.00");
        assertThat(lignes.get(1).interet()).isEqualByComparingTo("11000.00");
        assertThat(lignes.get(11).interet()).isEqualByComparingTo("1000.00");
        assertThat(lignes.stream().map(EcheancierCalcul.Ligne::interet).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("78000.00");
        assertThat(lignes.stream().map(EcheancierCalcul.Ligne::capital).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("1200000");
        // Les dates partent de la première échéance sans dérive de fin de mois.
        assertThat(lignes.get(1).date()).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(lignes.get(2).date()).isEqualTo(LocalDate.of(2027, 3, 31));
    }

    @Test
    void lesArrondisVontALaDerniereEcheanceEtLesInteretsSontArrondisAuCentime() {
        var lignes = EcheancierCalcul.generer(d("100000"), d("12"), 3, Periodicite.MENSUELLE, PREMIERE);
        assertThat(lignes).extracting(EcheancierCalcul.Ligne::capital).usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(d("33333.33"), d("33333.33"), d("33333.34"));
        assertThat(lignes).extracting(EcheancierCalcul.Ligne::interet).usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(d("1000.00"), d("666.67"), d("333.33"));
    }

    @Test
    void laPeriodiciteFixeLaPartD_anneeDesInterets() {
        assertThat(EcheancierCalcul.interet(d("520000"), d("10"), Periodicite.HEBDOMADAIRE)).isEqualByComparingTo("1000.00");
        assertThat(EcheancierCalcul.interet(d("400000"), d("10"), Periodicite.TRIMESTRIELLE)).isEqualByComparingTo("10000.00");
        assertThat(EcheancierCalcul.interet(d("1200000"), d("10"), Periodicite.MENSUELLE)).isEqualByComparingTo("10000.00");
    }

    @Test
    void laModificationExigeUnTotalEgalAuCapitalEtDesDatesCroissantes() {
        var dates = List.of(LocalDate.of(2027, 2, 1), LocalDate.of(2027, 4, 1));
        var lignes = EcheancierCalcul.recalculer(d("1000"), d("12"), Periodicite.MENSUELLE, dates, List.of(d("400"), d("600")));
        assertThat(lignes.get(0).interet()).isEqualByComparingTo("10.00");
        assertThat(lignes.get(1).interet()).isEqualByComparingTo("6.00");
        assertThatThrownBy(() -> EcheancierCalcul.recalculer(d("1000"), d("12"), Periodicite.MENSUELLE, dates, List.of(d("400"), d("500"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EcheancierCalcul.recalculer(d("1000"), d("12"), Periodicite.MENSUELLE, List.of(dates.get(1), dates.get(0)), List.of(d("400"), d("600"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EcheancierCalcul.recalculer(d("1000"), d("12"), Periodicite.MENSUELLE, dates, List.of(d("0"), d("1000"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lesEntreesInvalidesSontRefusees() {
        assertThatThrownBy(() -> EcheancierCalcul.generer(BigDecimal.ZERO, d("12"), 12, Periodicite.MENSUELLE, PREMIERE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EcheancierCalcul.generer(d("1000"), d("101"), 12, Periodicite.MENSUELLE, PREMIERE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EcheancierCalcul.generer(d("1000"), d("-1"), 12, Periodicite.MENSUELLE, PREMIERE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EcheancierCalcul.generer(d("1000"), d("12"), 0, Periodicite.MENSUELLE, PREMIERE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EcheancierCalcul.generer(d("1000"), d("12"), 121, Periodicite.MENSUELLE, PREMIERE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EcheancierCalcul.generer(d("0.05"), d("12"), 6, Periodicite.MENSUELLE, PREMIERE)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Situation et imputation ---------------------------------------------------------------------------------------

    private static SituationEmprunt situation(LocalDate aujourdhui, String... paiements) {
        Emprunt e = new Emprunt(); e.setId(1L); e.setCapital(d("300000"));
        var lignes = EcheancierCalcul.generer(d("300000"), d("12"), 3, Periodicite.MENSUELLE, LocalDate.of(2027, 1, 10));
        List<EcheanceEmprunt> echeances = new ArrayList<>();
        for (var l : lignes) {
            EcheanceEmprunt x = new EcheanceEmprunt(); x.setId((long) l.rang()); x.setEmprunt(e); x.setRang(l.rang()); x.setDateEcheance(l.date()); x.setCapital(l.capital()); x.setInteret(l.interet());
            echeances.add(x);
        }
        SituationEmprunt s = SituationEmprunt.de(e, echeances, List.of(), aujourdhui);
        for (String paiement : paiements) {
            List<ImputationEcheance> actives = new ArrayList<>();
            // Rejoue les paiements précédents : on reconstruit la situation avec les imputations cumulées.
            s = SituationEmprunt.de(e, echeances, cumuler(s, echeances, e, paiement), aujourdhui);
        }
        return s;
    }

    private static List<ImputationEcheance> cumuler(SituationEmprunt avant, List<EcheanceEmprunt> echeances, Emprunt e, String paiement) {
        List<ImputationEcheance> toutes = new ArrayList<>();
        for (var l : avant.lignes()) {
            if (l.capitalPaye().signum() > 0 || l.interetPaye().signum() > 0 || l.interetRemis().signum() > 0) {
                ImputationEcheance i = new ImputationEcheance(); i.setEcheance(l.echeance()); i.setCapital(l.capitalPaye()); i.setInteret(l.interetPaye()); i.setInteretRemis(l.interetRemis());
                toutes.add(i);
            }
        }
        for (var p : avant.imputer(d(paiement))) {
            ImputationEcheance i = new ImputationEcheance(); i.setEcheance(p.echeance()); i.setCapital(p.capital()); i.setInteret(p.interet()); i.setInteretRemis(p.interetRemis());
            toutes.add(i);
        }
        return toutes;
    }

    @Test
    void unRemboursementPartielPayeLesInteretsPuisLeCapitalDeLaPremiereEcheance() {
        var s = situation(LocalDate.of(2027, 1, 1));
        var parts = s.imputer(d("20000"));
        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).interet()).isEqualByComparingTo("3000.00");   // 300 000 x 12 % / 12 = 3 000
        assertThat(parts.get(0).capital()).isEqualByComparingTo("17000.00");
    }

    @Test
    void unPaiementCouvrePlusieursEcheancesDansLOrdre() {
        var s = situation(LocalDate.of(2027, 1, 1));
        // Échéance 1 : 100 000 + 3 000 ; échéance 2 : 100 000 + 2 000 ; paiement de 150 000.
        var parts = s.imputer(d("150000"));
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).capital()).isEqualByComparingTo("100000");
        assertThat(parts.get(0).interet()).isEqualByComparingTo("3000");
        assertThat(parts.get(1).interet()).isEqualByComparingTo("2000");
        assertThat(parts.get(1).capital()).isEqualByComparingTo("45000");
    }

    @Test
    void leMontantNePeutDepasserLeRestantDuNiEtreNul() {
        var s = situation(LocalDate.of(2027, 1, 1));
        assertThat(s.totalRestantDu()).isEqualByComparingTo("306000");
        assertThatThrownBy(() -> s.imputer(d("306000.01"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.imputer(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThat(s.imputer(d("306000"))).hasSize(3);
    }

    @Test
    void leCapitalRestantLesRetardsEtLaClotureSontCalcules() {
        var enCours = situation(LocalDate.of(2027, 1, 1), "103000");
        assertThat(enCours.capitalRestantDu()).isEqualByComparingTo("200000");
        assertThat(enCours.interetsPayes()).isEqualByComparingTo("3000");
        assertThat(enCours.prochaine().rang()).isEqualTo(2);
        assertThat(enCours.clos()).isFalse();
        assertThat(enCours.statut()).isEqualTo("En cours");

        var enRetard = situation(LocalDate.of(2027, 3, 20), "103000");
        assertThat(enRetard.enRetardMaintenant()).isTrue();
        assertThat(enRetard.nombreRetards()).isEqualTo(2);           // échéances 2 (10/02) et 3 (10/03) impayées
        assertThat(enRetard.plusGrandRetardEnJours()).isEqualTo(38);
        assertThat(enRetard.statut()).isEqualTo("En retard");

        var clos = situation(LocalDate.of(2027, 3, 20), "103000", "102000", "101000");
        assertThat(clos.clos()).isTrue();
        assertThat(clos.statut()).isEqualTo("Clos");
        assertThat(clos.enRetardMaintenant()).isFalse();
        assertThat(clos.capitalRestantDu()).isEqualByComparingTo("0");
    }

    @Test
    void leRemboursementAnticipeRemetLesInteretsFuturs() {
        var s = situation(LocalDate.of(2027, 2, 15), "103000");
        // Reste : échéance 2 (échue le 10/02) 100 000 + 2 000 ; échéance 3 (à venir) 100 000 + 1 000.
        assertThat(s.soldeAnticipe()).isEqualByComparingTo("202000");
        var parts = s.imputerAnticipe();
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).interet()).isEqualByComparingTo("2000");
        assertThat(parts.get(0).interetRemis()).isEqualByComparingTo("0");
        assertThat(parts.get(1).interet()).isEqualByComparingTo("0");
        assertThat(parts.get(1).interetRemis()).isEqualByComparingTo("1000");
        assertThat(s.interetsRemisParAnticipation()).isEqualByComparingTo("1000");
    }

    @Test
    void unPaiementEnAvanceNeReduitPasLesInteretsPrevus() {
        var s = situation(LocalDate.of(2027, 1, 1), "103000", "100000");
        // Le paiement de 100 000 a servi l'intérêt et le capital de l'échéance 2 (2 000 + 98 000) : rien n'est effacé.
        assertThat(s.interetsPrevus()).isEqualByComparingTo("6000");
        assertThat(s.interetsPayes()).isEqualByComparingTo("5000");
        assertThat(s.capitalPaye()).isEqualByComparingTo("198000");
    }
}
