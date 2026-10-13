package org.example.stock.service;

import org.example.stock.model.Boutique;

import java.time.LocalDateTime;

/** Règles communes à l'affichage et aux contrôles d'accès ; toutes les dates sont UTC. */
public final class ReglesAccesCommercial {
    private ReglesAccesCommercial() {}

    public static boolean periodeValide(LocalDateTime debut, LocalDateTime fin) {
        return debut != null && fin != null && fin.isAfter(debut);
    }

    public static boolean dansPeriode(LocalDateTime debut, LocalDateTime fin, LocalDateTime maintenant) {
        return periodeValide(debut, fin) && !maintenant.isBefore(debut) && maintenant.isBefore(fin);
    }

    public static boolean essaiActif(Boutique boutique, LocalDateTime maintenant) {
        return dansPeriode(boutique.getEssaiDebut(), boutique.getEssaiFin(), maintenant);
    }

    public static boolean abonnementActif(Boutique boutique, LocalDateTime maintenant) {
        return dansPeriode(boutique.getAbonnementDebut(), boutique.getAbonnementFin(), maintenant);
    }

    public static boolean accesExistant(Boutique boutique) {
        return boutique.getEssaiDebut() == null && boutique.getEssaiFin() == null
                && boutique.getAbonnementDebut() == null && boutique.getAbonnementFin() == null;
    }

    /** La suspension manuelle reste un contrôle indépendant, appliqué avant cette règle. */
    public static boolean estExpire(Boutique boutique, LocalDateTime maintenant) {
        if (boutique == null || boutique.isAccesOffert()) return false;
        return !accesExistant(boutique) && !essaiActif(boutique, maintenant)
                && !abonnementActif(boutique, maintenant);
    }
}
