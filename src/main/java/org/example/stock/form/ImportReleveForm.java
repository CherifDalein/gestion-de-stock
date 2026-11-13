package org.example.stock.form;

import lombok.Getter;
import lombok.Setter;

/**
 * Correspondance des colonnes choisie par le gérant pour un fichier de relevé. Les colonnes sont numérotées à partir de 1 ; la ligne d'en-tête vaut
 * 0 quand le fichier n'en a pas. {@code modeMontant} : {@code SIGNE} (une colonne de montant signé) ou {@code DEBIT_CREDIT} (deux colonnes).
 */
@Getter
@Setter
public class ImportReleveForm {
    private Integer ligneEntete;
    private Integer colonneDate;
    private Integer colonneLibelle;
    private Integer colonneReference;
    private String modeMontant;
    private Integer colonneMontant;
    private Integer colonneDebit;
    private Integer colonneCredit;
    private String formatDate;
    private String separateur;
    /** Importer aussi les lignes signalées comme doublons (ignorées par défaut). */
    private String avecDoublons;
}
