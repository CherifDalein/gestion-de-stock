package org.example.stock.export;

import org.example.stock.service.FiltreVentes;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Lecture stricte des paramètres de filtre d'un export : un paramètre invalide, répété ou trop long donne <b>400</b>, jamais
 * une liste différente de celle de l'écran. Un paramètre absent ou vide veut dire « pas de filtre ».
 */
public final class ParametresListe {
    public static final int RECHERCHE_MAX = 100;

    private ParametresListe() { }

    /** Texte de la barre de recherche (« » sans recherche), 100 caractères au plus, valeur unique. */
    public static String recherche(List<String> q) {
        if (q == null || q.isEmpty()) return "";
        if (q.size() != 1 || q.getFirst().length() > RECHERCHE_MAX) throw invalide("Recherche invalide");
        return q.getFirst().strip();
    }

    /** Valeur unique parmi {@code autorisees}, ou null si le paramètre est absent ou vide. */
    public static String choix(String nom, List<String> valeurs, Set<String> autorisees) {
        String valeur = unique(nom, valeurs);
        if (valeur == null) return null;
        if (!autorisees.contains(valeur)) throw invalide(nom + " invalide");
        return valeur;
    }

    /** Identifiant numérique unique, ou null si le paramètre est absent ou vide. */
    public static Long identifiant(String nom, List<String> valeurs) {
        String valeur = unique(nom, valeurs);
        if (valeur == null) return null;
        if (!valeur.matches("\\d{1,18}")) throw invalide(nom + " invalide");
        return Long.parseLong(valeur);
    }

    /** Date {@code AAAA-MM-JJ} réelle (2000 à 9998), ou null si absente ; mêmes règles que la liste des ventes. */
    public static LocalDate date(List<String> valeurs) {
        try {
            return FiltreVentes.analyserDate(valeurs);
        } catch (IllegalArgumentException e) {
            throw invalide("Période invalide");
        }
    }

    /** Vérifie que la période est dans l'ordre (les deux bornes sont facultatives). */
    public static void periodeCoherente(LocalDate du, LocalDate au) {
        if (!FiltreVentes.periodeCoherente(du, au)) throw invalide("Période invalide");
    }

    /** Valeur unique non vide (espaces retirés), null si absente ou vide ; plusieurs valeurs : 400. */
    public static String unique(String nom, List<String> valeurs) {
        if (valeurs == null || valeurs.isEmpty()) return null;
        if (valeurs.size() != 1 || valeurs.getFirst().length() > RECHERCHE_MAX) throw invalide(nom + " invalide");
        String valeur = valeurs.getFirst().strip();
        return valeur.isEmpty() ? null : valeur;
    }

    private static ResponseStatusException invalide(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
