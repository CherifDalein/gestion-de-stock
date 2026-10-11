package org.example.stock.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
public class CreationBoutiqueForm extends InscriptionForm {
    @NotBlank(message = "Le nom de la boutique est obligatoire")
    @Size(max = 255, message = "Le nom de la boutique accepte au maximum 255 caractères")
    private String nomBoutique;

    @Setter
    private boolean accesOffert;

    public void setNomBoutique(String nomBoutique) {
        this.nomBoutique = nomBoutique == null ? null : nomBoutique.trim();
    }
}
