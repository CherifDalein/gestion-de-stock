package org.example.stock.form;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import org.example.stock.model.Emails;
import org.example.stock.validation.MotDePasseValide;

@Getter
public class InscriptionForm {
    @NotBlank(message = "Le nom est obligatoire")
    @Size(max = 100, message = "Le nom accepte au maximum 100 caractères")
    private String nom;

    @NotBlank(message = "L'email est obligatoire")
    @Email(message = "Le format de l'email est invalide")
    @Size(max = 254, message = "L'email accepte au maximum 254 caractères")
    private String email;

    @MotDePasseValide
    private String password;

    public void setNom(String nom) { this.nom = nom == null ? null : nom.trim(); }
    public void setEmail(String email) { this.email = Emails.normaliser(email); }
    // Ne jamais modifier le mot de passe saisi, y compris ses espaces.
    public void setPassword(String password) { this.password = password; }
}
