package org.example.stock.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.http.HttpMethod;
import org.example.stock.security.PlateformeAuthenticationSuccessHandler;
import org.example.stock.security.ValidationSessionFilter;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, UtilisateurRepository utilisateurs) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/error", "/css/**", "/js/**", "/assets/**").permitAll()
                        .requestMatchers("/mot-de-passe-oublie", "/reinitialiser-mot-de-passe").permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/accueil").permitAll()
                        .requestMatchers("/inscription", "/inscription/confirmer").permitAll()
                        .requestMatchers("/plateforme", "/plateforme/**").hasRole("PLATEFORME_ADMIN")
                        .requestMatchers("/mon-compte", "/mon-compte/**").hasAnyRole("ADMIN", "CAISSIER")
                        .requestMatchers(HttpMethod.GET, "/mon-abonnement").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/acces-boutique", "/produits").hasAnyRole("ADMIN", "CAISSIER")
                        .requestMatchers("/clients/supprimer/**").hasRole("ADMIN")
                        .requestMatchers("/clients/**", "/ventes/**").hasAnyRole("ADMIN", "CAISSIER")
                        .requestMatchers(HttpMethod.GET, "/factures/liste", "/factures/vente/*",
                                "/factures/client/*", "/factures/client/*/cumule").hasAnyRole("ADMIN", "CAISSIER")
                        .requestMatchers("/boutique/**", "/utilisateurs", "/utilisateurs/**", "/register", "/categories/**", "/produits/**", "/fournisseurs/**",
                                "/achats/**", "/caisse/**", "/factures/achat/**", "/factures/fournisseur/**").hasRole("ADMIN")
                        .anyRequest().denyAll()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .successHandler(new PlateformeAuthenticationSuccessHandler())
                        .failureUrl("/login?error")
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll()
                );

        http.addFilterBefore(new ValidationSessionFilter(utilisateurs), AuthorizationFilter.class);
        http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new LengthCheckedBcryptPasswordEncoder();
    }
}
