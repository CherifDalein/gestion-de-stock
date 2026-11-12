package org.example.stock.service;

import org.example.stock.enums.Role;
import org.example.stock.model.Achat;
import org.example.stock.model.Emails;
import org.example.stock.model.Montants;
import org.example.stock.model.MouvementCaisse;
import org.example.stock.model.SourceMouvement;
import org.example.stock.model.Utilisateur;
import org.example.stock.model.Vente;
import org.example.stock.repository.MouvementCaisseRepository;
import org.example.stock.repository.RetourRepository;
import org.example.stock.repository.SoldePartenaireRepository.Partenaire;
import org.example.stock.repository.StockLockRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Annulation d'un règlement, réservée au gérant (ADMIN).
 *
 * <p><b>Ce qui s'annule</b> : un règlement ultérieur d'une vente ou d'un achat (source {@code REGLEMENT_VENTE} ou
 * {@code REGLEMENT_ACHAT}, fait depuis la page de règlement) et un règlement de solde de départ d'un client ou d'un
 * fournisseur ({@code SOLDE_CLIENT}, {@code SOLDE_FOURNISSEUR}). Le paiement saisi à la création d'une facture (source
 * {@code VENTE} ou {@code ACHAT}, qui se corrige par un retour), les dépenses, transferts, retours, fonds de départ,
 * un mouvement déjà annulé et une annulation elle-même ne s'annulent pas.</p>
 *
 * <p><b>Effets</b> : un mouvement inverse est créé sur le même compte ({@link CaisseService#enregistrerAnnulation}) ; le
 * mouvement d'origine n'est jamais modifié. Pour une facture, {@code montant_verse} diminue du montant annulé (le reste à
 * payer, le retard et les créances suivent) et une échéance est proposée si la facture n'en avait plus ; pour un solde de
 * départ, le reste du solde se rétablit par la somme des règlements, nette des annulations.</p>
 *
 * <p><b>Ordre des verrous</b> (docs/lot-2.md, « Ordre des verrous ») : boutique, document (vente, achat ou partenaire du
 * solde), partenaire, lignes lues pour le calcul, comptes de trésorerie. La transaction est en READ COMMITTED, comme les
 * retours : les lectures voient les opérations validées pendant l'attente des verrous.</p>
 */
@Service
public class AnnulationReglementService {
    public static final int MOTIF_MINIMUM = 3;
    public static final int MOTIF_MAXIMUM = 255;

    /** Ce que l'on annule : la source du règlement attendue et, pour les factures, le document verrouillé. */
    public enum Cible {
        VENTE(SourceMouvement.REGLEMENT_VENTE), ACHAT(SourceMouvement.REGLEMENT_ACHAT),
        SOLDE_CLIENT(SourceMouvement.SOLDE_CLIENT), SOLDE_FOURNISSEUR(SourceMouvement.SOLDE_FOURNISSEUR);

        final String source;
        Cible(String source) { this.source = source; }
    }

    /**
     * Ce que la page de confirmation montre : le mouvement, et pourquoi il ne peut pas être annulé le cas échéant.
     * {@code annulation} est l'annulation déjà enregistrée, si le règlement a déjà été annulé.
     */
    public record Confirmation(MouvementCaisse mouvement, String refus, MouvementCaisse annulation) {
        public boolean annulable() { return refus == null; }
        public boolean dejaAnnule() { return annulation != null; }
    }

    private final MouvementCaisseRepository mouvements;
    private final RetourRepository retours;
    private final StockLockRepository verrous;
    private final CaisseService caisse;
    private final BoutiqueService boutiques;
    private final UtilisateurRepository utilisateurs;
    private final EcheanceService echeances;
    private final SoldePartenaireService soldes;

    public AnnulationReglementService(MouvementCaisseRepository mouvements, RetourRepository retours, StockLockRepository verrous,
                                      CaisseService caisse, BoutiqueService boutiques, UtilisateurRepository utilisateurs,
                                      EcheanceService echeances, SoldePartenaireService soldes) {
        this.mouvements = mouvements;
        this.retours = retours;
        this.verrous = verrous;
        this.caisse = caisse;
        this.boutiques = boutiques;
        this.utilisateurs = utilisateurs;
        this.echeances = echeances;
        this.soldes = soldes;
    }

    // ---- Page de confirmation -------------------------------------------------------------------------------

    /**
     * Lit le mouvement pour la page de confirmation (gérant seulement). Un mouvement d'une autre boutique, ou d'un autre
     * document que celui de l'adresse, est introuvable (404) ; un mouvement qui ne peut pas être annulé est rendu avec son refus.
     */
    @Transactional(readOnly = true)
    public Confirmation preparer(Cible cible, Long documentId, Long mouvementId) {
        exigerGerantDeLaSession();
        MouvementCaisse mouvement = lire(mouvementId);
        MouvementCaisse annulation = mouvements.findAnnulationsDe(boutiques.idBoutiqueCourante(), List.of(mouvementId)).stream().findFirst().orElse(null);
        String refus = refusDeSource(mouvement);
        if (refus == null && annulation != null) refus = new AnnulationDejaFaiteException().getMessage();
        // Un règlement (annulé ou non) doit appartenir au document de l'adresse ; un autre mouvement est seulement refusé.
        if (SourceMouvement.estAnnulable(mouvement.getSource())) exigerLiaison(cible, mouvement, documentId);
        return new Confirmation(mouvement, refus, annulation);
    }

    // ---- Annulations ----------------------------------------------------------------------------------------

    /** Annule un règlement ultérieur d'une vente (encaissement) : sortie de trésorerie, le reste à payer remonte. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void annulerReglementVente(Long venteId, Long mouvementId, String motif) {
        annuler(Cible.VENTE, venteId, mouvementId, motif);
    }

    /** Annule un règlement ultérieur d'un achat (paiement) : entrée de trésorerie, le reste à payer remonte. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void annulerReglementAchat(Long achatId, Long mouvementId, String motif) {
        annuler(Cible.ACHAT, achatId, mouvementId, motif);
    }

    /** Annule un règlement du solde de départ d'un client : sortie de trésorerie, le reste du solde est rétabli. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void annulerReglementSoldeClient(Long clientId, Long mouvementId, String motif) {
        annuler(Cible.SOLDE_CLIENT, clientId, mouvementId, motif);
    }

    /** Annule un règlement du solde de départ d'un fournisseur : entrée de trésorerie, le reste du solde est rétabli. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void annulerReglementSoldeFournisseur(Long fournisseurId, Long mouvementId, String motif) {
        annuler(Cible.SOLDE_FOURNISSEUR, fournisseurId, mouvementId, motif);
    }

    private void annuler(Cible cible, Long documentId, Long mouvementId, String motifSaisi) {
        exigerGerantDeLaSession();
        String motif = motifValide(motifSaisi);
        // Ordre global des verrous : la boutique d'abord, avant toute lecture du mouvement ou du document.
        verrous.verrouillerBoutiqueCourante();
        Utilisateur acteur = gerant();
        MouvementCaisse origine = lire(mouvementId);
        String refus = refusDeSource(origine);
        if (refus != null) throw new IllegalArgumentException(refus);
        exigerNonAnnule(origine);
        exigerLiaison(cible, origine, documentId);

        switch (cible) {
            case VENTE -> {
                Vente vente = verrous.verrouillerVente(documentId);
                if (vente.getClient() != null) verrous.verrouillerClient(vente.getClient().getId());
                exigerNonAnnule(origine);
                refuserSiRetourPosterieur(retours.numerosDepuisVente(documentId, dateDe(origine)));
                BigDecimal montant = montantAnnule(origine, true);
                vente.setMontantVerse(retirer(vente.getMontantVerse(), montant, "Les montants de cette vente sont incohérents : le montant versé deviendrait négatif."));
                vente.setDateEcheance(echeances.apresChangement(vente.getDateVente(), vente.getResteAPayer(), null, vente.getDateEcheance(),
                        boutiques.boutiqueCourante().getDelaiClientsJours()));
            }
            case ACHAT -> {
                Achat achat = verrous.verrouillerAchat(documentId);
                if (achat.getFournisseur() != null) verrous.verrouillerFournisseur(achat.getFournisseur().getId());
                exigerNonAnnule(origine);
                refuserSiRetourPosterieur(retours.numerosDepuisAchat(documentId, dateDe(origine)));
                BigDecimal montant = montantAnnule(origine, false);
                achat.setMontantVerse(retirer(achat.getMontantVerse(), montant, "Les montants de cet achat sont incohérents : le montant versé deviendrait négatif."));
                achat.setDateEcheance(echeances.apresChangement(achat.getDateAchat(), achat.getResteAPayer(), null, achat.getDateEcheance(),
                        boutiques.boutiqueCourante().getDelaiFournisseursJours()));
            }
            case SOLDE_CLIENT -> {
                var client = verrous.verrouillerClient(documentId);
                exigerNonAnnule(origine);
                BigDecimal annule = montantAnnule(origine, true);
                verifierSoldeReglable(Partenaire.CLIENT, documentId, annule);
                verifierAvanceApresAnnulation(Partenaire.CLIENT, documentId, client.getSoldeInitial(), annule);
            }
            case SOLDE_FOURNISSEUR -> {
                var fournisseur = verrous.verrouillerFournisseur(documentId);
                exigerNonAnnule(origine);
                BigDecimal annule = montantAnnule(origine, false);
                verifierSoldeReglable(Partenaire.FOURNISSEUR, documentId, annule);
                verifierAvanceApresAnnulation(Partenaire.FOURNISSEUR, documentId, fournisseur.getSoldeInitial(), annule);
            }
        }
        // Mouvement inverse sur le même compte : les comptes sont verrouillés en dernier, et le solde est contrôlé pour une sortie.
        caisse.enregistrerAnnulation(origine, motif, acteur);
    }

    // ---- Règles ---------------------------------------------------------------------------------------------

    /** Message de refus d'après la seule nature du mouvement, ou {@code null} s'il s'agit d'un règlement annulable (pas encore annulé ?). */
    private String refusDeSource(MouvementCaisse mouvement) {
        String source = mouvement.getSource();
        if (SourceMouvement.ANNULATION.equals(source) || mouvement.getAnnulationDe() != null) {
            return "Ce mouvement est déjà une annulation : une annulation ne peut pas être annulée.";
        }
        if (SourceMouvement.VENTE.equals(source) || SourceMouvement.ACHAT.equals(source)) {
            return "Le paiement saisi à la création de la facture ne peut pas être annulé : il se corrige par un retour.";
        }
        if (!SourceMouvement.estAnnulable(source)) {
            return "Seuls les règlements d'une facture et les règlements d'un solde de départ peuvent être annulés. "
                    + "Ce mouvement (dépense, transfert, retour, solde initial ou ajustement) n'est pas un règlement.";
        }
        return null;
    }

    /** Relu sous les verrous : une annulation validée pendant l'attente donne le message « déjà annulé » (409). */
    private void exigerNonAnnule(MouvementCaisse origine) {
        if (mouvements.existsByAnnulationDeId(origine.getId())) throw new AnnulationDejaFaiteException();
    }

    private static void refuserSiRetourPosterieur(List<String> numeros) {
        if (numeros.isEmpty()) return;
        throw new IllegalArgumentException("Ce règlement ne peut pas être annulé : un retour (" + String.join(", ", numeros)
                + ") a été enregistré sur cette facture après lui. L'excédent remboursé ou mis en avoir a pu dépendre de ce paiement.");
    }

    private static java.time.LocalDateTime dateDe(MouvementCaisse mouvement) {
        if (mouvement.getDateMouvement() == null) {
            throw new IllegalArgumentException("Ce règlement n'a pas de date : il ne peut pas être annulé.");
        }
        return mouvement.getDateMouvement();
    }

    /**
     * Montant (positif) du règlement. Un encaissement est une entrée (montant positif), un paiement une sortie (montant
     * négatif) ; un mouvement qui ne respecte pas ce sens est incohérent et n'est pas annulé.
     */
    private static BigDecimal montantAnnule(MouvementCaisse origine, boolean entree) {
        BigDecimal montant = Montants.valider(origine.getMontant(), "Le montant du règlement");
        if (montant.signum() == 0 || (montant.signum() > 0) != entree) {
            throw new IllegalArgumentException("Le sens de ce mouvement ne correspond pas à un règlement : il ne peut pas être annulé.");
        }
        return montant.abs();
    }

    private static BigDecimal retirer(BigDecimal verse, BigDecimal montant, String incoherence) {
        BigDecimal nouveau = Montants.ouZero(verse).subtract(montant);
        if (nouveau.signum() < 0) throw new IllegalArgumentException(incoherence);
        return Montants.valider(nouveau, "Le montant versé");
    }

    /** Les règlements du solde, relus sous verrou, doivent couvrir le montant annulé (sinon la fiche est incohérente). */
    private void verifierSoldeReglable(Partenaire type, Long partenaireId, BigDecimal montant) {
        if (soldes.reglementsVerrouilles(type, partenaireId).net().compareTo(montant) < 0) {
            throw new IncoherenceAvanceException("Les règlements du solde de départ sont inférieurs au montant à annuler : vérifiez la fiche et son historique.");
        }
    }

    /** Un règlement devenu avance (solde ajusté) ne s'annule pas si l'avance a déjà été utilisée sur des factures. */
    private void verifierAvanceApresAnnulation(Partenaire type, Long partenaireId, BigDecimal soldeInitial, BigDecimal montant) {
        if (soldes.avanceApresAnnulationReglement(type, partenaireId, Montants.ouZero(soldeInitial), montant).signum() < 0) {
            throw new IllegalArgumentException("Ce règlement est devenu une avance après un ajustement du solde de départ, et cette avance a déjà été utilisée : il ne peut plus être annulé.");
        }
    }

    /** Le motif est obligatoire : 3 à 255 caractères, sans caractère de contrôle. Les espaces aux bords sont retirés. */
    static String motifValide(String saisi) {
        String motif = saisi == null ? "" : saisi.strip();
        if (motif.length() < MOTIF_MINIMUM || motif.length() > MOTIF_MAXIMUM || motif.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Le motif de l'annulation est obligatoire : " + MOTIF_MINIMUM + " à " + MOTIF_MAXIMUM
                    + " caractères, sans retour à la ligne.");
        }
        return motif;
    }

    // ---- Accès ----------------------------------------------------------------------------------------------

    private MouvementCaisse lire(Long mouvementId) {
        if (mouvementId == null) throw introuvable();
        return mouvements.findByIdAndBoutiqueId(mouvementId, boutiques.idBoutiqueCourante()).orElseThrow(AnnulationReglementService::introuvable);
    }

    /** Le règlement doit appartenir au document (ou partenaire) de l'adresse : sinon 404, sans rien révéler. */
    private static void exigerLiaison(Cible cible, MouvementCaisse mouvement, Long documentId) {
        Long lie = switch (cible) {
            case VENTE -> mouvement.getVente() == null ? null : mouvement.getVente().getId();
            case ACHAT -> mouvement.getAchat() == null ? null : mouvement.getAchat().getId();
            case SOLDE_CLIENT -> mouvement.getClient() == null ? null : mouvement.getClient().getId();
            case SOLDE_FOURNISSEUR -> mouvement.getFournisseur() == null ? null : mouvement.getFournisseur().getId();
        };
        if (!Objects.equals(lie, documentId) || !cible.source.equals(mouvement.getSource())) throw introuvable();
    }

    private static ResponseStatusException introuvable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Règlement introuvable");
    }

    /** Contrôle sans base de données : le caissier est refusé (403) avant toute lecture ou tout verrou. */
    private static void exigerGerantDeLaSession() {
        Authentication authentification = SecurityContextHolder.getContext().getAuthentication();
        if (authentification == null || !authentification.isAuthenticated()
                || authentification.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            throw interdit();
        }
    }

    /** Le compte connecté, relu : actif, gérant et de la boutique courante. */
    private Utilisateur gerant() {
        Authentication authentification = SecurityContextHolder.getContext().getAuthentication();
        Utilisateur compte = utilisateurs.findByEmail(Emails.normaliser(authentification.getName())).orElseThrow(AnnulationReglementService::interdit);
        if (!compte.isActif() || compte.getRole() != Role.ADMIN) throw interdit();
        boutiques.exigerAppartenance(compte.getBoutique());
        return compte;
    }

    private static ResponseStatusException interdit() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "L'annulation d'un règlement est réservée au gérant");
    }
}
