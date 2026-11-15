package org.example.stock.compta;

import org.example.stock.compta.ComptabiliteService.Balance;
import org.example.stock.compta.ComptabiliteService.CompteGrandLivre;
import org.example.stock.compta.ComptabiliteService.Controle;
import org.example.stock.compta.ComptabiliteService.LigneBalance;
import org.example.stock.compta.ComptabiliteService.LigneCompte;
import org.example.stock.compta.ComptabiliteService.Livres;
import org.example.stock.export.Colonne;
import org.example.stock.export.FormatPapier;
import org.example.stock.export.TableauExport;
import org.example.stock.export.Textes;
import org.example.stock.service.Etat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Met le journal, le grand livre, la balance et les contrôles sous la forme d'un {@link Etat} : la page, le PDF et l'Excel montrent
 * exactement les mêmes lignes. Chaque état porte la mention de validation.
 */
public final class ComptabiliteEtats {
    /** Mention affichée sur chaque page, chaque PDF et chaque Excel de la comptabilité. */
    public static final String MENTION = "Comptabilité en cours de validation par un expert-comptable : vérifiez les états avant tout usage officiel.";

    private ComptabiliteEtats() {}

    private static String periode(Livres l) { return "Période du " + Textes.date(l.debut()) + " au " + Textes.date(l.fin()) + " inclus"; }

    private static List<String> notes(String... plus) {
        List<String> n = new ArrayList<>();
        n.add(MENTION);
        n.addAll(List.of(plus));
        return n;
    }

    public static Etat journal(Livres l, List<Ecriture> ecritures, String filtre) {
        TableauExport t = TableauExport.titre("Journal").nomFeuille("Journal").filtres(periode(l) + (filtre.isEmpty() ? "" : " · " + filtre) + " · " + MENTION)
                .colonnes(Colonne.dateHeure("Date"), Colonne.texteCourt("Journal"), Colonne.texteCourt("Pièce"), Colonne.texte("Libellé"), Colonne.texteCourt("Compte"),
                        Colonne.texte("Intitulé du compte"), Colonne.texte("Tiers"), Colonne.montant("Débit"), Colonne.montant("Crédit"));
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        for (Ecriture e : ecritures) {
            boolean premiere = true;
            for (Ecriture.Ligne x : e.lignes()) {
                t.ligne(e.date(), e.journal().name(), e.piece(), premiere ? e.libelle() + (e.contrePassation() ? " (contre-passation)" : "") : "", x.compte(), l.intitule(x.compte()),
                        x.tiersNom() == null ? "" : x.tiersNom(), x.debit(), x.credit());
                debit = debit.add(x.debit()); credit = credit.add(x.credit());
                premiere = false;
            }
        }
        t.totaux("Total", null, null, null, null, null, null, debit, credit);
        t.note("Les écritures sont calculées à partir des opérations de l'application : elles ne sont pas enregistrées et se recalculent à chaque affichage.");
        return new Etat("journal", "Journal", periode(l), notes(), List.of(t), FormatPapier.A4_PAYSAGE, "journal", l.debut(), l.fin());
    }

    public static Etat grandLivre(Livres l, List<CompteGrandLivre> comptes, String filtre) {
        List<TableauExport> sections = new ArrayList<>();
        for (CompteGrandLivre c : comptes) {
            TableauExport t = TableauExport.titre("Compte " + c.numero() + " - " + c.intitule()).nomFeuille(c.numero())
                    .filtres(periode(l) + (filtre.isEmpty() ? "" : " · " + filtre) + " · " + MENTION)
                    .colonnes(Colonne.dateHeure("Date"), Colonne.texteCourt("Journal"), Colonne.texteCourt("Pièce"), Colonne.texte("Libellé"), Colonne.texte("Tiers"),
                            Colonne.montant("Débit"), Colonne.montant("Crédit"), Colonne.montant("Solde (débit +, crédit -)"));
            t.sousTotal(null, "", "", "Report à nouveau", "", null, null, c.ouverture());
            for (LigneCompte x : c.lignes()) t.ligne(x.date(), x.journal().name(), x.piece(), x.libelle(), x.tiers() == null ? "" : x.tiers(), x.debit(), x.credit(), x.solde());
            t.totaux("Total", null, null, null, null, c.totalDebit(), c.totalCredit(), c.soldeFin());
            sections.add(t);
        }
        if (sections.isEmpty()) {
            sections.add(TableauExport.titre("Grand livre").filtres(periode(l) + " · " + MENTION)
                    .colonnes(Colonne.texte("Compte"), Colonne.montant("Solde")));
        }
        return new Etat("grand-livre", "Grand livre", periode(l), notes(), sections, FormatPapier.A4_PAYSAGE, "grand-livre", l.debut(), l.fin());
    }

    public static Etat balance(Livres l, Balance b) {
        TableauExport t = TableauExport.titre("Balance").nomFeuille("Balance").filtres(periode(l) + " · " + MENTION)
                .colonnes(Colonne.texteCourt("Compte"), Colonne.texte("Intitulé"), Colonne.montant("Ouverture débit"), Colonne.montant("Ouverture crédit"),
                        Colonne.montant("Mouvements débit"), Colonne.montant("Mouvements crédit"), Colonne.montant("Solde débit"), Colonne.montant("Solde crédit"));
        for (LigneBalance x : b.lignes()) t.ligne(x.numero(), x.intitule(), x.ouvertureDebit(), x.ouvertureCredit(), x.debit(), x.credit(), x.finDebit(), x.finCredit());
        LigneBalance s = b.total();
        t.totaux("Total", "", s.ouvertureDebit(), s.ouvertureCredit(), s.debit(), s.credit(), s.finDebit(), s.finCredit());
        t.note("Le stock est régularisé à la fin de la période (inventaire intermittent) ; les comptes de gestion (classes 6 et 7) ne sont pas soldés.");
        return new Etat("balance", "Balance", periode(l), notes(), List.of(t), FormatPapier.A4_PAYSAGE, "balance", l.debut(), l.fin());
    }

    public static Etat controles(Livres l, List<Controle> controles) {
        TableauExport t = TableauExport.titre("Contrôles de cohérence").nomFeuille("Contrôles").filtres(periode(l) + " · " + MENTION)
                .colonnes(Colonne.entier("N°"), Colonne.texte("Contrôle"), Colonne.texteCourt("Résultat"), Colonne.texte("Détail"));
        List<TableauExport> sections = new ArrayList<>();
        sections.add(t);
        for (Controle c : controles) {
            t.ligne(c.numero(), c.libelle(), switch (c.statut()) { case OK -> "Conforme"; case ECART -> "Écart"; case NON_COMPARE -> "Non comparé"; }, c.detail());
        }
        TableauExport ecarts = TableauExport.titre("Écarts constatés").nomFeuille("Écarts").filtres(periode(l) + " · " + MENTION).colonnes(Colonne.entier("N°"), Colonne.texte("Écart"));
        for (Controle c : controles) for (String ligne : c.lignes()) ecarts.ligne(c.numero(), ligne);
        if (!controles.stream().allMatch(c -> c.lignes().isEmpty())) sections.add(ecarts);
        return new Etat("controles", "Contrôles de cohérence", periode(l), notes(), sections, FormatPapier.A4_PORTRAIT, "controles-comptables", l.debut(), l.fin());
    }
}
