package org.example.stock.service;

import org.example.stock.enums.SensEmprunt;
import org.example.stock.enums.TypeOperationTresorerie;
import org.example.stock.enums.TypePartenaireFinancier;
import org.example.stock.form.PartenaireFinancierForm;
import org.example.stock.model.Montants;
import org.example.stock.model.OperationTresorerie;
import org.example.stock.model.PartenaireFinancier;
import org.example.stock.repository.OperationTresorerieRepository;
import org.example.stock.repository.PartenaireFinancierRepository;
import org.example.stock.repository.StockLockRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Autres partenaires de la boutique (banques, associés, personnel, divers), réservés au gérant : fiche, solde et historique.
 *
 * <p><b>Solde</b> (positif : la boutique lui doit ; négatif : il doit à la boutique) = compte courant net (apports en compte courant
 * moins retraits en compte courant, annulations déduites) + pour chaque emprunt, capital restant dû et intérêts échus impayés, moins
 * la même somme pour chaque prêt. Le capital apporté est affiché à titre d'information, hors solde.</p>
 */
@Service
public class PartenairesFinanciersService {
    public static final Set<TypeOperationTresorerie> TYPES_ASSOCIE = EnumSet.of(
            TypeOperationTresorerie.APPORT_CAPITAL, TypeOperationTresorerie.APPORT_COMPTE_COURANT,
            TypeOperationTresorerie.RETRAIT_CAPITAL, TypeOperationTresorerie.RETRAIT_COMPTE_COURANT);

    /** Détail du solde d'un partenaire. */
    public record Solde(BigDecimal compteCourant, BigDecimal capitalApporte, BigDecimal empruntsNets, BigDecimal total) {
        public boolean laBoutiqueDoit() { return total.signum() > 0; }
        public boolean ilDoit() { return total.signum() < 0; }
        public BigDecimal absolu() { return total.abs(); }
        public boolean nul() { return total.signum() == 0; }
    }

    public record Vue(PartenaireFinancier partenaire, Solde solde, int empruntsEnCours) {
        public boolean archivable() { return solde.nul() && empruntsEnCours == 0; }
    }

    /** Une opération de l'historique, annulée ou non. */
    public record Ligne(OperationTresorerie operation, boolean annulee, boolean annulable) { }

    public record Detail(Vue vue, List<Ligne> operations, List<EmpruntService.Vue> emprunts) { }

    private final PartenaireFinancierRepository partenaires;
    private final OperationTresorerieRepository operations;
    private final EmpruntService emprunts;
    private final BoutiqueService boutiques;
    private final StockLockRepository verrous;
    private final CaisseService caisse;
    private final EcheanceService horloge;

    public PartenairesFinanciersService(PartenaireFinancierRepository partenaires, OperationTresorerieRepository operations, EmpruntService emprunts,
                                        BoutiqueService boutiques, StockLockRepository verrous, CaisseService caisse, EcheanceService horloge) {
        this.partenaires = partenaires; this.operations = operations; this.emprunts = emprunts; this.boutiques = boutiques;
        this.verrous = verrous; this.caisse = caisse; this.horloge = horloge;
    }

    @Transactional(readOnly = true)
    public List<Vue> lister(boolean avecArchives) {
        DroitsEtats.exigerGerant();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        Map<Long, Solde> soldes = soldes(boutiqueId);
        Map<Long, Integer> enCours = empruntsEnCours(boutiqueId);
        List<Vue> vues = new ArrayList<>();
        for (PartenaireFinancier p : partenaires.lister(boutiqueId)) {
            if (!p.isActif() && !avecArchives) continue;
            vues.add(new Vue(p, soldes.getOrDefault(p.getId(), solde(Montants.ZERO, Montants.ZERO, Montants.ZERO)), enCours.getOrDefault(p.getId(), 0)));
        }
        return vues;
    }

