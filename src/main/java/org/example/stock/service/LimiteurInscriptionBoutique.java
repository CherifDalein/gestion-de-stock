package org.example.stock.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Quotas propres à l'inscription, bornés en mémoire ; emails et jetons conservés uniquement en empreintes. */
@Component
public class LimiteurInscriptionBoutique {
    static final int CAPACITE = 10_000;
    private final Clock clock;
    private final Map<String, Fenetre> compteurs = new HashMap<>();
    private long demandes;

    public LimiteurInscriptionBoutique(@Qualifier("recoveryClock") Clock clock) { this.clock = clock; }

    public synchronized boolean autoriserDemande(String email, String ip) {
        return autoriser(List.of(new Limite("demande:global", 60, Duration.ofMinutes(1)),
                new Limite("demande:ip:" + adresse(ip), 10, Duration.ofHours(1)),
                new Limite("demande:email:" + EmpreintesRecuperation.calculer(email), 3, Duration.ofHours(1))));
    }

    public synchronized boolean autoriserConfirmation(String jeton, String ip) {
        return autoriser(List.of(new Limite("confirmation:global", 60, Duration.ofMinutes(1)),
                new Limite("confirmation:ip:" + adresse(ip), 20, Duration.ofMinutes(10)),
                new Limite("confirmation:jeton:" + EmpreintesRecuperation.calculer(jeton), 5, Duration.ofMinutes(10))));
    }

    private boolean autoriser(List<Limite> limites) {
        Instant maintenant = clock.instant();
        if (++demandes % 64 == 0 || compteurs.size() + limites.size() >= CAPACITE) {
            compteurs.entrySet().removeIf(entree -> !entree.getValue().expireLe.isAfter(maintenant));
        }
        int nouveaux = 0;
        for (Limite limite : limites) {
            Fenetre fenetre = compteurs.get(limite.cle);
            if (fenetre == null || !fenetre.expireLe.isAfter(maintenant)) nouveaux++;
            else if (fenetre.nombre >= limite.maximum) return false;
        }
        if (compteurs.size() + nouveaux > CAPACITE) return false;
        for (Limite limite : limites) {
            Fenetre fenetre = compteurs.get(limite.cle);
            if (fenetre == null || !fenetre.expireLe.isAfter(maintenant)) {
                compteurs.put(limite.cle, new Fenetre(1, maintenant.plus(limite.duree)));
            } else fenetre.nombre++;
        }
        return true;
    }

    private static String adresse(String ip) { return ip == null || ip.length() > 80 ? "indeterminee" : ip; }
    int nombreCompteurs() { synchronized (this) { return compteurs.size(); } }
    private record Limite(String cle, int maximum, Duration duree) {}
    private static final class Fenetre {
        private int nombre;
        private final Instant expireLe;
        private Fenetre(int nombre, Instant expireLe) { this.nombre = nombre; this.expireLe = expireLe; }
    }
}
