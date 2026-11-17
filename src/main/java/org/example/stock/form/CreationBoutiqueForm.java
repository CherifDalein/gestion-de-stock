package org.example.stock.form;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.pays.Pays;

@Getter
public class CreationBoutiqueForm extends InscriptionForm {
    @NotBlank(message = "Le nom de la boutique est obligatoire")
    @Size(max = 255, message = "Le nom de la boutique accepte au maximum 255 caractères")
    private String nomBoutique;

    @Setter
    private boolean accesOffert;

    /**
     * Pays de la boutique (code ISO, Guinée par défaut) : il fixe sa monnaie, définitivement. Le formulaire envoie le code ; le serveur
     * refuse un pays inconnu.
     */
    private String pays = Pays.PAR_DEFAUT.getCode();

    public void setPays(String pays) { this.pays = pays == null ? null : pays.strip(); }

    /** Le pays choisi, ou {@code null} s'il n'est pas dans la liste. */
    public Pays lirePays() { return Pays.depuisCode(pays); }

    @AssertTrue(message = "Choisissez le pays de la boutique dans la liste")
    public boolean isPaysValide() { return lirePays() != null; }

    public void setNomBoutique(String nomBoutique) {
        this.nomBoutique = nomBoutique == null ? null : nomBoutique.trim();
    }
}
