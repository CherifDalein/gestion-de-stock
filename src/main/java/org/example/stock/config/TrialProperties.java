package org.example.stock.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("stock.trial")
@Getter
@Setter
public class TrialProperties {
    private int days = 30;

    @PostConstruct
    public void verifier() {
        if (days < 1 || days > 3650) throw new IllegalArgumentException("La durée d'essai doit être comprise entre 1 et 3650 jours.");
    }
}
