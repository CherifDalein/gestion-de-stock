package org.example.stock.service;

/** Les tests remplacent ce contrat par une boîte mémoire, sans contacter de messagerie. */
public interface RecuperationMotDePasseMailer {
    void envoyerLien(String email, String lien, long dureeMinutes);
    void notifierChangement(String email);
}
