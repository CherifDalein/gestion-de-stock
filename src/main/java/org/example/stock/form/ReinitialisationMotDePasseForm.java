package org.example.stock.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.validation.MotDePasseValide;

/** Aucun toString : les secrets ne doivent pas apparaître dans un diagnostic. */
@Getter
@Setter
public class ReinitialisationMotDePasseForm {
    @NotBlank(message = "Le lien est invalide ou expiré")
    @Pattern(regexp = "[A-Za-z0-9_-]{43}", message = "Le lien est invalide ou expiré")
    private String jeton;

    @MotDePasseValide
    private String nouveauMotDePasse;

    @NotBlank(message = "Confirmez votre nouveau mot de passe")
    private String confirmationMotDePasse;

    public void effacerMotsDePasse() {
        nouveauMotDePasse = null;
        confirmationMotDePasse = null;
    }
}