    /** Partenaires actifs (listes déroulantes des formulaires), éventuellement d'un seul type. */
    @Transactional(readOnly = true)
    public List<PartenaireFinancier> actifs(TypePartenaireFinancier type) {
        DroitsEtats.exigerGerant();
        return partenaires.listerActifs(boutiques.idBoutiqueCourante()).stream().filter(p -> type == null || p.getType() == type).toList();
    }

    @Transactional(readOnly = true)
    public Vue vue(Long id) {
        DroitsEtats.exigerGerant();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        PartenaireFinancier p = partenaires.findByIdAndBoutiqueId(id, boutiqueId).orElseThrow(PartenairesFinanciersService::introuvable);
        return new Vue(p, soldes(boutiqueId).getOrDefault(id, solde(Montants.ZERO, Montants.ZERO, Montants.ZERO)), empruntsEnCours(boutiqueId).getOrDefault(id, 0));
    }

    @Transactional(readOnly = true)
    public Detail detail(Long id) {
        Vue vue = vue(id);
        Long boutiqueId = boutiques.idBoutiqueCourante();
        List<OperationTresorerie> ops = operations.duPartenaire(boutiqueId, id);
        Set<Long> annulees = new HashSet<>(ops.isEmpty() ? List.of() : operations.annulees(ops.stream().map(OperationTresorerie::getId).toList()));
        List<Ligne> lignes = new ArrayList<>();
        for (OperationTresorerie o : ops) {
            boolean annulee = annulees.contains(o.getId());
            lignes.add(new Ligne(o, annulee, !annulee && OperationsFinancieresService.TYPES_SAISISSABLES.contains(o.getType())));
        }
        return new Detail(vue, lignes, emprunts.listerDuPartenaire(id));
    }

    private Map<Long, Integer> empruntsEnCours(Long boutiqueId) {
        Map<Long, Integer> resultat = new HashMap<>();
        for (EmpruntService.Vue v : emprunts.lister()) if (!v.situation().clos()) resultat.merge(v.emprunt().getPartenaire().getId(), 1, Integer::sum);
        return resultat;
    }

    /** Solde de chaque partenaire de la boutique ayant une opération ou un emprunt. */
    Map<Long, Solde> soldes(Long boutiqueId) {
        Map<Long, BigDecimal> compteCourant = new HashMap<>(), capital = new HashMap<>(), nets = new HashMap<>();
        for (Object[] ligne : operations.sommesParPartenaire(boutiqueId, TYPES_ASSOCIE)) {
            Long id = (Long) ligne[0];
            TypeOperationTresorerie type = (TypeOperationTresorerie) ligne[1];
            BigDecimal somme = Montants.ouZero((BigDecimal) ligne[2]);
            switch (type) {
                case APPORT_COMPTE_COURANT -> compteCourant.merge(id, somme, BigDecimal::add);
                case RETRAIT_COMPTE_COURANT -> compteCourant.merge(id, somme.negate(), BigDecimal::add);
                case APPORT_CAPITAL -> capital.merge(id, somme, BigDecimal::add);
                case RETRAIT_CAPITAL -> capital.merge(id, somme.negate(), BigDecimal::add);
                default -> { }
            }
        }
        for (EmpruntService.Vue v : emprunts.lister()) {
            SituationEmprunt s = v.situation();
            BigDecimal du = s.capitalRestantDu().add(s.interetsEchusImpayes());
            nets.merge(v.emprunt().getPartenaire().getId(), v.emprunt().getSens() == SensEmprunt.EMPRUNT ? du : du.negate(), BigDecimal::add);
        }
        Set<Long> ids = new HashSet<>(compteCourant.keySet()); ids.addAll(capital.keySet()); ids.addAll(nets.keySet());
        Map<Long, Solde> resultat = new HashMap<>();
        for (Long id : ids) resultat.put(id, new Solde(compteCourant.getOrDefault(id, Montants.ZERO), capital.getOrDefault(id, Montants.ZERO),
                nets.getOrDefault(id, Montants.ZERO), compteCourant.getOrDefault(id, Montants.ZERO).add(nets.getOrDefault(id, Montants.ZERO))));
        return resultat;
    }

