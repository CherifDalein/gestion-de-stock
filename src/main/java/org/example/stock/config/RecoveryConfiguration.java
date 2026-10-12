package org.example.stock.config;

import org.example.stock.service.RecuperationMotDePasseMailer;
import org.example.stock.service.SmtpRecuperationMotDePasseMailer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(RecoveryProperties.class)
public class RecoveryConfiguration {
    @Bean("recoveryClock")
    @ConditionalOnMissingBean(name = "recoveryClock")
    public Clock recoveryClock() { return Clock.systemUTC(); }

    @Bean
    @ConditionalOnMissingBean(RecuperationMotDePasseMailer.class)
    public RecuperationMotDePasseMailer recoveryMailer(ObjectProvider<JavaMailSender> sender, RecoveryProperties properties) {
        JavaMailSender configured = sender.getIfAvailable();
        if (properties.isEnabled() && configured == null) {
            throw new IllegalStateException("Configurer SMTP avant d'activer la récupération des mots de passe");
        }
        return new SmtpRecuperationMotDePasseMailer(configured, properties);
    }
}
