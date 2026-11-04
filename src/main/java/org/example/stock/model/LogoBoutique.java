package org.example.stock.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** PNG normalisé, conservé avec la boutique et inclus dans les sauvegardes SQL. */
@Entity
@Table(name = "logo_boutique")
@Getter
@Setter
public class LogoBoutique {
    @Id
    @Column(name = "boutique_id")
    private Long boutiqueId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "boutique_id", nullable = false, foreignKey = @ForeignKey(name = "fk_logo_boutique"))
    private Boutique boutique;

    @Lob
    @Column(nullable = false, length = 2097152, columnDefinition = "LONGBLOB")
    private byte[] contenu;

    @Column(nullable = false, length = 36)
    private String revision;
}
