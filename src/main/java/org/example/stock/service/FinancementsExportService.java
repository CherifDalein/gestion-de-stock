package org.example.stock.service;

import org.example.stock.export.Colonne;
import org.example.stock.export.DroitsExport;
import org.example.stock.export.FormatPapier;
import org.example.stock.export.LimiteExport;
import org.example.stock.export.TableauExport;
import org.example.stock.export.Textes;
import org.example.stock.enums.SensEmprunt;
import org.example.stock.model.Montants;
import org.example.stock.model.OperationTresorerie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Exports Excel et PDF des financements (gérant seulement) : liste des partenaires, relevé d'un partenaire, échéancier d'un emprunt,
 * et état « Emprunts et prêts ». Le même {@link TableauExport} alimente la page, l'Excel et le PDF.
 */
@Service
@Transactional(readOnly = true)
public class FinancementsExportService {
    private final PartenairesFinanciersService partenaires;
    private final EmpruntService emprunts;
    private final EcheanceService echeances;
    private final LimiteExport limite;

    public FinancementsExportService(PartenairesFinanciersService partenaires, EmpruntService emprunts, EcheanceService echeances, LimiteExport limite) {
        this.partenaires = partenaires; this.emprunts = emprunts; this.echeances = echeances; this.limite = limite;
    }

    private static String libelleSolde(PartenairesFinanciersService.Solde s) {
        return s.nul() ? "Soldé" : s.laBoutiqueDoit() ? "La boutique doit" : "Il doit à la boutique";
    }

    public TableauExport partenaires(boolean avecArchives) {
        DroitsExport.exigerGerant();
        List<PartenairesFinanciersService.Vue> vues = partenaires.lister(avecArchives);
        limite.verifier(vues.size());
        TableauExport t = TableauExport.titre("Autres partenaires").nomFeuille("Partenaires")
                .filtres(avecArchives ? "Tous les partenaires, archivés compris" : "Partenaires actifs")
                .colonnes(Colonne.texte("Nom"), Colonne.texteCourt("Type"), Colonne.texteCourt("Téléphone"), Colonne.montant("La boutique doit"),
                        Colonne.montant("Il doit à la boutique"), Colonne.montant("Capital apporté (info)"), Colonne.entier("Emprunts en cours"), Colonne.texteCourt("Statut"));
        BigDecimal doit = Montants.ZERO, ilDoit = Montants.ZERO;
        for (var v : vues) {
            var s = v.solde();
            BigDecimal d = s.laBoutiqueDoit() ? s.total() : Montants.ZERO, i = s.ilDoit() ? s.absolu() : Montants.ZERO;
            doit = doit.add(d); ilDoit = ilDoit.add(i);
            t.ligne(v.partenaire().getNom(), v.partenaire().getType().getLibelle(), v.partenaire().getTelephone(), d.signum() > 0 ? d : null,
                    i.signum() > 0 ? i : null, s.capitalApporte().signum() != 0 ? s.capitalApporte() : null, v.empruntsEnCours(),
                    v.partenaire().isActif() ? "Actif" : "Archivé");
        }
        return t.totaux(vues.size() + (vues.size() > 1 ? " partenaires" : " partenaire"), null, null, doit, ilDoit, null, null, null)
                .note("Solde = compte courant net des retraits + emprunts (capital restant dû et intérêts échus impayés) − prêts. "
                        + "Le capital apporté est donné à titre d'information, hors solde.");
    }

    /** Relevé d'un partenaire : toutes ses opérations, annulations indiquées. */
    public TableauExport releve(Long partenaireId) {
        DroitsExport.exigerGerant();
        PartenairesFinanciersService.Detail d = partenaires.detail(partenaireId);
        limite.verifier(d.operations().size());
        var s = d.vue().solde();
        TableauExport t = TableauExport.titre("Relevé de " + d.vue().partenaire().getNom()).nomFeuille("Relevé")
                .filtres(d.vue().partenaire().getType().getLibelle() + " · solde au " + Textes.date(echeances.aujourdHui()) + " : " + libelleSolde(s)
                        + (s.nul() ? "" : " " + Textes.montant(s.absolu()) + " GNF"))
                .colonnes(Colonne.dateHeure("Date"), Colonne.texte("Opération"), Colonne.texte("Compte"), Colonne.texte("Motif"), Colonne.montant("Montant"), Colonne.texteCourt("État"));
        for (var l : d.operations()) {
            OperationTresorerie o = l.operation();
            t.ligne(o.getDateOperation(), o.getType().getLibelle() + (o.getEmprunt() != null ? " " + o.getEmprunt().getNumero() : ""),
                    o.getCompteSource().getNom(), o.getMotif(), o.getMontant(), l.annulee() ? "Annulée" : "");
        }
        return t.note("Les montants sont ceux des opérations ; une opération annulée reste visible, sans effet sur le solde.");
    }

