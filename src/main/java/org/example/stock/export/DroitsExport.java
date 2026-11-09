package org.example.stock.export;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Règle des droits d'export : le gérant (ADMIN) exporte tout ; le caissier n'exporte que ce qu'il voit déjà à l'écran,
 * SANS coût, marge ni trésorerie. La décision se lit ICI, côté serveur, dans le compte connecté ; un paramètre du
 * navigateur ne l'élargit jamais. Un export ne CALCULE même pas les colonnes réservées pour un caissier.
 */
public final class DroitsExport {
    private DroitsExport() { }

    public static boolean estGerant() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /**
     * Seconde barrière pour les listes réservées au gérant (la règle de sécurité des routes est la première) : un export
     * qui lirait ces données pour un autre rôle, par erreur de routage, répond 403 au lieu de les livrer.
     */
    public static void exigerGerant() {
        if (!estGerant()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Export réservé au gérant");
    }
}
