package org.example.stock.model;

import org.example.stock.enums.NatureProduit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ProduitMargeTests {
    private static Produit produit(String vente, String cout) {
        Produit p = new Produit();
        p.setPrixVente(new BigDecimal(vente)); p.setCoutMoyen(new BigDecimal(cout));
        return p;
    }

    @Test
    void laMargeEtLeTauxSontCalculesSurLePrixDeVenteEnBigDecimal() {
        Produit p = produit("150000", "110000");
        assertThat(p.getMargeUnitaire()).isEqualByComparingTo("40000");
        assertThat(p.getTauxMarge()).isEqualTo(new BigDecimal("26.7"));
    }

    @Test
    void leTauxEstArrondiALaDecimaleVersLeHaut() {
        // 1 / 8 = 12,5 % exactement ; 1 / 16 = 6,25 % → 6,3 ; (1000 − 999,9) / 1000 = 0,01 % → 0,0
        assertThat(produit("8", "7").getTauxMarge()).isEqualTo(new BigDecimal("12.5"));
        assertThat(produit("16", "15").getTauxMarge()).isEqualTo(new BigDecimal("6.3"));
        assertThat(produit("1000", "999.9").getTauxMarge()).isEqualTo(new BigDecimal("0.0"));
    }

    @Test
    void uneMargeNegativeGardeSonSigne() {
        Produit p = produit("3000", "4000");
        assertThat(p.getMargeUnitaire()).isEqualByComparingTo("-1000");
        assertThat(p.getTauxMarge()).isEqualTo(new BigDecimal("-33.3"));
    }

    @Test
    void sansPrixDefiniOuAvecPrixDeVenteNulIlNYAPasDeMarge() {
        Produit nul = produit("0", "1000");
        assertThat(nul.getMargeUnitaire()).isNull();
        assertThat(nul.getTauxMarge()).isNull();
        Produit indefini = produit("5000", "1000");
        indefini.setPrixDefinis(false);
        assertThat(indefini.getMargeUnitaire()).isNull();
        assertThat(indefini.getTauxMarge()).isNull();
    }

    @Test
    void pourUnServiceLeCoutEstSonCoutFixe() {
        Produit service = produit("50000", "20000");
        service.setNature(NatureProduit.SERVICE);
        assertThat(service.getMargeUnitaire()).isEqualByComparingTo("30000");
        assertThat(service.getTauxMarge()).isEqualTo(new BigDecimal("60.0"));
    }
}