    /** Échéancier d'un emprunt ou d'un prêt avec ce qui est payé et ce qui reste. */
    public TableauExport echeancier(Long empruntId) {
        DroitsExport.exigerGerant();
        EmpruntService.Vue v = emprunts.vue(empruntId);
        SituationEmprunt s = v.situation();
        var e = v.emprunt();
        limite.verifier(s.lignes().size());
        TableauExport t = TableauExport.titre("Échéancier " + e.getNumero()).nomFeuille("Échéancier")
                .filtres(e.getSens().getLibelle() + " · " + e.getPartenaire().getNom() + " · capital " + Textes.montant(e.getCapital()) + " GNF · taux annuel simple "
                        + Textes.pourcentage(e.getTauxAnnuel()) + " · " + e.getNombreEcheances() + " échéances " + e.getPeriodicite().getLibelle().toLowerCase(java.util.Locale.ROOT) + "s"
                        + " · débloqué le " + Textes.date(e.getDateEmprunt()))
                .colonnes(Colonne.entier("N°"), Colonne.date("Échéance"), Colonne.montant("Capital"), Colonne.montant("Intérêts"), Colonne.montant("Total prévu"),
                        Colonne.montant("Payé"), Colonne.montant("Reste à payer"), Colonne.texteCourt("État"));
        BigDecimal capital = Montants.ZERO, interet = Montants.ZERO, total = Montants.ZERO, paye = Montants.ZERO, reste = Montants.ZERO;
        for (var l : s.lignes()) {
            BigDecimal payeLigne = l.capitalPaye().add(l.interetPaye());
            capital = capital.add(l.echeance().getCapital()); interet = interet.add(l.echeance().getInteret()); total = total.add(l.totalPrevu());
            paye = paye.add(payeLigne); reste = reste.add(l.resteTotal());
            String etat = l.soldee() ? (l.interetRemis().signum() > 0 ? "Soldée (intérêts remis)" : "Soldée") : l.enRetard(s.aujourdhui()) ? "En retard" : l.partielle() ? "Partielle" : "À venir";
            t.ligne(l.rang(), l.date(), l.echeance().getCapital(), l.echeance().getInteret(), l.totalPrevu(), payeLigne, l.resteTotal(), etat);
        }
        t.totaux("Total", null, capital, interet, total, paye, reste, s.statut());
        t.note("Intérêts simples : capital restant dû avant l'échéance × taux annuel ÷ nombre de périodes par an, arrondi au centime. Capital constant, le reliquat d'arrondi est sur la dernière échéance.");
        if (s.interetsRemis().signum() > 0) t.note("Intérêts remis lors du remboursement anticipé du solde : " + Textes.montant(s.interetsRemis()) + " GNF.");
        return t;
    }

