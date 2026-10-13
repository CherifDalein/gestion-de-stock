package org.example.stock.service;

import org.example.stock.config.TrialProperties;
import org.example.stock.model.Boutique;
import org.example.stock.repository.ParametresPlateformeRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@Transactional(readOnly = true)
public class EssaiBoutiqueService {
    private final ParametresPlateformeRepository parametres;
    private final TrialProperties properties;
    private final Clock clock;

    public EssaiBoutiqueService(ParametresPlateformeRepository parametres, TrialProperties properties,
                               @Qualifier("recoveryClock") Clock clock) {
        this.parametres = parametres;
        this.properties = properties;
        this.clock = clock;
    }

    public int dureeEssaiJours() {
        int jours = parametres.findById(1).map(p -> p.getDureeEssaiJours()).orElse(properties.getDays());
        if (jours < 1 || jours > 3650) throw new IllegalStateException("La durée d'essai configurée est invalide.");
        return jours;
    }

    /** Appelé à la création finale ; une modification globale ne réécrit jamais les essais existants. */
    public void demarrerEssai(Boutique boutique) {
        if (boutique == null || boutique.getEssaiDebut() != null || boutique.getEssaiFin() != null) {
            throw new IllegalArgumentException("L'essai ne peut être démarré qu'une seule fois.");
        }
        LocalDateTime debut = maintenant();
        boutique.setEssaiDebut(debut);
        boutique.setEssaiFin(debut.plusDays(dureeEssaiJours()));
    }

    public boolean estExpire(Boutique boutique) {
        return ReglesAccesCommercial.estExpire(boutique, maintenant());
    }

    public long joursRestants(Boutique boutique) {
        if (boutique == null || boutique.isAccesOffert() || boutique.getEssaiDebut() == null
                || boutique.getEssaiFin() == null || !boutique.getEssaiFin().isAfter(boutique.getEssaiDebut())
                || !maintenant().isBefore(boutique.getEssaiFin())) return 0;
        long secondes = Duration.between(maintenant(), boutique.getEssaiFin()).getSeconds();
        return Math.max(1, (secondes + 86399) / 86400);
    }

    private LocalDateTime maintenant() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
