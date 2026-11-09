package org.example.stock.service;

import org.example.stock.enums.EtatDevis;
import org.example.stock.export.*;
import org.example.stock.model.Devis;
import org.example.stock.model.Montants;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Export de la liste des devis (Excel et PDF) : mêmes devis, même ordre et même filtre d'état que {@code /devis}
 * ({@link DevisService#lister}), boutique du compte connecté, gérant et caissier.
 */
@Service
@Transactional(readOnly = true)
public class DevisExportService {
    private final DevisService devis;
    private final EcheanceService echeances;
    private final LimiteExport limite;

    public DevisExportService(DevisService devis, EcheanceService echeances, LimiteExport limite) {
        this.devis = devis; this.echeances = echeances; this.limite = limite;
    }

    /**
     * @param etat      filtre d'état de l'écran ({@code null} : tous les devis)
     * @param recherche texte de la barre de recherche (facultatif)
     * @throws ExportTropVolumineuxException si le filtre donne plus de {@link LimiteExport#lignes()} devis
     */
    public TableauExport preparer(EtatDevis etat, String recherche) {
        LocalDate aujourdHui = echeances.aujourdHui();
        List<Devis> lus = devis.lister(etat, limite.lignes());
        limite.verifier(lus.size());
        String q = recherche == null ? "" : recherche.strip();
        List<Devis> retenus = q.isEmpty() ? lus : lus.stream().filter(d -> RechercheListe.correspond(q, texteDeLaLigne(d, aujourdHui))).toList();

        TableauExport tableau = TableauExport.titre("Liste des devis").nomFeuille("Devis")
                .filtres(decrireFiltres(etat, q))
                .colonnes(Colonne.texteCourt("Numéro"), Colonne.dateHeure("Date"), Colonne.texte("Client"), Colonne.texte("Auteur"),
                        Colonne.montant("Total"), Colonne.date("Valable jusqu’au"), Colonne.texteCourt("État"));
        BigDecimal total = Montants.ZERO;
        for (Devis d : retenus) {
            BigDecimal montant = Montants.ouZero(d.getMontantTotal());
            total = total.add(montant);
            tableau.ligne(d.getNumero(), d.getDateDevis(), client(d), auteur(d), montant, d.getDateValidite(), d.etat(aujourdHui).getLibelle());
        }
        return tableau.totaux(retenus.size() + " devis", null, null, null, total, null, null);
    }

    private static String decrireFiltres(EtatDevis etat, String recherche) {
        String base = etat == null ? "Tous les devis" : "Devis : " + etat.getLibelle();
        return recherche.isEmpty() ? base : base + " · Recherche : « " + recherche + " »";
    }

    private static String client(Devis d) { return d.getClient() != null ? d.getClient().getNom() : "Sans client"; }

    private static String auteur(Devis d) {
        return d.getUtilisateur() != null && d.getUtilisateur().getNom() != null && !d.getUtilisateur().getNom().isBlank()
                ? d.getUtilisateur().getNom() : "Non renseigné";
    }

    private static String texteDeLaLigne(Devis d, LocalDate aujourdHui) {
        return String.join(" ", d.getNumero(), Textes.dateHeure(d.getDateDevis()), Textes.date(d.getDateValidite()), client(d), auteur(d),
                RechercheListe.montantRecherchable(Montants.ouZero(d.getMontantTotal())), d.etat(aujourdHui).getLibelle());
    }
}
