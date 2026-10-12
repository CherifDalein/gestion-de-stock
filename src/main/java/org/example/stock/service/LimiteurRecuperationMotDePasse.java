package org.example.stock.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Limites par instance, mémoire bornée et aucune adresse email en clair dans les clés. */
@Component
public class LimiteurRecuperationMotDePasse {
    static final int CAPACITE = 10_000;
    private final Clock clock;
    private final Map<String, Fenetre> compteurs = new HashMap<>();
    private long demandes;

    public LimiteurRecuperationMotDePasse(@Qualifier("recoveryClock") Clock clock) { this.clock = clock; }

    public synchronized boolean autoriserDemande(String emailNormalise, String ip) {
        return autoriser(List.of(new Limite("demande:global", 60, Duration.ofMinutes(1)),
                new Limite("demande:ip:" + adresse(ip), 10, Duration.ofHours(1)),
                new Limite("demande:email:" + EmpreintesRecuperation.calculer(emailNormalise), 3, Duration.ofHours(1))));
    }

    public synchronized boolean autoriserReinitialisation(String jeton, String ip) {
        return autoriser(List.of(new Limite("reset:global", 60, Duration.ofMinutes(1)),
                new Limite("reset:ip:" + adresse(ip), 20, Duration.ofMinutes(10)),
                new Limite("reset:jeton:" + EmpreintesRecuperation.calculer(jeton == null ? "" : jeton), 5, Duration.ofMinutes(10))));
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
        // Ne pas évincer un compteur vivant : cela permettrait de contourner les limites.
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
