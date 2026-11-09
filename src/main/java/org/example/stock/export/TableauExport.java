package org.example.stock.export;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Description neutre d'une liste à exporter : un titre, un texte de période ou de filtres, des colonnes typées,
 * des lignes et, si c'est pertinent, une ligne de totaux. Le MÊME objet alimente {@link ExportExcelService#ecrire}
 * et {@link DocumentPdfService#rendreTableau}, ce qui garantit que l'Excel et le PDF montrent les mêmes données.
 *
 * <pre>{@code
 * TableauExport t = TableauExport.titre("Clients")
 *         .filtres("Période du 01/11/2026 au 30/11/2026")
 *         .colonnes(Colonne.texte("Nom"), Colonne.montant("Solde"))
 *         .ligne("Awa Diallo", new BigDecimal("125000"))
 *         .totaux("Total", new BigDecimal("125000"));
 * }</pre>
 *
 * Une cellule dont le type ne correspond pas à la colonne est refusée à l'écriture avec un message clair.
 */
public final class TableauExport {
    private final String titre;
    private String nomFeuille;
    private String filtres = "";
    private final List<String> notes = new ArrayList<>();
    private List<Colonne> colonnes = List.of();
    private final List<List<Object>> lignes = new ArrayList<>();
    private List<Object> totaux;

    private TableauExport(String titre) {
        if (titre == null || titre.isBlank()) throw new IllegalArgumentException("Un export doit avoir un titre");
        this.titre = titre.strip();
    }

    public static TableauExport titre(String titre) { return new TableauExport(titre); }

    /** Période et filtres appliqués, en une phrase lisible (« Période du … au … · Vendeur : … »). */
    public TableauExport filtres(String filtres) { this.filtres = filtres == null ? "" : filtres.strip(); return this; }

    /** Remarque imprimée sous le tableau (PDF) et sous les filtres (Excel), par exemple « 2 lignes sans coût connu ». */
    public TableauExport note(String note) { if (note != null && !note.isBlank()) notes.add(note.strip()); return this; }

    /** Nom de l'onglet Excel ; par défaut le titre. Les caractères interdits par Excel sont remplacés. */
    public TableauExport nomFeuille(String nom) { this.nomFeuille = nom; return this; }

    public TableauExport colonnes(Colonne... colonnes) {
        if (colonnes.length == 0) throw new IllegalArgumentException("Un export doit avoir au moins une colonne");
        this.colonnes = List.copyOf(Arrays.asList(colonnes));
        return this;
    }

    /** Ajoute une ligne : une valeur par colonne, dans l'ordre (null pour une cellule vide). */
    public TableauExport ligne(Object... cellules) {
        verifierLargeur(cellules.length);
        lignes.add(new ArrayList<>(Arrays.asList(cellules)));
        return this;
    }

    /** Ligne de totaux, une valeur par colonne (null pour vide) ; la première cellule porte en général le libellé « Total ». */
    public TableauExport totaux(Object... cellules) {
        verifierLargeur(cellules.length);
        this.totaux = new ArrayList<>(Arrays.asList(cellules));
        return this;
    }

    private void verifierLargeur(int n) {
        if (colonnes.isEmpty()) throw new IllegalStateException("Déclarez les colonnes avant les lignes");
        if (n != colonnes.size()) throw new IllegalArgumentException("Cette ligne a " + n + " cellule(s) pour " + colonnes.size() + " colonne(s)");
    }

    public String getTitre() { return titre; }
    public String getNomFeuille() { return nomFeuille == null || nomFeuille.isBlank() ? titre : nomFeuille; }
    public String getFiltres() { return filtres; }
    public List<String> getNotes() { return List.copyOf(notes); }
    public List<Colonne> getColonnes() { return colonnes; }
    public List<List<Object>> getLignes() { return java.util.Collections.unmodifiableList(lignes); }
    public List<Object> getTotaux() { return totaux; }
    public boolean aDesMontants() { return colonnes.stream().anyMatch(c -> c.type() == TypeColonne.MONTANT); }
}
