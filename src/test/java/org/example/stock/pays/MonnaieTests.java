package org.example.stock.pays;

import org.example.stock.export.MontantEnLettres;
import org.example.stock.model.Boutique;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Affichage des montants (sans décimales, HALF_UP, espaces insécables), lettres, coupures, opérateurs et immuabilité du pays. */
class MonnaieTests {
    static final String NB = "\u00a0";

    @Test void unMontantSAfficheSansDecimalesAvecLeSymboleDeLaMonnaie() {
        assertThat(Monnaie.GNF.formater(new BigDecimal("150000.00"))).isEqualTo("150" + NB + "000" + NB + "GNF");
        assertThat(Monnaie.XOF.formater(new BigDecimal("150000"))).isEqualTo("150" + NB + "000" + NB + "FCFA");
        assertThat(Monnaie.XAF.formater(150000L)).isEqualTo("150" + NB + "000" + NB + "FCFA");
        assertThat(Monnaie.GNF.formater(new BigDecimal("1250000.00"))).doesNotContain(",00", ".00");
        assertThat(Monnaie.GNF.getSymbole()).isEqualTo("GNF");
        assertThat(Monnaie.XOF.getSymbole()).isEqualTo(Monnaie.XAF.getSymbole()).isEqualTo("FCFA");
    }

    @ParameterizedTest
    @CsvSource({"0.49,0", "0.50,1", "1250.50,1251", "1250.49,1250", "2.5,3", "-0.49,0", "-0.50,-1", "-1250.50,-1251", "999.99,1000", "0.00,0"})
    void l_arrondi_est_half_up_et_ne_touche_que_l_affichage(String valeur, String attendu) {
        assertThat(Monnaie.nombre(new BigDecimal(valeur))).isEqualTo(attendu.replaceAll("(\\d)(?=(\\d{3})+$)", "$1" + NB));
    }

    @Test void lesNegatifsLeZeroEtLesGrandsMontants() {
        assertThat(Monnaie.GNF.formater(new BigDecimal("-5000"))).isEqualTo("-5" + NB + "000" + NB + "GNF");
        assertThat(Monnaie.XOF.formater(BigDecimal.ZERO)).isEqualTo("0" + NB + "FCFA");
        assertThat(Monnaie.XOF.formater(new BigDecimal("-0.20"))).isEqualTo("0" + NB + "FCFA");   // pas de « -0 »
        assertThat(Monnaie.GNF.formater(new BigDecimal("999999999999999.99")))
                .isEqualTo("1" + NB + "000" + NB + "000" + NB + "000" + NB + "000" + NB + "000" + NB + "GNF");
        assertThat(Monnaie.GNF.formater(new BigDecimal("12345678901234"))).isEqualTo("12" + NB + "345" + NB + "678" + NB + "901" + NB + "234" + NB + "GNF");
        assertThat(Monnaie.GNF.formater(null)).isEmpty();
    }

    @Test void lEspaceEstInsecablePartout() {
        String texte = Monnaie.XOF.formater(new BigDecimal("1234567"));
        assertThat(texte).doesNotContain(" ").contains(NB);
        assertThat(texte).isEqualTo("1" + NB + "234" + NB + "567" + NB + "FCFA");
    }

    @Test void unEcartSigneEcritToujoursSonSigne() {
        assertThat(Monnaie.XOF.formaterAvecSigne(new BigDecimal("5000"))).isEqualTo("+ 5" + NB + "000" + NB + "FCFA");
        assertThat(Monnaie.XOF.formaterAvecSigne(new BigDecimal("-5000"))).isEqualTo("− 5" + NB + "000" + NB + "FCFA");
        assertThat(Monnaie.GNF.formaterAvecSigne(BigDecimal.ZERO)).isEqualTo("0" + NB + "GNF");
    }

    @Test void lesMontantsEnLettresSuiventLaMonnaie() {
        assertThat(MontantEnLettres.montant(new BigDecimal("140000"), Monnaie.GNF)).isEqualTo("cent quarante mille francs guinéens");
        assertThat(MontantEnLettres.montant(new BigDecimal("140000"), Monnaie.XOF)).isEqualTo("cent quarante mille francs CFA");
        assertThat(MontantEnLettres.montant(new BigDecimal("140000"), Monnaie.XAF)).isEqualTo("cent quarante mille francs CFA");
        assertThat(MontantEnLettres.montant(BigDecimal.ONE, Monnaie.GNF)).isEqualTo("un franc guinéen");
        assertThat(MontantEnLettres.montant(BigDecimal.ONE, Monnaie.XOF)).isEqualTo("un franc CFA");
        assertThat(MontantEnLettres.montant(BigDecimal.ZERO, Monnaie.XAF)).isEqualTo("zéro franc CFA");
    }

