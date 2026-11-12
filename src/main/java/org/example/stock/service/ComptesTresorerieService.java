package org.example.stock.service;

import jakarta.persistence.EntityManager;
import org.example.stock.enums.Role;
import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.form.CompteTresorerieForm;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Comptes de trésorerie d'une boutique : lecture, choix des comptes utilisables, gestion par le gérant et verrous.
 * Règles : voir {@code docs/lot-3.md}, « Comptes de trésorerie multiples ». Le solde d'un compte reste la somme de ses
 * mouvements (son solde de départ est le premier d'entre eux) ; rien n'est stocké en double.
 */
@Service
public class ComptesTresorerieService {
    /** Comptes actifs au plus par boutique, les trois comptes de base compris. */
    public static final int LIMITE = 12;

    private final CompteTresorerieBoutiqueRepository comptes;
    private final CompteTresorerieAutoriseRepository autorisations;
    private final MouvementCaisseRepository mouvements;
    private final UtilisateurRepository utilisateurs;
    private final BoutiqueService boutiques;
    private final StockLockRepository verrous;
    private final EntityManager entityManager;
    private final Clock clock;

    public ComptesTresorerieService(CompteTresorerieBoutiqueRepository comptes, CompteTresorerieAutoriseRepository autorisations,
                                    MouvementCaisseRepository mouvements, UtilisateurRepository utilisateurs, BoutiqueService boutiques,
                                    StockLockRepository verrous, EntityManager entityManager, @Qualifier("recoveryClock") Clock clock) {
        this.comptes = comptes;
        this.autorisations = autorisations;
        this.mouvements = mouvements;
        this.utilisateurs = utilisateurs;
        this.boutiques = boutiques;
        this.verrous = verrous;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    // ---- Vues pour les écrans ------------------------------------------------------------------------------------

    public record CompteVue(CompteTresorerieBoutique compte, BigDecimal solde, boolean soldeDepartModifiable, boolean archivable) { }
    public record GroupeVue(TypeCompteTresorerie type, List<CompteVue> comptes, BigDecimal total) { }
    public record CaissierVue(Utilisateur utilisateur, Set<Long> autorises, boolean explicite) { }
    public record Ecran(List<GroupeVue> groupes, List<CompteVue> archives, long actifs, int limite, BigDecimal total,
                        List<CaissierVue> caissiers, List<CompteTresorerieBoutique> actifsListe) {
        public boolean limiteAtteinte() { return actifs >= limite; }
    }

    /** Page « Comptes de trésorerie » : réservée au gérant. */
    @Transactional(readOnly = true)
    public Ecran ecran() {
        Utilisateur gerant = gerant();
        Long boutiqueId = gerant.getBoutique().getId();
        Map<Long, BigDecimal> soldes = soldes(boutiqueId);
        List<CompteTresorerieBoutique> tous = comptes.lister(boutiqueId);
        List<GroupeVue> groupes = new ArrayList<>();
        List<CompteVue> archives = new ArrayList<>();
        BigDecimal total = Montants.ZERO;
        for (TypeCompteTresorerie type : TypeCompteTresorerie.values()) {
            List<CompteVue> vues = new ArrayList<>();
            BigDecimal sousTotal = Montants.ZERO;
            for (CompteTresorerieBoutique c : tous) {
                if (c.getType() != type) continue;
                BigDecimal solde = soldes.getOrDefault(c.getId(), Montants.ZERO);
                boolean aMouvement = soldes.containsKey(c.getId());
                CompteVue vue = new CompteVue(c, solde, !aMouvement, c.isActif() && !c.isPrincipal() && solde.signum() == 0);
                if (c.isActif()) { vues.add(vue); sousTotal = sousTotal.add(solde); } else archives.add(vue);
            }
            groupes.add(new GroupeVue(type, vues, sousTotal));
            total = total.add(sousTotal);
        }
        List<CaissierVue> caissiers = new ArrayList<>();
        Map<Long, Set<Long>> parUtilisateur = new HashMap<>();
        for (CompteTresorerieAutorise a : autorisations.deLaBoutique(boutiqueId)) {
            parUtilisateur.computeIfAbsent(a.getUtilisateur().getId(), k -> new HashSet<>()).add(a.getCompte().getId());
        }
        for (Utilisateur u : utilisateurs.findAllByBoutiqueIdOrderByNomAscIdAsc(boutiqueId)) {
            if (u.getRole() != Role.CAISSIER || !u.isActif()) continue;
            Set<Long> ids = parUtilisateur.getOrDefault(u.getId(), Set.of());
            caissiers.add(new CaissierVue(u, ids, !ids.isEmpty()));
        }
        long actifs = tous.stream().filter(CompteTresorerieBoutique::isActif).count();
        return new Ecran(groupes, archives, actifs, LIMITE, total, caissiers, tous.stream().filter(CompteTresorerieBoutique::isActif).toList());
    }

    /** Solde de chaque compte de la boutique courante, par identifiant ; un compte sans mouvement n'a pas d'entrée. */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> soldes(Long boutiqueId) {
        Map<Long, BigDecimal> resultat = new HashMap<>();
        for (Object[] ligne : mouvements.soldesParCompte(boutiqueId)) {
            resultat.put((Long) ligne[0], Montants.ouZero((BigDecimal) ligne[1]));
        }
        return resultat;
    }

    // ---- Comptes utilisables -------------------------------------------------------------------------------------

    /** Comptes actifs de la boutique courante que l'utilisateur connecté peut utiliser, groupés par type puis ordre d'affichage. */
    @Transactional(readOnly = true)
    public List<CompteTresorerieBoutique> utilisables() {
        Utilisateur u = courant();
        return utilisablesPar(u);
    }

    @Transactional(readOnly = true)
    public List<CompteTresorerieBoutique> utilisablesPar(Utilisateur u) {
        List<CompteTresorerieBoutique> actifs = comptes.listerActifs(u.getBoutique().getId());
        Set<Long> ids = new HashSet<>(autorisations.idsComptes(u.getId()));
        return actifs.stream().filter(c -> u.getRole() == Role.ADMIN || (ids.isEmpty() ? c.isPrincipal() : ids.contains(c.getId())))
                .sorted(java.util.Comparator.comparing(CompteTresorerieBoutique::getType).thenComparingInt(CompteTresorerieBoutique::getOrdre)
                        .thenComparing(CompteTresorerieBoutique::getId)).toList();
    }

    /** Tous les comptes de la boutique courante, archivés compris (filtre du journal, qui garde l'historique). Gérant seulement. */
    @Transactional(readOnly = true)
    public List<CompteTresorerieBoutique> listerTous() {
        return comptes.lister(gerant().getBoutique().getId());
    }

    /** Regroupe des comptes par type, dans l'ordre des types ; les types sans compte sont omis (listes de choix des écrans). */
    public Map<TypeCompteTresorerie, List<CompteTresorerieBoutique>> grouper(Collection<CompteTresorerieBoutique> liste) {
        Map<TypeCompteTresorerie, List<CompteTresorerieBoutique>> groupes = new EnumMap<>(TypeCompteTresorerie.class);
        for (CompteTresorerieBoutique c : liste) groupes.computeIfAbsent(c.getType(), k -> new ArrayList<>()).add(c);
        return groupes;
    }

    /** Utilisables regroupés par type, dans l'ordre des types ; les types sans compte utilisable sont omis. */
    @Transactional(readOnly = true)
    public Map<TypeCompteTresorerie, List<CompteTresorerieBoutique>> utilisablesParType() {
        Map<TypeCompteTresorerie, List<CompteTresorerieBoutique>> groupes = new EnumMap<>(TypeCompteTresorerie.class);
        for (CompteTresorerieBoutique c : utilisables()) groupes.computeIfAbsent(c.getType(), k -> new ArrayList<>()).add(c);
        return groupes;
    }

    /**
     * Valeur à présélectionner dans une liste : vrai pour le compte dont l'identifiant est saisi, ou pour le principal du
     * type saisi (valeur par défaut des formulaires). Sans saisie, le principal des espèces.
     */
    public boolean estSelectionne(CompteTresorerieBoutique compte, Object saisie) {
        String valeur = saisie == null ? "" : saisie.toString().strip();
        if (valeur.isEmpty()) return compte.getType() == TypeCompteTresorerie.ESPECES && compte.isPrincipal();
        TypeCompteTresorerie type = TypeCompteTresorerie.depuisSaisie(valeur);
        if (type != null) return compte.getType() == type && compte.isPrincipal();
        return valeur.equals(String.valueOf(compte.getId()));
    }

    /** Identifiant d'un compte pour une saisie de formulaire : identifiant numérique, ou nom d'un type (son principal). */
    @Transactional(readOnly = true)
    public Long resoudreId(String saisie) {
        return resoudreUtilisable(saisie).getId();
    }

    /**
     * Compte désigné par la saisie, contrôlé pour l'utilisateur connecté : de la boutique courante, actif et autorisé.
     * Lecture sans verrou ; {@link CaisseService} relit et verrouille le compte avant d'écrire.
     */
    @Transactional(readOnly = true)
    public CompteTresorerieBoutique resoudreUtilisable(String saisie) {
        Utilisateur u = courant();
        if (saisie == null || saisie.isBlank()) throw new CompteTresorerieInvalideException();
        String valeur = saisie.strip();
        TypeCompteTresorerie type = TypeCompteTresorerie.depuisSaisie(valeur);
        CompteTresorerieBoutique compte;
        if (type != null) {
            compte = comptes.principal(u.getBoutique().getId(), type).orElseThrow(CompteTresorerieInvalideException::new);
        } else {
            if (!valeur.matches("[0-9]{1,18}")) throw new CompteTresorerieInvalideException();
            compte = comptes.findByIdAndBoutiqueId(Long.valueOf(valeur), u.getBoutique().getId()).orElseThrow(CompteTresorerieInvalideException::new);
        }
        exigerUtilisable(compte, u);
        return compte;
    }

    /** Compte principal d'un type pour la boutique courante (création des mouvements « par défaut »). */
    @Transactional(readOnly = true)
    public CompteTresorerieBoutique principal(TypeCompteTresorerie type) {
        return comptes.principal(boutiques.idBoutiqueCourante(), type).orElseThrow(() -> new IllegalStateException(
                "Les comptes de la boutique ne sont pas initialisés. La migration du lot 3 est nécessaire."));
    }

    boolean estAutorise(Utilisateur u, CompteTresorerieBoutique compte) {
        if (u == null) return false;
        if (u.getRole() == Role.ADMIN) return true;
        List<Long> ids = autorisations.idsComptes(u.getId());
        return ids.isEmpty() ? compte.isPrincipal() : ids.contains(compte.getId());
    }

    private void exigerUtilisable(CompteTresorerieBoutique compte, Utilisateur u) {
        if (!compte.isActif() || !Objects.equals(compte.getBoutique().getId(), u.getBoutique().getId()) || !estAutorise(u, compte)) {
            throw new CompteTresorerieInvalideException();
        }
    }

    // ---- Verrous --------------------------------------------------------------------------------------------------

    /**
     * Verrouille les comptes dans l'ordre croissant des identifiants (dernier maillon de l'ordre global : boutique, document,
     * partenaire, produits, comptes) et les contrôle : de la boutique, actifs (sauf {@code exigerActif = false}, pour
     * contre-passer un ancien mouvement) et autorisés à {@code acteur}. À appeler dans la transaction de l'écriture.
     *
     * @param lecture verrou partagé (encaissement ou paiement) ou exclusif (transfert, annulation, archivage)
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Map<Long, CompteTresorerieBoutique> verrouiller(Long boutiqueId, Utilisateur acteur, boolean lecture, boolean exigerActif, Collection<Long> ids) {
        Map<Long, CompteTresorerieBoutique> verrouilles = new LinkedHashMap<>();
        for (Long id : new TreeSet<>(ids)) {
            if (id == null) throw new CompteTresorerieInvalideException();
            CompteTresorerieBoutique compte = (lecture ? comptes.verrouillerLecture(boutiqueId, id) : comptes.verrouiller(boutiqueId, id))
                    .orElseThrow(CompteTresorerieInvalideException::new);
            // Un verrou sur une entité déjà chargée ne la relit pas : l'état « actif » doit être celui validé par le dernier commit.
            entityManager.refresh(compte);
            if (exigerActif && !compte.isActif()) throw new CompteTresorerieInvalideException();
            if (acteur != null && !estAutorise(acteur, compte)) throw new CompteTresorerieInvalideException();
            verrouilles.put(id, compte);
        }
        return verrouilles;
    }

    // ---- Gestion par le gérant -----------------------------------------------------------------------------------

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CompteTresorerieBoutique creer(CompteTresorerieForm form) {
        Utilisateur gerant = gerantVerrouille();
        Boutique boutique = gerant.getBoutique();
        if (form == null || form.getType() == null) throw new IllegalArgumentException("Choisissez le type du compte.");
        String nom = nomValide(form.getNom());
        String numero = numeroValide(form.getNumero());
        BigDecimal solde = Montants.positifOuNul(form.getSoldeDepart() == null ? Montants.ZERO : form.getSoldeDepart(), "Le solde de départ");
        if (comptes.listerActifs(boutique.getId()).size() >= LIMITE) throw limiteAtteinte();
        if (comptes.existsByBoutiqueIdAndNomCle(boutique.getId(), CompteTresorerieBoutique.cle(nom))) throw nomPris();
        CompteTresorerieBoutique compte = new CompteTresorerieBoutique(boutique, form.getType(), nom, false,
                comptes.dernierOrdre(boutique.getId(), form.getType()) + 1);
        compte.renommer(nom, numero);
        compte = comptes.saveAndFlush(compte);
        if (solde.signum() > 0) ecrireSoldeDepart(compte, solde, gerant);
        return compte;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void modifier(Long id, String nom, String numero) {
        Utilisateur gerant = gerantVerrouille();
        CompteTresorerieBoutique compte = deLaBoutique(id, gerant);
        String nomNettoye = nomValide(nom);
        if (comptes.existsByBoutiqueIdAndNomCleAndIdNot(gerant.getBoutique().getId(), CompteTresorerieBoutique.cle(nomNettoye), id)) throw nomPris();
        compte.renommer(nomNettoye, numeroValide(numero));
        comptes.saveAndFlush(compte);
    }

    /** Solde de départ d'un compte qui n'a encore aucun mouvement : il devient son premier mouvement, daté d'aujourd'hui. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void definirSoldeDepart(Long id, BigDecimal montant) {
        Utilisateur gerant = gerantVerrouille();
        CompteTresorerieBoutique compte = deLaBoutique(id, gerant);
        verrouiller(gerant.getBoutique().getId(), gerant, false, true, List.of(id));
        BigDecimal solde = Montants.positifOuNul(montant, "Le solde de départ");
        if (mouvements.existsByBoutiqueIdAndCompteId(gerant.getBoutique().getId(), id)) {
            throw new IllegalArgumentException("Le solde de départ de ce compte est figé : le compte a déjà des mouvements.");
        }
        if (solde.signum() == 0) return;
        ecrireSoldeDepart(compte, solde, gerant);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void definirPrincipal(Long id) {
        Utilisateur gerant = gerantVerrouille();
        Long boutiqueId = gerant.getBoutique().getId();
        CompteTresorerieBoutique nouveau = deLaBoutique(id, gerant);
        if (!nouveau.isActif()) throw new IllegalArgumentException("Un compte archivé ne peut pas devenir le compte principal.");
        if (nouveau.isPrincipal()) return;
        CompteTresorerieBoutique ancien = comptes.principal(boutiqueId, nouveau.getType()).orElse(null);
        List<Long> ids = new ArrayList<>(List.of(id));
        if (ancien != null) ids.add(ancien.getId());
        verrouiller(boutiqueId, gerant, false, false, ids);
        if (ancien != null) { ancien.definirPrincipal(false); comptes.saveAndFlush(ancien); }
        nouveau.definirPrincipal(true);
        comptes.saveAndFlush(nouveau);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void archiver(Long id) {
        Utilisateur gerant = gerantVerrouille();
        Long boutiqueId = gerant.getBoutique().getId();
        CompteTresorerieBoutique compte = deLaBoutique(id, gerant);
        verrouiller(boutiqueId, gerant, false, false, List.of(id));
        if (!compte.isActif()) return;
        if (compte.isPrincipal()) {
            throw new IllegalArgumentException("Le compte principal ne peut pas être archivé : choisissez d'abord un autre compte principal pour ce type.");
        }
        BigDecimal solde = Montants.ouZero(mouvements.calculerSoldeCompte(boutiqueId, id));
        if (solde.signum() != 0) {
            throw new IllegalArgumentException("Ce compte ne peut pas être archivé : son solde n'est pas nul. Transférez ou dépensez d'abord son solde.");
        }
        compte.definirActif(false);
        comptes.saveAndFlush(compte);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void desarchiver(Long id) {
        Utilisateur gerant = gerantVerrouille();
        CompteTresorerieBoutique compte = deLaBoutique(id, gerant);
        if (compte.isActif()) return;
        if (comptes.listerActifs(gerant.getBoutique().getId()).size() >= LIMITE) throw limiteAtteinte();
        compte.definirActif(true);
        comptes.saveAndFlush(compte);
    }

    /**
     * Remplace la liste des comptes autorisés d'un caissier. Une liste vide rend le caissier aux comptes principaux.
     * Le caissier doit être un caissier de la boutique ; chaque compte doit en être un aussi.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void autoriser(Long utilisateurId, Collection<Long> compteIds) {
        Utilisateur gerant = gerantVerrouille();
        Long boutiqueId = gerant.getBoutique().getId();
        Utilisateur caissier = utilisateurId == null ? null : utilisateurs.findById(utilisateurId).orElse(null);
        if (caissier == null || !Objects.equals(caissier.getBoutique().getId(), boutiqueId) || caissier.getRole() != Role.CAISSIER) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Caissier introuvable");
        }
        Set<Long> demandes = new TreeSet<>(compteIds == null ? Set.of() : compteIds);
        List<CompteTresorerieBoutique> retenus = new ArrayList<>();
        for (Long id : demandes) retenus.add(comptes.findByIdAndBoutiqueId(id, boutiqueId).orElseThrow(CompteTresorerieInvalideException::new));
        autorisations.supprimerDeUtilisateur(utilisateurId);
        for (CompteTresorerieBoutique c : retenus) autorisations.save(new CompteTresorerieAutorise(caissier, c));
        autorisations.flush();
    }

    // ---- Outils ---------------------------------------------------------------------------------------------------

    private void ecrireSoldeDepart(CompteTresorerieBoutique compte, BigDecimal montant, Utilisateur gerant) {
        MouvementCaisse m = new MouvementCaisse();
        m.setBoutique(gerant.getBoutique()); m.setUtilisateur(gerant); m.setCompte(compte);
        m.setMontant(Montants.valider(montant, "Le solde de départ"));
        m.setType("OUVERTURE"); m.setSource("SOLDE_INITIAL");
        m.setMotif("Solde de départ du compte " + compte.getNom());
        m.setDateMouvement(LocalDateTime.now(clock.withZone(ZoneId.systemDefault())));
        mouvements.saveAndFlush(m);
    }

    private CompteTresorerieBoutique deLaBoutique(Long id, Utilisateur gerant) {
        return comptes.findByIdAndBoutiqueId(id, gerant.getBoutique().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte introuvable"));
    }

    private static String nomValide(String nom) {
        String net = CompteTresorerieBoutique.normaliserNom(nom);
        if (net.isEmpty()) throw new IllegalArgumentException("Le nom du compte est obligatoire.");
        if (net.length() > CompteTresorerieBoutique.TAILLE_NOM) throw new IllegalArgumentException("Le nom du compte accepte au maximum 60 caractères.");
        return net;
    }

    private static String numeroValide(String numero) {
        if (numero == null || numero.isBlank()) return null;
        String net = numero.strip();
        if (net.length() > CompteTresorerieBoutique.TAILLE_NUMERO) throw new IllegalArgumentException("Le numéro accepte au maximum 40 caractères.");
        return net;
    }

    private static IllegalArgumentException limiteAtteinte() {
        return new IllegalArgumentException("Une boutique peut avoir au plus " + LIMITE + " comptes actifs, comptes de base compris. Archivez un compte inutilisé pour en ajouter un autre.");
    }

    private static IllegalArgumentException nomPris() {
        return new IllegalArgumentException("Un compte de cette boutique porte déjà ce nom (sans tenir compte des majuscules). Choisissez un autre nom.");
    }

    /** Gérant connecté, boutique verrouillée en premier (ordre global des verrous). */
    private Utilisateur gerantVerrouille() {
        verrous.verrouillerBoutiqueCourante();
        return gerant();
    }

    private Utilisateur gerant() {
        Utilisateur u = courant();
        if (u.getRole() != Role.ADMIN) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Gestion de la trésorerie réservée au gérant");
        return u;
    }

    private Utilisateur courant() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Boutique inaccessible");
        }
        boutiques.idBoutiqueCourante();
        Utilisateur u = utilisateurs.findByEmail(Emails.normaliser(auth.getName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Boutique inaccessible"));
        if (!u.isActif()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "L'accès à ce compte est désactivé");
        return u;
    }
}
