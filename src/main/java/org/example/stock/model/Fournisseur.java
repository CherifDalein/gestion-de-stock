package org.example.stock.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import org.example.stock.enums.SensSolde;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Entity
@Getter
@Setter
public class Fournisseur {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "boutique_id", nullable = false)
    private Boutique boutique;

    @NotBlank(message = "Le nom du fournisseur est obligatoire")
    private String nom;

    private String telephone;

    @Email(message = "Format d'email invalide")
    private String email;

    private String adresse;

    /** Solde d'ouverture signé : positif si le partenaire me doit, négatif si je lui dois ou s'il a versé une avance. */
    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal soldeInitial = Montants.ZERO;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate echeanceSoldeInitial;

    /**
     * Saisie du solde de départ (jamais persistée telle quelle) : un montant positif ou nul et un sens, que le service
     * convertit en {@code soldeInitial} signé. Le champ signé brut n'est jamais lié depuis un formulaire.
     */
    @Transient
    private BigDecimal montantSoldeDepart;

    @Transient
    private SensSolde sensSoldeDepart;

    /** Vrai quand le formulaire contenait la section « solde de départ » ; renseigné par le contrôleur, jamais lié. */
    @Transient
    private boolean soldeDepartSoumis;

    /** Case « Je confirme le nouveau solde de départ » de la modification d'une fiche ; lie uniquement le champ du formulaire. */
    @Transient
    private boolean confirmationSoldeDepart;

    /** Préremplit la saisie avec le solde enregistré : valeur absolue et sens. */
    public void preparerSaisieSoldeDepart() {
        montantSoldeDepart = Montants.ouZero(soldeInitial).abs();
        sensSoldeDepart = Montants.ouZero(soldeInitial).signum() < 0 ? SensSolde.JE_LUI_DOIS : SensSolde.IL_ME_DOIT;
    }

    @OneToMany(mappedBy = "fournisseur")
    private List<Produit> produits;
}