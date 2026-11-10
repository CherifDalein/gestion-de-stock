package org.example.stock.service;

import org.example.stock.enums.CompteTresorerie;
import org.example.stock.enums.SensSolde;
import org.example.stock.model.Client;
import org.example.stock.model.Emails;
import org.example.stock.model.Fournisseur;
import org.example.stock.model.Montants;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.SoldePartenaireRepository.Partenaire;
import org.example.stock.repository.StockLockRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Solde de départ d'un client ou d'un fournisseur : saisie sur la fiche (gérant seulement) et règlement.
 *
 * <p>Le solde est enregistré signé dans {@code solde_initial} (positif : le partenaire me doit ; négatif : je lui dois).
 * Sa saisie ne crée aucun mouvement de trésorerie. Son règlement, lui, entre ou sort en trésorerie, rattaché au partenaire.
 * Ordre des verrous : boutique, fiche du partenaire, lignes lues pour le calcul, comptes de trésorerie (docs/lot-2.md,
 * « Ordre des verrous »).</p>
 */
@Service
public class SoldeDepartService {
    private final StockLockRepository verrous;
    private final SoldePartenaireService soldes;
    private final BoutiqueService boutiques;
    private final CaisseService caisse;
    private final InitialisationBoutiqueService initialisation;
    private final UtilisateurRepository utilisateurs;

    public SoldeDepartService(StockLockRepository verrous, SoldePartenaireService soldes, BoutiqueService boutiques,
                              CaisseService caisse, InitialisationBoutiqueService initialisation, UtilisateurRepository utilisateurs) {
        this.verrous = verrous;
        this.soldes = soldes;
        this.boutiques = boutiques;
        this.caisse = caisse;
        this.initialisation = initialisation;
        this.utilisateurs = utilisateurs;
    }

    /** Résultat d'une saisie : solde signé et échéance (vide si le partenaire ne doit rien). */
    private record Solde(BigDecimal signe, LocalDate echeance) {}

    // ---- Saisie sur la fiche --------------------------------------------------------------------------------

