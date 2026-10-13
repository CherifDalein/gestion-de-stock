package org.example.stock.service;

import jakarta.annotation.PreDestroy;
import org.example.stock.config.RecoveryProperties;
import org.example.stock.config.SignupProperties;
import org.example.stock.form.ConfirmationInscriptionForm;
import org.example.stock.model.Emails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Recherche SQL et envoi différés : la réponse publique ne révèle pas l'existence d'un compte. */
@Service
public class InscriptionBoutiqueService {
    private static final Logger LOG = LoggerFactory.getLogger(InscriptionBoutiqueService.class);
    private final OperationsInscriptionBoutique operations;
    private final InscriptionBoutiqueMailer mailer;
    private final RecoveryProperties messagerie;
    private final SignupProperties properties;
    private final LimiteurInscriptionBoutique limiteur;
    private final ThreadPoolExecutor file = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), runnable -> {
                Thread thread = new Thread(runnable, "stock-boutique-signup"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public InscriptionBoutiqueService(OperationsInscriptionBoutique operations, InscriptionBoutiqueMailer mailer,
                                     RecoveryProperties messagerie, SignupProperties properties, LimiteurInscriptionBoutique limiteur) {
        this.operations = operations;
        this.mailer = mailer;
        this.messagerie = messagerie;
        this.properties = properties;
        this.limiteur = limiteur;
    }

    public void demander(String email, String ip) {
        String normalise = Emails.normaliser(email);
        if (!messagerie.isEnabled() || normalise == null || normalise.isBlank() || normalise.length() > 254
                || !limiteur.autoriserDemande(normalise, ip)) return;
        try {
            file.execute(() -> {
                try {
                    operations.emettre(normalise).ifPresent(lien -> mailer.envoyerLien(lien.email(),
                            messagerie.getBaseUrl() + "/inscription/confirmer?jeton=" + lien.jeton(), properties.getTtl().toHours()));
                } catch (RuntimeException exception) {
                    // Les exceptions SMTP/JDBC peuvent contenir l'email ou le jeton : aucun détail dans les journaux.
                    LOG.warn("Une demande d'inscription n'a pas pu être terminée.");
                }
            });
        } catch (RejectedExecutionException exception) {
            LOG.warn("La file d'inscription est temporairement indisponible.");
        }
    }

    public Optional<String> emailPourLien(String jeton) { return operations.emailPourLien(jeton); }

    public void confirmer(ConfirmationInscriptionForm formulaire, String ip) {
        String jeton = formulaire == null ? null : formulaire.getJeton();
        if (!OperationsInscriptionBoutique.formatJetonValide(jeton) || !limiteur.autoriserConfirmation(jeton, ip)) {
            throw OperationsInscriptionBoutique.lienInvalide();
        }
        try { operations.confirmer(formulaire); }
        catch (RuntimeException exception) {
            // La transaction a déjà été annulée : aucune boutique orpheline si l'email vient d'être utilisé ailleurs.
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                        && violation.getConstraintName() != null
                        && violation.getConstraintName().toLowerCase(Locale.ROOT).contains("uk_utilisateur_email")) {
                    throw OperationsInscriptionBoutique.lienInvalide();
                }
            }
            throw exception;
        }
    }

    @PreDestroy
    public void fermer() {
        file.shutdown();
        try { if (!file.awaitTermination(2, TimeUnit.SECONDS)) file.shutdownNow(); }
        catch (InterruptedException exception) { file.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
