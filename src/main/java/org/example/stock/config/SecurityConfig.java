package org.example.stock.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.http.HttpMethod;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/error", "/css/**", "/js/**", "/assets/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/produits").hasAnyRole("ADMIN", "CAISSIER")
                        .requestMatchers("/clients/supprimer/**").hasRole("ADMIN")
                        .requestMatchers("/clients/**", "/ventes/**").hasAnyRole("ADMIN", "CAISSIER")
                        .requestMatchers(HttpMethod.GET, "/factures/liste", "/factures/vente/*",
                                "/factures/client/*", "/factures/client/*/cumule").hasAnyRole("ADMIN", "CAISSIER")
                        .requestMatchers("/register", "/categories/**", "/produits/**", "/fournisseurs/**",
                                "/achats/**", "/caisse/**", "/factures/achat/**", "/factures/fournisseur/**").hasRole("ADMIN")
                        .anyRequest().denyAll()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .defaultSuccessUrl("/", true)
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

        http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new LengthCheckedBcryptPasswordEncoder();
    }
}
