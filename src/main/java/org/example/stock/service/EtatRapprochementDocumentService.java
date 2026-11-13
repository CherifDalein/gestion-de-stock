package org.example.stock.service;

import org.example.stock.export.Colonne;
import org.example.stock.export.ContexteDocument;
import org.example.stock.export.DocumentPdfService;
import org.example.stock.export.DroitsExport;
import org.example.stock.export.FormatPapier;
import org.example.stock.export.Textes;
import org.example.stock.export.TableauExport;
import org.example.stock.model.LigneReleve;
import org.example.stock.model.Montants;
import org.example.stock.model.MouvementCaisse;
import org.example.stock.model.SourceMouvement;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/** Documents de l'état de rapprochement : PDF A4 (en-tête de la boutique, numéro du relevé, signatures) et tableau pour l'export Excel. */
@Service
public class EtatRapprochementDocumentService {
    /** Mises en forme exposées au gabarit PDF sous le nom {@code fmt}. */
    public static final class Formats {
        public String montant(Number valeur) { return Textes.montant(valeur); }

        /** Montant avec son signe : « + 5 000 », « − 5 000 », « 0 ». */
        public String signe(BigDecimal valeur) {
            if (valeur == null || valeur.signum() == 0) return "0";
            return (valeur.signum() > 0 ? "+ " : "− ") + Textes.montant(valeur.abs());
        }

        public String date(LocalDate d) { return Textes.date(d); }

        public String dateHeure(LocalDateTime d) { return Textes.dateHeure(d); }

        public String libelleMouvement(MouvementCaisse m) {
            String base = m.getMotif() == null || m.getMotif().isBlank() ? m.getSourceLibelle() : m.getMotif();
            return m.getAnnulationDe() != null ? base + " (annulation)" : base;
        }
    }

    private final DocumentPdfService pdf;
    private final EtatRapprochementService etats;
    private final RapprochementService pointage;

    public EtatRapprochementDocumentService(DocumentPdfService pdf, EtatRapprochementService etats, RapprochementService pointage) {
        this.pdf = pdf; this.etats = etats; this.pointage = pointage;
    }

    @Transactional(readOnly = true)
    public byte[] pdf(Long releveId, ContexteDocument contexte) {
        DroitsExport.exigerGerant();
        EtatRapprochementService.Etat e = etats.calculer(releveId);
        Map<String, Object> variables = new HashMap<>();
        variables.put("titre", "État de rapprochement " + e.releve().getNumero());
        variables.put("e", e);
        variables.put("numero", e.releve().getNumero());
        variables.put("groupes", pointage.groupes(e.releve()).size());
        variables.put("fmt", new Formats());
        return pdf.rendre("pdf/etat-rapprochement", variables, contexte, FormatPapier.A4_PORTRAIT);
    }

    /** Les mêmes chiffres que l'écran et le PDF, en tableau pour l'export Excel. */
    @Transactional(readOnly = true)
    public TableauExport tableau(Long releveId) {
        DroitsExport.exigerGerant();
        EtatRapprochementService.Etat e = etats.calculer(releveId);
        var r = e.releve();
        TableauExport t = TableauExport.titre("État de rapprochement " + r.getNumero()).nomFeuille("Rapprochement")
                .filtres("Compte : " + r.getCompte().getNom() + " · Période du " + Textes.date(r.getDateDebut()) + " au " + Textes.date(r.getDateFin())
                        + " · Ouverture " + Textes.montant(r.getSoldeOuverture()) + " GNF · Clôture " + Textes.montant(r.getSoldeCloture()) + " GNF · " + r.getStatut().getLibelle())
                .colonnes(Colonne.texte("Rubrique"), Colonne.date("Date"), Colonne.texte("Libellé"), Colonne.montant("Montant"));
        t.sousTotal("Solde du compte selon l'appli au " + Textes.date(r.getDateFin()), null, null, e.soldeAppli());
        if (e.pointesApresCloture().signum() != 0) t.ligne("Plus : mouvements rapprochés datés après la clôture", null, null, e.pointesApresCloture());
        for (MouvementCaisse m : e.mouvementsNonPointes()) {
            t.ligne("Mouvement de l'appli non pointé", m.getDateMouvement().toLocalDate(), libelle(m), m.getMontant());
        }
        t.sousTotal("Moins : mouvements de l'appli non pointés (" + e.mouvementsNonPointes().size() + ")", null, null, e.totalMouvementsNonPointes().negate());
        for (LigneReleve l : e.lignesNonPointees()) {
            t.ligne("Ligne du relevé non pointée", l.getDateOperation(), l.getReference() == null ? l.getLibelle() : l.getLibelle() + " (" + l.getReference() + ")", l.getMontant());
        }
        t.sousTotal("Plus : lignes du relevé non pointées (" + e.lignesNonPointees().size() + ")", null, null, e.totalLignesNonPointees());
        t.sousTotal("Solde du relevé calculé", null, null, e.soldeCalcule());
        t.ligne("Solde de clôture du relevé", null, null, r.getSoldeCloture());
        t.totaux("Écart de rapprochement (zéro attendu)", null, null, Montants.valider(e.ecart(), "L'écart"));
        t.note("Écart = solde calculé − solde de clôture du relevé. Crédit positif, débit négatif. Les lignes des relevés précédents non pointées restent comptées tant qu'elles ne sont pas enregistrées dans l'appli.");
        return t;
    }

    private static String libelle(MouvementCaisse m) {
        String base = m.getMotif() == null || m.getMotif().isBlank() ? SourceMouvement.libelle(m.getSource()) : m.getMotif();
        return m.getAnnulationDe() != null ? base + " (annulation)" : base;
    }
}
