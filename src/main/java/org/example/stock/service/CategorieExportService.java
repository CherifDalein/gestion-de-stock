package org.example.stock.service;

import org.example.stock.export.*;
import org.example.stock.repository.CategorieRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/** Export de la liste des catégories (nom et nombre de produits) : mêmes catégories que {@code /categories}, réservé au gérant. */
@Service
@Transactional(readOnly = true)
public class CategorieExportService {
    private final CategorieRepository categories;
    private final BoutiqueService boutiques;
    private final LimiteExport limite;

    public CategorieExportService(CategorieRepository categories, BoutiqueService boutiques, LimiteExport limite) {
        this.categories = categories; this.boutiques = boutiques; this.limite = limite;
    }

    /** @param recherche texte de la barre de recherche de l'écran : identifiant ou nom (facultatif) */
    public TableauExport preparer(String recherche) {
        DroitsExport.exigerGerant();
        List<Object[]> lues = categories.compterProduitsParCategorie(boutiques.idBoutiqueCourante(), PageRequest.of(0, limite.lignes() + 1));
        limite.verifier(lues.size());
        String q = recherche == null ? "" : recherche.strip();
        List<Object[]> retenues = lues.stream().filter(c -> q.isEmpty() || RechercheListe.correspond(q, c[0] + " " + c[1]))
                .sorted(Comparator.comparing((Object[] c) -> (String) c[1], RechercheListe.ordreNaturel()).thenComparing(c -> (Long) c[0])).toList();
        TableauExport tableau = TableauExport.titre("Catégories").nomFeuille("Catégories")
                .filtres(q.isEmpty() ? "Toutes les catégories" : "Recherche : « " + q + " »")
                .colonnes(Colonne.texte("Nom"), Colonne.entier("Nombre de produits"));
        long produits = 0;
        for (Object[] c : retenues) {
            long nombre = ((Number) c[2]).longValue();
            produits += nombre;
            tableau.ligne(c[1], nombre);
        }
        return tableau.totaux(retenues.size() + (retenues.size() > 1 ? " catégories" : " catégorie"), produits);
    }
}
