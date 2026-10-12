package org.example.stock.form;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.validation.MotDePasseValide;

/** Les valeurs restent exactes : les espaces peuvent faire partie d'un mot de passe. */
@Getter
@Setter
public class ChangementMotDePasseForm {
    @NotBlank(message = "Votre mot de passe actuel est obligatoire")
    private String ancienMotDePasse;

    @MotDePasseValide
    private String nouveauMotDePasse;

    @NotBlank(message = "Confirmez votre nouveau mot de passe")
    private String confirmationMotDePasse;

    public void effacer() {
        ancienMotDePasse = null;
        nouveauMotDePasse = null;
        confirmationMotDePasse = null;
    }
}
