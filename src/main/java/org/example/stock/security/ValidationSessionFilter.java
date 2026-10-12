package org.example.stock.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/** Refuse les sessions authentifiées avant un changement de mot de passe, sans registre local de sessions. */
public final class ValidationSessionFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLICS = Set.of("/login", "/error", "/mot-de-passe-oublie", "/reinitialiser-mot-de-passe");
    private final UtilisateurRepository utilisateurs;

    public ValidationSessionFilter(UtilisateurRepository utilisateurs) {
        this.utilisateurs = utilisateurs;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String chemin = request.getServletPath();
        return chemin.startsWith("/css/") || chemin.startsWith("/js/") || chemin.startsWith("/assets/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof StockUserDetails principal) {
            boolean valide = utilisateurs.findByEmail(principal.getUsername()).map(principal::correspond).orElse(false);
            if (!valide) {
                new SecurityContextLogoutHandler().logout(request, response, authentication);
                String chemin = request.getServletPath();
                if (chemin == null || chemin.isEmpty()) chemin = request.getRequestURI().substring(request.getContextPath().length());
                if (!PUBLICS.contains(chemin)) {
                    response.sendRedirect(request.getContextPath() + "/login?expired");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
