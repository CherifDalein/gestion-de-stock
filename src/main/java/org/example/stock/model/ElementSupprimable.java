package org.example.stock.model;

import java.time.LocalDateTime;

/** Une fiche qui passe par la corbeille (lot 6) : client, fournisseur, produit ou catégorie. */
public interface ElementSupprimable {
    Long getId();

    String getNom();

    /** Place la fiche dans la corbeille : elle disparaît de tous les écrans (restriction SQL de l'entité) mais reste en base. */
    void mettreALaCorbeille(LocalDateTime quand, Long par);
}
