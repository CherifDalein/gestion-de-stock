package org.example.stock.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

/** L'administration reste accessible lorsque la boutique de l'opérateur est suspendue. */
public class PlateformeAuthenticationSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {
    public PlateformeAuthenticationSuccessHandler() {
        setAlwaysUseDefaultTargetUrl(true);
        setDefaultTargetUrl("/");
    }

    @Override
    protected String determineTargetUrl(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) {
        if (authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_PLATEFORME_ADMIN".equals(authority.getAuthority()))) {
            return "/plateforme/boutiques";
        }
        return super.determineTargetUrl(request, response, authentication);
    }
}
