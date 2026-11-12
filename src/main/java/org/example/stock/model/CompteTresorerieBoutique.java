package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.stock.enums.TypeCompteTresorerie;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Locale;

/**
 * Compte de trésorerie d'une boutique (caisse, Orange Money, banque…). C'est aussi le point de verrouillage stable des
 * écritures : le journal ({@link MouvementCaisse}) reste l'unique source du solde, le solde de départ étant son premier
 * mouvement. Un compte archivé ({@code actif = false}) n'est plus proposé mais garde son historique.
 */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "compte_tresorerie_boutique", uniqueConstraints = {
        @UniqueConstraint(name = "uk_compte_tresorerie_nom", columnNames = {"boutique_id", "nom_cle"}),
        @UniqueConstraint(name = "uk_compte_tresorerie_principal", columnNames = {"boutique_id", "principal_type"})})
public class CompteTresorerieBoutique {
    public static final int TAILLE_NOM = 60;
    public static final int TAILLE_NUMERO = 40;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "boutique_id", nullable = false, foreignKey = @ForeignKey(name = "fk_compte_tresorerie_boutique"))
    private Boutique boutique;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private TypeCompteTresorerie type;

    @Column(nullable = false, length = TAILLE_NOM)
    private String nom;

    /** Nom ramené en minuscules et sans espaces superflus : l'unicité du nom par boutique ne tient pas compte de la casse. */
    @Column(name = "nom_cle", nullable = false, length = TAILLE_NOM)
    private String nomCle;

    /** Numéro de compte bancaire ou téléphone Orange Money ; facultatif. */
    @Column(length = TAILLE_NUMERO)
    private String numero;

    /**
     * Vide, ou égal au type du compte quand c'est le compte principal de ce type. Une seule colonne porte l'information
     * et l'unicité {@code (boutique_id, principal_type)} garantit un seul principal par type et par boutique.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "principal_type", length = 32)
    private TypeCompteTresorerie principalType;

    @Column(nullable = false)
    private boolean actif = true;

    @Column(nullable = false)
    private int ordre;

    public CompteTresorerieBoutique(Boutique boutique, TypeCompteTresorerie type, String nom, boolean principal, int ordre) {
        this.boutique = boutique;
        this.type = type;
        renommer(nom, null);
        this.principalType = principal ? type : null;
        this.ordre = ordre;
    }

    public boolean isPrincipal() { return principalType != null; }

    public void renommer(String nom, String numero) {
        this.nom = normaliserNom(nom);
        this.nomCle = cle(this.nom);
        this.numero = numero == null || numero.isBlank() ? null : numero.strip();
    }

    public void definirPrincipal(boolean principal) { this.principalType = principal ? type : null; }
    public void definirActif(boolean actif) { this.actif = actif; }
    public void definirOrdre(int ordre) { this.ordre = ordre; }

    public static String normaliserNom(String nom) {
        return nom == null ? "" : nom.strip().replaceAll("\\s+", " ");
    }

    public static String cle(String nom) { return normaliserNom(nom).toLowerCase(Locale.ROOT); }
}
