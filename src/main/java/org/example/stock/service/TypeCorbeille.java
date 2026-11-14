package org.example.stock.service;

import java.util.List;
import java.util.Optional;

/**
 * Les quatre sortes de fiches qui passent par la corbeille (lot 6), avec les liens qui, aujourd'hui comme avant la corbeille,
 * interdisent de les supprimer : la même liste que les clés étrangères de {@code docs/database-schema.sql}
 * (un test compare les deux). Les noms de tables et de colonnes viennent d'ici, jamais du navigateur.
 *
 * <p>Les partenaires financiers (banque, associé…) ne se suppriment pas : ils s'archivent, ils ne passent donc pas par la corbeille.</p>
 */
public enum TypeCorbeille {
    CLIENT("client", "Client", "Clients", false, "client", "clients", List.of(
            new Lien("vente", "client_id", false), new Lien("devis", "client_id", false), new Lien("retour", "client_id", false),
            new Lien("mouvement_caisse", "client_id", false), new Lien("ajustement_solde_depart", "client_id", false),
            new Lien("ligne_inventaire_creances", "client_id", false)),
            "Ce client a des ventes, des devis, des retours ou des règlements : il doit être conservé pour préserver l’historique."),
    FOURNISSEUR("fournisseur", "Fournisseur", "Fournisseurs", false, "fournisseur", "fournisseurs", List.of(
            new Lien("achat", "fournisseur_id", false), new Lien("retour", "fournisseur_id", false),
            new Lien("mouvement_caisse", "fournisseur_id", false), new Lien("ajustement_solde_depart", "fournisseur_id", false),
            new Lien("ligne_inventaire_creances", "fournisseur_id", false), new Lien("produit", "fournisseur_id", true)),
            "Ce fournisseur a des achats, des retours, des règlements ou des produits : il doit être conservé pour préserver l’historique."),
    PRODUIT("produit", "Produit", "Produits", false, "produit", "produits", List.of(
            new Lien("detail_achat", "produit_id", false), new Lien("detail_vente", "produit_id", false),
            new Lien("detail_devis", "produit_id", false), new Lien("detail_retour", "produit_id", false),
            new Lien("mouvement_stock", "produit_id", false)),
            "Ce produit figure dans un achat, une vente, un devis ou un historique d’inventaire : il doit être conservé pour préserver l’historique."),
    CATEGORIE("categorie", "Catégorie", "Catégories", true, "categorie", "categories", List.of(
            new Lien("produit", "categorie_id", true)),
            "Cette catégorie contient encore des produits : déplacez-les ou supprimez-les d’abord.");

    /** Une table qui référence la fiche. {@code corbeille} : la table a elle-même une corbeille (ses lignes supprimées ne comptent pas à la suppression). */
    public record Lien(String table, String colonne, boolean corbeille) {}

    private final String table;
    private final String libelle, libellePluriel;
    private final boolean feminin;
    private final String code, route;
    private final List<Lien> liens;
    private final String messageRefus;

    TypeCorbeille(String table, String libelle, String libellePluriel, boolean feminin, String code, String route, List<Lien> liens, String messageRefus) {
        this.table = table; this.libelle = libelle; this.libellePluriel = libellePluriel; this.feminin = feminin;
        this.code = code; this.route = route; this.liens = liens; this.messageRefus = messageRefus;
    }

    public String table() { return table; }
    /** « Client », « Catégorie »… (début de phrase). */
    public String libelle() { return libelle; }
    public String libellePluriel() { return libellePluriel; }
    /** Valeur du paramètre d'adresse et du filtre : {@code client}, {@code fournisseur}, {@code produit}, {@code categorie}. */
    public String code() { return code; }
    /** Début de l'adresse de la liste d'origine ({@code clients}, {@code categories}…), sans barre oblique. */
    public String route() { return route; }
    public List<Lien> liens() { return liens; }
    public String messageRefus() { return messageRefus; }
    /** Vrai pour « catégorie » : accord du participe (« placée dans la corbeille »). */
    public boolean feminin() { return feminin; }
    public String placeDansLaCorbeille() { return feminin ? "placée" : "placé"; }
    public String restaure() { return feminin ? "restaurée" : "restauré"; }
    public String efface() { return feminin ? "effacée" : "effacé"; }

    public static Optional<TypeCorbeille> parCode(String code) {
        if (code == null) return Optional.empty();
        for (TypeCorbeille t : values()) if (t.code.equals(code)) return Optional.of(t);
        return Optional.empty();
    }
}
