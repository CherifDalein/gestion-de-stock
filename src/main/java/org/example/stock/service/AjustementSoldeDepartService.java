package org.example.stock.service;

import org.example.stock.enums.SensAjustement;
import org.example.stock.model.AjustementSoldeDepart;
import org.example.stock.model.Client;
import org.example.stock.model.Emails;
import org.example.stock.model.Fournisseur;
import org.example.stock.model.Montants;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.AjustementSoldeDepartRepository;
import org.example.stock.repository.SoldePartenaireRepository.Partenaire;
import org.example.stock.repository.StockLockRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Ajustement du solde de départ d'un client ou d'un fournisseur déjà utilisé (gérant seulement).
 *
 * <p>Règle (docs/lot-3.md, « Contrôles ») : le nouveau solde remplace {@code solde_initial} ; les règlements passés ne sont ni
 * modifiés ni supprimés. Le reste à régler devient max(0, dû − réglé) et, si le réglé dépasse le nouveau dû, l'excédent est une avance
 * (client : avance du client ; fournisseur : avance de la boutique chez lui). Aucun mouvement de trésorerie n'est créé. Le changement
 * est refusé si l'avance de ce partenaire deviendrait négative (avance déjà consommée par des factures).</p>
 *
 * <p>Ordre des verrous : boutique, fiche du partenaire, puis lignes lues pour le calcul (docs/lot-2.md, « Ordre des verrous »).</p>
 */
@Service
public class AjustementSoldeDepartService {
    private final StockLockRepository verrous;
    private final SoldePartenaireService soldes;
    private final AjustementSoldeDepartRepository ajustements;
    private final BoutiqueService boutiques;
    private final UtilisateurRepository utilisateurs;

