package org.example.stock.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Numéros de facture uniques par boutique : {@code FV-000123} pour les ventes, {@code FA-000123} pour les achats.
 *
 * <p>Le compteur est lu et avancé sous le verrou de la ligne boutique, dans la transaction de la création :
 * deux créations simultanées d'une même boutique sont numérotées l'une après l'autre, et une création annulée
 * rend son numéro. Appeler après {@code InitialisationBoutiqueService.verrouillerEtClorePourActivite()} et avant
 * tout verrou de produit ou toute insertion liée à la boutique, pour garder le même ordre de verrous partout.</p>
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class NumerotationFactureService {
    public static final int LONGUEUR_MAXIMALE = 30;
    private static final int CHIFFRES_MINIMUM = 6;

    private final EntityManager entityManager;
    private final BoutiqueService boutiques;

    public NumerotationFactureService(EntityManager entityManager, BoutiqueService boutiques) {
        this.entityManager = entityManager;
        this.boutiques = boutiques;
    }

    private enum Type {
        VENTE("FV-", "Vente", "prochain_numero_vente"),
        ACHAT("FA-", "Achat", "prochain_numero_achat");

        final String prefixe, entite, colonne;
        Type(String prefixe, String entite, String colonne) {
            this.prefixe = prefixe; this.entite = entite; this.colonne = colonne;
        }
    }

    /** Numéro saisi s'il est libre, sinon numéro automatique tiré du compteur des ventes. */
    public String attribuerNumeroVente(String numeroSaisi) {
        return attribuer(Type.VENTE, numeroSaisi);
    }

    /** Numéro saisi s'il est libre, sinon numéro automatique tiré du compteur des achats. */
    public String attribuerNumeroAchat(String numeroSaisi) {
        return attribuer(Type.ACHAT, numeroSaisi);
    }

    /** Retire les espaces aux bords ; un numéro vide est absent. Ne vérifie pas la longueur. */
    public static String normaliser(String numero) {
        if (numero == null || numero.isBlank()) return null;
        return numero.strip();
    }

    /** Numéro manuel normalisé et contrôlé, ou null pour demander un numéro automatique. */
    public static String validerSaisie(String numero) {
        String normalise = normaliser(numero);
        if (normalise == null) return null;
        if (normalise.length() > LONGUEUR_MAXIMALE) {
            throw new IllegalArgumentException("Le numéro de facture accepte au maximum " + LONGUEUR_MAXIMALE + " caractères.");
        }
        if (normalise.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Le numéro de facture contient un caractère non autorisé.");
        }
        return normalise;
    }

    /** Format automatique, au moins six chiffres, jamais tronqué : {@code FV-000042}, {@code FV-1234567}. */
    static String formater(String prefixe, long valeur) {
        return prefixe + String.format("%0" + CHIFFRES_MINIMUM + "d", valeur);
    }

    private String attribuer(Type type, String numeroSaisi) {
        String manuel = validerSaisie(numeroSaisi);
        Long boutiqueId = boutiques.idBoutiqueCourante();
        // Lecture verrouillée : elle voit le dernier compteur validé, même après un instantané REPEATABLE READ,
        // et sérialise les créations de la boutique jusqu'au commit.
        Object valeur = entityManager.createNativeQuery("select " + type.colonne + " from boutique where id = :id for update")
                .setParameter("id", boutiqueId).getSingleResult();
        long compteur = ((Number) valeur).longValue();
        if (manuel != null) {
            if (dejaPris(type, boutiqueId, manuel)) {
                throw new IllegalArgumentException("Le numéro « " + manuel + " » est déjà utilisé par une autre facture de la boutique. "
                        + "Saisissez un autre numéro ou laissez le champ vide pour un numéro automatique.");
            }
            // Un numéro manuel ne consomme pas le compteur.
            return manuel;
        }
        if (compteur < 1) compteur = 1;
        String numero = formater(type.prefixe, compteur);
        // Un numéro déjà saisi à la main est sauté : on avance jusqu'au premier libre.
        while (dejaPris(type, boutiqueId, numero)) {
            compteur = Math.addExact(compteur, 1);
            numero = formater(type.prefixe, compteur);
        }
        entityManager.createNativeQuery("update boutique set " + type.colonne + " = :suivant where id = :id")
                .setParameter("suivant", Math.addExact(compteur, 1)).setParameter("id", boutiqueId).executeUpdate();
        return numero;
    }

    private boolean dejaPris(Type type, Long boutiqueId, String numero) {
        // Lecture courante sous verrou partagé : un document validé après l'instantané de la transaction est vu.
        return !entityManager.createQuery("select d.id from " + type.entite
                        + " d where d.boutique.id = :boutique and d.numero = :numero", Long.class)
                .setParameter("boutique", boutiqueId).setParameter("numero", numero)
                .setLockMode(LockModeType.PESSIMISTIC_READ).setMaxResults(1).getResultList().isEmpty();
    }
}
