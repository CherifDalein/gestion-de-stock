package org.example.stock.compta;

import java.util.Arrays;
import java.util.List;

/**
 * Lignes de la table de correspondance : à chaque nature d'opération de l'application, le compte du plan SYSCOHADA qui la reçoit.
 * Chaque ligne a une valeur par défaut (celle du brouillon validé par le client) que le gérant peut remplacer par boutique ; seules
 * les valeurs modifiées sont stockées. Les lignes {@link Groupe#DEPENSES} classent une dépense d'après les mots-clés de son motif.
 */
public enum CleCompta {
    // ---- Clients et ventes
    CREANCES_CLIENTS(Groupe.VENTES, "Créances clients", "4111", "Débité à la vente, crédité par les règlements, les retours et les avances utilisées."),
    AVANCES_CLIENTS(Groupe.VENTES, "Avances reçues des clients", "4191", "Crédité par un trop-payé ou un avoir de retour ; débité quand l'avance paie une vente."),
    VENTES_MARCHANDISES(Groupe.VENTES, "Ventes de marchandises", "701", "Crédité par la vente d'articles en stock (hors services)."),
    VENTES_SERVICES(Groupe.VENTES, "Ventes de services", "706", "Crédité par la vente de services (livraison, prestation)."),
    RETOURS_VENTES(Groupe.VENTES, "Retours de marchandises vendues", "701", "Débité par un retour de vente. Par défaut le même compte que les ventes (retour en moins du chiffre d'affaires) ; un comptable peut préférer un compte de rabais."),
    // ---- Fournisseurs et achats
    DETTES_FOURNISSEURS(Groupe.ACHATS, "Dettes fournisseurs", "4011", "Crédité à l'achat, débité par les paiements, les retours et les avances utilisées."),
    AVANCES_FOURNISSEURS(Groupe.ACHATS, "Avances versées aux fournisseurs", "4091", "Débité par un trop-payé ou un avoir de retour d'achat ; crédité quand l'avance paie un achat."),
    ACHATS_MARCHANDISES(Groupe.ACHATS, "Achats de marchandises", "601", "Débité par chaque achat (inventaire intermittent)."),
    RETOURS_ACHATS(Groupe.ACHATS, "Retours de marchandises achetées", "601", "Crédité par un retour d'achat."),
    // ---- Stock
    STOCK(Groupe.STOCK, "Stock de marchandises", "31", "Valeur du stock : report d'ouverture puis régularisation de fin de période."),
    VARIATION_STOCK(Groupe.STOCK, "Variation des stocks de marchandises", "6031", "Contrepartie de la régularisation du stock (inventaire intermittent)."),
    // ---- Trésorerie
    VIREMENTS_INTERNES(Groupe.TRESORERIE, "Virements de fonds entre comptes", "585", "Un transfert entre deux comptes de trésorerie passe par ce compte, qui revient à zéro."),
    FRAIS_BANCAIRES(Groupe.TRESORERIE, "Frais bancaires (charge financière saisie)", "6318", "Frais, agios et commissions saisis comme charge financière (dont « Enregistrer dans l'appli » d'un relevé)."),
    ECART_MANQUE(Groupe.TRESORERIE, "Écart de caisse : manque", "658", "Débité par un manque constaté au comptage de caisse."),
    ECART_EXCEDENT(Groupe.TRESORERIE, "Écart de caisse : excédent", "758", "Crédité par un excédent constaté au comptage de caisse."),
    // ---- Financements
    EMPRUNTS(Groupe.FINANCEMENTS, "Emprunts reçus (capital)", "162", "Crédité au déblocage, débité par le capital remboursé."),
    INTERETS_EMPRUNTS(Groupe.FINANCEMENTS, "Intérêts d'emprunts payés", "6712", "Débité par les intérêts payés avec un remboursement."),
    FRAIS_DOSSIER(Groupe.FINANCEMENTS, "Frais de dossier d'emprunt", "631", "Débité par les frais de dossier payés au déblocage."),
    PRETS(Groupe.FINANCEMENTS, "Prêts accordés (capital)", "272", "Débité au versement du prêt, crédité par le capital remboursé."),
    INTERETS_PRETS(Groupe.FINANCEMENTS, "Intérêts de prêts reçus", "7712", "Crédité par les intérêts reçus avec un remboursement."),
    EXPLOITANT(Groupe.FINANCEMENTS, "Apports et retraits en capital de l'exploitant", "104", "Crédité par un apport en capital, débité par un retrait de capital."),
    ASSOCIES_COMPTE_COURANT(Groupe.FINANCEMENTS, "Associés : compte courant", "4621", "Crédité par un apport en compte courant, débité par un retrait."),
    PRODUITS_FINANCIERS(Groupe.FINANCEMENTS, "Produits financiers saisis", "774", "Produits financiers hors intérêts de prêts (revenus de placement, intérêts bancaires)."),
    AUTRES_PRODUITS(Groupe.FINANCEMENTS, "Autres produits", "758", "Produits divers hors activité courante."),
    AUTRES_CHARGES(Groupe.FINANCEMENTS, "Autres charges", "658", "Charges diverses hors activité courante."),
    // ---- Reports
    REPORT_A_NOUVEAU(Groupe.DIVERS, "Contrepartie des soldes de départ et ajustements", "12", "Soldes de départ (trésorerie, clients, fournisseurs, stock) et ajustements de solde de départ."),
    ATTENTE(Groupe.DIVERS, "Compte d'attente", "471", "Mouvement de trésorerie d'une nature inconnue de la correspondance : à examiner."),
    // ---- Dépenses : un compte de charge par catégorie, reconnue par les mots-clés du motif
    DEPENSE_EAU(Groupe.DEPENSES, "Eau", "6051", "Fournitures non stockables : eau.", "eau,sde,water"),
    DEPENSE_ELECTRICITE(Groupe.DEPENSES, "Électricité", "6052", "Fournitures non stockables : électricité.", "électricité,electricite,courant,edg,carburant groupe"),
    DEPENSE_FOURNITURES(Groupe.DEPENSES, "Fournitures de bureau", "6055", "Fournitures de bureau non stockables.", "fourniture,bureau,papeterie,papier,stylo"),
    DEPENSE_TRANSPORT(Groupe.DEPENSES, "Transports", "61", "Transports (taxi, carburant, livraison, déplacements).", "transport,taxi,carburant,essence,gasoil,déplacement,deplacement,livraison,moto"),
    DEPENSE_LOYER(Groupe.DEPENSES, "Loyers et charges locatives", "622", "Locations et charges locatives.", "loyer,location,bail,charges locatives"),
    DEPENSE_ENTRETIEN(Groupe.DEPENSES, "Entretien et réparations", "624", "Entretien, réparations et maintenance.", "entretien,réparation,reparation,maintenance,nettoyage,réparer"),
    DEPENSE_ASSURANCE(Groupe.DEPENSES, "Assurances", "625", "Primes d'assurance.", "assurance"),
    DEPENSE_PUBLICITE(Groupe.DEPENSES, "Publicité", "627", "Publicité et relations publiques.", "publicité,publicite,affiche,annonce,enseigne,flyer"),
    DEPENSE_TELEPHONE(Groupe.DEPENSES, "Téléphone et internet", "628", "Frais de télécommunications.", "téléphone,telephone,internet,communication,forfait,unités,unites"),
    DEPENSE_BANQUE(Groupe.DEPENSES, "Frais bancaires", "6318", "Frais bancaires, agios et commissions.", "frais bancaire,agios,commission,tenue de compte,frais de compte"),
    DEPENSE_IMPOTS(Groupe.DEPENSES, "Impôts et taxes", "64", "Impôts, taxes et droits.", "impôt,impot,taxe,patente,douane,droit de timbre"),
    DEPENSE_PERSONNEL(Groupe.DEPENSES, "Salaires et personnel", "66", "Charges de personnel.", "salaire,paie,prime,personnel,gardien,main d'oeuvre,main-d'œuvre"),
    DEPENSE_AUTRE(Groupe.DEPENSES, "Autre dépense (catégorie inconnue)", "658", "Charges diverses : toute dépense dont le motif ne correspond à aucune catégorie ci-dessus.", "");

