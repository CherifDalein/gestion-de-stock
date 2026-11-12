package org.example.stock.service;

import org.example.stock.enums.TypeOperationTresorerie;
import org.example.stock.model.Boutique;
import org.example.stock.model.CompteTresorerieBoutique;
import org.example.stock.model.Montants;
import org.example.stock.model.MouvementCaisse;
import org.example.stock.model.OperationTresorerie;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.MouvementCaisseRepository;
import org.example.stock.repository.OperationTresorerieRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Écriture commune des opérations de financement : un en-tête ({@link OperationTresorerie}, qui porte le jeton d'idempotence) et un ou
 * deux mouvements sur le compte concerné. Aucun solde n'est stocké : le solde d'un compte reste la somme de ses mouvements.
 * À appeler dans la transaction d'un service de financement, après les verrous de la boutique, du document, du partenaire et des comptes.
 */
@Component
class EcrituresFinancieres {
    private final OperationTresorerieRepository operations;
    private final MouvementCaisseRepository mouvements;
    private final EcheanceService horloge;

    EcrituresFinancieres(OperationTresorerieRepository operations, MouvementCaisseRepository mouvements, EcheanceService horloge) {
        this.operations = operations;
        this.mouvements = mouvements;
        this.horloge = horloge;
    }

    /** Un mouvement du jour est daté de l'instant courant ; un mouvement d'un autre jour, de midi de ce jour. */
    LocalDateTime dateDe(LocalDate jour) {
        return jour == null || jour.equals(horloge.aujourdHui()) ? horloge.maintenant() : jour.atTime(12, 0);
    }

    OperationTresorerie operation(Boutique boutique, Utilisateur acteur, String jeton, TypeOperationTresorerie type, CompteTresorerieBoutique compte,
                                  BigDecimal montant, String motif, LocalDateTime date, org.example.stock.model.PartenaireFinancier partenaire,
                                  org.example.stock.model.Emprunt emprunt) {
        OperationTresorerie operation = new OperationTresorerie();
        operation.setBoutique(boutique); operation.setUtilisateur(acteur); operation.setJeton(jeton); operation.setType(type);
        operation.setCompteSource(compte); operation.setMontant(montant); operation.setMotif(motif); operation.setDateOperation(date);
        operation.setPartenaireFinancier(partenaire); operation.setEmprunt(emprunt);
        return operations.saveAndFlush(operation);
    }

    /** Mouvement d'une opération : {@code montantSigne} positif pour une entrée, négatif pour une sortie ; un montant nul n'écrit rien. */
    void mouvement(OperationTresorerie operation, CompteTresorerieBoutique compte, BigDecimal montantSigne, String source, String motif) {
        if (montantSigne.signum() == 0) return;
        MouvementCaisse mouvement = new MouvementCaisse();
        mouvement.setBoutique(operation.getBoutique()); mouvement.setUtilisateur(operation.getUtilisateur());
        mouvement.setCompte(compte); mouvement.setMontant(montantSigne); mouvement.setType(montantSigne.signum() > 0 ? "ENTREE" : "SORTIE");
        mouvement.setSource(source); mouvement.setMotif(motif); mouvement.setDateMouvement(operation.getDateOperation());
        mouvement.setOperationTresorerie(operation);
        mouvements.save(mouvement);
    }

    /** Même règle qu'une dépense ou un transfert : le compte doit disposer de la somme (lecture après le verrou du compte). */
    void exigerSolde(Long boutiqueId, CompteTresorerieBoutique compte, BigDecimal sortie) {
        BigDecimal solde = Montants.ouZero(mouvements.calculerSoldeCompte(boutiqueId, compte.getId()));
        if (solde.compareTo(sortie) < 0) throw new IllegalArgumentException("Solde insuffisant sur le compte " + compte.getNom() + ".");
    }

    /** Répétition exacte d'un formulaire déjà traité : vraie si tout concorde, conflit si le jeton a servi à autre chose. */
    boolean repetition(String jeton, Long boutiqueId, Long acteurId, TypeOperationTresorerie type, Long compteId, BigDecimal montant, String motif,
                       Long partenaireId, Long empruntId) {
        OperationTresorerie precedent = operations.findByJeton(jeton).orElse(null);
        if (precedent == null) return false;
        if (!Objects.equals(precedent.getBoutique().getId(), boutiqueId) || !Objects.equals(precedent.getUtilisateur().getId(), acteurId)
                || precedent.getType() != type || !Objects.equals(precedent.getCompteSource().getId(), compteId)
                || precedent.getMontant().compareTo(montant) != 0 || !Objects.equals(precedent.getMotif(), motif)
                || !Objects.equals(precedent.getPartenaireFinancier() == null ? null : precedent.getPartenaireFinancier().getId(), partenaireId)
                || !Objects.equals(precedent.getEmprunt() == null ? null : precedent.getEmprunt().getId(), empruntId)) {
            throw new OperationTresorerieConflitException();
        }
        return true;
    }
}