    @Test void lesCoupuresDuBilletageSuiventLeClient() {
        assertThat(Monnaie.GNF.getCoupures()).containsExactly(20000, 10000, 5000, 2000, 1000, 500, 100);
        assertThat(Monnaie.XOF.getBillets()).containsExactly(10000, 5000, 2000, 1000, 500);
        assertThat(Monnaie.XOF.getPieces()).containsExactly(500, 250, 200, 100, 50, 25, 10, 5);
        // Une valeur qui existe en billet et en pièce (500) n'a qu'une ligne ; de la plus grande à la plus petite.
        assertThat(Monnaie.XOF.getCoupures()).containsExactly(10000, 5000, 2000, 1000, 500, 250, 200, 100, 50, 25, 10, 5);
        assertThat(Monnaie.XAF.getBillets()).containsExactly(10000, 5000, 2000, 1000, 500);
        assertThat(Monnaie.XAF.getPieces()).containsExactly(500, 200, 100, 50, 25, 10, 5);
        assertThat(Monnaie.XAF.getCoupures()).containsExactly(10000, 5000, 2000, 1000, 500, 200, 100, 50, 25, 10, 5);
        for (Monnaie m : Monnaie.values()) assertThat(m.getCoupures()).doesNotHaveDuplicates().isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test void chaquePaysRepondDeSaMonnaieEtDeSesOperateurs() {
        assertThat(Pays.GUINEE.getMonnaie()).isEqualTo(Monnaie.GNF);
        assertThat(Pays.GUINEE.getOperateurs()).containsExactly("Orange Money", "MTN MoMo");
        assertThat(Pays.SENEGAL.getOperateurs()).containsExactly("Orange Money", "Wave", "Free Money");
        assertThat(Pays.COTE_D_IVOIRE.getOperateurs()).containsExactly("Orange Money", "MTN MoMo", "Moov Money", "Wave");
        assertThat(Pays.MALI.getOperateurs()).containsExactly("Orange Money", "Moov Money");
        assertThat(Pays.BURKINA_FASO.getOperateurs()).containsExactly("Orange Money", "Moov Money");
        assertThat(Pays.CAMEROUN.getOperateurs()).containsExactly("Orange Money", "MTN MoMo");
        List<Pays> uemoa = List.of(Pays.SENEGAL, Pays.COTE_D_IVOIRE, Pays.MALI, Pays.BURKINA_FASO, Pays.NIGER, Pays.TOGO, Pays.BENIN, Pays.GUINEE_BISSAU);
        List<Pays> cemac = List.of(Pays.CAMEROUN, Pays.GABON, Pays.CONGO, Pays.TCHAD, Pays.CENTRAFRIQUE, Pays.GUINEE_EQUATORIALE);
        uemoa.forEach(p -> assertThat(p.getMonnaie()).as(p.getNom()).isEqualTo(Monnaie.XOF));
        cemac.forEach(p -> assertThat(p.getMonnaie()).as(p.getNom()).isEqualTo(Monnaie.XAF));
        assertThat(Pays.values()).hasSize(1 + uemoa.size() + cemac.size());
        for (Pays p : Pays.values()) {
            assertThat(p.getOperateurs()).as(p.getNom()).isNotEmpty().doesNotHaveDuplicates();
            assertThat(p.getIndicatif()).matches("\\+\\d{3}");
            assertThat(p.getCode()).matches("[A-Z]{2}");
        }
        assertThat(Pays.values()).extracting(Pays::getCode).doesNotHaveDuplicates();
        assertThat(Pays.values()).extracting(Pays::getIndicatif).doesNotHaveDuplicates();
    }

    @Test void laListeDesPaysEstTrieeEtLaGuineeEstParDefaut() {
        List<String> noms = Pays.tries().stream().map(Pays::getNom).collect(Collectors.toList());
        assertThat(noms).hasSize(Pays.values().length).startsWith("Bénin");
        assertThat(Pays.PAR_DEFAUT).isEqualTo(Pays.GUINEE);
        assertThat(Pays.depuisCode("SN")).isEqualTo(Pays.SENEGAL);
        assertThat(Pays.depuisCode("sn")).isEqualTo(Pays.SENEGAL);
        assertThat(Pays.depuisCode("XX")).isNull();
        assertThat(Pays.depuisCode(null)).isNull();
    }

    @Test void lOperateurChoisiDoitEtreDuPaysOuUnNomLibre() {
        assertThat(Pays.SENEGAL.operateurValide("Wave", null, 40)).isEqualTo("Wave");
        assertThat(Pays.SENEGAL.operateurValide(Pays.AUTRE, "  Mon   opérateur ", 40)).isEqualTo("Mon opérateur");
        assertThatThrownBy(() -> Pays.SENEGAL.operateurValide("MTN MoMo", null, 40)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pays.SENEGAL.operateurValide(Pays.AUTRE, " ", 40)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pays.SENEGAL.operateurValide(Pays.AUTRE, "x".repeat(41), 40)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pays.SENEGAL.operateurValide("", null, 40)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void lIndicatifTelephoniqueEstProposeEtAjoute() {
        assertThat(Pays.SENEGAL.indicatifSaisie()).isEqualTo("+221 ");
        assertThat(Pays.GUINEE.indicatifSaisie()).isEqualTo("+224 ");
        assertThat(Pays.SENEGAL.numeroInternational("77 123 45 67")).isEqualTo("+221771234567");
        assertThat(Pays.SENEGAL.numeroInternational("+224 600 00 00 00")).isEqualTo("+224600000000");
        assertThat(Pays.SENEGAL.numeroInternational("00224600000000")).isEqualTo("+224600000000");
        assertThat(Pays.SENEGAL.sansIndicatifSeul("+221 ")).isNull();
        assertThat(Pays.SENEGAL.sansIndicatifSeul("+221 77 123 45 67")).isEqualTo("+221 77 123 45 67");
    }

    @Test void lePaysEtLaMonnaieNeChangentJamaisApresLaCreation() throws Exception {
        Boutique neuve = new Boutique(Pays.SENEGAL);
        assertThat(neuve.getPays()).isEqualTo(Pays.SENEGAL);
        assertThat(neuve.getMonnaie()).isEqualTo(Monnaie.XOF);
        neuve.definirPays(Pays.MALI);    // pas encore enregistrée : le choix peut encore changer, la monnaie suit
        assertThat(neuve.getMonnaie()).isEqualTo(Monnaie.XOF);
        // Aucun accesseur d'écriture pour le pays ou la monnaie.
        assertThat(Boutique.class.getMethods()).extracting(java.lang.reflect.Method::getName).doesNotContain("setPays", "setMonnaie");
        // Enregistrée (identifiant posé), la boutique refuse tout changement de pays.
        var id = Boutique.class.getDeclaredField("id"); id.setAccessible(true); id.set(neuve, 7L);
        assertThatThrownBy(() -> neuve.definirPays(Pays.GUINEE)).isInstanceOf(IllegalStateException.class).hasMessageContaining("ne peuvent plus être modifiés");
        assertThatThrownBy(() -> neuve.definirPays(Pays.SENEGAL)).isInstanceOf(IllegalStateException.class);
        neuve.definirPays(Pays.MALI);    // même pays : sans effet
        assertThat(neuve.getPays()).isEqualTo(Pays.MALI);
        assertThat(neuve.getMonnaie()).isEqualTo(Monnaie.XOF);
        // Colonnes non modifiables : Hibernate n'écrit jamais pays ni monnaie dans un UPDATE.
        for (String champ : List.of("pays", "monnaie"))
            assertThat(Boutique.class.getDeclaredField(champ).getAnnotation(jakarta.persistence.Column.class).updatable()).as(champ).isFalse();
    }

    @Test void uneBoutiqueDuSenegalAUnCompteMobileMoneyNommeEtOperateurOrangeMoney() {
        Boutique dakar = new Boutique(Pays.SENEGAL);
        var mobile = dakar.getComptesTresorerie().stream().filter(c -> c.getType() == org.example.stock.enums.TypeCompteTresorerie.MOBILE_MONEY).findFirst().orElseThrow();
        assertThat(mobile.getOperateur()).isEqualTo("Orange Money");
        assertThat(mobile.getNom()).isEqualTo("Orange Money");
        assertThat(mobile.getLibelleType()).isEqualTo("Mobile money · Orange Money");
        assertThat(org.example.stock.enums.TypeCompteTresorerie.MOBILE_MONEY.getLibelle()).isEqualTo("Mobile money");
        assertThat(org.example.stock.enums.TypeCompteTresorerie.depuisSaisie("ORANGE_MONEY")).isEqualTo(org.example.stock.enums.TypeCompteTresorerie.MOBILE_MONEY);
        assertThat(org.example.stock.enums.TypeCompteTresorerie.depuisSaisie("CAISSE")).isEqualTo(org.example.stock.enums.TypeCompteTresorerie.ESPECES);
    }
}
