package org.example.stock.security;

import org.example.stock.controller.FormBindingAdvice;
import org.example.stock.form.ReglementVenteForm;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.ServletRequestDataBinder;
import org.springframework.web.servlet.mvc.method.annotation.ExtendedServletRequestDataBinder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.example.stock.model.*;
import org.springframework.beans.BeanWrapperImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormBindingAdviceTests {
    @ParameterizedTest
    @ValueSource(strings = {"achat", "vente"})
    void leJetonDeCreationEstUnTransportAutoriseUniquementSurLaCreation(String nom) {
        Object cible = nom.equals("achat") ? new Achat() : new Vente();
        String route = "/" + (nom.equals("achat") ? "achats" : "ventes");
        MockHttpServletRequest requete = new MockHttpServletRequest("POST", "/stock" + route + "/enregistrer");
        requete.setContextPath("/stock");
        requete.addParameter("jetonCreation", java.util.UUID.randomUUID().toString());
        ServletRequestDataBinder binder = new ServletRequestDataBinder(cible, nom);
        new FormBindingAdvice().limiterChamps(binder, requete);
        binder.bind(requete);
        FormBindingAdvice.verifier(binder.getBindingResult());
        requete.setRequestURI("/stock" + route + "/modifier/1");
        assertThatThrownBy(() -> new FormBindingAdvice().limiterChamps(new ServletRequestDataBinder(cible, nom), requete))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void leChampCsrfNeDevientPasUnMarqueurDeChamp() {
        MockHttpServletRequest requete = new MockHttpServletRequest("POST", "/ventes/regler/1");
        requete.addParameter("montant", "30000");
        requete.addParameter("montantVerseAttendu", "0.00");
        requete.addParameter("_csrf", "jeton-de-test");
        ReglementVenteForm formulaire = new ReglementVenteForm();
        ServletRequestDataBinder binder = new ServletRequestDataBinder(formulaire, "reglementVente");
        new FormBindingAdvice().limiterChamps(binder, requete);
        binder.bind(requete);
        assertThat(binder.getBindingResult().getSuppressedFields()).isEmpty();
        assertThat(binder.getBindingResult().hasErrors()).isFalse();
        assertThat(formulaire.getMontant()).isEqualByComparingTo("30000");
        FormBindingAdvice.verifier(binder.getBindingResult());
    }

    @ParameterizedTest
    @ValueSource(strings = {"achat", "vente", "produit", "client", "fournisseur", "categorie", "nouvelleCategorie", "reglementAchat", "reglementVente", "inscription"})
    void lesEntetesNeSontPasDesChampsDeFormulaire(String nom) {
        Object cible = switch (nom) {
            case "achat" -> new Achat();
            case "vente" -> new Vente();
            case "produit" -> new Produit();
            case "client" -> new Client();
            case "fournisseur" -> new Fournisseur();
            case "categorie", "nouvelleCategorie" -> new Categorie();
            case "reglementAchat" -> new org.example.stock.form.ReglementAchatForm();
            case "inscription" -> new org.example.stock.form.InscriptionForm();
            default -> new ReglementVenteForm();
        };
        MockHttpServletRequest requete = new MockHttpServletRequest("POST", "/formulaire-test");
        requete.addParameter("_csrf", "jeton-de-test");
        requete.addHeader("Accept-Language", "fr-FR,fr;q=0.9");
        requete.addHeader("User-Agent", "Navigateur de test");
        requete.addHeader("Accept-Encoding", "gzip, deflate, br");
        requete.addHeader("Content-Type", "application/x-www-form-urlencoded");
        requete.addHeader("Content-Length", "100");
        requete.addHeader("Nom", "Nom injecté par en-tête");
        requete.addHeader("Montant", "999999");
        ExtendedServletRequestDataBinder binder = new ExtendedServletRequestDataBinder(cible, nom);
        new FormBindingAdvice().limiterChamps(binder, requete);
        binder.bind(requete);
        assertThat(binder.getBindingResult().getSuppressedFields()).isEmpty();
        assertThat(binder.getBindingResult().hasErrors()).isFalse();
        BeanWrapperImpl proprietes = new BeanWrapperImpl(cible);
        for (String champ : new String[]{"nom", "montant"}) {
            if (proprietes.isReadableProperty(champ)) assertThat(proprietes.getPropertyValue(champ)).isNull();
        }
        FormBindingAdvice.verifier(binder.getBindingResult());
    }
}