    /** État « Emprunts et prêts » : situation au jour de l'édition et échéances de la période. */
    public Etat etatEmpruntsEtPrets(PeriodeEtat periode) {
        DroitsExport.exigerGerant();
        LocalDate jour = echeances.aujourdHui();
        List<EmpruntService.Vue> vues = emprunts.lister();
        limite.verifier(vues.size());
        String parametres = periode.texte() + " · situation au " + Textes.date(jour);

        BigDecimal restantEmprunts = Montants.ZERO, restantPrets = Montants.ZERO, interetsEmprunts = Montants.ZERO, interetsPrets = Montants.ZERO;
        BigDecimal aPayer = Montants.ZERO, aRecevoir = Montants.ZERO, retardMontant = Montants.ZERO;
        int retardNombre = 0, dossiersEnRetard = 0, enCours = 0;
        TableauExport dossiers = TableauExport.titre("Emprunts et prêts").nomFeuille("Dossiers").filtres(parametres)
                .colonnes(Colonne.texteCourt("N°"), Colonne.texteCourt("Sens"), Colonne.texte("Partenaire"), Colonne.date("Date"), Colonne.montant("Capital"),
                        Colonne.pourcentage("Taux annuel"), Colonne.montant("Capital restant dû"), Colonne.montant("Intérêts payés"), Colonne.date("Prochaine échéance"),
                        Colonne.montant("Montant de l'échéance"), Colonne.entier("Échéances en retard"), Colonne.texteCourt("Statut"));
        TableauExport duPeriode = TableauExport.titre("Échéances de la période").nomFeuille("Échéances").filtres(parametres)
                .colonnes(Colonne.texteCourt("N°"), Colonne.texte("Partenaire"), Colonne.entier("Échéance n°"), Colonne.date("Date"), Colonne.montant("Capital"),
                        Colonne.montant("Intérêts"), Colonne.montant("Total prévu"), Colonne.montant("Reste à régler"), Colonne.texteCourt("État"));
        BigDecimal periodeTotal = Montants.ZERO, periodeReste = Montants.ZERO;
        for (var v : vues) {
            var e = v.emprunt(); var s = v.situation(); boolean emprunt = e.getSens() == SensEmprunt.EMPRUNT;
            var prochaine = s.prochaine();
            dossiers.ligne(e.getNumero(), e.getSens().getLibelle(), e.getPartenaire().getNom(), e.getDateEmprunt(), e.getCapital(), e.getTauxAnnuel(),
                    s.capitalRestantDu(), s.interetsPayes(), prochaine == null ? null : prochaine.date(), prochaine == null ? null : prochaine.resteTotal(),
                    s.nombreRetards(), s.statut());
            if (emprunt) { restantEmprunts = restantEmprunts.add(s.capitalRestantDu()); interetsEmprunts = interetsEmprunts.add(s.interetsRestants()); }
            else { restantPrets = restantPrets.add(s.capitalRestantDu()); interetsPrets = interetsPrets.add(s.interetsRestants()); }
            if (!s.clos()) enCours++;
            if (s.enRetardMaintenant()) { dossiersEnRetard++; retardNombre += s.nombreRetards(); retardMontant = retardMontant.add(s.montantEnRetard()); }
            for (var l : s.lignes()) {
                if (l.date().isBefore(periode.du()) || l.date().isAfter(periode.au())) continue;
                aPayer = emprunt ? aPayer.add(l.totalPrevu()) : aPayer; aRecevoir = emprunt ? aRecevoir : aRecevoir.add(l.totalPrevu());
                periodeTotal = periodeTotal.add(l.totalPrevu()); periodeReste = periodeReste.add(l.resteTotal());
                String etat = l.soldee() ? "Soldée" : l.enRetard(jour) ? "En retard" : l.partielle() ? "Partielle" : "À venir";
                duPeriode.ligne(e.getNumero(), e.getPartenaire().getNom(), l.rang(), l.date(), l.echeance().getCapital(), l.echeance().getInteret(), l.totalPrevu(), l.resteTotal(), etat);
            }
        }
        dossiers.totaux(vues.size() + (vues.size() > 1 ? " dossiers" : " dossier"), null, null, null, null, null, restantEmprunts.add(restantPrets), null, null, null, retardNombre, null);
        duPeriode.totaux("Total", null, null, null, null, null, periodeTotal, periodeReste, null);

        TableauExport synthese = TableauExport.titre("Synthèse").nomFeuille("Synthèse").filtres(parametres)
                .colonnes(Colonne.texte("Rubrique"), Colonne.montant("Montant"))
                .ligne("Capital restant dû sur les emprunts (la boutique doit)", restantEmprunts)
                .ligne("Capital restant dû sur les prêts (on doit à la boutique)", restantPrets)
                .ligne("Intérêts restants prévus sur les emprunts", interetsEmprunts)
                .ligne("Intérêts restants prévus sur les prêts", interetsPrets)
                .ligne("Échéances d'emprunts de la période (à payer)", aPayer)
                .ligne("Échéances de prêts de la période (à recevoir)", aRecevoir)
                .totaux("En retard au " + Textes.date(jour) + " (" + retardNombre + (retardNombre > 1 ? " échéances, " : " échéance, ") + dossiersEnRetard + (dossiersEnRetard > 1 ? " dossiers)" : " dossier)"), retardMontant);
        List<String> notes = new ArrayList<>();
        notes.add("Situation au jour de l'édition (" + Textes.date(jour) + ") : le capital restant dû et les retards ne sont pas reconstitués à la date de fin de la période. "
                + enCours + (enCours > 1 ? " dossiers en cours." : " dossier en cours."));
        return new Etat("emprunts-prets", "Emprunts et prêts", parametres, notes, List.of(synthese, dossiers, duPeriode), FormatPapier.A4_PAYSAGE,
                "emprunts-prets", periode.du(), periode.au());
    }
}
