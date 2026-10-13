package org.example.stock.service;

public interface InscriptionBoutiqueMailer {
    void envoyerLien(String email, String lien, long dureeHeures);
}
