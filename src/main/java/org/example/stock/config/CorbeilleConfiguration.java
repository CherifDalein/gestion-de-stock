package org.example.stock.config;

import org.example.stock.service.CorbeilleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableConfigurationProperties(CorbeilleProperties.class)
public class CorbeilleConfiguration {
    /**
     * L'effacement automatique est un bean à part, créé seulement quand {@code stock.corbeille.purge.actif} vaut {@code true}
     * (valeur par défaut) : les tests le désactivent, et un déploiement peut le couper sans toucher au code.
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "stock.corbeille.purge.actif", havingValue = "true", matchIfMissing = true)
    static class Planification {
        private static final Logger JOURNAL = LoggerFactory.getLogger(Planification.class);
        private final CorbeilleService corbeille;

        Planification(CorbeilleService corbeille) { this.corbeille = corbeille; }

        /** Une fois par jour (3 h du matin, heure du serveur, par défaut) : efface définitivement ce qui est à la corbeille depuis trop longtemps. */
        @Scheduled(cron = "${stock.corbeille.purge.cron:0 0 3 * * *}")
        void purger() {
            try {
                var bilan = corbeille.purgerExpires();
                JOURNAL.info("Corbeille : {} fiche(s) effacée(s) définitivement, {} conservée(s) faute de pouvoir les effacer.", bilan.effaces(), bilan.conserves());
            } catch (RuntimeException e) {
                // Une panne de la tâche ne doit jamais s'ajouter à celles de l'application : elle sera relancée le lendemain.
                JOURNAL.error("Corbeille : l'effacement automatique a échoué ({}).", e.getClass().getSimpleName());
            }
        }
    }
}
