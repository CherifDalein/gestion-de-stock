package org.example.stock.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validator;
import org.example.stock.enums.ActionAbonnement;
import org.example.stock.enums.Role;
import org.example.stock.enums.StatutAbonnement;
import org.example.stock.form.AccesBoutiqueForm;
import org.example.stock.form.ActivationAbonnementForm;
import org.example.stock.form.ProlongationEssaiForm;
import org.example.stock.model.Boutique;
import org.example.stock.model.Emails;
import org.example.stock.model.HistoriqueAbonnement;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.BoutiqueRepository;
import org.example.stock.repository.HistoriqueAbonnementRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Gestion commerciale manuelle : droits, mutation et historique sont vérifiés dans la même transaction. */
@Service
@Transactional(readOnly = true)
public class GestionAbonnementService {
    public static final int TAILLE_PAGE = 20;

    private final BoutiqueRepository boutiques;
    private final UtilisateurRepository utilisateurs;
    private final HistoriqueAbonnementRepository historique;
    private final Validator validator;
    private final EntityManager entityManager;
    private final Clock clock;

    public GestionAbonnementService(BoutiqueRepository boutiques, UtilisateurRepository utilisateurs,
                                   HistoriqueAbonnementRepository historique, Validator validator,
                                   EntityManager entityManager, @Qualifier("recoveryClock") Clock clock) {
        this.boutiques = boutiques;
        this.utilisateurs = utilisateurs;
        this.historique = historique;
        this.validator = validator;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    public SituationAbonnement situation(Boutique boutique) {
        if (boutique == null) throw introuvable();
        LocalDateTime maintenant = maintenant();
        StatutAbonnement statut;
        LocalDateTime fin = null;
        if (!boutique.isActif()) statut = StatutAbonnement.SUSPENDU;
        else if (boutique.isAccesOffert()) statut = StatutAbonnement.OFFERT;
        else if (ReglesAccesCommercial.abonnementActif(boutique, maintenant)) {
            statut = StatutAbonnement.ABONNEMENT;
            fin = boutique.getAbonnementFin();
        } else if (ReglesAccesCommercial.essaiActif(boutique, maintenant)) {
            statut = StatutAbonnement.ESSAI;
            fin = boutique.getEssaiFin();
        } else if (ReglesAccesCommercial.accesExistant(boutique)) statut = StatutAbonnement.ACCES_EXISTANT;
        else statut = StatutAbonnement.EXPIRE;
        long jours = 0;
        if (fin != null) {
            Duration restant = Duration.between(maintenant, fin);
            jours = restant.toDays() + (restant.minusDays(restant.toDays()).isZero() ? 0 : 1);
        }
        return new SituationAbonnement(boutique.getId(), boutique.getNom(), statut,
                boutique.getEssaiDebut(), boutique.getEssaiFin(), boutique.getAbonnementDebut(), boutique.getAbonnementFin(),
                jours, boutique.isActif(), boutique.isAccesOffert(),
                boutique.isActif() && !ReglesAccesCommercial.estExpire(boutique, maintenant),
                ReglesAccesCommercial.periodeValide(boutique.getEssaiDebut(), boutique.getEssaiFin())
                        && !abonnementPrevuOuActif(boutique, maintenant));
    }

    /** La lecture reste possible après expiration ou suspension afin d'expliquer la situation au gérant. */
    public VueAbonnement consulterPourCompteCourant(int page) {
        Utilisateur compte = compteConnecte("ROLE_ADMIN");
        if (!compte.isActif() || compte.getRole() != Role.ADMIN) throw interdit();
        if (compte.getBoutique() == null || compte.getBoutique().getId() == null) throw interdit();
        return consulter(compte.getBoutique(), page);
    }

    public VueAbonnement consulterPourPlateforme(Long boutiqueId, int page) {
        exigerAdministrationPlateforme();
        return consulter(chargerBoutique(boutiqueId), page);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SituationAbonnement prolongerEssai(Long boutiqueId, ProlongationEssaiForm formulaire) {
        Utilisateur acteur = exigerAdministrationPlateforme();
        valider(formulaire);
        Boutique boutique = verrouiller(acteur, boutiqueId);
        if (repetition(boutique, acteur, formulaire.getJetonAction(), ActionAbonnement.PROLONGATION_ESSAI,
                formulaire.getJours(), formulaire.getMotif())) return situation(boutique);
        if (!ReglesAccesCommercial.periodeValide(boutique.getEssaiDebut(), boutique.getEssaiFin())) {
            throw new AbonnementException("jours", "Cette boutique ne possède pas d’essai à prolonger.");
        }
        LocalDateTime maintenant = maintenant();
        if (abonnementPrevuOuActif(boutique, maintenant)) {
            throw new AbonnementException("jours", "Un abonnement est déjà prévu ou actif. "
                    + "Ajoutez un mois à cet abonnement pour prolonger l’accès.");
        }
        LocalDateTime ancienneFin = boutique.getEssaiFin();
        LocalDateTime debutAjout = apres(maintenant, ancienneFin);
        LocalDateTime nouvelleFin = debutAjout.plusDays(formulaire.getJours());
        boutique.setEssaiFin(nouvelleFin);
        ajouter(boutique, acteur, ActionAbonnement.PROLONGATION_ESSAI, maintenant, debutAjout, nouvelleFin,
                ancienneFin, formulaire.getMotif(), formulaire.getJetonAction(), formulaire.getJours());
        return situation(boutique);
    }

    /** Un mois civil ; renouveler un accès encore valide ne fait perdre aucun jour déjà accordé. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SituationAbonnement activerMois(Long boutiqueId, ActivationAbonnementForm formulaire) {
        Utilisateur acteur = exigerAdministrationPlateforme();
        valider(formulaire);
        Boutique boutique = verrouiller(acteur, boutiqueId);
        if (repetition(boutique, acteur, formulaire.getJetonAction(), ActionAbonnement.ACTIVATION_ABONNEMENT,
                1, formulaire.getMotif())) return situation(boutique);
        if ((boutique.getAbonnementDebut() != null || boutique.getAbonnementFin() != null)
                && !ReglesAccesCommercial.periodeValide(boutique.getAbonnementDebut(), boutique.getAbonnementFin())) {
            throw new AbonnementException("jetonAction", "La période d’abonnement existante est incohérente.");
        }
        LocalDateTime maintenant = maintenant();
        LocalDateTime ancienneFin = boutique.getAbonnementFin();
        LocalDateTime debutAjout = apres(maintenant, ancienneFin);
        if (ReglesAccesCommercial.periodeValide(boutique.getEssaiDebut(), boutique.getEssaiFin())) {
            debutAjout = apres(debutAjout, boutique.getEssaiFin());
        }
        LocalDateTime nouvelleFin = debutAjout.plusMonths(1);
        if (ancienneFin == null || !ancienneFin.isAfter(maintenant)) boutique.setAbonnementDebut(debutAjout);
        boutique.setAbonnementFin(nouvelleFin);
        ajouter(boutique, acteur, ActionAbonnement.ACTIVATION_ABONNEMENT, maintenant, debutAjout, nouvelleFin,
                ancienneFin, formulaire.getMotif(), formulaire.getJetonAction(), 1);
        return situation(boutique);
    }

    /** Même point d'entrée que les réglages précédents, avec enregistrement des seuls changements effectifs. */
    @Transactional
    public Boutique modifierAcces(Long boutiqueId, AccesBoutiqueForm formulaire) {
        Utilisateur acteur = exigerAdministrationPlateforme();
        valider(formulaire);
        Boutique boutique = verrouiller(acteur, boutiqueId);
        LocalDateTime maintenant = maintenant();
        if (boutique.isActif() != formulaire.getActif()) {
            boutique.setActif(formulaire.getActif());
            ajouter(boutique, acteur, formulaire.getActif() ? ActionAbonnement.REACTIVATION_BOUTIQUE
                    : ActionAbonnement.SUSPENSION_BOUTIQUE, maintenant, null, null, null, null,
                    UUID.randomUUID().toString(), null);
        }
        if (boutique.isAccesOffert() != formulaire.getAccesOffert()) {
            boutique.setAccesOffert(formulaire.getAccesOffert());
            ajouter(boutique, acteur, formulaire.getAccesOffert() ? ActionAbonnement.ACCES_OFFERT
                    : ActionAbonnement.RETRAIT_ACCES_OFFERT, maintenant, null, null, null, null,
                    UUID.randomUUID().toString(), null);
        }
        return boutique;
    }

    private VueAbonnement consulter(Boutique boutique, int page) {
        if (page < 0 || page > 100_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La page d’historique est invalide");
        }
        var tranche = historique.findAllByBoutiqueIdOrderByCreeLeDescIdDesc(boutique.getId(),
                PageRequest.of(page, TAILLE_PAGE));
        var evenements = tranche.getContent().stream().map(e -> new EvenementAbonnement(e.getId(), e.getAction(),
                e.getActeurNom(), e.getCreeLe(), e.getPeriodeDebut(), e.getPeriodeFin(), e.getAncienneFin(),
                e.getMotif(), e.getQuantite())).toList();
        return new VueAbonnement(situation(boutique), evenements, page, tranche.hasPrevious(), tranche.hasNext());
    }

    private boolean repetition(Boutique boutique, Utilisateur acteur, String jeton, ActionAbonnement action,
                                Integer quantite, String motif) {
        var evenement = historique.findByJetonAction(jeton);
        if (evenement.isEmpty()) return false;
        HistoriqueAbonnement precedent = evenement.orElseThrow();
        if (!Objects.equals(precedent.getBoutique().getId(), boutique.getId())
                || !Objects.equals(precedent.getActeur().getId(), acteur.getId()) || precedent.getAction() != action
                || !Objects.equals(precedent.getQuantite(), quantite) || !Objects.equals(precedent.getMotif(), motif)) {
            throw conflitJeton();
        }
        return true;
    }

    private void ajouter(Boutique boutique, Utilisateur acteur, ActionAbonnement action, LocalDateTime maintenant,
                         LocalDateTime debut, LocalDateTime fin, LocalDateTime ancienneFin, String motif,
                         String jeton, Integer quantite) {
        HistoriqueAbonnement evenement = new HistoriqueAbonnement();
        evenement.setBoutique(boutique);
        evenement.setActeur(acteur);
        String nom = acteur.getNom();
        if (nom == null || nom.isBlank()) nom = "Administrateur de plateforme";
        nom = nom.strip();
        evenement.setActeurNom((nom.codePointCount(0, nom.length()) <= 255 ? nom
                : nom.substring(0, nom.offsetByCodePoints(0, 255))).strip());
        evenement.setAction(action);
        evenement.setCreeLe(maintenant);
        evenement.setPeriodeDebut(debut);
        evenement.setPeriodeFin(fin);
        evenement.setAncienneFin(ancienneFin);
        evenement.setMotif(motif);
        evenement.setJetonAction(jeton);
        evenement.setQuantite(quantite);
        try {
            historique.saveAndFlush(evenement);
        } catch (DataIntegrityViolationException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                        && violation.getConstraintName() != null
                        && violation.getConstraintName().toLowerCase(Locale.ROOT)
                        .contains("uk_historique_abonnement_jeton")) throw conflitJeton();
            }
            throw e;
        }
    }

