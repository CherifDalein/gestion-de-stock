package org.example.stock.service;

import org.example.stock.config.RecoveryProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

public final class SmtpInscriptionBoutiqueMailer implements InscriptionBoutiqueMailer {
    private final JavaMailSender sender;
    private final RecoveryProperties properties;

    public SmtpInscriptionBoutiqueMailer(JavaMailSender sender, RecoveryProperties properties) {
        this.sender = sender;
        this.properties = properties;
    }

    @Override
    public void envoyerLien(String email, String lien, long dureeHeures) {
        if (sender == null) throw new IllegalStateException("La messagerie d'inscription est indisponible");
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getFrom());
        message.setTo(email);
        message.setSubject("Stock Pro — Créez votre boutique");
        message.setText("Vous avez demandé à créer une boutique Stock Pro.\n\n"
                + "Confirmez votre email puis renseignez votre boutique avec ce lien :\n" + lien
                + "\n\nCe lien expire dans " + dureeHeures + " heures et ne peut être utilisé qu'une fois."
                + "\nVotre essai gratuit commencera lorsque vous aurez terminé la création de votre boutique."
                + "\nSi vous n'avez pas fait cette demande, vous pouvez ignorer ce message.\n");
        sender.send(message);
    }
}
