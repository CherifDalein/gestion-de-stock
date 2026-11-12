package org.example.stock.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Email;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.enums.FormatRecu;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Espace propriétaire des données d'une boutique. */
@Entity
@DynamicUpdate
@Getter
@Setter
public class Boutique {
    /** Les trois comptes de base (principaux) sont provisionnés avec la boutique, jamais créés à la volée pendant un encaissement. */
    @OneToMany(mappedBy = "boutique", cascade = CascadeType.ALL)
    private List<CompteTresorerieBoutique> comptesTresorerie = new ArrayList<>();

    public Boutique() {
        for (TypeCompteTresorerie type : TypeCompteTresorerie.values()) {
            comptesTresorerie.add(new CompteTresorerieBoutique(this, type, type.getNomParDefaut(), true, type.ordinal()));
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false)
    private String nom;

    @Size(max = 500, message = "L'adresse accepte au maximum 500 caractères")
    @Column(length = 500)
    private String adresse;

    @Size(max = 50, message = "Le téléphone accepte au maximum 50 caractères")
    @Column(length = 50)
    private String telephone;

    @Size(max = 50, message = "Le second téléphone accepte au maximum 50 caractères")
    @Column(length = 50)
    private String telephoneSecondaire;

    @Email(message = "Le format de l'email est invalide")
    @Size(max = 254, message = "L'email accepte au maximum 254 caractères")
    @Column(length = 254)
    private String email;

    @Column(nullable = false)
    private LocalDateTime dateCreation;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean actif = true;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean accesOffert;

    /** Après le démarrage, seules les opérations commerciales font varier le stock. */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean initialisationTerminee;

    /** Délais de paiement proposés par défaut, en jours. */
    @Column(nullable = false)
    private int delaiClientsJours = 30;

    @Column(nullable = false)
    private int delaiFournisseursJours = 30;

    /** Prochains numéros de facture ; initialisés au-delà des numéros migrés. */
    @Column(nullable = false)
    private long prochainNumeroVente = 1L;

    @Column(nullable = false)
    private long prochainNumeroAchat = 1L;

    /** Lot 2 : prochains numéros des devis et des retours, par boutique. */
    @Column(nullable = false)
    private long prochainNumeroDevis = 1L;

    @Column(nullable = false)
    private long prochainNumeroRetourVente = 1L;

    @Column(nullable = false)
    private long prochainNumeroRetourAchat = 1L;

    /** Lot 4 : format des reçus imprimés (A4 par défaut, ou ticket de 80 mm), choisi par le gérant pour cette boutique. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16) default 'A4'")
    private FormatRecu formatRecu = FormatRecu.A4;

    /** Lot 4 : propose (ou non) le bouton « Imprimer le reçu » après chaque vente et chaque règlement. */
    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean proposerImpressionRecu = true;

    /** Dates UTC de l'essai public ; null pour les boutiques créées avant son introduction. */
    private LocalDateTime essaiDebut;

    private LocalDateTime essaiFin;

    /** Période UTC activée manuellement ; null pour les accès existants et les seuls essais. */
    private LocalDateTime abonnementDebut;

    private LocalDateTime abonnementFin;

    @PrePersist
    private void daterCreation() {
        if (dateCreation == null) dateCreation = LocalDateTime.now();
    }
}
