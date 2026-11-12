package org.example.stock.service;

import jakarta.validation.Validator;
import org.example.stock.enums.TypeOperationTresorerie;
import org.example.stock.enums.TypePartenaireFinancier;
import org.example.stock.form.OperationFinanciereForm;
import org.example.stock.model.CompteTresorerieBoutique;
import org.example.stock.model.Montants;
import org.example.stock.model.MouvementCaisse;
import org.example.stock.model.OperationTresorerie;
import org.example.stock.model.PartenaireFinancier;
import org.example.stock.model.SourceMouvement;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.MouvementCaisseRepository;
import org.example.stock.repository.OperationTresorerieRepository;
import org.example.stock.repository.StockLockRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Apports et retraits des associés (en capital ou en compte courant) et produits et charges hors activité courante, réservés au gérant.
 * Chaque opération est un en-tête {@link OperationTresorerie} (avec le jeton qui évite le double enregistrement) et un mouvement sur le
 * compte choisi ; l'annulation est une contre-passation du mouvement.
 *
 * <p>Ordre des verrous : boutique, partenaire, compte (voir « Ordre des verrous » dans {@code docs/lot-2.md}).</p>
 */
@Service
public class OperationsFinancieresService {
    public static final Set<TypeOperationTresorerie> TYPES_SAISISSABLES = EnumSet.of(
            TypeOperationTresorerie.APPORT_CAPITAL, TypeOperationTresorerie.APPORT_COMPTE_COURANT,
            TypeOperationTresorerie.RETRAIT_CAPITAL, TypeOperationTresorerie.RETRAIT_COMPTE_COURANT,
            TypeOperationTresorerie.PRODUIT_FINANCIER, TypeOperationTresorerie.CHARGE_FINANCIERE,
            TypeOperationTresorerie.AUTRE_PRODUIT, TypeOperationTresorerie.AUTRE_CHARGE);

    private final CaisseService caisse;
    private final StockLockRepository verrous;
    private final ComptesTresorerieService comptes;
    private final BoutiqueService boutiques;
    private final InitialisationBoutiqueService initialisation;
    private final OperationTresorerieRepository operations;
    private final MouvementCaisseRepository mouvements;
    private final EcrituresFinancieres ecritures;
    private final EcheanceService horloge;
    private final Validator validator;

    public OperationsFinancieresService(CaisseService caisse, StockLockRepository verrous, ComptesTresorerieService comptes, BoutiqueService boutiques,
                                        InitialisationBoutiqueService initialisation, OperationTresorerieRepository operations,
                                        MouvementCaisseRepository mouvements, EcrituresFinancieres ecritures, EcheanceService horloge, Validator validator) {
        this.caisse = caisse; this.verrous = verrous; this.comptes = comptes; this.boutiques = boutiques; this.initialisation = initialisation;
        this.operations = operations; this.mouvements = mouvements; this.ecritures = ecritures; this.horloge = horloge; this.validator = validator;
    }

    /** Source du mouvement et sens de l'opération (vrai pour une entrée de trésorerie). */
    static String source(TypeOperationTresorerie type) {
        return switch (type) {
            case APPORT_CAPITAL -> SourceMouvement.APPORT_CAPITAL;
            case APPORT_COMPTE_COURANT -> SourceMouvement.APPORT_COMPTE_COURANT;
            case RETRAIT_CAPITAL -> SourceMouvement.RETRAIT_CAPITAL;
            case RETRAIT_COMPTE_COURANT -> SourceMouvement.RETRAIT_COMPTE_COURANT;
            case PRODUIT_FINANCIER -> SourceMouvement.PRODUIT_FINANCIER;
            case CHARGE_FINANCIERE -> SourceMouvement.CHARGE_FINANCIERE;
            case AUTRE_PRODUIT -> SourceMouvement.AUTRE_PRODUIT;
            case AUTRE_CHARGE -> SourceMouvement.AUTRE_CHARGE;
            default -> throw new IllegalArgumentException("Cette nature d'opération ne se saisit pas ici.");
        };
    }

    static boolean entree(TypeOperationTresorerie type) {
        return type == TypeOperationTresorerie.APPORT_CAPITAL || type == TypeOperationTresorerie.APPORT_COMPTE_COURANT
                || type == TypeOperationTresorerie.PRODUIT_FINANCIER || type == TypeOperationTresorerie.AUTRE_PRODUIT;
    }

