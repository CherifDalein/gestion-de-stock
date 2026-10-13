package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.TypeMouvementStock;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Table(name = "mouvement_stock", indexes = @Index(name = "idx_mouvement_stock_produit_date", columnList = "boutique_id,produit_id,cree_le,id"))
public class MouvementStock {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "boutique_id", nullable = false) private Boutique boutique;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "produit_id", nullable = false) private Produit produit;
    // L'identifiant et le nom sont figés ; supprimer un compte ne supprime pas la trace d'inventaire.
    @Column(nullable = false) private Long acteurId;
    @Column(nullable = false, length = 255) private String acteurNom;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 16) private TypeMouvementStock type;
    @Column(nullable = false) private LocalDateTime creeLe;
    @Column(nullable = false) private Long quantiteAvant;
    @Column(nullable = false) private Long quantiteApres;
    @Column(precision = 17, scale = 2) private BigDecimal coutUnitaire;
    @Column(precision = 17, scale = 2) private BigDecimal prixVente;
    @Column(nullable = false, length = 500) private String motif;
}
