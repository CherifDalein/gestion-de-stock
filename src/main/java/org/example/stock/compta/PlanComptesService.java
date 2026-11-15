package org.example.stock.compta;

import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.model.Boutique;
import org.example.stock.model.CompteTresorerieBoutique;
import org.example.stock.model.SousCompteBoutique;
import org.example.stock.repository.CompteTresorerieBoutiqueRepository;
import org.example.stock.repository.CorrespondanceComptaRepository;
import org.example.stock.repository.SousCompteBoutiqueRepository;
import org.example.stock.service.BoutiqueService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Plan de comptes d'une boutique : le plan officiel du SYSCOHADA révisé (lecture seule, embarqué) et les sous-comptes de la boutique.
 * Chaque compte de trésorerie reçoit un sous-compte (par exemple 5711 « Caisse » sous 571) ; le gérant peut en ouvrir d'autres.
 */
@Service
public class PlanComptesService {
    private final PlanOfficiel plan;
    private final SousCompteBoutiqueRepository sousComptes;
    private final CompteTresorerieBoutiqueRepository tresorerie;
    private final CorrespondanceComptaRepository correspondances;
    private final BoutiqueService boutiques;
    private final Clock clock;

    public PlanComptesService(PlanOfficiel plan, SousCompteBoutiqueRepository sousComptes, CompteTresorerieBoutiqueRepository tresorerie,
                              CorrespondanceComptaRepository correspondances, BoutiqueService boutiques, @Qualifier("recoveryClock") Clock clock) {
        this.plan = plan;
        this.sousComptes = sousComptes;
        this.tresorerie = tresorerie;
        this.correspondances = correspondances;
        this.boutiques = boutiques;
        this.clock = clock;
    }

    /** Ligne du plan de la boutique : un compte officiel ou un sous-compte (alors {@code sousCompteId} est renseigné). */
    public record CompteVue(String numero, String intitule, String classe, String libelleClasse, String niveau, Long sousCompteId, boolean tresorerie) {
        public boolean estSousCompte() { return sousCompteId != null; }
    }

    public PlanOfficiel getPlan() { return plan; }

    /** Parent officiel proposé pour un type de compte de trésorerie. */
    public static String parentTresorerie(TypeCompteTresorerie type) {
        return switch (type) { case ESPECES -> "571"; case ORANGE_MONEY -> "552"; case BANQUE -> "521"; };
    }

    /** Crée le sous-compte de chaque compte de trésorerie qui n'en a pas, et remet à jour l'intitulé de ceux dont le compte a été renommé. */
    @Transactional
    public void synchroniserTresorerie(Long boutiqueId) {
        List<CompteTresorerieBoutique> comptes = tresorerie.lister(boutiqueId);
        Map<Long, SousCompteBoutique> existants = new HashMap<>();
        List<SousCompteBoutique> tous = sousComptes.deLaBoutique(boutiqueId);
        for (SousCompteBoutique s : tous) if (s.getCompteTresorerie() != null) existants.put(s.getCompteTresorerie().getId(), s);
        Boutique boutique = null;
        for (CompteTresorerieBoutique compte : comptes) {
            SousCompteBoutique s = existants.get(compte.getId());
            String intitule = intituleTresorerie(compte);
            if (s != null) {
                if (!intitule.equals(s.getIntitule())) { s.modifier(s.getNumero(), intitule); sousComptes.save(s); }
                continue;
            }
            if (boutique == null) boutique = compte.getBoutique();
            String parent = parentTresorerie(compte.getType());
            String numero = prochainNumero(boutiqueId, parent);
            sousComptes.save(new SousCompteBoutique(boutique, parent, numero, intitule, compte, LocalDateTime.now(clock)));
        }
    }

    private static String intituleTresorerie(CompteTresorerieBoutique compte) {
        String nom = compte.getNom() == null ? "" : compte.getNom().strip();
        return nom.length() > SousCompteBoutique.INTITULE_MAX ? nom.substring(0, SousCompteBoutique.INTITULE_MAX) : nom;
    }

    private String prochainNumero(Long boutiqueId, String parent) {
        for (int i = 1; i < 100000; i++) {
            String numero = parent + i;
            if (numero.length() > SousCompteBoutique.NUMERO_MAX) break;
            if (!plan.existe(numero) && !sousComptes.existsByBoutiqueIdAndNumero(boutiqueId, numero)) return numero;
        }
        throw new IllegalStateException("Plus de numéro libre sous le compte " + parent + ".");
    }

    /** Plan complet de la boutique (officiel et sous-comptes) trié par numéro. */
    @Transactional(readOnly = true)
    public List<CompteVue> plan(Long boutiqueId) {
        List<CompteVue> liste = new ArrayList<>();
        for (CompteOfficiel c : plan.comptes()) liste.add(new CompteVue(c.numero(), c.intitule(), String.valueOf(c.classe()), c.libelleClasse(), c.niveau(), null, false));
        for (SousCompteBoutique s : sousComptes.deLaBoutique(boutiqueId)) {
            CompteOfficiel parent = plan.trouver(s.getParentNumero()).orElse(null);
            liste.add(new CompteVue(s.getNumero(), s.getIntitule(), parent == null ? s.getNumero().substring(0, 1) : String.valueOf(parent.classe()),
                    parent == null ? "" : parent.libelleClasse(), "Sous-compte de la boutique", s.getId(), s.getCompteTresorerie() != null));
        }
        liste.sort(Comparator.comparing(CompteVue::numero));
        return liste;
    }

