package org.example.stock.releve;

import java.util.List;

/** Contenu brut d'un fichier de relevé : des lignes de cellules, toutes ramenées à la même largeur. */
public record TableauFichier(List<List<Cellule>> lignes, int colonnes) {
    public Cellule cellule(int ligne, int colonne) {
        if (ligne < 0 || ligne >= lignes.size()) return Cellule.VIDE;
        List<Cellule> l = lignes.get(ligne);
        return colonne < 0 || colonne >= l.size() ? Cellule.VIDE : l.get(colonne);
    }

    public boolean ligneVide(int ligne) {
        return lignes.get(ligne).stream().allMatch(Cellule::estVide);
    }
}
