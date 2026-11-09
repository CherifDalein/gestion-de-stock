package org.example.stock.service;

import org.example.stock.enums.TypeRetour;
import org.example.stock.export.*;
import org.example.stock.model.Montants;
import org.example.stock.model.Retour;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Export de la liste des retours (Excel et PDF) : mêmes retours, même ordre et même filtre de type que {@code /retours}
 * (retours de vente et d'achat de la boutique du compte connecté). Réservé au gérant : les services de retour le vérifient aussi.
 */
@Service
@Transactional(readOnly = true)
public class RetourExportService {
    private final RetourVenteService retoursVente;
    private final RetourAchatService retoursAchat;
    private final LimiteExport limite;

    public RetourExportService(RetourVenteService retoursVente, RetourAchatService retoursAchat, LimiteExport limite) {
        this.retoursVente = retoursVente; this.retoursAchat = retoursAchat; this.limite = limite;
    }

    /**
     * @param type      {@link TypeRetour#VENTE}, {@link TypeRetour#ACHAT} ou {@code null} pour tous, comme le filtre « Vente / Achat » de l'écran
     * @param recherche texte de la barre de recherche (facultatif)
     * @throws ExportTropVolumineuxException si le filtre donne plus de {@link LimiteExport#lignes()} retours
     */
    public TableauExport preparer(TypeRetour type, String recherche) {
        DroitsExport.exigerGerant();
        List<Retour> lus = new ArrayList<>();
        if (type != TypeRetour.ACHAT) lus.addAll(retoursVente.lister(limite.lignes()));
        if (type != TypeRetour.VENTE) lus.addAll(retoursAchat.lister(limite.lignes()));
        limite.verifier(lus.size());
        lus.sort(Comparator.comparing(Retour::getDateRetour).thenComparing(Retour::getId).reversed());
        String q = recherche == null ? "" : recherche.strip();
        List<Retour> retenus = q.isEmpty() ? lus : lus.stream().filter(r -> RechercheListe.correspond(q, texteDeLaLigne(r))).toList();

        TableauExport tableau = TableauExport.titre("Liste des retours").nomFeuille("Retours")
                .filtres(decrireFiltres(type, q))
                .colonnes(Colonne.texteCourt("Numéro"), Colonne.texteCourt("Type"), Colonne.dateHeure("Date"), Colonne.texteCourt("Document d’origine"),
                        Colonne.texte("Client / fournisseur"), Colonne.montant("Montant"), Colonne.montant("Remboursé"), Colonne.montant("Avoir"));
        BigDecimal montant = Montants.ZERO, rembourse = Montants.ZERO, avoir = Montants.ZERO;
        for (Retour r : retenus) {
            BigDecimal total = Montants.ouZero(r.getMontantTotal()), rendu = Montants.ouZero(r.getMontantRembourse()), garde = Montants.ouZero(r.getMontantAvoir());
            montant = montant.add(total); rembourse = rembourse.add(rendu); avoir = avoir.add(garde);
            tableau.ligne(r.getNumero(), typeAffiche(r), r.getDateRetour(), origine(r), partenaire(r), total, rendu, garde);
        }
        return tableau.totaux(retenus.size() + (retenus.size() > 1 ? " retours" : " retour"), null, null, null, null, montant, rembourse, avoir);
    }

    private static String decrireFiltres(TypeRetour type, String recherche) {
        String base = type == null ? "Tous les retours" : (type == TypeRetour.VENTE ? "Retours de vente" : "Retours d’achat");
        return recherche.isEmpty() ? base : base + " · Recherche : « " + recherche + " »";
    }

    private static String typeAffiche(Retour r) { return r.getType() == TypeRetour.ACHAT ? "Achat" : "Vente"; }

    private static String origine(Retour r) {
        if (r.getType() == TypeRetour.ACHAT) return r.getAchat() == null ? "—" : (r.getAchat().getNumero() != null ? r.getAchat().getNumero() : "#" + r.getAchat().getId());
        return r.getVente() == null ? "—" : (r.getVente().getNumero() != null ? r.getVente().getNumero() : "#" + r.getVente().getId());
    }

    private static String partenaire(Retour r) {
        if (r.getType() == TypeRetour.ACHAT) return r.getFournisseur() != null ? r.getFournisseur().getNom() : "Non renseigné";
        return r.getClient() != null ? r.getClient().getNom() : "Client de passage";
    }

    private static String texteDeLaLigne(Retour r) {
        return String.join(" ", typeAffiche(r), r.getNumero(), Textes.dateHeure(r.getDateRetour()), origine(r), partenaire(r),
                RechercheListe.montantRecherchable(Montants.ouZero(r.getMontantTotal())), RechercheListe.montantRecherchable(Montants.ouZero(r.getMontantRembourse())),
                RechercheListe.montantRecherchable(Montants.ouZero(r.getMontantAvoir())));
    }
}
