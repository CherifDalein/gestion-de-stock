package org.example.stock.service;

import org.example.stock.export.DroitsExport;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Les états du gérant ne se calculent que pour le gérant (ADMIN). La configuration de sécurité refuse déjà le caissier
 * sur {@code /etats/**} ; ce contrôle, répété dans chaque service d'état, garantit que rien (coût, marge, trésorerie, noms
 * de clients) n'est même lu pour un autre rôle, quelle que soit la façon d'appeler le service.
 */
final class DroitsEtats {
    private DroitsEtats() { }

    static void exigerGerant() {
        if (!DroitsExport.estGerant()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "États réservés au gérant");
    }
}
