package org.example.stock.model;

import java.math.BigDecimal;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ProduitValidationTests {
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void ouvrirValidateur() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void fermerValidateur() {
        factory.close();
    }

    @Test
    void refuseLesChampsObligatoiresAbsents() {
        assertThat(validator.validate(new Produit()))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("nom", "reference", "prixAchat", "prixVente", "quantite", "categorie");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prixAchat", "prixVente", "quantite"})
    void refuseLesValeursNegatives(String champ) {
        Produit produit = produitValide();
        switch (champ) {
            case "prixAchat" -> produit.setPrixAchat(new BigDecimal("-1.0"));
            case "prixVente" -> produit.setPrixVente(new BigDecimal("-1.0"));
            case "quantite" -> produit.setQuantite(-1L);
        }
        assertThat(validator.validate(produit))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains(champ);
    }

    @Test
    void accepteUnProduitSansStockAvecDesPrixNulsEtSansFournisseur() {
        assertThat(validator.validate(produitValide())).isEmpty();
    }

    private Produit produitValide() {
        Produit produit = new Produit();
        produit.setNom("Produit test");
        produit.setReference("REF-TEST");
        produit.setPrixAchat(new BigDecimal("0.0"));
        produit.setPrixVente(new BigDecimal("0.0"));
        produit.setQuantite(0L);
        produit.setCategorie(new Categorie());
        return produit;
    }
}