    /** Regroupement des lignes dans la page de correspondance. */
    public enum Groupe {
        VENTES("Clients et ventes"), ACHATS("Fournisseurs et achats"), STOCK("Stock"), TRESORERIE("Trésorerie"),
        FINANCEMENTS("Financements, apports et retraits"), DEPENSES("Dépenses par catégorie"), DIVERS("Soldes de départ et divers");
        private final String libelle;
        Groupe(String libelle) { this.libelle = libelle; }
        public String getLibelle() { return libelle; }
    }

    private final Groupe groupe;
    private final String libelle, compteParDefaut, aide, motsClesParDefaut;

    CleCompta(Groupe groupe, String libelle, String compteParDefaut, String aide) { this(groupe, libelle, compteParDefaut, aide, null); }

    CleCompta(Groupe groupe, String libelle, String compteParDefaut, String aide, String motsClesParDefaut) {
        this.groupe = groupe; this.libelle = libelle; this.compteParDefaut = compteParDefaut; this.aide = aide; this.motsClesParDefaut = motsClesParDefaut;
    }

    public Groupe getGroupe() { return groupe; }
    public String getLibelle() { return libelle; }
    public String getCompteParDefaut() { return compteParDefaut; }
    public String getAide() { return aide; }
    /** Mots-clés par défaut, séparés par des virgules ; {@code null} hors catégories de dépense. */
    public String getMotsClesParDefaut() { return motsClesParDefaut; }
    public boolean estDepense() { return groupe == Groupe.DEPENSES; }
    public String getCode() { return name(); }

    public static CleCompta depuisCode(String code) {
        if (code == null) return null;
        for (CleCompta c : values()) if (c.name().equals(code.strip())) return c;
        return null;
    }

    /** Catégories de dépense, dans l'ordre où les mots-clés sont essayés ; la dernière (inconnue) est toujours le repli. */
    public static List<CleCompta> categoriesDepense() {
        return Arrays.stream(values()).filter(CleCompta::estDepense).toList();
    }

    public static List<CleCompta> du(Groupe groupe) {
        return Arrays.stream(values()).filter(c -> c.groupe == groupe).toList();
    }
}
