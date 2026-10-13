package org.example.stock.service;

import org.example.stock.model.Boutique;
import org.example.stock.model.Emails;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** La boutique provient du compte authentifié, jamais des paramètres du navigateur. */
@Service
@Transactional(readOnly = true)
public class BoutiqueService {
    private final UtilisateurRepository utilisateurs;
    private final EssaiBoutiqueService essais;

    public BoutiqueService(UtilisateurRepository utilisateurs, EssaiBoutiqueService essais) {
        this.utilisateurs = utilisateurs;
        this.essais = essais;
    }

    public Boutique boutiqueCourante() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Boutique inaccessible");
        }
        var utilisateur = utilisateurs.findByEmail(Emails.normaliser(authentication.getName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Boutique inaccessible"));
        if (!utilisateur.isActif()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "L'accès à ce compte est désactivé");
        }
        Boutique boutique = utilisateur.getBoutique();
        if (boutique == null || boutique.getId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Boutique inaccessible");
        }
        if (!boutique.isActif()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "L'accès à cette boutique est suspendu");
        }
        if (essais.estExpire(boutique)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "L'essai gratuit de cette boutique est terminé");
        }
        return boutique;
    }

    public Long idBoutiqueCourante() {
        return boutiqueCourante().getId();
    }

    public void exigerAppartenance(Boutique boutique) {
        Long autorisee = idBoutiqueCourante();
        if (boutique == null || !autorisee.equals(boutique.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Élément introuvable");
        }
    }
}
