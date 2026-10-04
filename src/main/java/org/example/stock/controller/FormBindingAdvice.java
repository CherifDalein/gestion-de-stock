package org.example.stock.controller;

import org.example.stock.model.Categorie;
import org.example.stock.model.Client;
import org.example.stock.model.Fournisseur;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.server.ResponseStatusException;

import java.beans.PropertyEditorSupport;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.Arrays;
import java.util.regex.Pattern;
import jakarta.servlet.http.HttpServletRequest;

/** Limite les données des formulaires et empêche le binding sur une relation déjà persistée. */
@ControllerAdvice
public class FormBindingAdvice {
    @InitBinder({"achat", "vente", "produit", "client", "fournisseur", "categorie", "nouvelleCategorie", "reglementAchat", "reglementVente"})
    public void limiterChamps(WebDataBinder binder, HttpServletRequest request) {
        String[] champs = switch (binder.getObjectName()) {
            case "achat" -> new String[]{"fournisseur", "fournisseur.id", "montantVerse",
                    "lignes[*].produit.id", "lignes[*].quantite", "lignes[*].prixAchatUnitaire"};
            case "vente" -> new String[]{"client", "client.id", "montantVerse",
                    "lignes[*].produit.id", "lignes[*].quantite"};
            case "reglementAchat", "reglementVente" -> new String[]{"montant", "montantVerseAttendu"};
            case "produit" -> new String[]{"nom", "reference", "prixAchat", "prixVente", "quantite",
                    "categorie", "categorie.id", "fournisseur", "fournisseur.id"};
            case "client", "fournisseur" -> new String[]{"nom", "telephone", "email", "adresse"};
            case "categorie", "nouvelleCategorie" -> new String[]{"nom"};
            default -> throw new IllegalArgumentException("Formulaire non pris en charge");
        };
        if (binder.getObjectName().equals("achat")
                && request.getRequestURI().startsWith(request.getContextPath() + "/achats/modifier/")) {
            champs = Arrays.stream(champs).filter(champ -> !champ.equals("montantVerse")).toArray(String[]::new);
        }
        if (binder.getObjectName().equals("produit")
                && request.getRequestURI().startsWith(request.getContextPath() + "/produits/modifier/")) {
            champs = Stream.concat(Stream.of(champs), Stream.of("version")).toArray(String[]::new);
        }
        for (String parametre : request.getMethod().equals("POST") ? request.getParameterMap().keySet() : java.util.Set.<String>of()) {
            if (!parametre.equals("_csrf") && Arrays.stream(champs).noneMatch(champ -> correspond(champ, parametre))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champ de formulaire non autorisé");
            }
        }
        // L'id de route peut être ajouté par Spring, mais il n'est jamais accepté comme paramètre client.
        binder.setAllowedFields(Stream.concat(Stream.of(champs), Stream.of("_csrf", "id")).toArray(String[]::new));
        binder.registerCustomEditor(Categorie.class, reference(id -> {
            Categorie categorie = new Categorie(); categorie.setId(id); return categorie;
        }));
        binder.registerCustomEditor(Fournisseur.class, reference(id -> {
            Fournisseur fournisseur = new Fournisseur(); fournisseur.setId(id); return fournisseur;
        }));
        binder.registerCustomEditor(Client.class, reference(id -> {
            Client client = new Client(); client.setId(id); return client;
        }));
    }

    private boolean correspond(String champ, String parametre) {
        if (!champ.startsWith("lignes[*]")) return champ.equals(parametre);
        String suffixe = champ.substring("lignes[*]".length());
        if (!parametre.matches("lignes\\[[0-9]{1,3}\\]" + Pattern.quote(suffixe))) return false;
        int fin = parametre.indexOf(']');
        return Integer.parseInt(parametre.substring(7, fin)) < 256;
    }

    public static void verifier(BindingResult result) {
        boolean conversionInvalide = result.getFieldErrors().stream().anyMatch(FieldError::isBindingFailure);
        if (result.getSuppressedFields().length > 0 || conversionInvalide) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champs de formulaire non autorisés ou invalides");
        }
    }

    private PropertyEditorSupport reference(Function<Long, Object> factory) {
        return new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                if (text == null || text.isBlank()) {
                    setValue(null);
                    return;
                }
                long id = Long.parseLong(text);
                if (id <= 0) throw new IllegalArgumentException("Identifiant invalide");
                setValue(factory.apply(id));
            }

            @Override
            public String getAsText() {
                Object value = getValue();
                Long id = switch (value) {
                    case Categorie categorie -> categorie.getId();
                    case Fournisseur fournisseur -> fournisseur.getId();
                    case Client client -> client.getId();
                    case null -> null;
                    default -> throw new IllegalArgumentException("Relation non prise en charge");
                };
                return id == null ? "" : id.toString();
            }
        };
    }
}
