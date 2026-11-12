package org.example.stock.service;

import org.example.stock.enums.Role;
import org.example.stock.enums.StatutComptage;
import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.model.ComptageCaisse;
import org.example.stock.model.CompteTresorerieBoutique;
import org.example.stock.model.Emails;
import org.example.stock.model.Montants;
import org.example.stock.model.MouvementCaisse;
import org.example.stock.model.SourceMouvement;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.ComptageCaisseRepository;
import org.example.stock.repository.MouvementCaisseRepository;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Comptage de caisse (étape 3b2) : le caissier ou le gérant compte un compte qu'il peut utiliser ; le gérant valide ou refuse les écarts.
 *
 * <ul>
 *   <li><b>Écart</b> = montant compté − solde théorique ({@code SUM(mouvement_caisse.montant)} du compte) <b>à l'instant du comptage</b>.
 *       Les mouvements saisis ensuite ne changent pas l'écart enregistré : c'est un choix documenté (docs/lot-3.md). Écart nul : statut
 *       « Conforme », aucun mouvement. Écart non nul : « En attente de validation », aucun mouvement avant la décision du gérant.</li>
 *   <li><b>Validation</b> : un mouvement « Écart de caisse » du montant exact de l'écart est posté sur le compte (entrée si excédent,
 *       sortie si manque) ; <b>refus</b> : aucun mouvement. Le motif est obligatoire dans les deux cas.</li>
 *   <li><b>Annulation</b> d'un comptage validé : contre-passation du mouvement par le gérant, avec motif.</li>
 * </ul>
 *
 * <p>Ordre des verrous : boutique, comptage, compte de trésorerie (docs/lot-2.md, « Ordre des verrous »). Une seconde validation du même
 * comptage relit le statut sous verrou et reçoit 409.</p>
 */
@Service
public class ComptageCaisseService {
    public static final int MOTIF_MINIMUM = 3, MOTIF_MAXIMUM = 255, COMMENTAIRE_MAXIMUM = 255;

    private final StockLockRepository verrous;
    private final ComptesTresorerieService comptes;
    private final CaisseService caisse;
    private final BoutiqueService boutiques;
    private final UtilisateurRepository utilisateurs;
    private final ComptageCaisseRepository comptages;
    private final MouvementCaisseRepository mouvements;

    public ComptageCaisseService(StockLockRepository verrous, ComptesTresorerieService comptes, CaisseService caisse, BoutiqueService boutiques,
                                 UtilisateurRepository utilisateurs, ComptageCaisseRepository comptages, MouvementCaisseRepository mouvements) {
        this.verrous = verrous; this.comptes = comptes; this.caisse = caisse; this.boutiques = boutiques;
        this.utilisateurs = utilisateurs; this.comptages = comptages; this.mouvements = mouvements;
    }

    // ---- Saisie ---------------------------------------------------------------------------------------------

