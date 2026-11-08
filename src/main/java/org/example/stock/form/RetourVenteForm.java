package org.example.stock.form;

import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.CompteTresorerie;
import org.example.stock.enums.ModeRemboursement;

import java.util.ArrayList;
import java.util.List;

/**
 * Saisie d'un retour de vente. Le montant, le numéro, la date, le client, l'utilisateur et le stock ne viennent jamais
 * du navigateur : le serveur les recalcule. {@code retoursConnus} est le nombre de retours de la vente au moment où le
 * formulaire a été ouvert ; il protège contre un double envoi et contre un formulaire périmé.
 */
@Getter
@Setter
public class RetourVenteForm {
    private List<Ligne> lignes = new ArrayList<>();
    private String motif;
    /** Choix demandé seulement quand il reste un excédent à rembourser. */
    private ModeRemboursement modeRemboursement;
    /** Compte de trésorerie qui rend l'argent (mode {@code ARGENT}). */
    private CompteTresorerie comptePaiement;
    private Long retoursConnus;

    @Getter
    @Setter
    public static class Ligne {
        /** Ligne de la vente d'origine ; la quantité retournée est contrôlée contre ce qui reste à retourner. */
        private Long detailVenteId;
        private Integer quantite;
    }
}