    public AjustementSoldeDepartService(StockLockRepository verrous, SoldePartenaireService soldes, AjustementSoldeDepartRepository ajustements,
                                        BoutiqueService boutiques, UtilisateurRepository utilisateurs) {
        this.verrous = verrous;
        this.soldes = soldes;
        this.ajustements = ajustements;
        this.boutiques = boutiques;
        this.utilisateurs = utilisateurs;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AjustementSoldeDepart ajusterClient(Long clientId, BigDecimal montant, SensAjustement sens, String motif, String jeton) {
        SoldeDepartService.exigerGerant();
        verrous.verrouillerBoutiqueCourante();
        Client client = verrous.verrouillerClient(clientId);
        AjustementSoldeDepart deja = dejaEnregistre(jeton, client.getId(), true);
        if (deja != null) return deja;
        BigDecimal nouveau = controler(Partenaire.CLIENT, clientId, client.getSoldeInitial(), montant, sens, motif, jeton);
        AjustementSoldeDepart ajustement = nouveau(jeton, motif, client.getSoldeInitial(), nouveau);
        ajustement.setClient(client);
        client.setSoldeInitial(nouveau);
        if (nouveau.signum() <= 0) client.setEcheanceSoldeInitial(null);
        return ajustements.save(ajustement);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AjustementSoldeDepart ajusterFournisseur(Long fournisseurId, BigDecimal montant, SensAjustement sens, String motif, String jeton) {
        SoldeDepartService.exigerGerant();
        verrous.verrouillerBoutiqueCourante();
        Fournisseur fournisseur = verrous.verrouillerFournisseur(fournisseurId);
        AjustementSoldeDepart deja = dejaEnregistre(jeton, fournisseur.getId(), false);
        if (deja != null) return deja;
        BigDecimal nouveau = controler(Partenaire.FOURNISSEUR, fournisseurId, fournisseur.getSoldeInitial(), montant, sens, motif, jeton);
        AjustementSoldeDepart ajustement = nouveau(jeton, motif, fournisseur.getSoldeInitial(), nouveau);
        ajustement.setFournisseur(fournisseur);
        fournisseur.setSoldeInitial(nouveau);
        if (nouveau.signum() >= 0) fournisseur.setEcheanceSoldeInitial(null);
        return ajustements.save(ajustement);
    }

    /** Historique d'un client de la boutique courante, du plus ancien au plus récent (gérant seulement). */
    @Transactional(readOnly = true)
    public List<AjustementSoldeDepart> historiqueClient(Long clientId) {
        return ajustements.findByBoutiqueIdAndClientIdOrderByDateAjustementAscIdAsc(boutiques.idBoutiqueCourante(), clientId);
    }

    @Transactional(readOnly = true)
    public List<AjustementSoldeDepart> historiqueFournisseur(Long fournisseurId) {
        return ajustements.findByBoutiqueIdAndFournisseurIdOrderByDateAjustementAscIdAsc(boutiques.idBoutiqueCourante(), fournisseurId);
    }

    /** Historique pour la fiche du partenaire : vide pour un caissier, qui ne voit pas les ajustements. */
    @Transactional(readOnly = true)
    public List<AjustementSoldeDepart> historiqueClientPourFiche(Long clientId) {
        return gerant() ? historiqueClient(clientId) : List.of();
    }

    @Transactional(readOnly = true)
    public List<AjustementSoldeDepart> historiqueFournisseurPourFiche(Long fournisseurId) {
        return gerant() ? historiqueFournisseur(fournisseurId) : List.of();
    }

    private static boolean gerant() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.isAuthenticated() && a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_ADMIN"));
    }

    // ---- Règles ---------------------------------------------------------------------------------------------

    private BigDecimal controler(Partenaire type, Long id, BigDecimal soldeActuel, BigDecimal montantSaisi, SensAjustement sens, String motif, String jeton) {
        if (sens == null) throw new IllegalArgumentException("Choisissez le sens du nouveau solde : « il me doit », « je lui dois » ou « avance ».");
        if (jeton == null || jeton.isBlank() || jeton.length() > 36) throw new IllegalArgumentException("Rechargez le formulaire d'ajustement.");
        motifValide(motif);
        BigDecimal montant = Montants.positifOuNul(montantSaisi, "Le nouveau solde");
        BigDecimal nouveau = sens.signe(montant, type == Partenaire.CLIENT);
        if (nouveau.compareTo(Montants.ouZero(soldeActuel)) == 0) {
            throw new IllegalArgumentException("Le nouveau solde est identique au solde actuel : il n'y a rien à ajuster.");
        }
        if (soldes.avanceDisponibleAvecSolde(type, id, nouveau).signum() < 0) {
            throw new IllegalArgumentException("Une partie de l'avance de ce partenaire a déjà été utilisée sur ses factures : "
                    + "ce nouveau solde la rendrait négative.");
        }
        return nouveau;
    }

    private AjustementSoldeDepart dejaEnregistre(String jeton, Long partenaireId, boolean client) {
        if (jeton == null || jeton.isBlank()) return null;
        AjustementSoldeDepart existant = ajustements.findByBoutiqueIdAndJeton(boutiques.idBoutiqueCourante(), jeton).orElse(null);
        if (existant == null) return null;
        Long lie = client ? (existant.getClient() == null ? null : existant.getClient().getId())
                : (existant.getFournisseur() == null ? null : existant.getFournisseur().getId());
        if (!Objects.equals(lie, partenaireId)) throw new IllegalArgumentException("Rechargez le formulaire d'ajustement.");
        return existant;
    }

    private AjustementSoldeDepart nouveau(String jeton, String motif, BigDecimal ancien, BigDecimal nouveau) {
        AjustementSoldeDepart ajustement = new AjustementSoldeDepart();
        ajustement.setBoutique(boutiques.boutiqueCourante());
        ajustement.setUtilisateur(utilisateurCourant());
        ajustement.setDateAjustement(LocalDateTime.now());
        ajustement.setAncienSolde(Montants.valider(Montants.ouZero(ancien), "L'ancien solde"));
        ajustement.setNouveauSolde(Montants.valider(nouveau, "Le nouveau solde"));
        ajustement.setMotif(motifValide(motif));
        ajustement.setJeton(jeton);
        return ajustement;
    }

    /** Le motif est obligatoire : 3 à 255 caractères, sans caractère de contrôle. */
    static String motifValide(String saisi) {
        String motif = saisi == null ? "" : saisi.strip();
        if (motif.length() < 3 || motif.length() > 255 || motif.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Le motif de l'ajustement est obligatoire : 3 à 255 caractères, sans retour à la ligne.");
        }
        return motif;
    }

    private Utilisateur utilisateurCourant() {
        Authentication authentification = SecurityContextHolder.getContext().getAuthentication();
        return utilisateurs.findByEmail(Emails.normaliser(authentification.getName()))
                .orElseThrow(() -> new IllegalArgumentException("Utilisateur introuvable pour enregistrer l'ajustement."));
    }
}
