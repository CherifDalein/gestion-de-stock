package org.example.stock.compta;

import org.example.stock.enums.StatutExercice;
import org.example.stock.model.ExerciceComptable;
import org.example.stock.repository.ExerciceComptableRepository;
import org.example.stock.service.BoutiqueService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

/**
 * Verrou des exercices clôturés (étape 5c) : <b>le seul endroit</b> où l'on décide qu'une date est figée. Tous les services qui créent, modifient ou
 * annulent une opération l'appellent <b>sous le verrou de la boutique</b>, avec la date de l'opération (ou de l'opération annulée).
 *
 * <p>Les exercices clôturés se suivent depuis le premier (on ne clôture un exercice que si le précédent l'est), donc une date est figée si et seulement si
 * elle n'est pas postérieure à la fin du dernier exercice clôturé. Ordre des verrous : la boutique d'abord, puis la ligne du dernier exercice clôturé (lecture
 * partagée, prise ici), puis le document, le partenaire, les produits et les comptes. La clôture et son annulation prennent la boutique, puis l'exercice,
 * en écriture : aucune écriture ne se glisse pendant qu'elles travaillent, et une écriture qui attendait relit l'état validé.</p>
 */
@Service
public class VerrouExercice {
    private final ExerciceComptableRepository exercices;
    private final BoutiqueService boutiques;

    public VerrouExercice(ExerciceComptableRepository exercices, BoutiqueService boutiques) {
        this.exercices = exercices;
        this.boutiques = boutiques;
    }

    /** Refuse une opération datée de {@code date} si cette date est dans un exercice clôturé de la boutique courante. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void exigerOuvert(LocalDateTime date) {
        if (date != null) exigerOuvert(date.toLocalDate());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void exigerOuvert(LocalDate date) {
        if (date == null) return;
        exigerOuvert(boutiques.idBoutiqueCourante(), date);
    }

    /** Refuse si l'une des dates est figée. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void exigerOuverts(Collection<LocalDateTime> dates) {
        if (dates == null) return;
        LocalDate plusAncienne = null;
        for (LocalDateTime d : dates) if (d != null && (plusAncienne == null || d.toLocalDate().isBefore(plusAncienne))) plusAncienne = d.toLocalDate();
        // La date la plus ancienne est la plus exposée : une seule vérification suffit.
        exigerOuvert(plusAncienne);
    }

    /** Variante pour un traitement sans compte connecté (tâche planifiée) : la boutique est donnée. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void exigerOuvert(Long boutiqueId, LocalDate date) {
        Optional<ExerciceComptable> dernier = exercices.findFirstByBoutiqueIdAndStatutOrderByDateFinDesc(boutiqueId, StatutExercice.CLOTURE);
        if (dernier.isPresent() && !date.isAfter(dernier.get().getDateFin())) throw new ExerciceClotureException(libelleDe(boutiqueId, date, dernier.get()));
    }

    /** Vrai si la date est dans un exercice clôturé (sans verrou : pour l'affichage). */
    @Transactional(readOnly = true)
    public boolean estCloture(Long boutiqueId, LocalDate date) {
        LocalDate limite = exercices.derniereDateCloturee(boutiqueId);
        return limite != null && date != null && !date.isAfter(limite);
    }

    private String libelleDe(Long boutiqueId, LocalDate date, ExerciceComptable dernier) {
        for (ExerciceComptable e : exercices.findByBoutiqueIdAndStatutOrderByDateDebut(boutiqueId, StatutExercice.CLOTURE))
            if (!date.isBefore(e.getDateDebut()) && !date.isAfter(e.getDateFin())) return ExercicesService.libelle(e.getDateDebut(), e.getDateFin());
        // Date antérieure au premier exercice : elle appartient au premier.
        var premier = exercices.findByBoutiqueIdAndStatutOrderByDateDebut(boutiqueId, StatutExercice.CLOTURE).stream().findFirst().orElse(dernier);
        return ExercicesService.libelle(premier.getDateDebut(), premier.getDateFin());
    }
}
