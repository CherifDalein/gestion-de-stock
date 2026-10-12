package org.example.stock.service;

import org.example.stock.config.RecoveryProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

public final class SmtpRecuperationMotDePasseMailer implements RecuperationMotDePasseMailer {
    private final JavaMailSender sender;
    private final RecoveryProperties properties;

    public SmtpRecuperationMotDePasseMailer(JavaMailSender sender, RecoveryProperties properties) {
        this.sender = sender;
        this.properties = properties;
    }

    @Override
    public void envoyerLien(String email, String lien, long dureeMinutes) {
        envoyer(email, "Stock Pro — Réinitialisation de votre mot de passe",
                "Une réinitialisation de votre mot de passe Stock Pro a été demandée.\n\n"
                        + "Choisissez votre nouveau mot de passe avec ce lien :\n" + lien
                        + "\n\nCe lien expire dans " + dureeMinutes + " minutes et ne peut être utilisé qu'une fois."
                        + "\nSi vous n'avez pas fait cette demande, vous pouvez ignorer ce message."
                        + " Votre mot de passe actuel reste inchangé.\n");
    }

    @Override
    public void notifierChangement(String email) {
        envoyer(email, "Stock Pro — Votre mot de passe a été modifié",
                "Le mot de passe de votre compte Stock Pro vient d'être réinitialisé.\n"
                        + "Vos anciennes sessions sont fermées.\n"
                        + "Si vous n'avez pas effectué cette modification, contactez votre administrateur.\n");
    }

    private void envoyer(String email, String sujet, String texte) {
        if (sender == null) throw new IllegalStateException("La messagerie de récupération est indisponible");
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getFrom());
        message.setTo(email);
        message.setSubject(sujet);
        message.setText(texte);
        sender.send(message);
    }
}
