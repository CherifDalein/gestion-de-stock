package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Un document figé d'un instantané (balance, bilan, compte de résultat, tableau des flux ou SMT) : son PDF et son Excel, tels qu'ils ont été édités à la clôture. */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "instantane_document", uniqueConstraints = @UniqueConstraint(name = "uk_instantane_document_code", columnNames = {"instantane_id", "code"}))
public class InstantaneDocument {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instantane_id", nullable = false, foreignKey = @ForeignKey(name = "fk_instantane_document"))
    private InstantaneExercice instantane;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 120)
    private String titre;

    @Column(name = "nom_fichier", nullable = false, length = 160)
    private String nomFichier;

    @Lob
    @Column(nullable = false, length = 33554432, columnDefinition = "LONGBLOB")
    private byte[] pdf;

    @Lob
    @Column(nullable = false, length = 33554432, columnDefinition = "LONGBLOB")
    private byte[] excel;

    public InstantaneDocument(InstantaneExercice instantane, String code, String titre, String nomFichier, byte[] pdf, byte[] excel) {
        this.instantane = instantane; this.code = code; this.titre = titre; this.nomFichier = nomFichier; this.pdf = pdf; this.excel = excel;
    }
}
