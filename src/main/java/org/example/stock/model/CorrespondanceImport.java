package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.FormatDateReleve;
import org.example.stock.enums.SeparateurDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Correspondance des colonnes d'un fichier de relevé, mémorisée par compte pour le prochain import. Les colonnes sont numérotées à partir
 * de 1 ; soit {@code colonneMontant}, soit {@code colonneDebit} et {@code colonneCredit} sont renseignées.
 */
@Entity
@Getter @Setter
@Table(name = "correspondance_import", uniqueConstraints = @UniqueConstraint(name = "uk_correspondance_import_compte", columnNames = "compte_id"))
public class CorrespondanceImport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compte_id", nullable = false, foreignKey = @ForeignKey(name = "fk_correspondance_import_compte"))
    private CompteTresorerieBoutique compte;

    @Column(nullable = false) private int ligneEntete;
    @Column(nullable = false) private int colonneDate;
    @Column(nullable = false) private int colonneLibelle;
    private Integer colonneReference;
    private Integer colonneMontant;
    private Integer colonneDebit;
    private Integer colonneCredit;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 20)
    private FormatDateReleve formatDate;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 10)
    private SeparateurDecimal separateurDecimal;

    @Column(nullable = false)
    private LocalDateTime dateModification;
}