    /** À la création d'une fiche : applique la section « solde de départ » si elle a été soumise. Gérant seulement. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appliquerALaCreation(Client client) {
        if (!client.isSoldeDepartSoumis()) return;
        Solde solde = calculer(Partenaire.CLIENT, null, Montants.ZERO, null, client.getMontantSoldeDepart(), client.getSensSoldeDepart(), client.getEcheanceSoldeInitial(), false);
        client.setSoldeInitial(solde.signe());
        client.setEcheanceSoldeInitial(solde.echeance());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void appliquerALaCreation(Fournisseur fournisseur) {
        if (!fournisseur.isSoldeDepartSoumis()) return;
        Solde solde = calculer(Partenaire.FOURNISSEUR, null, Montants.ZERO, null, fournisseur.getMontantSoldeDepart(), fournisseur.getSensSoldeDepart(), fournisseur.getEcheanceSoldeInitial(), false);
        fournisseur.setSoldeInitial(solde.signe());
        fournisseur.setEcheanceSoldeInitial(solde.echeance());
    }

    /** À la modification : la fiche est verrouillée ; le solde ne change plus une fois réglé ou utilisé, et un changement de montant ou de sens doit être confirmé. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appliquerALaModification(Long id, Client saisie) {
        if (!saisie.isSoldeDepartSoumis()) return;
        exigerGerant();
        verrous.verrouillerBoutiqueCourante();
        Client client = verrous.verrouillerClient(id);
        Solde solde = calculer(Partenaire.CLIENT, id, client.getSoldeInitial(), client.getEcheanceSoldeInitial(),
                saisie.getMontantSoldeDepart(), saisie.getSensSoldeDepart(), saisie.getEcheanceSoldeInitial(), saisie.isConfirmationSoldeDepart());
        client.setSoldeInitial(solde.signe());
        client.setEcheanceSoldeInitial(solde.echeance());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void appliquerALaModification(Long id, Fournisseur saisie) {
        if (!saisie.isSoldeDepartSoumis()) return;
        exigerGerant();
        verrous.verrouillerBoutiqueCourante();
        Fournisseur fournisseur = verrous.verrouillerFournisseur(id);
        Solde solde = calculer(Partenaire.FOURNISSEUR, id, fournisseur.getSoldeInitial(), fournisseur.getEcheanceSoldeInitial(),
                saisie.getMontantSoldeDepart(), saisie.getSensSoldeDepart(), saisie.getEcheanceSoldeInitial(), saisie.isConfirmationSoldeDepart());
        fournisseur.setSoldeInitial(solde.signe());
        fournisseur.setEcheanceSoldeInitial(solde.echeance());
    }

    private Solde calculer(Partenaire type, Long id, BigDecimal soldeActuel, LocalDate echeanceActuelle,
                           BigDecimal montantSaisi, SensSolde sens, LocalDate echeanceSaisie, boolean confirme) {
        exigerGerant();
        BigDecimal montant = montantSaisi == null ? Montants.ZERO : Montants.positifOuNul(montantSaisi, "Le solde de départ");
        if (montant.signum() > 0 && sens == null) {
            throw new IllegalArgumentException("Choisissez le sens du solde de départ : « il me doit » ou « je lui dois ».");
        }
        BigDecimal signe = montant.signum() == 0 ? Montants.ZERO : sens == SensSolde.JE_LUI_DOIS ? montant.negate() : montant;
        // Le client doit quand le solde est positif, la boutique quand il est négatif (fournisseur) : seule cette dette a une échéance.
        boolean dette = type == Partenaire.CLIENT ? signe.signum() > 0 : signe.signum() < 0;
        LocalDate echeance = dette ? echeanceSaisie : null;
        EcheanceService.exigerValide(echeance, null);
        if (id != null) {
            BigDecimal actuel = Montants.ouZero(soldeActuel);
            boolean change = signe.compareTo(actuel) != 0 || !Objects.equals(echeance, echeanceActuelle);
            // Bloqué après usage : un règlement du solde ou une avance consommée par une facture (quelle qu'en soit l'origine) le figent.
            if (change && (soldes.reglementsVerrouilles(type, id).brut().signum() > 0 || soldes.avanceUtiliseeVerrouillee(type, id))) {
                throw new IllegalArgumentException("Ce solde de départ a déjà servi (règlement ou avance utilisée) : il ne peut plus être modifié. "
                        + "Une correction se fera par un ajustement de solde.");
            }
            if (signe.compareTo(actuel) != 0 && !confirme) {
                throw new IllegalArgumentException("Confirmez le changement du solde de départ.");
            }
            if (signe.compareTo(actuel) != 0 && soldes.avanceDisponibleAvecSolde(type, id, signe).signum() < 0) {
                throw new IllegalArgumentException("Une partie de l'avance de ce partenaire a déjà été utilisée sur ses factures : "
                        + "le nouveau solde de départ la rendrait négative.");
            }
        }
        return new Solde(signe, echeance);
    }

    // ---- Règlement du solde de départ -----------------------------------------------------------------------

    /** Un client règle (en partie) son solde de départ : entrée de trésorerie rattachée au client. Retourne l'identifiant du mouvement (son reçu). */
    @Transactional
    public Long reglerClient(Long clientId, BigDecimal montant, BigDecimal reglementAttendu, CompteTresorerie compte, LocalDate nouvelleEcheance) {
        montant = controlerSaisie(montant, reglementAttendu, compte);
        verrous.verrouillerBoutiqueCourante();
        initialisation.verrouillerEtClorePourActivite();
        Client client = verrous.verrouillerClient(clientId);
        BigDecimal regle = soldes.reglementsVerrouilles(Partenaire.CLIENT, clientId).net();
        BigDecimal reste = reste(Montants.ouZero(client.getSoldeInitial()).max(BigDecimal.ZERO), regle);
        controlerMontant(montant, reglementAttendu, regle, reste);
        if (nouvelleEcheance != null && reste.subtract(montant).signum() > 0) {
            EcheanceService.exigerValide(nouvelleEcheance, null);
            client.setEcheanceSoldeInitial(nouvelleEcheance);
        }
        return caisse.enregistrerEntreeSoldeClient(montant, "Règlement du solde de départ - Client: " + client.getNom(), client, utilisateurCourant(), compte).getId();
    }

