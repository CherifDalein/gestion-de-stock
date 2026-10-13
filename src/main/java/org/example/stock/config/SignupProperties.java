package org.example.stock.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "stock.signup")
public class SignupProperties {
    private Duration ttl = Duration.ofHours(24);

    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }

    @PostConstruct
    public void valider() {
        if (ttl == null || ttl.compareTo(Duration.ofHours(1)) < 0 || ttl.compareTo(Duration.ofHours(48)) > 0) {
            throw new IllegalStateException("Le lien d'inscription doit durer entre 1 et 48 heures");
        }
    }
}
