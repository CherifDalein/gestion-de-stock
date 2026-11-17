package org.example.stock.form;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.pays.Pays;
import org.example.stock.validation.MotDePasseValide;

/** Les permissions et les dates d'essai sont exclusivement décidées côté serveur. */
@Getter
public class ConfirmationInscriptionForm {
    @Setter
    @NotBlank(message = "Le lien est invalide ou expiré")
    @Pattern(regexp = "[A-Za-z0-9_-]{43}", message = "Le lien est invalide ou expiré")
    private String jeton;

    @NotBlank(message = "Votre nom est obligatoire")
    @Size(max = 100, message = "Votre nom accepte au maximum 100 caractères")
    private String nom;

    @NotBlank(message = "Le nom de la boutique est obligatoire")
    @Size(max = 255, message = "Le nom de la boutique accepte au maximum 255 caractères")
    private String nomBoutique;

    /** Pays de la boutique (code ISO, Guinée par défaut) : il fixe sa monnaie, définitivement. */
    private String pays = Pays.PAR_DEFAUT.getCode();

    @Setter
    @MotDePasseValide
    private String nouveauMotDePasse;

    @Setter
    @NotBlank(message = "Confirmez votre mot de passe")
    private String confirmationMotDePasse;

    public void setPays(String pays) { this.pays = pays == null ? null : pays.strip(); }

    /** Le pays choisi, ou {@code null} s'il n'est pas dans la liste. */
    public Pays lirePays() { return Pays.depuisCode(pays); }

    @AssertTrue(message = "Choisissez le pays de la boutique dans la liste")
    public boolean isPaysValide() { return lirePays() != null; }

    public void setNom(String nom) { this.nom = nom == null ? null : nom.trim(); }
    public void setNomBoutique(String nomBoutique) { this.nomBoutique = nomBoutique == null ? null : nomBoutique.trim(); }

    public void effacerMotsDePasse() {
        nouveauMotDePasse = null;
        confirmationMotDePasse = null;
    }
}
