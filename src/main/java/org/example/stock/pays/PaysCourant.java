package org.example.stock.pays;

import org.example.stock.model.Emails;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Pays de la boutique du compte connecté (bean {@code paysCourant}), lu une seule fois par requête. Sans compte connecté (page publique,
 * traitement hors requête), c'est {@link Pays#PAR_DEFAUT}.
 */
@Component("paysCourant")
public class PaysCourant {
    private static final String ATTRIBUT_REQUETE = PaysCourant.class.getName() + ".pays";

    private final UtilisateurRepository utilisateurs;

    public PaysCourant(UtilisateurRepository utilisateurs) { this.utilisateurs = utilisateurs; }

    public Pays pays() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) return Pays.PAR_DEFAUT;
        RequestAttributes requete = RequestContextHolder.getRequestAttributes();
        if (requete != null && requete.getAttribute(ATTRIBUT_REQUETE, RequestAttributes.SCOPE_REQUEST) instanceof Pays cache) return cache;
        Pays pays = utilisateurs.findByEmail(Emails.normaliser(auth.getName()))
                .map(u -> u.getBoutique() == null ? null : u.getBoutique().getPays())
                .orElse(Pays.PAR_DEFAUT);
        if (requete != null) requete.setAttribute(ATTRIBUT_REQUETE, pays, RequestAttributes.SCOPE_REQUEST);
        return pays;
    }

    public Monnaie monnaie() { return pays().getMonnaie(); }

    /** Début de numéro proposé dans les formulaires de nouvelle fiche : « +224 ». */
    public String indicatifSaisie() { return pays().indicatifSaisie(); }

    /**
     * Adresse d'un lien {@code tel:} : le numéro tel qu'il a été saisi, avec l'indicatif du pays quand il n'en porte pas
     * (voir {@link Pays#numeroInternational(String)}). Les numéros enregistrés ne sont jamais modifiés.
     */
    public String lienTel(String numero) { return "tel:" + pays().numeroInternational(numero); }

    /** Numéro à enregistrer pour une saisie : vide si elle ne contient que l'indicatif proposé par défaut. */
    public String telephoneSaisi(String saisie) { return pays().sansIndicatifSeul(saisie); }
}