    /** Enregistre l'opération ; retourne vrai pour la répétition exacte d'un formulaire déjà traité. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean enregistrer(OperationFinanciereForm formulaire) {
        verrous.verrouillerBoutiqueCourante();
        Utilisateur acteur = caisse.gerantPourEcriture();
        if (formulaire == null || !validator.validate(formulaire).isEmpty()) {
            throw new IllegalArgumentException("La nature, le compte, le montant et le motif de l'opération doivent être valides.");
        }
        TypeOperationTresorerie type = formulaire.getType();
        if (!TYPES_SAISISSABLES.contains(type)) throw new IllegalArgumentException("Cette nature d'opération ne se saisit pas ici.");
        Long boutiqueId = boutiques.idBoutiqueCourante();
        Long compteId = comptes.resoudreId(formulaire.getCompte());
        BigDecimal montant = Montants.positifOuNul(formulaire.getMontant(), "Le montant");
        if (montant.signum() <= 0) throw new IllegalArgumentException("Le montant doit être supérieur à zéro.");
        boolean apportOuRetrait = type.estApportOuRetrait();
        if (apportOuRetrait && formulaire.getPartenaireId() == null) throw new IllegalArgumentException("Choisissez l'associé concerné.");
        if (ecritures.repetition(formulaire.getJetonOperation(), boutiqueId, acteur.getId(), type, compteId, montant, formulaire.getMotif(),
                formulaire.getPartenaireId(), null)) return true;
        initialisation.verrouillerEtClorePourActivite();
        PartenaireFinancier partenaire = null;
        if (formulaire.getPartenaireId() != null) {
            partenaire = verrous.verrouillerPartenaireFinancier(formulaire.getPartenaireId());
            if (!partenaire.isActif()) throw new IllegalArgumentException("Ce partenaire est archivé : réactivez-le avant d'enregistrer une opération.");
            if (apportOuRetrait && partenaire.getType() != TypePartenaireFinancier.ASSOCIE) {
                throw new IllegalArgumentException("Un apport ou un retrait se rattache à un partenaire de type Associé.");
            }
        }
        CompteTresorerieBoutique compte = comptes.verrouiller(boutiqueId, acteur, false, true, List.of(compteId)).get(compteId);
        if (ecritures.repetition(formulaire.getJetonOperation(), boutiqueId, acteur.getId(), type, compteId, montant, formulaire.getMotif(),
                formulaire.getPartenaireId(), null)) return true;
        boolean entree = entree(type);
        if (!entree) ecritures.exigerSolde(boutiqueId, compte, montant);
        OperationTresorerie operation = ecritures.operation(boutiques.boutiqueCourante(), acteur, formulaire.getJetonOperation(), type, compte, montant,
                formulaire.getMotif(), horloge.maintenant(), partenaire, null);
        ecritures.mouvement(operation, compte, entree ? montant : montant.negate(), source(type), formulaire.getMotif());
        mouvements.flush();
        return false;
    }

    /**
     * Annule un apport, un retrait, un produit ou une charge par contre-passation de son mouvement, avec un motif de 3 à 255 caractères.
     * Un déblocage, des frais de dossier ou un remboursement ne s'annulent pas ici (voir {@link EmpruntService}).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void annuler(Long operationId, String motifSaisi) {
        verrous.verrouillerBoutiqueCourante();
        Utilisateur acteur = caisse.gerantPourEcriture();
        String motif = motifSaisi == null ? "" : motifSaisi.strip();
        if (motif.length() < AnnulationReglementService.MOTIF_MINIMUM || motif.length() > AnnulationReglementService.MOTIF_MAXIMUM) {
            throw new IllegalArgumentException("Le motif de l'annulation doit comporter de 3 à 255 caractères.");
        }
        Long boutiqueId = boutiques.idBoutiqueCourante();
        OperationTresorerie operation = operations.findByIdAndBoutiqueId(operationId, boutiqueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Opération introuvable"));
        if (!TYPES_SAISISSABLES.contains(operation.getType())) {
            throw new IllegalArgumentException("Cette opération ne s'annule pas ici : un transfert, une dépense, un déblocage ou un remboursement a son propre écran.");
        }
        if (operation.getPartenaireFinancier() != null) verrous.verrouillerPartenaireFinancier(operation.getPartenaireFinancier().getId());
        List<MouvementCaisse> origines = mouvements.findByOperationTresorerieIdOrderByIdAsc(operation.getId());
        if (origines.stream().anyMatch(m -> SourceMouvement.ANNULATION.equals(m.getSource()))) throw new AnnulationDejaFaiteException("Cette opération a déjà été annulée. Rien n'a été enregistré une seconde fois : vérifiez le journal de caisse.");
        for (MouvementCaisse origine : origines) caisse.enregistrerAnnulation(origine, motif, acteur);
    }
}
