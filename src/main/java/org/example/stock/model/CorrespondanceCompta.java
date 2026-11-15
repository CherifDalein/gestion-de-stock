package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Ligne de la table de correspondance modifiée par le gérant : à la clé d'une nature d'opération ({@code CleCompta}, ou {@code TRESO_<id>}
 * pour un compte de trésorerie) correspond un compte du plan officiel ou un sous-compte de la boutique. Seules les valeurs qui
 * diffèrent du défaut sont stockées : « Revenir aux valeurs par défaut » supprime les lignes.
 */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "correspondance_compta", uniqueConstraints = @UniqueConstraint(name = "uk_correspondance_compta_cle", columnNames = {"boutique_id", "cle"}))
public class CorrespondanceCompta {
    public static final int CLE_MAX = 40;
    public static final int MOTS_CLES_MAX = 255;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "boutique_id", nullable = false, foreignKey = @ForeignKey(name = "fk_correspondance_compta_boutique"))
    private Boutique boutique;

    @Column(nullable = false, length = CLE_MAX)
    private String cle;

    @Column(name = "compte_numero", nullable = false, length = SousCompteBoutique.NUMERO_MAX)
    private String compteNumero;

    /** Mots-clés du motif (séparés par des virgules) d'une catégorie de dépense ; vide pour les autres lignes ou pour les mots-clés par défaut. */
    @Column(name = "mots_cles", length = MOTS_CLES_MAX)
    private String motsCles;

    public CorrespondanceCompta(Boutique boutique, String cle, String compteNumero, String motsCles) {
        this.boutique = boutique; this.cle = cle; this.compteNumero = compteNumero; this.motsCles = motsCles;
    }

    public void modifier(String compteNumero, String motsCles) { this.compteNumero = compteNumero; this.motsCles = motsCles; }
}
