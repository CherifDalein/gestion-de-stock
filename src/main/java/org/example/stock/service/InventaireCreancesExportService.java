package org.example.stock.service;

import org.example.stock.export.Colonne;
import org.example.stock.export.DroitsExport;
import org.example.stock.export.TableauExport;
import org.example.stock.export.Textes;
import org.example.stock.model.InventaireCreances;
import org.example.stock.model.LigneInventaire;
import org.example.stock.model.Montants;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** Export Excel et PDF d'un inventaire des créances et dettes (gérant seulement). La colonne « Saisi par » est la dernière. */
@Service
@Transactional(readOnly = true)
public class InventaireCreancesExportService {
    private final InventaireCreancesService inventaires;

    public InventaireCreancesExportService(InventaireCreancesService inventaires) { this.inventaires = inventaires; }

    public static String numero(InventaireCreances i) { return "IC-" + i.getId(); }

    public TableauExport preparer(Long id) {
        DroitsExport.exigerGerant();
        InventaireCreances inventaire = inventaires.trouver(id);
        TableauExport t = TableauExport.titre("Inventaire des créances et dettes " + numero(inventaire)).nomFeuille("Inventaire")
                .filtres("Inventaire au " + Textes.date(inventaire.getDateInventaire()) + " · enregistré le " + Textes.dateHeure(inventaire.getDateCreation())
                        + (inventaire.getNote() == null ? "" : " · " + inventaire.getNote()))
                .colonnes(Colonne.texteCourt("Type"), Colonne.texte("Partenaire"), Colonne.montant("Solde de l'application"), Colonne.montant("Solde confirmé"),
                        Colonne.montant("Écart"), Colonne.texte("Raison de l'écart"), Colonne.texte("Saisi par"));
        BigDecimal ecarts = Montants.ZERO;
        int avecEcart = 0;
        for (LigneInventaire l : inventaire.getLignes()) {
            ecarts = ecarts.add(l.getEcart());
            if (l.aEcart()) avecEcart++;
            t.ligne(l.getType().getLibelle(), l.getNom(), l.getSoldeAppli(), l.getSoldeConfirme(), l.getEcart(), l.getRaison() == null ? "" : l.getRaison(),
                    inventaire.getUtilisateur().getNom());
        }
        t.totaux(inventaire.getLignes().size() + (inventaire.getLignes().size() > 1 ? " partenaires" : " partenaire") + " · " + avecEcart
                + (avecEcart > 1 ? " écarts" : " écart"), null, null, null, ecarts, null, null);
        t.note("Soldes signés du point de vue de la boutique : positif, le partenaire doit de l'argent à la boutique ; négatif, la boutique lui en doit (avance comprise). "
                + "Écart = solde confirmé − solde de l'application.");
        t.note("Le solde de l'application est celui du " + Textes.dateHeure(inventaire.getDateCreation()) + " (enregistrement de l'inventaire) : l'application ne conserve pas l'historique des soldes. "
                + "L'inventaire ne modifie aucun solde.");
        return t;
    }
}
