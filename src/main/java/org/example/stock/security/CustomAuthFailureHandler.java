package org.example.stock.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class CustomAuthFailureHandler implements AuthenticationFailureHandler {

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {

        // Affiche l'exception exacte dans la console
        System.out.println("Échec de connexion : " + exception.getClass().getSimpleName() + " -> " + exception.getMessage());

        // Redirection vers la page login avec paramètre error
        response.sendRedirect("/login?error");
    }
}
