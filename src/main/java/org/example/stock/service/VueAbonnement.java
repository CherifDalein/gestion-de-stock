package org.example.stock.service;

import java.util.List;

public record VueAbonnement(SituationAbonnement situation, List<EvenementAbonnement> historique,
                            int page, boolean pagePrecedente, boolean pageSuivante) {
    public VueAbonnement { historique = List.copyOf(historique); }
}
