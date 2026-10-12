package org.example.stock.form;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

/** Coordonnées publiques de la boutique, sans identifiant ni propriétés d'accès. */
@Getter
public class ParametresBoutiqueForm {
    @NotBlank(message = "Le nom de la boutique est obligatoire")
    @Size(max = 255, message = "Le nom de la boutique accepte au maximum 255 caractères")
    private String nom;

    @Size(max = 500, message = "L'adresse accepte au maximum 500 caractères")
    private String adresse;

    @Size(max = 50, message = "Le téléphone accepte au maximum 50 caractères")
    private String telephone;

    @Email(message = "Le format de l'email est invalide")
    @Size(max = 254, message = "L'email accepte au maximum 254 caractères")
    private String email;

    public void setNom(String nom) { this.nom = nom == null ? null : nom.strip(); }
    public void setAdresse(String adresse) { this.adresse = facultatif(adresse); }
    public void setTelephone(String telephone) { this.telephone = facultatif(telephone); }
    public void setEmail(String email) { this.email = facultatif(email); }

    private static String facultatif(String valeur) {
        if (valeur == null || valeur.isBlank()) return null;
        return valeur.strip();
    }
}
