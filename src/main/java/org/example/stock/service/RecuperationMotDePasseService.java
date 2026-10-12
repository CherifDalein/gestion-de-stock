package org.example.stock.service;

import jakarta.annotation.PreDestroy;
import org.example.stock.config.RecoveryProperties;
import org.example.stock.form.ReinitialisationMotDePasseForm;
import org.example.stock.model.Emails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Réponse indépendante du compte : les recherches et les emails sont effectués après la demande HTTP. */
@Service
public class RecuperationMotDePasseService {
    private static final Logger LOG = LoggerFactory.getLogger(RecuperationMotDePasseService.class);
    private final OperationsReinitialisationMotDePasse operations;
    private final RecuperationMotDePasseMailer mailer;
    private final RecoveryProperties properties;
    private final LimiteurRecuperationMotDePasse limiteur;
    private final ThreadPoolExecutor file = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), runnable -> {
                Thread thread = new Thread(runnable, "stock-password-recovery"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public RecuperationMotDePasseService(OperationsReinitialisationMotDePasse operations, RecuperationMotDePasseMailer mailer,
                                       RecoveryProperties properties, LimiteurRecuperationMotDePasse limiteur) {
        this.operations = operations;
        this.mailer = mailer;
        this.properties = properties;
        this.limiteur = limiteur;
    }

    public void demander(String email, String ip) {
        String normalise = Emails.normaliser(email);
        if (!properties.isEnabled() || normalise == null || normalise.isBlank() || normalise.length() > 254
                || !limiteur.autoriserDemande(normalise, ip)) return;
        soumettre(() -> operations.emettre(normalise).ifPresent(lien -> mailer.envoyerLien(lien.email(),
                properties.getBaseUrl() + "/reinitialiser-mot-de-passe?jeton=" + lien.jeton(), properties.getTtl().toMinutes())));
    }

    public boolean lienValide(String jeton) { return operations.lienValide(jeton); }

    public void reinitialiser(ReinitialisationMotDePasseForm formulaire, String ip) {
        // Vérifier la taille avant de calculer une empreinte ou d'accéder à SQL/BCrypt.
        String jeton = formulaire == null ? null : formulaire.getJeton();
        if (!OperationsReinitialisationMotDePasse.formatJetonValide(jeton)
                || !limiteur.autoriserReinitialisation(jeton, ip)) {
            throw new RecuperationMotDePasseException(null, "Le lien est invalide ou expiré. Demandez un nouveau lien.");
        }
        String email = operations.reinitialiser(formulaire);
        // Le changement et la consommation ont été validés en base avant la notification.
        soumettre(() -> mailer.notifierChangement(email));
    }

    private void soumettre(Runnable operation) {
        try {
            file.execute(() -> {
                try { operation.run(); }
                catch (RuntimeException exception) {
                    // Une exception SMTP/JDBC peut contenir le destinataire, un jeton ou des identifiants : ne pas la publier.
                    LOG.warn("Une opération de récupération de mot de passe n'a pas pu être terminée.");
                }
            });
        } catch (RejectedExecutionException exception) {
            LOG.warn("La file de récupération de mot de passe est temporairement indisponible.");
        }
    }

    @PreDestroy
    public void fermer() {
        file.shutdown();
        try { if (!file.awaitTermination(2, TimeUnit.SECONDS)) file.shutdownNow(); }
        catch (InterruptedException exception) { file.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