    private static Solde solde(BigDecimal cc, BigDecimal capital, BigDecimal nets) { return new Solde(cc, capital, nets, cc.add(nets)); }

    // ---- Écritures ------------------------------------------------------------------------------------------------------

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PartenaireFinancier creer(PartenaireFinancierForm f) {
        verrous.verrouillerBoutiqueCourante();
        caisse.gerantPourEcriture();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        String nom = nomValide(f);
        PartenaireFinancier p = new PartenaireFinancier();
        p.setBoutique(boutiques.boutiqueCourante());
        p.renommer(nom);
        if (partenaires.existsByBoutiqueIdAndNomCle(boutiqueId, p.getNomCle())) throw new IllegalArgumentException("Un partenaire de ce nom existe déjà.");
        p.setType(f.getType()); p.setTelephone(f.getTelephone()); p.setNote(f.getNote()); p.setActif(true); p.setDateCreation(horloge.maintenant());
        try {
            return partenaires.saveAndFlush(p);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Un partenaire de ce nom existe déjà.");
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PartenaireFinancier modifier(Long id, PartenaireFinancierForm f) {
        verrous.verrouillerBoutiqueCourante();
        caisse.gerantPourEcriture();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        PartenaireFinancier p = verrous.verrouillerPartenaireFinancier(id);
        String nom = nomValide(f);
        String cle = org.example.stock.model.CompteTresorerieBoutique.cle(nom);
        if (partenaires.existsByBoutiqueIdAndNomCleAndIdNot(boutiqueId, cle, id)) throw new IllegalArgumentException("Un partenaire de ce nom existe déjà.");
        if (p.getType() != f.getType() && aDeLHistorique(boutiqueId, id)) {
            throw new IllegalArgumentException("Le type d'un partenaire ne change plus dès qu'il a une opération ou un emprunt.");
        }
        p.renommer(nom); p.setType(f.getType()); p.setTelephone(f.getTelephone()); p.setNote(f.getNote());
        try {
            return partenaires.saveAndFlush(p);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Un partenaire de ce nom existe déjà.");
        }
    }

    /** Archive un partenaire dont le solde est nul et sans emprunt en cours (il garde son historique), ou le réactive. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void definirActif(Long id, boolean actif) {
        verrous.verrouillerBoutiqueCourante();
        caisse.gerantPourEcriture();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        PartenaireFinancier p = verrous.verrouillerPartenaireFinancier(id);
        if (!actif) {
            Solde solde = soldes(boutiqueId).getOrDefault(id, solde(Montants.ZERO, Montants.ZERO, Montants.ZERO));
            if (!solde.nul()) throw new IllegalArgumentException("Ce partenaire a un solde : réglez-le avant de l'archiver.");
            if (empruntsEnCours(boutiqueId).getOrDefault(id, 0) > 0) throw new IllegalArgumentException("Ce partenaire a un emprunt ou un prêt en cours : soldez-le avant de l'archiver.");
        }
        p.setActif(actif);
        partenaires.save(p);
    }

    private boolean aDeLHistorique(Long boutiqueId, Long id) {
        return !operations.duPartenaire(boutiqueId, id).isEmpty() || !emprunts.listerDuPartenaire(id).isEmpty();
    }

    private static String nomValide(PartenaireFinancierForm f) {
        if (f == null || f.getNom() == null || f.getNom().isBlank() || f.getNom().length() > PartenaireFinancier.NOM_MAX || f.getType() == null) {
            throw new IllegalArgumentException("Le nom (80 caractères au plus) et le type du partenaire sont obligatoires.");
        }
        return org.example.stock.model.CompteTresorerieBoutique.normaliserNom(f.getNom());
    }

    private static ResponseStatusException introuvable() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Partenaire introuvable"); }
}
