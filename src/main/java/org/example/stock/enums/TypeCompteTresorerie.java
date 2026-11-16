package org.example.stock.enums;

import org.example.stock.pays.Pays;

/**
 * Famille d'un compte de trésorerie. Une boutique peut avoir plusieurs comptes de chaque type (par exemple deux comptes de mobile
 * money, « Orange Money Labé » et « Wave Dakar ») ; le type sert aux regroupements (bilan, états, tableau de bord) et à choisir le
 * compte principal proposé par défaut. Les libellés des types sont ceux que le bilan et les exports affichent en en-tête de colonne.
 *
 * <p>Le type {@link #MOBILE_MONEY} (anciennement {@code ORANGE_MONEY}) regroupe tous les opérateurs ; chaque compte porte le sien
 * ({@code CompteTresorerieBoutique#getOperateur()}, choisi dans la liste du pays de la boutique ou saisi librement). En comptabilité,
 * rien ne change : ce sont toujours des sous-comptes de 552.</p>
 */
public enum TypeCompteTresorerie {
    ESPECES("Caisse (espèces)", "Caisse"),
    MOBILE_MONEY("Mobile money", null),
    BANQUE("Banque", "Banque");

    private final String libelle;
    private final String nomParDefaut;

    TypeCompteTresorerie(String libelle, String nomParDefaut) {
        this.libelle = libelle;
        this.nomParDefaut = nomParDefaut;
    }

    public String getLibelle() { return libelle; }

    /**
     * Nom du compte de base créé avec toute boutique : « Caisse », « Banque » et, pour le mobile money, l'opérateur le plus répandu du
     * pays (« Orange Money » en Guinée, « T-Money » au Togo : voir {@link Pays}).
     */
    public String getNomParDefaut(Pays pays) {
        return this == MOBILE_MONEY ? pays.operateurParDefaut() : nomParDefaut;
    }

    /** Vrai pour les comptes qui ont un opérateur (mobile money). */
    public boolean aUnOperateur() { return this == MOBILE_MONEY; }

    /**
     * Valeur reçue d'un formulaire : le nom du type, ou un ancien nom des formulaires ouverts avant les lots 3 et pays/monnaie :
     * {@code CAISSE} (lu comme {@code ESPECES}) et {@code ORANGE_MONEY} (lu comme {@code MOBILE_MONEY}).
     * Retourne {@code null} si ce n'est pas un type.
     */
    public static TypeCompteTresorerie depuisSaisie(String saisie) {
        if (saisie == null) return null;
        String valeur = saisie.strip();
        if (valeur.equals("CAISSE")) return ESPECES;
        if (valeur.equals("ORANGE_MONEY")) return MOBILE_MONEY;
        for (TypeCompteTresorerie type : values()) if (type.name().equals(valeur)) return type;
        return null;
    }
}