    /**
     * Enregistre un comptage. {@code compteSaisie} est l'identifiant du compte (ou un type, soit son compte principal) ; le compte doit être
     * actif et autorisé à l'acteur (règle de l'étape 3a). Pour un compte d'espèces, un billetage donne le total compté ; il doit alors
     * concorder avec le montant saisi s'il y en a un. Un second envoi du même jeton rend le comptage déjà enregistré.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ComptageCaisse saisir(String compteSaisie, BigDecimal montantSaisi, List<Integer> quantites, String commentaire, String jeton) {
        if (jeton == null || jeton.isBlank() || jeton.length() > 36) throw new IllegalArgumentException("Rechargez le formulaire de comptage.");
        String note = commentaire == null || commentaire.isBlank() ? null : commentaire.strip();
        if (note != null && (note.length() > COMMENTAIRE_MAXIMUM || note.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r'))) {
            throw new IllegalArgumentException("Le commentaire comporte au plus " + COMMENTAIRE_MAXIMUM + " caractères, sans caractère de contrôle.");
        }
        Billetage.controler(quantites);
        verrous.verrouillerBoutiqueCourante();
        Utilisateur acteur = utilisateurCourant();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        ComptageCaisse deja = comptages.findByBoutiqueIdAndJeton(boutiqueId, jeton).orElse(null);
        if (deja != null) {
            if (!Objects.equals(deja.getUtilisateur().getId(), acteur.getId())) throw new IllegalArgumentException("Rechargez le formulaire de comptage.");
            return deja;
        }
        CompteTresorerieBoutique choisi = comptes.resoudreUtilisable(compteSaisie);
        // Compte verrouillé en exclusif : le solde théorique lu ensuite est celui de l'instant du comptage, sans mouvement concurrent.
        CompteTresorerieBoutique compte = comptes.verrouiller(boutiqueId, acteur, false, true, List.of(choisi.getId())).get(choisi.getId());

        BigDecimal total = montantCompte(compte, montantSaisi, quantites);
        BigDecimal theorique = Montants.ouZero(mouvements.calculerSoldeCompte(boutiqueId, compte.getId()));
        BigDecimal ecart = total.subtract(theorique);

        ComptageCaisse comptage = new ComptageCaisse();
        comptage.setBoutique(boutiques.boutiqueCourante());
        comptage.setCompte(compte);
        comptage.setUtilisateur(acteur);
        comptage.setDateComptage(LocalDateTime.now());
        comptage.setMontantCompte(total);
        comptage.setSoldeTheorique(Montants.valider(theorique, "Le solde théorique"));
        comptage.setEcart(Montants.valider(ecart, "L'écart de caisse"));
        comptage.setCommentaire(note);
        comptage.setStatut(ecart.signum() == 0 ? StatutComptage.CONFORME : StatutComptage.EN_ATTENTE);
        comptage.setJeton(jeton);
        if (Billetage.saisi(quantites)) {
            for (int i = 0; i < Billetage.COUPURES.size() && i < quantites.size(); i++) {
                Integer quantite = quantites.get(i);
                if (quantite != null && quantite > 0) comptage.ajouterLigne(Billetage.COUPURES.get(i), quantite);
            }
        }
        return comptages.saveAndFlush(comptage);
    }

    private static BigDecimal montantCompte(CompteTresorerieBoutique compte, BigDecimal montantSaisi, List<Integer> quantites) {
        boolean billetage = Billetage.saisi(quantites);
        if (billetage && compte.getType() != TypeCompteTresorerie.ESPECES) {
            throw new IllegalArgumentException("Le billetage ne concerne que les comptes d'espèces.");
        }
        if (billetage) {
            BigDecimal total = Montants.valider(Billetage.total(quantites), "Le total du billetage");
            if (montantSaisi != null && montantSaisi.compareTo(total) != 0) {
                throw new IllegalArgumentException("Le montant compté (" + Montants.valider(montantSaisi, "Le montant compté").toPlainString()
                        + ") ne correspond pas au total du billetage (" + total.toPlainString() + ").");
            }
            return total;
        }
        if (montantSaisi == null) throw new IllegalArgumentException("Saisissez le montant compté ou le billetage.");
        return Montants.positifOuNul(montantSaisi, "Le montant compté");
    }

    // ---- Décision du gérant -----------------------------------------------------------------------------------

    /** Valide un comptage en attente : poste le mouvement « Écart de caisse ». 409 si le comptage n'est plus en attente. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MouvementCaisse valider(Long id, String motifSaisi) {
        exigerGerantSession();
        String motif = motifValide(motifSaisi, "de la validation");
        verrous.verrouillerBoutiqueCourante();
        Utilisateur gerant = caisse.gerantPourEcriture();
        ComptageCaisse comptage = verrous.verrouillerComptage(id);
        if (comptage.getStatut() != StatutComptage.EN_ATTENTE) throw new ComptageDejaTraiteException(comptage.getStatut(), "en attente de validation");
        MouvementCaisse mouvement = caisse.enregistrerEcartCaisse(comptage, tronquer("Écart de caisse (comptage n° " + comptage.getId() + ") : " + motif), gerant);
        comptage.setStatut(StatutComptage.VALIDE);
        decider(comptage, gerant, motif);
        return mouvement;
    }

    /** Refuse un comptage en attente : aucun mouvement, l'écart n'est pas régularisé. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void refuser(Long id, String motifSaisi) {
        exigerGerantSession();
        String motif = motifValide(motifSaisi, "du refus");
        verrous.verrouillerBoutiqueCourante();
        Utilisateur gerant = caisse.gerantPourEcriture();
        ComptageCaisse comptage = verrous.verrouillerComptage(id);
        if (comptage.getStatut() != StatutComptage.EN_ATTENTE) throw new ComptageDejaTraiteException(comptage.getStatut(), "en attente de validation");
        comptage.setStatut(StatutComptage.REFUSE);
        decider(comptage, gerant, motif);
    }

    /** Annule un comptage validé par contre-passation de son mouvement (même compte, sens opposé). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void annuler(Long id, String motifSaisi) {
        exigerGerantSession();
        String motif = motifValide(motifSaisi, "de l'annulation");
        verrous.verrouillerBoutiqueCourante();
        Utilisateur gerant = caisse.gerantPourEcriture();
        ComptageCaisse comptage = verrous.verrouillerComptage(id);
        if (comptage.getStatut() != StatutComptage.VALIDE) throw new ComptageDejaTraiteException(comptage.getStatut(), "validé");
        MouvementCaisse origine = mouvements.mouvementsDuComptage(boutiques.idBoutiqueCourante(), id).stream()
                .filter(m -> !SourceMouvement.ANNULATION.equals(m.getSource())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Le mouvement de ce comptage validé est introuvable."));
        caisse.enregistrerAnnulation(origine, tronquer("Annulation de l'écart de caisse (comptage n° " + id + ") : " + motif), gerant);
        comptage.setStatut(StatutComptage.ANNULE);
        comptage.setAnnulePar(gerant);
        comptage.setDateAnnulation(LocalDateTime.now());
        comptage.setMotifAnnulation(motif);
    }

    private static void decider(ComptageCaisse comptage, Utilisateur gerant, String motif) {
        comptage.setDecideur(gerant);
        comptage.setDateDecision(LocalDateTime.now());
        comptage.setMotifDecision(motif);
    }

    // ---- Lectures -------------------------------------------------------------------------------------------

    /** Filtre d'une liste de comptages : statut, période (jours inclus). Tous les champs sont facultatifs. */
    public record Filtre(StatutComptage statut, java.time.LocalDate du, java.time.LocalDate au) {
        public static final Filtre AUCUN = new Filtre(null, null, null);
    }