    private Boutique verrouiller(Utilisateur acteur, Long boutiqueId) {
        entityManager.lock(acteur, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(acteur);
        verifierPlateforme(acteur);
        Boutique boutique = chargerBoutique(boutiqueId);
        entityManager.lock(boutique, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(boutique);
        return boutique;
    }

    private Utilisateur exigerAdministrationPlateforme() {
        Utilisateur compte = compteConnecte("ROLE_PLATEFORME_ADMIN");
        verifierPlateforme(compte);
        return compte;
    }

    private void verifierPlateforme(Utilisateur compte) {
        if (!compte.isActif() || !compte.isAdministrateurPlateforme() || compte.getRole() != Role.ADMIN) throw interdit();
    }

    private Utilisateur compteConnecte(String autorite) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getAuthorities().stream().noneMatch(a -> autorite.equals(a.getAuthority()))) throw interdit();
        return utilisateurs.findByEmail(Emails.normaliser(authentication.getName())).orElseThrow(GestionAbonnementService::interdit);
    }

    private Boutique chargerBoutique(Long id) {
        if (id == null) throw introuvable();
        return boutiques.findById(id).orElseThrow(GestionAbonnementService::introuvable);
    }

    private void valider(Object formulaire) {
        if (formulaire == null) throw new IllegalArgumentException("Le formulaire est obligatoire.");
        var erreurs = validator.validate(formulaire);
        if (!erreurs.isEmpty()) {
            var erreur = erreurs.stream().sorted(java.util.Comparator.comparing(e -> e.getPropertyPath().toString()))
                    .findFirst().orElseThrow();
            throw new AbonnementException(erreur.getPropertyPath().toString(), erreur.getMessage());
        }
    }

    private LocalDateTime maintenant() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }

    private static boolean abonnementPrevuOuActif(Boutique boutique, LocalDateTime maintenant) {
        return ReglesAccesCommercial.periodeValide(boutique.getAbonnementDebut(), boutique.getAbonnementFin())
                && boutique.getAbonnementFin().isAfter(maintenant);
    }

    private static LocalDateTime apres(LocalDateTime minimum, LocalDateTime candidat) {
        return candidat != null && candidat.isAfter(minimum) ? candidat : minimum;
    }

    private static ResponseStatusException interdit() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Gestion de l’abonnement inaccessible");
    }

    private static ResponseStatusException introuvable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Boutique introuvable");
    }

    private static ResponseStatusException conflitJeton() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Ce formulaire a déjà été utilisé pour une autre action");
    }
}
