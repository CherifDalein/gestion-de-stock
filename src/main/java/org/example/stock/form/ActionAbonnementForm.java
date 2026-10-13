package org.example.stock.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;

import java.util.Locale;

@Getter
public class ActionAbonnementForm {
    @NotBlank(message = "Rechargez la page avant de confirmer cette action")
    @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
            message = "Rechargez la page avant de confirmer cette action")
    private String jetonAction;

    @Size(max = 500, message = "Le motif accepte au maximum 500 caractères")
    private String motif;

    public void setJetonAction(String jetonAction) {
        this.jetonAction = jetonAction == null ? null : jetonAction.trim().toLowerCase(Locale.ROOT);
    }

    public void setMotif(String motif) {
        this.motif = motif == null || motif.isBlank() ? null : motif.strip();
    }
}