    /** Intitulés de tous les comptes connus de la boutique, par numéro. */
    @Transactional(readOnly = true)
    public Map<String, String> intitules(Long boutiqueId) {
        Map<String, String> m = new LinkedHashMap<>();
        for (CompteOfficiel c : plan.comptes()) m.put(c.numero(), c.intitule());
        for (SousCompteBoutique s : sousComptes.deLaBoutique(boutiqueId)) m.put(s.getNumero(), s.getIntitule());
        return m;
    }

    @Transactional(readOnly = true)
    public boolean existe(Long boutiqueId, String numero) {
        return numero != null && (plan.existe(numero) || sousComptes.existsByBoutiqueIdAndNumero(boutiqueId, numero));
    }

    /** Numéros de trésorerie (identifiant du compte de trésorerie → numéro du sous-compte). */
    @Transactional(readOnly = true)
    public Map<Long, String> comptesTresorerie(Long boutiqueId) {
        Map<Long, String> m = new HashMap<>();
        for (SousCompteBoutique s : sousComptes.deLaBoutique(boutiqueId)) if (s.getCompteTresorerie() != null) m.put(s.getCompteTresorerie().getId(), s.getNumero());
        return m;
    }

    /** Recherche : numéro (début) ou mots de l'intitulé, accents et majuscules ignorés. */
    public static boolean correspond(CompteVue c, String recherche) {
        if (recherche == null || recherche.isBlank()) return true;
        String r = recherche.strip();
        if (r.chars().allMatch(Character::isDigit)) return c.numero().startsWith(r);
        String texte = " " + Normalisation.mots(c.intitule()) + " " + c.numero();
        for (String mot : Normalisation.mots(r).split(" ")) if (!mot.isEmpty() && !texte.contains(mot)) return false;
        return true;
    }

    // ---- Sous-comptes manuels --------------------------------------------------------------------------------------

    @Transactional
    public SousCompteBoutique ajouter(Long boutiqueId, String parent, String numero, String intitule) {
        String p = parent == null ? "" : parent.strip();
        if (!plan.existe(p) || p.length() < 2 || p.length() > 4) throw new IllegalArgumentException("Choisissez un compte officiel de 2 à 4 chiffres comme compte parent.");
        String n = validerNumero(boutiqueId, p, numero, null);
        String i = validerIntitule(intitule);
        Boutique boutique = boutiques.boutiqueCourante();
        if (!boutique.getId().equals(boutiqueId)) throw new IllegalArgumentException("Boutique inconnue.");
        return sousComptes.save(new SousCompteBoutique(boutique, p, n, i, null, LocalDateTime.now(clock)));
    }

    @Transactional
    public void modifier(Long boutiqueId, Long id, String numero, String intitule) {
        SousCompteBoutique s = sousComptes.findByIdAndBoutiqueId(id, boutiqueId).orElseThrow(() -> new IllegalArgumentException("Sous-compte introuvable."));
        String i = validerIntitule(intitule);
        String n = s.getNumero();
        if (s.getCompteTresorerie() == null) {
            n = validerNumero(boutiqueId, s.getParentNumero(), numero, s.getId());
            if (!n.equals(s.getNumero()) && correspondances.existsByBoutiqueIdAndCompteNumero(boutiqueId, s.getNumero())) {
                throw new IllegalArgumentException("Le sous-compte " + s.getNumero() + " sert dans la table de correspondance : changez d'abord la correspondance.");
            }
        }
        s.modifier(n, i);
        sousComptes.save(s);
    }

    @Transactional
    public void supprimer(Long boutiqueId, Long id) {
        SousCompteBoutique s = sousComptes.findByIdAndBoutiqueId(id, boutiqueId).orElseThrow(() -> new IllegalArgumentException("Sous-compte introuvable."));
        if (s.getCompteTresorerie() != null) throw new IllegalArgumentException("Ce sous-compte représente un compte de trésorerie : il ne se supprime pas.");
        if (correspondances.existsByBoutiqueIdAndCompteNumero(boutiqueId, s.getNumero())) {
            throw new IllegalArgumentException("Le sous-compte " + s.getNumero() + " sert dans la table de correspondance : changez d'abord la correspondance.");
        }
        sousComptes.delete(s);
    }

    private String validerNumero(Long boutiqueId, String parent, String numero, Long sauf) {
        String n = numero == null ? "" : numero.strip();
        if (!n.matches("\\d{3,8}") || !n.startsWith(parent) || n.length() <= parent.length()) {
            throw new IllegalArgumentException("Le numéro doit commencer par " + parent + ", compter 8 chiffres au plus et être plus long que le compte parent.");
        }
        if (plan.existe(n)) throw new IllegalArgumentException("Le numéro " + n + " existe déjà dans le plan officiel.");
        Optional<SousCompteBoutique> pris = sousComptes.deLaBoutique(boutiqueId).stream().filter(s -> s.getNumero().equals(n) && !s.getId().equals(sauf)).findFirst();
        if (pris.isPresent()) throw new IllegalArgumentException("Le numéro " + n + " est déjà un sous-compte de la boutique.");
        return n;
    }

    private static String validerIntitule(String intitule) {
        String i = intitule == null ? "" : intitule.strip().replaceAll("\\s+", " ");
        if (i.isEmpty()) throw new IllegalArgumentException("Saisissez l'intitulé du sous-compte.");
        if (i.length() > SousCompteBoutique.INTITULE_MAX) throw new IllegalArgumentException("L'intitulé compte " + SousCompteBoutique.INTITULE_MAX + " caractères au plus.");
        return i;
    }
}