    /** Nombre maximal de lignes de la liste affichée ; l'export, lui, suit la limite d'export. */
    public static final int LIGNES_ECRAN = 500;

    /** Comptages visibles : tous ceux de la boutique pour le gérant, les siens pour le caissier. Les plus récents d'abord. */
    @Transactional(readOnly = true)
    public List<ComptageCaisse> lister(Filtre filtre) {
        return chercher(filtre, LIGNES_ECRAN);
    }

    /** Comme {@link #lister}, avec un plafond de lignes fixé par l'appelant (l'export lit une ligne de plus pour détecter le dépassement). */
    @Transactional(readOnly = true)
    public List<ComptageCaisse> chercher(Filtre filtre, int plafond) {
        Utilisateur acteur = utilisateurCourant();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        boolean gerant = acteur.getRole() == Role.ADMIN;
        return comptages.findAll((c, requete, cb) -> {
            if (requete.getResultType() != Long.class && requete.getResultType() != long.class) {
                c.fetch("compte"); c.fetch("utilisateur"); c.fetch("decideur", jakarta.persistence.criteria.JoinType.LEFT);
                c.fetch("annulePar", jakarta.persistence.criteria.JoinType.LEFT);
            }
            var conditions = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            conditions.add(cb.equal(c.get("boutique").get("id"), boutiqueId));
            if (!gerant) conditions.add(cb.equal(c.get("utilisateur").get("id"), acteur.getId()));
            if (filtre.statut() != null) conditions.add(cb.equal(c.get("statut"), filtre.statut()));
            if (filtre.du() != null) conditions.add(cb.greaterThanOrEqualTo(c.<LocalDateTime>get("dateComptage"), filtre.du().atStartOfDay()));
            if (filtre.au() != null) conditions.add(cb.lessThan(c.<LocalDateTime>get("dateComptage"), filtre.au().plusDays(1).atStartOfDay()));
            return cb.and(conditions.toArray(jakarta.persistence.criteria.Predicate[]::new));
        }, org.springframework.data.domain.PageRequest.of(0, plafond, org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Order.desc("dateComptage"), org.springframework.data.domain.Sort.Order.desc("id")))).getContent();
    }

    /** Comptages à décider, du plus ancien au plus récent (gérant seulement). */
    @Transactional(readOnly = true)
    public List<ComptageCaisse> enAttente() {
        exigerGerantSession();
        return comptages.findByBoutiqueIdAndStatutOrderByDateComptageAscIdAsc(boutiques.idBoutiqueCourante(), StatutComptage.EN_ATTENTE);
    }

    /** Nombre de comptages en attente pour le badge du gérant ; zéro pour tout autre utilisateur. */
    @Transactional(readOnly = true)
    public long nombreEnAttente() {
        if (!estGerantSession()) return 0;
        return comptages.countByBoutiqueIdAndStatut(boutiques.idBoutiqueCourante(), StatutComptage.EN_ATTENTE);
    }

    /** Un comptage de la boutique, billetage chargé ; le caissier ne voit que les siens (404 sinon, sans rien révéler). */
    @Transactional(readOnly = true)
    public ComptageCaisse trouver(Long id) {
        Utilisateur acteur = utilisateurCourant();
        ComptageCaisse comptage = comptages.findByIdAndBoutiqueId(id, boutiques.idBoutiqueCourante()).orElseThrow(ComptageCaisseService::introuvable);
        if (acteur.getRole() != Role.ADMIN && !Objects.equals(comptage.getUtilisateur().getId(), acteur.getId())) throw introuvable();
        comptage.getBilletage().size();
        return comptage;
    }

    /** Mouvements de ce comptage (écart puis éventuelle annulation), pour la fiche du gérant. */
    @Transactional(readOnly = true)
    public List<MouvementCaisse> mouvementsDe(ComptageCaisse comptage) {
        if (!estGerantSession()) return List.of();
        return mouvements.mouvementsDuComptage(boutiques.idBoutiqueCourante(), comptage.getId());
    }

    /**
     * Comptes que l'utilisateur peut compter. Comptage à l'aveugle : le solde n'est renseigné que pour le gérant (indicatif : le solde qui fait
     * foi est lu sous verrou à l'envoi) ; pour un caissier il vaut {@code null}, jamais lu ni transmis à l'écran.
     */
    @Transactional(readOnly = true)
    public List<CompteAvecSolde> comptesAComter() {
        boolean gerant = estGerantSession();
        Map<Long, BigDecimal> soldes = gerant ? comptes.soldes(boutiques.idBoutiqueCourante()) : Map.of();
        return comptes.utilisables().stream().map(c -> new CompteAvecSolde(c, gerant ? soldes.getOrDefault(c.getId(), BigDecimal.ZERO) : null)).toList();
    }

    /** Solde actuel de chaque compte de la boutique, pour avertir le gérant qu'un manque validé rendrait un solde négatif (gérant seulement). */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> soldesActuels() {
        exigerGerantSession();
        return comptes.soldes(boutiques.idBoutiqueCourante());
    }

    public record CompteAvecSolde(CompteTresorerieBoutique compte, BigDecimal solde) { }

    // ---- Règles ---------------------------------------------------------------------------------------------

    static String motifValide(String saisi, String de) {
        String motif = saisi == null ? "" : saisi.strip();
        if (motif.length() < MOTIF_MINIMUM || motif.length() > MOTIF_MAXIMUM || motif.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Le motif " + de + " est obligatoire : " + MOTIF_MINIMUM + " à " + MOTIF_MAXIMUM + " caractères, sans retour à la ligne.");
        }
        return motif;
    }

    private static String tronquer(String texte) {
        return texte.length() <= MOTIF_MAXIMUM ? texte : texte.substring(0, MOTIF_MAXIMUM - 1) + "…";
    }

    private static boolean estGerantSession() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.isAuthenticated() && a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_ADMIN"));
    }

    private static void exigerGerantSession() {
        if (!estGerantSession()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cette décision est réservée au gérant");
    }

    private Utilisateur utilisateurCourant() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Connexion requise");
        Utilisateur u = utilisateurs.findByEmail(Emails.normaliser(a.getName())).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Utilisateur introuvable"));
        if (!u.isActif() || (u.getRole() != Role.ADMIN && u.getRole() != Role.CAISSIER)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Compte non autorisé");
        boutiques.exigerAppartenance(u.getBoutique());
        return u;
    }

    private static ResponseStatusException introuvable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Comptage introuvable");
    }
}
