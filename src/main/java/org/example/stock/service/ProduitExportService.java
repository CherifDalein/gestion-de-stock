package org.example.stock.service;

import org.example.stock.export.*;
import org.example.stock.model.Categorie;
import org.example.stock.model.Montants;
import org.example.stock.model.Produit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Export de la liste des produits (Excel et PDF) : mêmes produits que {@code /produits}, filtres de l'écran (recherche, catégorie,
 * état du stock). Ouvert au gérant et au caissier comme l'écran ; les colonnes de coût sont réservées au gérant :
 * pour un caissier elles ne sont ni lues, ni calculées, ni écrites.
 */
@Service
@Transactional(readOnly = true)
public class ProduitExportService {
    /** Valeurs du filtre « État du stock » de l'écran. */
    public static final Set<String> ETATS = Set.of("en-stock", "disponible", "faible", "rupture", "a-surveiller", "service");

    private final ProduitService produits;
    private final CategorieService categories;
    private final LimiteExport limite;

    public ProduitExportService(ProduitService produits, CategorieService categories, LimiteExport limite) {
        this.produits = produits; this.categories = categories; this.limite = limite;
    }

    /**
     * @param categorieId identifiant d'une catégorie de la boutique, ou null pour toutes (autre catégorie : 400)
     * @param stock       une valeur de {@link #ETATS} ou null pour tous les états
     * @param recherche   texte de la barre de recherche (facultatif)
     * @throws ExportTropVolumineuxException si la boutique a plus de {@link LimiteExport#lignes()} produits
     */
    public TableauExport preparer(Long categorieId, String stock, String recherche) {
        boolean gerant = DroitsExport.estGerant();
        String nomCategorie = null;
        if (categorieId != null) {
            nomCategorie = categories.listerToutes().stream().filter(c -> categorieId.equals(c.getId())).map(Categorie::getNom).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Catégorie invalide"));
        }
        List<Produit> lus = produits.listerPourExport(limite.lignes());
        limite.verifier(lus.size());
        String q = recherche == null ? "" : recherche.strip();
        List<Produit> retenus = lus.stream()
                .filter(p -> categorieId == null || (p.getCategorie() != null && categorieId.equals(p.getCategorie().getId())))
                .filter(p -> stock == null || correspondEtat(stock, p))
                .filter(p -> q.isEmpty() || RechercheListe.correspond(q, texteDeLaLigne(p, gerant)))
                .sorted(Comparator.comparing(Produit::getReference, RechercheListe.ordreNaturel()).thenComparing(Produit::getId))
                .toList();

        List<Colonne> colonnes = new ArrayList<>(List.of(Colonne.texteCourt("Référence"), Colonne.texte("Produit"), Colonne.texte("Catégorie"),
                Colonne.texteCourt("Nature"), Colonne.texteCourt("Unité"), Colonne.entier("Quantité"), Colonne.entier("Seuil d’alerte"),
                Colonne.texteCourt("État du stock"), Colonne.montant("Prix de vente")));
        if (gerant) colonnes.addAll(List.of(Colonne.texte("Fournisseur"), Colonne.montant("Coût moyen"), Colonne.montant("Dernier prix d’achat"),
                Colonne.montant("Marge unitaire"), Colonne.pourcentage("Taux de marge"), Colonne.montant("Valeur du stock")));
        TableauExport tableau = TableauExport.titre("Liste des produits").nomFeuille("Produits")
                .filtres(decrireFiltres(nomCategorie, stock, q)).colonnes(colonnes.toArray(Colonne[]::new));

        BigDecimal valeurTotale = Montants.ZERO;
        for (Produit p : retenus) {
            boolean service = p.estService();
            List<Object> ligne = new ArrayList<>(java.util.Arrays.asList(p.getReference(), p.getNom(), nomCategorie(p), service ? "Service" : "Article",
                    service ? null : p.getUnite(), service ? null : p.getQuantite(), service ? null : p.getSeuilAlerte(), libelleEtat(p.etatStock()),
                    p.isPrixDefinis() ? p.getPrixVente() : null));
            if (gerant) {
                // Coûts et marges : calculés pour le gérant seulement.
                BigDecimal valeur = !service && p.isPrixDefinis() && p.getQuantite() != null && p.getCoutMoyen() != null
                        ? p.getCoutMoyen().multiply(BigDecimal.valueOf(p.getQuantite())) : null;
                if (valeur != null) valeurTotale = valeurTotale.add(valeur);
                ligne.addAll(java.util.Arrays.asList(fournisseur(p), p.isPrixDefinis() ? p.getCoutMoyen() : null,
                        p.isPrixDefinis() && !service ? p.getPrixAchat() : null, p.getMargeUnitaire(), p.getTauxMarge(), valeur));
            }
            tableau.ligne(ligne.toArray());
        }
        List<Object> totaux = new ArrayList<>(java.util.Arrays.asList(retenus.size() + (retenus.size() > 1 ? " produits" : " produit"),
                null, null, null, null, null, null, null, null));
        if (gerant) totaux.addAll(java.util.Arrays.asList(null, null, null, null, null, valeurTotale));
        tableau.totaux(totaux.toArray());
        if (gerant) tableau.note("Valeur du stock = quantité × coût moyen pondéré. Marge unitaire = prix de vente − coût moyen ; taux de marge sur le prix de vente.");
        return tableau;
    }

    private static boolean correspondEtat(String stock, Produit p) {
        String etat = p.etatStock();
        return switch (stock) {
            case "en-stock" -> !"service".equals(etat) && p.getQuantite() != null && p.getQuantite() > 0;
            case "a-surveiller" -> "rupture".equals(etat) || "faible".equals(etat);
            default -> etat.equals(stock);
        };
    }

    private static String decrireFiltres(String categorie, String stock, String recherche) {
        List<String> parties = new ArrayList<>();
        if (categorie != null) parties.add("Catégorie : " + categorie);
        if (stock != null) parties.add("Stock : " + libelleFiltreStock(stock));
        if (!recherche.isEmpty()) parties.add("Recherche : « " + recherche + " »");
        return parties.isEmpty() ? "Tous les produits" : String.join(" · ", parties);
    }

    private static String libelleFiltreStock(String stock) {
        return switch (stock) {
            case "en-stock" -> "en stock";
            case "disponible" -> "disponible (au-dessus du seuil)";
            case "faible" -> "stock faible (seuil atteint)";
            case "rupture" -> "en rupture";
            case "a-surveiller" -> "à surveiller (rupture ou seuil atteint)";
            default -> "services";
        };
    }

    private static String libelleEtat(String etat) {
        return switch (etat) {
            case "service" -> "Service";
            case "rupture" -> "En rupture";
            case "faible" -> "Stock faible";
            case "disponible" -> "Disponible";
            default -> "Non renseigné";
        };
    }

    private static String nomCategorie(Produit p) { return p.getCategorie() != null ? p.getCategorie().getNom() : "Sans catégorie"; }

    private static String fournisseur(Produit p) { return p.getFournisseur() != null ? p.getFournisseur().getNom() : "Non défini"; }

    /** Texte de recherche de l'écran : nom, référence, catégorie, et le fournisseur pour le gérant seulement. */
    private static String texteDeLaLigne(Produit p, boolean gerant) {
        return String.join(" ", p.getNom(), p.getReference(), nomCategorie(p), gerant ? fournisseur(p) : "");
    }
}
