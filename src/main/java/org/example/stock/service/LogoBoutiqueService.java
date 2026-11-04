package org.example.stock.service;

import org.example.stock.enums.Role;
import org.example.stock.model.Emails;
import org.example.stock.repository.LogoBoutiqueRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class LogoBoutiqueService {
    private final LogoBoutiqueRepository logos;
    private final BoutiqueService boutiques;
    private final UtilisateurRepository utilisateurs;

    public LogoBoutiqueService(LogoBoutiqueRepository logos, BoutiqueService boutiques, UtilisateurRepository utilisateurs) {
        this.logos = logos; this.boutiques = boutiques; this.utilisateurs = utilisateurs;
    }

    public byte[] getPourBoutiqueCourante() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) throw interdit();
        var compte = utilisateurs.findByEmail(Emails.normaliser(auth.getName())).orElseThrow(LogoBoutiqueService::interdit);
        if ((compte.getRole() != Role.ADMIN && compte.getRole() != Role.CAISSIER)
                || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_" + compte.getRole().name()))) throw interdit();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        return logos.findById(boutiqueId).map(logo -> logo.getContenu().clone())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cette boutique n'a pas de logo"));
    }

    private static ResponseStatusException interdit() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Logo de boutique inaccessible");
    }
}