    /** La boutique paie (en partie) son solde de départ à un fournisseur : sortie de trésorerie rattachée au fournisseur. Retourne l'identifiant du mouvement (son reçu). */
    @Transactional
    public Long reglerFournisseur(Long fournisseurId, BigDecimal montant, BigDecimal reglementAttendu, CompteTresorerie compte, LocalDate nouvelleEcheance) {
        montant = controlerSaisie(montant, reglementAttendu, compte);
        verrous.verrouillerBoutiqueCourante();
        initialisation.verrouillerEtClorePourActivite();
        Fournisseur fournisseur = verrous.verrouillerFournisseur(fournisseurId);
        BigDecimal regle = soldes.reglementsVerrouilles(Partenaire.FOURNISSEUR, fournisseurId).net();
        BigDecimal reste = reste(Montants.ouZero(fournisseur.getSoldeInitial()).negate().max(BigDecimal.ZERO), regle);
        controlerMontant(montant, reglementAttendu, regle, reste);
        if (nouvelleEcheance != null && reste.subtract(montant).signum() > 0) {
            EcheanceService.exigerValide(nouvelleEcheance, null);
            fournisseur.setEcheanceSoldeInitial(nouvelleEcheance);
        }
        return caisse.enregistrerSortieSoldeFournisseur(montant, "Règlement du solde de départ - Fournisseur: " + fournisseur.getNom(), fournisseur, utilisateurCourant(), compte).getId();
    }

    private BigDecimal controlerSaisie(BigDecimal montant, BigDecimal reglementAttendu, CompteTresorerie compte) {
        if (compte == null) throw new IllegalArgumentException("Choisissez le compte de trésorerie du règlement.");
        montant = Montants.positifOuNul(montant, "Le règlement");
        if (montant.signum() == 0) throw new IllegalArgumentException("Le règlement doit être supérieur à zéro.");
        if (reglementAttendu == null || reglementAttendu.signum() < 0) throw new IllegalArgumentException("Rechargez le formulaire de règlement.");
        return montant;
    }

    private static BigDecimal reste(BigDecimal du, BigDecimal regle) {
        BigDecimal reste = du.subtract(regle);
        if (reste.signum() < 0) {
            throw new IncoherenceAvanceException("Les règlements du solde de départ dépassent le solde : vérifiez la fiche et son historique.");
        }
        return reste;
    }

    private static void controlerMontant(BigDecimal montant, BigDecimal reglementAttendu, BigDecimal regle, BigDecimal reste) {
        if (reglementAttendu.compareTo(regle) != 0) throw new ReglementSoldeObsoleteException();
        if (reste.signum() == 0) throw new IllegalArgumentException("Ce solde de départ est déjà entièrement réglé.");
        if (montant.compareTo(reste) > 0) throw new IllegalArgumentException("Le règlement ne peut pas dépasser le reste du solde de départ.");
    }

    private Utilisateur utilisateurCourant() {
        Authentication authentification = SecurityContextHolder.getContext().getAuthentication();
        return utilisateurs.findByEmail(Emails.normaliser(authentification.getName()))
                .orElseThrow(() -> new IllegalArgumentException("Utilisateur introuvable pour enregistrer le règlement."));
    }

    /** La saisie et la modification d'un solde de départ sont réservées au gérant, comme les autres données financières. */
    static void exigerGerant() {
        Authentication authentification = SecurityContextHolder.getContext().getAuthentication();
        if (authentification == null || !authentification.isAuthenticated()
                || authentification.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Le solde de départ est réservé au gérant");
        }
    }
}
