package org.example.stock.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.stock.repository.UtilisateurRepository;
import org.example.stock.service.EssaiBoutiqueService;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;

/** Vérifie aussi les sessions ouvertes avant l'expiration, avant tout contrôleur métier. */
@Configuration
public class EssaiAccessConfiguration implements WebMvcConfigurer {
    private static final Set<String> PUBLICS = Set.of("/accueil", "/login", "/logout", "/error", "/inscription",
            "/inscription/confirmer", "/mot-de-passe-oublie", "/reinitialiser-mot-de-passe", "/acces-boutique", "/mon-abonnement");
    private final UtilisateurRepository utilisateurs;
    private final EssaiBoutiqueService essais;

    public EssaiAccessConfiguration(UtilisateurRepository utilisateurs, EssaiBoutiqueService essais) {
        this.utilisateurs = utilisateurs; this.essais = essais;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String chemin = request.getRequestURI().substring(request.getContextPath().length());
                if (PUBLICS.contains(chemin) || chemin.equals("/plateforme") || chemin.startsWith("/plateforme/")
                        || chemin.equals("/mon-compte") || chemin.startsWith("/mon-compte/")) return true;
                var auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) return true;
                var compte = utilisateurs.findByEmail(auth.getName()).orElse(null);
                if (compte != null && compte.isActif() && compte.getBoutique() != null
                        && compte.getBoutique().isActif() && essais.estExpire(compte.getBoutique())) {
                    response.setStatus("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()) ? 302 : 303);
                    response.setHeader("Location", request.getContextPath() + "/acces-boutique");
                    response.setHeader("Cache-Control", "no-store");
                    return false;
                }
                return true;
            }
        }).excludePathPatterns("/css/**", "/js/**", "/assets/**");
    }
}
