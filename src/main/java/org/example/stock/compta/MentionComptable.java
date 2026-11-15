package org.example.stock.compta;

import org.example.stock.export.TableauExport;
import org.example.stock.model.ReglageComptableBoutique;
import org.example.stock.repository.ReglageComptableBoutiqueRepository;
import org.example.stock.service.Etat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Mention de validation affichée sur les pages, les PDF et les Excel de la comptabilité. Par défaut, c'est « Comptabilité en cours de validation par un
 * expert-comptable ». Quand le gérant a rempli le réglage « États validés par un expert-comptable » (cabinet et date), la validation la remplace. Un
 * exercice clôturé ajoute « Exercice clôturé le … par … ».
 */
@Service
public class MentionComptable {
    private final ReglageComptableBoutiqueRepository reglages;

    public MentionComptable(ReglageComptableBoutiqueRepository reglages) { this.reglages = reglages; }

    /** « États validés par un expert-comptable : cabinet X, le 12/03/2027. » */
    public static String validation(String cabinet, LocalDate le) { return "États validés par un expert-comptable : " + cabinet.strip() + ", le " + ExercicesService.jour(le) + "."; }

    public static String cloture(LocalDateTime le, String par) { return "Exercice clôturé le " + ExercicesService.jour(le.toLocalDate()) + " par " + par + "."; }

    /** La mention de la boutique : sa validation si elle est renseignée, sinon « en cours de validation ». */
    @Transactional(readOnly = true)
    public String pour(Long boutiqueId) {
        Optional<ReglageComptableBoutique> r = reglages.findByBoutiqueId(boutiqueId);
        return r.isPresent() && r.get().estValide() ? validation(r.get().getValideCabinet(), r.get().getValideLe()) : ComptabiliteEtats.MENTION;
    }

    /** La mention d'un exercice figé : sa ligne de clôture, puis la validation ou « en cours de validation ». */
    @Transactional(readOnly = true)
    public String pourExerciceFige(Long boutiqueId, LocalDateTime clotureLe, String cloturePar) { return cloture(clotureLe, cloturePar) + " " + pour(boutiqueId); }

    /** Un état dont la mention standard est remplacée par {@code mention} (sur la page, les notes, le PDF et chaque feuille Excel). */
    public static Etat avecMention(Etat e, String mention) {
        if (mention == null || mention.equals(ComptabiliteEtats.MENTION)) return e;
        List<String> notes = new ArrayList<>();
        for (String n : e.notes()) notes.add(n.replace(ComptabiliteEtats.MENTION, mention));
        if (e.notes().stream().noneMatch(n -> n.contains(ComptabiliteEtats.MENTION))) notes.addFirst(mention);
        for (TableauExport t : e.sections()) t.filtres(t.getFiltres().replace(ComptabiliteEtats.MENTION, mention));
        return new Etat(e.code(), e.titre(), e.parametres(), notes, e.sections(), e.format(), e.base(), e.du(), e.au());
    }
}
