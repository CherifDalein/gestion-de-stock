package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Sous-compte ouvert par une boutique à partir d'un compte officiel du SYSCOHADA (par exemple 5521 « Orange Money Labé » sous 552).
 * Son numéro commence par celui du compte parent et n'est jamais un numéro officiel. Les sous-comptes de trésorerie sont créés avec
 * le compte de trésorerie qu'ils représentent ({@code compteTresorerie}) ; les autres sont ouverts à la main par le gérant. Rien n'est
 * stocké des écritures : un sous-compte ne fait que nommer un compte du plan de la boutique.
 */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "sous_compte_boutique", uniqueConstraints = {
        @UniqueConstraint(name = "uk_sous_compte_boutique_numero", columnNames = {"boutique_id", "numero"}),
        @UniqueConstraint(name = "uk_sous_compte_compte_tresorerie", columnNames = "compte_tresorerie_id")})
public class SousCompteBoutique {
    public static final int NUMERO_MAX = 8;
    public static final int INTITULE_MAX = 120;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "boutique_id", nullable = false, foreignKey = @ForeignKey(name = "fk_sous_compte_boutique"))
    private Boutique boutique;

    @Column(nullable = false, length = NUMERO_MAX)
    private String numero;

    /** Numéro du compte officiel dont ce sous-compte est issu : 2 à 4 chiffres. */
    @Column(name = "parent_numero", nullable = false, length = 4)
    private String parentNumero;

    @Column(nullable = false, length = INTITULE_MAX)
    private String intitule;

    /** Compte de trésorerie que ce sous-compte représente ; vide pour un sous-compte ouvert à la main. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "compte_tresorerie_id", foreignKey = @ForeignKey(name = "fk_sous_compte_compte_tresorerie"))
    private CompteTresorerieBoutique compteTresorerie;

    @Column(nullable = false)
    private LocalDateTime dateCreation;

    public SousCompteBoutique(Boutique boutique, String parentNumero, String numero, String intitule, CompteTresorerieBoutique compteTresorerie,
                              LocalDateTime dateCreation) {
        this.boutique = boutique; this.parentNumero = parentNumero; this.numero = numero; this.intitule = intitule;
        this.compteTresorerie = compteTresorerie; this.dateCreation = dateCreation;
    }

    public void modifier(String numero, String intitule) { this.numero = numero; this.intitule = intitule; }
}
