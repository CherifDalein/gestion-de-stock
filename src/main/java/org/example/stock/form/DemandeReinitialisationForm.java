package org.example.stock.form;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import org.example.stock.model.Emails;

@Getter
public class DemandeReinitialisationForm {
    @NotBlank(message = "Votre email est obligatoire")
    @Email(message = "Le format de l'email est invalide")
    @Size(max = 254, message = "L'email accepte au maximum 254 caractères")
    private String email;

    public void setEmail(String email) { this.email = Emails.normaliser(email); }
}
