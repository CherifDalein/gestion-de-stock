package org.example.stock.form;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.TypeCompteTresorerie;

import java.math.BigDecimal;

/** Création ou modification d'un compte de trésorerie ; seuls ces champs viennent du navigateur. */
@Getter @Setter
public class CompteTresorerieForm {
    @NotNull(message = "Choisissez le type du compte")
    private TypeCompteTresorerie type = TypeCompteTresorerie.ESPECES;

    @NotBlank(message = "Le nom du compte est obligatoire")
    @Size(max = 60, message = "Le nom accepte au maximum 60 caractères")
    private String nom;

    @Size(max = 40, message = "Le numéro accepte au maximum 40 caractères")
    private String numero;

    /** Argent déjà présent sur le compte à la date du jour ; sans objet en modification. */
    @PositiveOrZero(message = "Le solde de départ ne peut pas être négatif")
    @Digits(integer = 15, fraction = 2, message = "Le solde de départ accepte au maximum 15 chiffres et 2 décimales")
    private BigDecimal soldeDepart;

    public void setNom(String nom) { this.nom = nom == null ? null : nom.strip(); }
    public void setNumero(String numero) { this.numero = numero == null ? null : numero.strip(); }
}
