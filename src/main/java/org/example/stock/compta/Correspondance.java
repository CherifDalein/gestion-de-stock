package org.example.stock.compta;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Table de correspondance en vigueur pour une boutique : le compte de chaque nature d'opération, les mots-clés de chaque catégorie de
 * dépense et le compte de chaque compte de trésorerie. Objet immuable construit par {@code CorrespondanceService} (valeurs par défaut
 * remplacées par les lignes du gérant) ; les tests de calcul des écritures le construisent directement.
 */
public final class Correspondance {
    private final Map<CleCompta, String> comptes = new EnumMap<>(CleCompta.class);
    private final Map<CleCompta, List<String>> motsCles = new EnumMap<>(CleCompta.class);
    private final Map<Long, String> tresorerie = new HashMap<>();

    private Correspondance() {}

    /** Valeurs par défaut, avec les comptes de trésorerie donnés (identifiant du compte → numéro du compte comptable). */
    public static Correspondance parDefaut(Map<Long, String> tresorerie) {
        return avec(Map.of(), Map.of(), tresorerie);
    }

    /** Valeurs par défaut remplacées par {@code comptes} et {@code motsCles} (une catégorie sans mots-clés saisis garde les siens). */
    public static Correspondance avec(Map<CleCompta, String> comptes, Map<CleCompta, String> motsCles, Map<Long, String> tresorerie) {
        Correspondance c = new Correspondance();
        for (CleCompta cle : CleCompta.values()) {
            c.comptes.put(cle, comptes.getOrDefault(cle, cle.getCompteParDefaut()));
            if (cle.estDepense()) c.motsCles.put(cle, decouperMotsCles(motsCles.getOrDefault(cle, cle.getMotsClesParDefaut())));
        }
        c.tresorerie.putAll(tresorerie);
        return c;
    }

    public String compte(CleCompta cle) { return comptes.get(cle); }

    /** Compte comptable d'un compte de trésorerie ; le compte d'attente si l'application n'en connaît aucun. */
    public String compteTresorerie(Long compteId) { return tresorerie.getOrDefault(compteId, comptes.get(CleCompta.ATTENTE)); }

    /** Première catégorie dont un mot-clé commence un mot du motif, dans l'ordre des catégories ; sinon « autre dépense ». */
    public CleCompta categorieDepense(String motif) {
        String texte = " " + Normalisation.mots(motif);
        for (CleCompta cle : CleCompta.categoriesDepense()) {
            for (String mot : motsCles.getOrDefault(cle, List.of())) if (texte.contains(" " + mot)) return cle;
        }
        return CleCompta.DEPENSE_AUTRE;
    }

    public String compteDepense(String motif) { return comptes.get(categorieDepense(motif)); }

    /** Liste normalisée de mots-clés séparés par des virgules (sans doublon ni mot vide). */
    public static List<String> decouperMotsCles(String saisie) {
        List<String> mots = new ArrayList<>();
        if (saisie == null) return mots;
        for (String brut : saisie.split(",")) {
            String mot = Normalisation.mots(brut);
            if (!mot.isEmpty() && !mots.contains(mot)) mots.add(mot);
        }
        return mots;
    }
}
