package org.example.stock.compta;

/**
 * Compte du plan officiel SYSCOHADA révisé. {@code niveau} est celui du texte de l'Acte uniforme : « Compte principal » (2 chiffres),
 * « Compte » (3 chiffres) ou « Compte divisionnaire » (4 chiffres).
 */
public record CompteOfficiel(String numero, String intitule, int classe, String libelleClasse, String niveau) {
    public int longueur() { return numero.length(); }
}
