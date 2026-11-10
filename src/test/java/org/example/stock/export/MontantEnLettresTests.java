package org.example.stock.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Montant en lettres (justificatifs de solde, lot 4 tâche D) : les cas difficiles de l'orthographe française. */
class MontantEnLettresTests {

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', value = {
            "0|zéro", "1|un", "2|deux", "10|dix", "11|onze", "16|seize", "17|dix-sept", "19|dix-neuf",
            "20|vingt", "21|vingt et un", "22|vingt-deux", "31|trente et un", "61|soixante et un", "60|soixante",
            "70|soixante-dix", "71|soixante et onze", "72|soixante-douze", "77|soixante-dix-sept", "79|soixante-dix-neuf",
            "80|quatre-vingts", "81|quatre-vingt-un", "85|quatre-vingt-cinq", "90|quatre-vingt-dix", "91|quatre-vingt-onze", "99|quatre-vingt-dix-neuf",
            "100|cent", "101|cent un", "180|cent quatre-vingts", "199|cent quatre-vingt-dix-neuf",
            "200|deux cents", "201|deux cent un", "280|deux cent quatre-vingts", "300|trois cents", "900|neuf cents", "999|neuf cent quatre-vingt-dix-neuf",
            "1000|mille", "1001|mille un", "1100|mille cent", "1200|mille deux cents", "1980|mille neuf cent quatre-vingts", "2000|deux mille",
            "21000|vingt et un mille", "80000|quatre-vingt mille", "81000|quatre-vingt-un mille", "100000|cent mille", "125000|cent vingt-cinq mille",
            "200000|deux cent mille", "200001|deux cent mille un", "999000|neuf cent quatre-vingt-dix-neuf mille",
            "1000000|un million", "1000001|un million un", "2000000|deux millions", "1250300|un million deux cent cinquante mille trois cents",
            "80000000|quatre-vingts millions", "81000000|quatre-vingt-un millions", "200000000|deux cents millions", "201000000|deux cent un millions",
            "1000000000|un milliard", "2000000000|deux milliards",
            "999999999|neuf cent quatre-vingt-dix-neuf millions neuf cent quatre-vingt-dix-neuf mille neuf cent quatre-vingt-dix-neuf",
            "1234567890|un milliard deux cent trente-quatre millions cinq cent soixante-sept mille huit cent quatre-vingt-dix"})
    void lesNombresSEcriventCommeEnFrancais(long nombre, String attendu) {
        assertThat(MontantEnLettres.enLettres(nombre)).isEqualTo(attendu);
    }

    @Test
    void lesMontantsEnFrancsGuineens() {
        assertThat(MontantEnLettres.francsGuineens(new BigDecimal("125000"))).isEqualTo("cent vingt-cinq mille francs guinéens");
        assertThat(MontantEnLettres.francsGuineens(new BigDecimal("125000.00"))).isEqualTo("cent vingt-cinq mille francs guinéens");
        assertThat(MontantEnLettres.francsGuineens(BigDecimal.ZERO)).isEqualTo("zéro franc guinéen");
        assertThat(MontantEnLettres.francsGuineens(BigDecimal.ONE)).isEqualTo("un franc guinéen");
        assertThat(MontantEnLettres.francsGuineens(new BigDecimal("2"))).isEqualTo("deux francs guinéens");
        // Une somme qui se termine par « million(s) » ou « milliard(s) » prend « de ».
        assertThat(MontantEnLettres.francsGuineens(new BigDecimal("1000000"))).isEqualTo("un million de francs guinéens");
        assertThat(MontantEnLettres.francsGuineens(new BigDecimal("2000000"))).isEqualTo("deux millions de francs guinéens");
        assertThat(MontantEnLettres.francsGuineens(new BigDecimal("3000000000"))).isEqualTo("trois milliards de francs guinéens");
        assertThat(MontantEnLettres.francsGuineens(new BigDecimal("2000001"))).isEqualTo("deux millions un francs guinéens");
        assertThat(MontantEnLettres.francsGuineens(new BigDecimal("1250300"))).isEqualTo("un million deux cent cinquante mille trois cents francs guinéens");
        // Centimes et montant négatif.
        assertThat(MontantEnLettres.francsGuineens(new BigDecimal("1250.50"))).isEqualTo("mille deux cent cinquante francs guinéens et cinquante centimes");
        assertThat(MontantEnLettres.francsGuineens(new BigDecimal("0.01"))).isEqualTo("zéro franc guinéen et un centime");
        assertThat(MontantEnLettres.francsGuineens(new BigDecimal("-5000"))).isEqualTo("moins cinq mille francs guinéens");
        assertThat(MontantEnLettres.francsGuineensEnPhrase(new BigDecimal("80"))).isEqualTo("Quatre-vingts francs guinéens");
    }

    @Test
    void lesNombresTropGrandsSontRefuses() {
        assertThatThrownBy(() -> MontantEnLettres.enLettres(MontantEnLettres.MAXIMUM + 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MontantEnLettres.francsGuineens(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(MontantEnLettres.enLettres(MontantEnLettres.MAXIMUM)).startsWith("neuf cent quatre-vingt-dix-neuf milliards");
    }

    @Test
    void aucuneRegleDOrthographeNEstViolee() {
        for (long n = 0; n <= 200_000; n++) {
            String t = MontantEnLettres.enLettres(n);
            assertThat(t).as(String.valueOf(n)).isNotBlank().doesNotContain("  ", "cents mille", "vingts mille", "cents million", "vingts-", "cents ")
                    .doesNotStartWith(" ").doesNotEndWith(" ");
            assertThat(t).as(String.valueOf(n)).doesNotContainPattern("^un mille|llions? un mille|lliards? un mille");
            // « cents » et « quatre-vingts » ne s'écrivent qu'en fin de nombre.
            if (t.contains("cents ")) throw new AssertionError(n + " : " + t);
            assertThat(t).as(String.valueOf(n)).doesNotContain("quatre-vingts ");
        }
        for (long n = 1_000_000; n <= 1_000_000_000L; n += 7_654_321) {
            assertThat(MontantEnLettres.enLettres(n)).doesNotContain("  ").doesNotContainPattern("^un mille|llions? un mille|lliards? un mille");
        }
    }
}
