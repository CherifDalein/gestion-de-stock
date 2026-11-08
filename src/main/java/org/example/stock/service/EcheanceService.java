package org.example.stock.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Échéances des factures et date « du jour ».
 *
 * <p>Le jour est celui de l'heure du serveur, comme la date des ventes, des achats et de la caisse ;
 * l'horloge {@code recoveryClock} reste injectable pour les tests.</p>
 */
@Service
public class EcheanceService {
    private static final DateTimeFormatter AFFICHAGE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private final Clock clock;

    public EcheanceService(@Qualifier("recoveryClock") Clock clock) {
        this.clock = clock;
    }

    public LocalDate aujourdHui() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.systemDefault());
    }

    /** Date et heure du serveur, avec la même horloge que {@link #aujourdHui()}. */
    public LocalDateTime maintenant() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
    }

    /** Proposition affichée dans un formulaire : date du jour + délai de la boutique. */
    public LocalDate proposer(int delaiJours) {
        return aujourdHui().plusDays(Math.max(0, delaiJours));
    }

    /**
     * Échéance d'une nouvelle facture : vide si tout est payé, sinon la date saisie
     * (au plus tôt le jour de la facture) ou, à défaut, la date de la facture + délai.
     */
    public LocalDate pourCreation(LocalDateTime dateDocument, BigDecimal resteAPayer, LocalDate saisie, int delaiJours) {
        if (resteAPayer == null || resteAPayer.signum() <= 0) return null;
        LocalDate jour = dateDocument == null ? aujourdHui() : dateDocument.toLocalDate();
        if (saisie == null) return jour.plusDays(Math.max(0, delaiJours));
        exigerValide(saisie, dateDocument);
        return saisie;
    }

    /**
     * Échéance après une modification d'achat ou un règlement. Une facture soldée garde son échéance
     * en historique ; sinon la date saisie remplace l'ancienne, ou l'ancienne est conservée, ou une
     * échéance est proposée à partir du jour.
     */
    public LocalDate apresChangement(LocalDateTime dateDocument, BigDecimal resteAPayer, LocalDate saisie,
                                     LocalDate actuelle, int delaiJours) {
        if (resteAPayer == null || resteAPayer.signum() <= 0) return actuelle;
        if (saisie != null) {
            exigerValide(saisie, dateDocument);
            return saisie;
        }
        return actuelle != null ? actuelle : proposer(delaiJours);
    }

    /** Une échéance ne précède jamais le jour de la facture. */
    public static void exigerValide(LocalDate echeance, LocalDateTime dateDocument) {
        if (echeance == null) return;
        if (echeance.getYear() > 9999) throw new IllegalArgumentException("L'échéance saisie est invalide.");
        if (dateDocument != null && echeance.isBefore(dateDocument.toLocalDate())) {
            throw new IllegalArgumentException("L'échéance ne peut pas être antérieure à la date de la facture ("
                    + AFFICHAGE.format(dateDocument.toLocalDate()) + ").");
        }
    }
}
