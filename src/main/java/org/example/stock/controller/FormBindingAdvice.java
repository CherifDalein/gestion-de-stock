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
import org.springframework.web.servlet.mvc.method.annotation.ExtendedServletRequestDataBinder;

import java.beans.PropertyEditorSupport;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.Arrays;
import java.util.regex.Pattern;
import jakarta.servlet.http.HttpServletRequest;

/** Limite les données des formulaires et empêche le binding sur une relation déjà persistée. */
@ControllerAdvice
public class FormBindingAdvice {
    @InitBinder({"achat", "vente", "produit", "client", "fournisseur", "categorie", "nouvelleCategorie", "reglementAchat", "reglementVente", "reglementSolde", "inscription", "devisForm", "prolongationDevis", "retourVenteForm", "retourAchatForm"})
    public void limiterChamps(WebDataBinder binder, HttpServletRequest request) {
        // Depuis Spring 6.2, les en-têtes sont aussi proposés au binding des formulaires.
        // Ils ne font pas partie des champs métier et ne doivent ni les alimenter ni être rejetés comme tels.
        if (binder instanceof ExtendedServletRequestDataBinder servletBinder) {
            servletBinder.addHeaderPredicate(nom -> false);
        }
        String[] champs = switch (binder.getObjectName()) {
            case "achat" -> new String[]{"fournisseur", "fournisseur.id", "montantVerse", "comptePaiement",
                    "lignes[*].produit.id", "lignes[*].quantite", "lignes[*].prixAchatUnitaire", "lignes[*].prixVenteUnitaire"};
            case "vente" -> new String[]{"client", "client.id", "montantVerse", "comptePaiement",
                    "lignes[*].produit.id", "lignes[*].quantite"};
            case "devisForm" -> new String[]{"client", "client.id", "dateValidite", "note",
                    "lignes[*].produit.id", "lignes[*].quantite", "lignes[*].prixUnitaire"};
            case "prolongationDevis" -> new String[]{"dateValidite"};
            // Retour de vente : seules les quantités par ligne, le motif et le choix de remboursement viennent du navigateur.
            case "retourVenteForm" -> new String[]{"lignes[*].detailVenteId", "lignes[*].quantite", "motif", "modeRemboursement", "comptePaiement", "retoursConnus"};
            // Retour d'achat : mêmes champs que le retour de vente, avec la ligne d'achat retournée.
            case "retourAchatForm" -> new String[]{"lignes[*].detailAchatId", "lignes[*].quantite", "motif", "modeRemboursement", "comptePaiement", "retoursConnus"};
            case "reglementAchat", "reglementVente" -> new String[]{"montant", "montantVerseAttendu", "comptePaiement", "dateEcheance"};
            case "reglementSolde" -> new String[]{"montant", "reglementAttendu", "comptePaiement", "dateEcheance"};
            case "inscription" -> new String[]{"nom", "email", "password"};
            case "produit" -> new String[]{"nom", "reference", "unite", "seuilAlerte",
                    "categorie", "categorie.id", "fournisseur", "fournisseur.id"};
            // Solde de départ : un montant positif ou nul, un sens et une échéance. Le solde signé (soldeInitial) n'est jamais lié.
            case "client", "fournisseur" -> new String[]{"nom", "telephone", "email", "adresse", "montantSoldeDepart", "sensSoldeDepart", "echeanceSoldeInitial"};
            case "categorie", "nouvelleCategorie" -> new String[]{"nom"};
            default -> throw new IllegalArgumentException("Formulaire non pris en charge");
        };
        boolean modificationAchat = binder.getObjectName().equals("achat")
                && request.getRequestURI().startsWith(request.getContextPath() + "/achats/modifier/");
        if (modificationAchat) {
            champs = Arrays.stream(champs).filter(champ -> !champ.equals("montantVerse") && !champ.equals("comptePaiement")).toArray(String[]::new);
            // Le numéro reste celui de la création ; seule l'échéance du reste à payer peut changer.
            champs = Stream.concat(Stream.of(champs), Stream.of("dateEcheance")).toArray(String[]::new);
        }
        if (binder.getObjectName().equals("produit")) {
            // La nature se choisit uniquement à la création ; le prix et le coût saisis ne concernent qu'un service.
            boolean modification = request.getRequestURI().startsWith(request.getContextPath() + "/produits/modifier/");
            String[] nature = request.getParameterValues("nature");
            if (nature != null && nature.length != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Une seule nature de fiche doit être choisie");
            }
            boolean creationService = !modification && nature != null && "SERVICE".equals(nature[0]);
            Stream<String> ajouts = modification ? Stream.of("version", "prixVente", "coutMoyen")
                    : creationService ? Stream.of("nature", "prixVente", "coutMoyen") : Stream.of("nature");
            champs = Stream.concat(Stream.of(champs), ajouts).toArray(String[]::new);
        }
        // La confirmation du changement de solde n'existe qu'à la modification d'une fiche client ou fournisseur.
        boolean modificationPartenaire = (binder.getObjectName().equals("client")
                && request.getRequestURI().startsWith(request.getContextPath() + "/clients/modifier/"))
                || (binder.getObjectName().equals("fournisseur")
                && request.getRequestURI().startsWith(request.getContextPath() + "/fournisseurs/modifier/"));
        if (modificationPartenaire) champs = Stream.concat(Stream.of(champs), Stream.of("confirmationSoldeDepart")).toArray(String[]::new);
        boolean creation = (binder.getObjectName().equals("achat")
                && request.getRequestURI().equals(request.getContextPath() + "/achats/enregistrer"))
                || (binder.getObjectName().equals("vente")
                && request.getRequestURI().equals(request.getContextPath() + "/ventes/enregistrer"));
        // jetonCreation : paramètre de transport lu séparément par le contrôleur, jamais une propriété persistante.
        // Le numéro de facture se saisit uniquement à la création, avec l'échéance proposée.
        if (creation) champs = Stream.concat(Stream.of(champs), Stream.of("jetonCreation", "numero", "dateEcheance")).toArray(String[]::new);
        // Vente issue d'un devis : l'identifiant du devis est un paramètre de transport lu par le contrôleur, jamais lié à la vente.
        if (creation && binder.getObjectName().equals("vente")) champs = Stream.concat(Stream.of(champs), Stream.of("devis")).toArray(String[]::new);
        for (String parametre : request.getMethod().equals("POST") ? request.getParameterMap().keySet() : java.util.Set.<String>of()) {
            if (parametre.equals("comptePaiement") && request.getParameterValues(parametre).length != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Un seul compte de paiement doit être choisi");
            }
            if (CHAMPS_SOLDE_DEPART.contains(parametre) && request.getParameterValues(parametre).length != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Une seule valeur du solde de départ peut être saisie");
            }
            if (parametre.equals("confirmationSoldeDepart") && request.getParameterValues(parametre).length != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Une seule confirmation du solde de départ peut être envoyée");
            }
            if ((parametre.equals("numero") || parametre.equals("dateEcheance")) && request.getParameterValues(parametre).length != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Un seul numéro et une seule échéance peuvent être saisis");
            }
            if ((parametre.equals("devis") || binder.getObjectName().equals("devisForm") && CHAMPS_UNIQUES_DEVIS.contains(parametre)
                    || binder.getObjectName().equals("prolongationDevis")) && request.getParameterValues(parametre).length != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Une seule valeur peut être envoyée pour ce champ");
            }
            if ((binder.getObjectName().equals("retourVenteForm") || binder.getObjectName().equals("retourAchatForm")) && (CHAMPS_UNIQUES_RETOUR.contains(parametre) || parametre.startsWith("lignes[")) && request.getParameterValues(parametre).length != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Une seule valeur peut être envoyée pour ce champ");
            }
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

    /** Champs d'un retour (de vente ou d'achat) qui n'acceptent qu'une valeur (hors lignes). */
    private static final java.util.Set<String> CHAMPS_UNIQUES_RETOUR = java.util.Set.of("motif", "modeRemboursement", "comptePaiement", "retoursConnus");

    /** Champs d'un devis qui n'acceptent qu'une valeur (hors lignes). */
    private static final java.util.Set<String> CHAMPS_UNIQUES_DEVIS = java.util.Set.of("client", "client.id", "dateValidite", "note");

    /** Paramètres de la section « solde de départ » des fiches client et fournisseur. */
    private static final java.util.Set<String> CHAMPS_SOLDE_DEPART = java.util.Set.of("montantSoldeDepart", "sensSoldeDepart", "echeanceSoldeInitial");

    /** Vrai quand la requête contient au moins un champ du solde de départ ; le service refuse alors le caissier (403). */
    public static boolean soldeDepartSoumis(HttpServletRequest request) {
        return CHAMPS_SOLDE_DEPART.stream().anyMatch(request.getParameterMap()::containsKey);
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
