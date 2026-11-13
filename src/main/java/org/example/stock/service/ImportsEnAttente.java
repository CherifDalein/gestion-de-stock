package org.example.stock.service;

import org.example.stock.releve.TableauFichier;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Fichiers de relevé en cours d'import, gardés <b>en mémoire seulement</b> entre l'envoi du fichier et la confirmation : jamais écrits sur le
 * disque, jamais dans la session HTTP (qui peut être sauvegardée à l'arrêt du serveur). Chaque entrée appartient à un gérant, une boutique et un
 * relevé ; elle expire après {@value #DUREE_MINUTES} minutes et au plus {@value #CAPACITE} entrées sont gardées. Seules les lignes confirmées
 * sont enregistrées ; le contenu brut disparaît à la confirmation, à l'expiration ou au redémarrage.
 */
@Component
public class ImportsEnAttente {
    static final int DUREE_MINUTES = 30, CAPACITE = 30;
    private static final SecureRandom ALEA = new SecureRandom();

    public record Entree(String jeton, Long boutiqueId, Long releveId, Long utilisateurId, String nomFichier, TableauFichier tableau, Instant expire) { }

    private final Map<String, Entree> entrees = new LinkedHashMap<>();

    public synchronized String deposer(Long boutiqueId, Long releveId, Long utilisateurId, String nomFichier, TableauFichier tableau) {
        purger();
        while (entrees.size() >= CAPACITE) {
            Iterator<String> plusAncien = entrees.keySet().iterator();
            plusAncien.next(); plusAncien.remove();
        }
        byte[] octets = new byte[16];
        ALEA.nextBytes(octets);
        String jeton = HexFormat.of().formatHex(octets);
        entrees.put(jeton, new Entree(jeton, boutiqueId, releveId, utilisateurId, nomFichier, tableau, Instant.now().plus(Duration.ofMinutes(DUREE_MINUTES))));
        return jeton;
    }

    /** L'entrée, seulement pour le même gérant, la même boutique et le même relevé ; vide sinon (expirée, inconnue ou d'un autre). */
    public synchronized Optional<Entree> retrouver(String jeton, Long boutiqueId, Long releveId, Long utilisateurId) {
        purger();
        Entree e = jeton == null ? null : entrees.get(jeton);
        if (e == null || !e.boutiqueId().equals(boutiqueId) || !e.releveId().equals(releveId) || !e.utilisateurId().equals(utilisateurId)) return Optional.empty();
        return Optional.of(e);
    }

    public synchronized void retirer(String jeton) { entrees.remove(jeton); }

    synchronized int taille() { purger(); return entrees.size(); }

    private void purger() {
        Instant maintenant = Instant.now();
        entrees.values().removeIf(e -> e.expire().isBefore(maintenant));
    }
}
