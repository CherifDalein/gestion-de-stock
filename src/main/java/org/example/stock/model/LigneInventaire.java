package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.TypeLigneInventaire;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

/**
 * Ligne d'un inventaire : un partenaire, son solde dans l'application et son solde confirmé. Les soldes sont signés du point de vue de la
 * boutique : positif, le partenaire doit de l'argent à la boutique ; négatif, la boutique lui en doit (avance comprise). Le nom est copié :
 * la ligne reste lisible même si la fiche est renommée.
 */
@Entity
@Getter @Setter
@Table(name = "ligne_inventaire_creances")
public class LigneInventaire {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inventaire_id", nullable = false, foreignKey = @ForeignKey(name = "fk_ligne_inventaire_inventaire"))
    private InventaireCreances inventaire;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 16)
    private TypeLigneInventaire type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", foreignKey = @ForeignKey(name = "fk_ligne_inventaire_client"))
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fournisseur_id", foreignKey = @ForeignKey(name = "fk_ligne_inventaire_fournisseur"))
    private Fournisseur fournisseur;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partenaire_financier_id", foreignKey = @ForeignKey(name = "fk_ligne_inventaire_partenaire"))
    private PartenaireFinancier partenaireFinancier;

    @Column(nullable = false, length = 255)
    private String nom;

    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal soldeAppli;

    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal soldeConfirme;

    /** Solde confirmé moins solde de l'application. */
    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal ecart;

    @Column(length = 255)
    private String raison;

    public boolean aEcart() { return ecart != null && ecart.signum() != 0; }

    /** Identifiant du partenaire dans sa propre table. */
    @Transient
    public Long getPartenaireId() {
        return switch (type) { case CLIENT -> client == null ? null : client.getId(); case FOURNISSEUR -> fournisseur == null ? null : fournisseur.getId();
            case FINANCIER -> partenaireFinancier == null ? null : partenaireFinancier.getId(); };
    }
}
