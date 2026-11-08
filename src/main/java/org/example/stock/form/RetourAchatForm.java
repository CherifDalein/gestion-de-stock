package org.example.stock.form;

import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.CompteTresorerie;
import org.example.stock.enums.ModeRemboursement;

import java.util.ArrayList;
import java.util.List;

/**
 * Saisie d'un retour d'achat (marchandise rendue au fournisseur). Le montant, le numéro, la date, le fournisseur,
 * l'utilisateur et le stock ne viennent jamais du navigateur : le serveur les recalcule. {@code retoursConnus} est le
 * nombre de retours de l'achat au moment où le formulaire a été ouvert ; il protège contre un double envoi et contre un
 * formulaire périmé.
 */
@Getter
@Setter
public class RetourAchatForm {
    private List<Ligne> lignes = new ArrayList<>();
    private String motif;
    /** Choix demandé seulement quand il reste un excédent déjà payé au fournisseur : argent rendu par lui, ou avoir. */
    private ModeRemboursement modeRemboursement;
    /** Compte de trésorerie qui reçoit l'argent rendu par le fournisseur (mode {@code ARGENT}). */
    private CompteTresorerie comptePaiement;
    private Long retoursConnus;

    @Getter
    @Setter
    public static class Ligne {
        /** Ligne de l'achat d'origine ; la quantité retournée est contrôlée contre ce qui reste à retourner et le stock. */
        private Long detailAchatId;
        private Integer quantite;
    }
}
