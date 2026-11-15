package org.example.stock.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages de la corbeille (lot 6). {@code stock.corbeille.retention-jours} : combien de jours une fiche supprimée reste restaurable
 * avant l'effacement automatique ; {@code stock.corbeille.purge.actif} et {@code stock.corbeille.purge.cron} : la tâche quotidienne.
 */
@ConfigurationProperties(prefix = "stock.corbeille")
public class CorbeilleProperties {
    public static final int RETENTION_MAX_JOURS = 3650;

    private int retentionJours = 30;
    private final Purge purge = new Purge();

    public int getRetentionJours() { return retentionJours; }
    public void setRetentionJours(int retentionJours) { this.retentionJours = retentionJours; }
    public Purge getPurge() { return purge; }

    @PostConstruct
    public void valider() {
        if (retentionJours < 1 || retentionJours > RETENTION_MAX_JOURS)
            throw new IllegalStateException("stock.corbeille.retention-jours doit être compris entre 1 et " + RETENTION_MAX_JOURS + ".");
    }

    public static class Purge {
        private boolean actif = true;
        private String cron = "0 0 3 * * *";

        public boolean isActif() { return actif; }
        public void setActif(boolean actif) { this.actif = actif; }
        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
    }
}
