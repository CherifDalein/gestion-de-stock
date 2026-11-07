package org.example.stock.form;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import org.springframework.web.multipart.MultipartFile;

/** Coordonnées publiques et délais de paiement de la boutique, sans identifiant ni propriétés d'accès. */
@Getter
public class ParametresBoutiqueForm {
    @NotBlank(message = "Le nom de la boutique est obligatoire")
    @Size(max = 255, message = "Le nom de la boutique accepte au maximum 255 caractères")
    private String nom;

    @Size(max = 500, message = "L'adresse accepte au maximum 500 caractères")
    private String adresse;

    @Size(max = 50, message = "Le téléphone accepte au maximum 50 caractères")
    private String telephone;

    @Size(max = 50, message = "Le second téléphone accepte au maximum 50 caractères")
    private String telephoneSecondaire;

    private MultipartFile logo;

    private boolean supprimerLogo;

    @Email(message = "Le format de l'email est invalide")
    @Size(max = 254, message = "L'email accepte au maximum 254 caractères")
    private String email;

    /** Délais de paiement proposés, en jours ; absents d'un envoi, les délais enregistrés sont conservés. */
    @Min(value = 0, message = "Le délai accordé aux clients doit être compris entre 0 et 365 jours")
    @Max(value = 365, message = "Le délai accordé aux clients doit être compris entre 0 et 365 jours")
    private Integer delaiClientsJours;

    @Min(value = 0, message = "Le délai accordé par les fournisseurs doit être compris entre 0 et 365 jours")
    @Max(value = 365, message = "Le délai accordé par les fournisseurs doit être compris entre 0 et 365 jours")
    private Integer delaiFournisseursJours;

    public void setDelaiClientsJours(Integer delaiClientsJours) { this.delaiClientsJours = delaiClientsJours; }
    public void setDelaiFournisseursJours(Integer delaiFournisseursJours) { this.delaiFournisseursJours = delaiFournisseursJours; }

    public void setNom(String nom) { this.nom = nom == null ? null : nom.strip(); }
    public void setAdresse(String adresse) { this.adresse = facultatif(adresse); }
    public void setTelephone(String telephone) { this.telephone = facultatif(telephone); }
    public void setTelephoneSecondaire(String telephoneSecondaire) { this.telephoneSecondaire = facultatif(telephoneSecondaire); }
    public void setLogo(MultipartFile logo) { this.logo = logo; }
    public void setSupprimerLogo(boolean supprimerLogo) { this.supprimerLogo = supprimerLogo; }
    public void setEmail(String email) { this.email = facultatif(email); }

    private static String facultatif(String valeur) {
        if (valeur == null || valeur.isBlank()) return null;
        return valeur.strip();
    }
}
