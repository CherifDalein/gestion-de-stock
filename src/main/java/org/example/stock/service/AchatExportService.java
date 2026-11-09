package org.example.stock.service;

import org.example.stock.export.*;
import org.example.stock.model.Achat;
import org.example.stock.model.Montants;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Export de la liste des achats (Excel et PDF). Les lignes sont celles de l'écran {@code /achats} : mêmes achats, même ordre,
 * boutique du compte connecté, filtre « échus non soldés » et recherche. La liste est réservée au gérant (règle de sécurité
 * « /achats/** ») et revérifiée ici.
 */
@Service
@Transactional(readOnly = true)
public class AchatExportService {
    private final AchatService achats;
    private final EcheanceService echeances;
    private final LimiteExport limite;

    public AchatExportService(AchatService achats, EcheanceService echeances, LimiteExport limite) {
        this.achats = achats; this.echeances = echeances; this.limite = limite;
    }

    /** @throws ExportTropVolumineuxException si le filtre donne plus de {@link LimiteExport#lignes()} achats */
    public TableauExport preparer(boolean echus, String recherche) {
        DroitsExport.exigerGerant();
        LocalDate aujourdHui = echeances.aujourdHui();
        List<Achat> lus = achats.listerPourExport(echus, limite.lignes());
        limite.verifier(lus.size());
        String q = recherche == null ? "" : recherche.strip();
        List<Achat> retenus = q.isEmpty() ? lus : lus.stream().filter(a -> RechercheListe.correspond(q, texteDeLaLigne(a, aujourdHui))).toList();

        TableauExport tableau = TableauExport.titre("Journal des achats").nomFeuille("Achats")
                .filtres(decrireFiltres(echus, q))
                .colonnes(Colonne.texteCourt("Numéro"), Colonne.dateHeure("Date"), Colonne.texte("Fournisseur"), Colonne.texte("Saisi par"),
                        Colonne.montant("Total"), Colonne.montant("Retours"), Colonne.montant("Versé"), Colonne.montant("Avance utilisée"),
                        Colonne.montant("Reste"), Colonne.texteCourt("Statut"), Colonne.date("Échéance"));
        BigDecimal total = Montants.ZERO, retours = Montants.ZERO, verse = Montants.ZERO, avance = Montants.ZERO, reste = Montants.ZERO;
        for (Achat a : retenus) {
            BigDecimal montant = Montants.ouZero(a.getMontantTotal()), retourne = Montants.ouZero(a.getMontantRetours()),
                    verseAchat = Montants.ouZero(a.getMontantVerse()), avanceAchat = Montants.ouZero(a.getAvanceUtilisee()), resteAchat = a.getResteAPayer();
            total = total.add(montant); retours = retours.add(retourne); verse = verse.add(verseAchat); avance = avance.add(avanceAchat);
            reste = reste.add(resteAchat);
            tableau.ligne(numero(a), a.getDateAchat(), fournisseur(a), saisiPar(a), montant, retourne, verseAchat, avanceAchat, resteAchat,
                    statut(a, aujourdHui), a.getDateEcheance());
        }
        return tableau.totaux(retenus.size() + (retenus.size() > 1 ? " achats" : " achat"), null, null, null, total, retours, verse, avance, reste, null, null);
    }

    private static String decrireFiltres(boolean echus, String recherche) {
        String base = echus ? "Achats échus non soldés" : "Tous les achats";
        return recherche.isEmpty() ? base : base + " · Recherche : « " + recherche + " »";
    }

    private static String numero(Achat a) { return a.getNumero() != null ? a.getNumero() : "#" + a.getId(); }

    private static String fournisseur(Achat a) { return a.getFournisseur() != null ? a.getFournisseur().getNom() : "Non renseigné"; }

    private static String saisiPar(Achat a) {
        return a.getUtilisateur() != null && a.getUtilisateur().getNom() != null && !a.getUtilisateur().getNom().isBlank()
                ? a.getUtilisateur().getNom() : "Non renseigné";
    }

    private static String statut(Achat a, LocalDate aujourdHui) {
        if (a.getResteAPayer().signum() <= 0) return "Réglé";
        return a.estEnRetard(aujourdHui) ? "À régler (en retard)" : "À régler";
    }

    private static String texteDeLaLigne(Achat a, LocalDate aujourdHui) {
        return String.join(" ", numero(a), Textes.dateHeure(a.getDateAchat()), Textes.date(a.getDateEcheance()), fournisseur(a), saisiPar(a),
                RechercheListe.montantRecherchable(Montants.ouZero(a.getMontantTotal())), RechercheListe.montantRecherchable(Montants.ouZero(a.getMontantRetours())),
                RechercheListe.montantRecherchable(Montants.ouZero(a.getMontantVerse())), RechercheListe.montantRecherchable(Montants.ouZero(a.getAvanceUtilisee())),
                RechercheListe.montantRecherchable(a.getResteAPayer()), statut(a, aujourdHui));
    }
}
