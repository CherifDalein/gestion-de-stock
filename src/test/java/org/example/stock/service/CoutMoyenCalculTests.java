package org.example.stock.service;

import org.example.stock.service.CoutMoyenService.Evenement;
import org.example.stock.service.CoutMoyenService.Ordre;
import org.example.stock.service.CoutMoyenService.Rang;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Formule et rejeu du coût moyen pondéré, sans base de données. */
class CoutMoyenCalculTests {
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 1, 9, 0);

    private static BigDecimal cout(long stockAvant, String coutActuel, long quantite, String prix) {
        return CoutMoyenService.nouveauCout(stockAvant, coutActuel == null ? null : new BigDecimal(coutActuel),
                quantite, new BigDecimal(prix));
    }

    @Test void laMoyenneEstPondereeParLesQuantites() {
        assertThat(cout(10, "1000.00", 10, "2000")).isEqualByComparingTo("1500.00");
        assertThat(cout(7, "1000.00", 10, "2000")).isEqualByComparingTo("1588.24");
    }

    @Test void leResultatEstArrondiUneSeuleFoisAuCentimeLePlusProche() {
        // (1 × 1 000,00 + 3 × 1 000,01) / 4 = 1 000,0075
        assertThat(cout(1, "1000.00", 3, "1000.01")).isEqualByComparingTo("1000.01").hasScaleOf(2);
        // (3 × 1 000,01 + 3 × 1 000,00) / 6 = 1 000,005 : la demie est arrondie vers le haut.
        assertThat(cout(3, "1000.01", 3, "1000.00")).isEqualByComparingTo("1000.01");
        // (2 × 1 000,00 + 1 × 1 000,01) / 3 = 1 000,00333…
        assertThat(cout(2, "1000.00", 1, "1000.01")).isEqualByComparingTo("1000.00");
        assertThat(cout(1, "1.00", 2, "2.00")).isEqualByComparingTo("1.67");
    }

    @Test void sansStockOuSansCoutConnuLeCoutDevientLePrixDeLEntree() {
        assertThat(cout(0, "1500.00", 3, "1000.01")).isEqualByComparingTo("1000.01").hasScaleOf(2);
        assertThat(cout(-2, "1500.00", 3, "900")).isEqualByComparingTo("900.00");
        assertThat(cout(5, null, 3, "900")).isEqualByComparingTo("900.00");
    }

    @Test void unCoutNulAvecDuStockResteUneVraieValeur() {
        assertThat(cout(10, "0.00", 10, "1000")).isEqualByComparingTo("500.00");
    }

    @Test void uneEntreeSansQuantiteNeChangeRien() {
        assertThat(cout(10, "1000.00", 0, "5000")).isEqualByComparingTo("1000.00");
    }

    @Test void leRejeuReproduitLeCalculIncrementalEtIgnoreLOrdreDeLecture() {
        var evenements = new ArrayList<>(List.of(
                new Evenement(Rang.INITIAL, null, Ordre.ENTREE, 1, 0, 10, new BigDecimal("1000.00")),
                new Evenement(Rang.OPERATION, T0.plusHours(1), Ordre.SORTIE, 1, 1, -4, null),
                new Evenement(Rang.OPERATION, T0.plusHours(2), Ordre.ENTREE, 1, 2, 10, new BigDecimal("2000.00")),
                new Evenement(Rang.OPERATION, T0.plusHours(3), Ordre.AJUSTEMENT, 2, 0, 3, null),
                new Evenement(Rang.OPERATION, T0.plusHours(4), Ordre.ENTREE, 2, 3, 1, new BigDecimal("1000.01"))));
        BigDecimal attendu = cout(16 + 3, cout(6, "1000.00", 10, "2000").toPlainString(), 1, "1000.01");
        assertThat(CoutMoyenService.rejouer(20, null, evenements)).isEqualByComparingTo(attendu);
        Collections.reverse(evenements);
        assertThat(CoutMoyenService.rejouer(20, null, evenements)).isEqualByComparingTo(attendu);
    }

    @Test void unAjustementChangeLePoidsMaisPasLeCout() {
        var evenements = List.of(
                new Evenement(Rang.OPERATION, T0, Ordre.ENTREE, 1, 1, 10, new BigDecimal("1000")),
                new Evenement(Rang.OPERATION, T0.plusHours(1), Ordre.AJUSTEMENT, 1, 0, -4, null));
        assertThat(CoutMoyenService.rejouer(6, null, evenements)).isEqualByComparingTo("1000.00");
        var suite = new ArrayList<>(evenements);
        suite.add(new Evenement(Rang.OPERATION, T0.plusHours(2), Ordre.ENTREE, 2, 2, 10, new BigDecimal("2000")));
        assertThat(CoutMoyenService.rejouer(16, null, suite)).isEqualByComparingTo("1625.00");
    }

    @Test void unStockToutEntierVenduRepartAuPrixDuNouvelAchat() {
        var evenements = List.of(
                new Evenement(Rang.OPERATION, T0, Ordre.ENTREE, 1, 1, 10, new BigDecimal("1000")),
                new Evenement(Rang.OPERATION, T0.plusHours(1), Ordre.SORTIE, 1, 1, -10, null),
                new Evenement(Rang.OPERATION, T0.plusHours(2), Ordre.ENTREE, 2, 2, 5, new BigDecimal("3000")));
        assertThat(CoutMoyenService.rejouer(5, null, evenements)).isEqualByComparingTo("3000.00");
    }

    @Test void aDateEgaleLesEntreesPassentAvantLesSortiesPuisParDocument() {
        var evenements = List.of(
                new Evenement(Rang.OPERATION, T0, Ordre.SORTIE, 1, 1, -10, null),
                new Evenement(Rang.OPERATION, T0, Ordre.ENTREE, 2, 2, 10, new BigDecimal("2000")),
                new Evenement(Rang.OPERATION, T0, Ordre.ENTREE, 1, 1, 10, new BigDecimal("1000")));
        // 1 000 puis 2 000 pondérés à 10/10, la vente ensuite : 1 500 quel que soit l'ordre de lecture.
        assertThat(CoutMoyenService.rejouer(10, null, evenements)).isEqualByComparingTo("1500.00");
    }

    @Test void leStockNonExpliqueParLHistoriqueEstValoriseAuPremierCoutConnu() {
        var evenements = List.of(
                new Evenement(Rang.OPERATION, T0, Ordre.ENTREE, 1, 1, 10, new BigDecimal("1200")),
                new Evenement(Rang.OPERATION, T0.plusHours(1), Ordre.ENTREE, 2, 2, 10, new BigDecimal("2000")));
        // 10 unités antérieures au journal : (10 × 1 200 + 10 × 1 200) / 20, puis + 10 × 2 000.
        assertThat(CoutMoyenService.rejouer(30, null, evenements)).isEqualByComparingTo("1466.67");
        // Un écart négatif ne change que la quantité.
        assertThat(CoutMoyenService.rejouer(5, null, evenements)).isEqualByComparingTo("1600.00");
    }

    @Test void sansAucuneEntreeLeCoutActuelEstConserve() {
        var ventes = List.of(new Evenement(Rang.OPERATION, T0, Ordre.SORTIE, 1, 1, -2, null));
        assertThat(CoutMoyenService.rejouer(8, new BigDecimal("750.00"), ventes)).isEqualByComparingTo("750.00");
    }
}
