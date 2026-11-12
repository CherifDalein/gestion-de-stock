package org.example.stock.service;

import org.example.stock.export.Colonne;
import org.example.stock.export.DroitsExport;
import org.example.stock.export.LimiteExport;
import org.example.stock.export.TableauExport;
import org.example.stock.export.Textes;
import org.example.stock.model.ComptageCaisse;
import org.example.stock.model.Montants;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Export des comptages de caisse en Excel et PDF (gérant seulement). Les colonnes déjà publiées gardent leur ordre ; la colonne « Saisi par »
 * est la dernière.
 */
@Service
@Transactional(readOnly = true)
public class ComptageExportService {
    private final ComptageCaisseService comptages;
    private final LimiteExport limite;

    public ComptageExportService(ComptageCaisseService comptages, LimiteExport limite) {
        this.comptages = comptages; this.limite = limite;
    }

    public TableauExport preparer(ComptageCaisseService.Filtre filtre) {
        DroitsExport.exigerGerant();
        List<ComptageCaisse> lus = comptages.chercher(filtre, limite.lignes() + 1);
        limite.verifier(lus.size());
        StringBuilder criteres = new StringBuilder();
        if (filtre.statut() != null) criteres.append("Statut : ").append(filtre.statut().getLibelle());
        if (filtre.du() != null || filtre.au() != null) {
            if (criteres.length() > 0) criteres.append(" · ");
            criteres.append("Période : ").append(filtre.du() == null ? "…" : Textes.date(filtre.du())).append(" au ").append(filtre.au() == null ? "…" : Textes.date(filtre.au()));
        }
        TableauExport t = TableauExport.titre("Comptages de caisse").nomFeuille("Comptages")
                .filtres(criteres.length() == 0 ? "Tous les comptages" : criteres.toString())
                .colonnes(Colonne.texteCourt("Numéro"), Colonne.dateHeure("Date et heure"), Colonne.texteCourt("Compte"), Colonne.montant("Solde théorique"),
                        Colonne.montant("Montant compté"), Colonne.montant("Écart"), Colonne.texteCourt("Statut"), Colonne.texte("Commentaire"),
                        Colonne.texte("Décision (motif)"), Colonne.texte("Saisi par"));
        BigDecimal ecarts = Montants.ZERO;
        for (ComptageCaisse c : lus) {
            ecarts = ecarts.add(c.getEcart());
            String decision = c.getMotifDecision() == null ? "" : c.getMotifDecision() + (c.getDecideur() == null ? "" : " (" + c.getDecideur().getNom() + ")");
            t.ligne(ComptageCaissePdfService.numero(c), c.getDateComptage(), c.getCompte().getNom(), c.getSoldeTheorique(), c.getMontantCompte(), c.getEcart(),
                    c.getStatut().getLibelle(), c.getCommentaire() == null ? "" : c.getCommentaire(), decision, c.getUtilisateur().getNom());
        }
        t.totaux(lus.size() + (lus.size() > 1 ? " comptages" : " comptage"), null, null, null, null, ecarts, null, null, null, null);
        t.note("Écart = montant compté − solde théorique à l'heure du comptage (positif : excédent ; négatif : manque). Un comptage conforme ou refusé ne passe aucun mouvement.");
        return t;
    }
}
