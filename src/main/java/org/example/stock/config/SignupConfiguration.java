package org.example.stock.config;

import org.example.stock.service.InscriptionBoutiqueMailer;
import org.example.stock.service.SmtpInscriptionBoutiqueMailer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
@EnableConfigurationProperties(SignupProperties.class)
public class SignupConfiguration {
    @Bean
    @ConditionalOnMissingBean(InscriptionBoutiqueMailer.class)
    public InscriptionBoutiqueMailer inscriptionBoutiqueMailer(ObjectProvider<JavaMailSender> sender,
                                                               RecoveryProperties properties) {
        // RecoveryConfiguration vérifie la configuration SMTP commune lors de son activation.
        return new SmtpInscriptionBoutiqueMailer(sender.getIfAvailable(), properties);
    }
}
