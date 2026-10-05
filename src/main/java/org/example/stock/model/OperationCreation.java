package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.TypeOperationCreation;
import java.time.Instant;

/** Le résultat et les effets métier sont validés dans la même transaction. */
@Entity
@Getter
@Setter
public class OperationCreation {
    @Id
    @Column(length = 36)
    private String jeton;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "utilisateur_id", nullable = false)
    private Utilisateur utilisateur;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private TypeOperationCreation type;

    @Column(nullable = false)
    private Instant dateCreation;

    @Column(length = 64)
    private String empreinte;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "achat_id", unique = true)
    private Achat achat;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vente_id", unique = true)
    private Vente vente;
}
