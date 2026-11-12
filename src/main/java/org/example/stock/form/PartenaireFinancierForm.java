package org.example.stock.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.TypePartenaireFinancier;

@Getter @Setter
public class PartenaireFinancierForm {
    @NotBlank(message = "Le nom est obligatoire")
    @Size(max = 80, message = "Le nom accepte au maximum 80 caractères")
    private String nom;

    @NotNull(message = "Choisissez le type du partenaire")
    private TypePartenaireFinancier type = TypePartenaireFinancier.BANQUE;

    @Size(max = 30, message = "Le téléphone accepte au maximum 30 caractères")
    private String telephone;

    @Size(max = 255, message = "La note accepte au maximum 255 caractères")
    private String note;

    public void setNom(String nom) { this.nom = nom == null ? null : nom.strip(); }
    public void setTelephone(String telephone) { this.telephone = telephone == null || telephone.isBlank() ? null : telephone.strip(); }
    public void setNote(String note) { this.note = note == null || note.isBlank() ? null : note.strip(); }
}
