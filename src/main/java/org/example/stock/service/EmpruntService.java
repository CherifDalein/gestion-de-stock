package org.example.stock.service;

import jakarta.validation.Validator;
import org.example.stock.enums.Periodicite;
import org.example.stock.enums.SensEmprunt;
import org.example.stock.enums.TypeOperationTresorerie;
import org.example.stock.form.EcheancierForm;
import org.example.stock.form.EmpruntForm;
import org.example.stock.form.RemboursementForm;
import org.example.stock.model.CompteTresorerieBoutique;
import org.example.stock.model.EcheanceEmprunt;
import org.example.stock.model.Emprunt;
import org.example.stock.model.ImputationEcheance;
import org.example.stock.model.Montants;
import org.example.stock.model.MouvementCaisse;
import org.example.stock.model.OperationTresorerie;
import org.example.stock.model.PartenaireFinancier;
import org.example.stock.model.SourceMouvement;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.EcheanceEmpruntRepository;
import org.example.stock.repository.EmpruntRepository;
import org.example.stock.repository.ImputationEcheanceRepository;
import org.example.stock.repository.MouvementCaisseRepository;
import org.example.stock.repository.OperationTresorerieRepository;
import org.example.stock.repository.StockLockRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Emprunts (EM-) et prêts (PR-) : création avec déblocage, échéancier à intérêts simples, remboursements imputés sur les échéances,
 * remboursement anticipé du solde et annulation. Réservé au gérant.
 *
 * <p>Ordre des verrous : boutique, emprunt, partenaire, comptes (voir « Ordre des verrous » dans {@code docs/lot-2.md}). Les écritures
 * d'une même boutique sont sérialisées par le verrou de la boutique, et un remboursement vérifie sous verrou que l'échéance et le reste
 * dû affichés par la page sont toujours ceux de la base : deux remboursements simultanés de la même échéance ne la paient pas deux fois.</p>
 */
@Service
public class EmpruntService {
    private final CaisseService caisse;
    private final StockLockRepository verrous;
    private final ComptesTresorerieService comptes;
    private final BoutiqueService boutiques;
    private final InitialisationBoutiqueService initialisation;
    private final NumerotationFactureService numerotation;
    private final EmpruntRepository emprunts;
    private final EcheanceEmpruntRepository echeances;
    private final ImputationEcheanceRepository imputations;
    private final OperationTresorerieRepository operations;
    private final MouvementCaisseRepository mouvements;
    private final EcrituresFinancieres ecritures;
    private final EcheanceService horloge;
    private final Validator validator;

    public EmpruntService(CaisseService caisse, StockLockRepository verrous, ComptesTresorerieService comptes, BoutiqueService boutiques,
                          InitialisationBoutiqueService initialisation, NumerotationFactureService numerotation, EmpruntRepository emprunts,
                          EcheanceEmpruntRepository echeances, ImputationEcheanceRepository imputations, OperationTresorerieRepository operations,
                          MouvementCaisseRepository mouvements, EcrituresFinancieres ecritures, EcheanceService horloge, Validator validator) {
        this.caisse = caisse; this.verrous = verrous; this.comptes = comptes; this.boutiques = boutiques; this.initialisation = initialisation;
        this.numerotation = numerotation; this.emprunts = emprunts; this.echeances = echeances; this.imputations = imputations;
        this.operations = operations; this.mouvements = mouvements; this.ecritures = ecritures; this.horloge = horloge; this.validator = validator;
    }

    // ---- Lectures (gérant seulement) -----------------------------------------------------------------------------------

    /** Un emprunt et sa situation calculée à la date du jour. */
    public record Vue(Emprunt emprunt, SituationEmprunt situation) { }

    /** Un remboursement de l'historique : son opération, ce qu'il a payé (capital, intérêts, remise) et son état. */
    public record Remboursement(OperationTresorerie operation, BigDecimal capital, BigDecimal interet, BigDecimal interetRemis,
                                boolean annule, boolean annulable) { }

    /** Un emprunt avec son historique : déblocage, frais de dossier et remboursements. */
    public record Detail(Vue vue, List<Remboursement> remboursements, OperationTresorerie deblocage, OperationTresorerie frais) { }

    /** Retards de la boutique pour le tableau de bord et la liste. */
    public record Retards(int emprunts, int echeances, BigDecimal montant, List<Vue> premiers) {
        public boolean aucun() { return emprunts == 0; }
    }

    private void exigerGerant() { DroitsEtats.exigerGerant(); }

    @Transactional(readOnly = true)
    public List<Vue> lister() {
        exigerGerant();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        Map<Long, SituationEmprunt> situations = situations(boutiqueId);
        List<Vue> vues = new ArrayList<>();
        for (Emprunt e : emprunts.lister(boutiqueId)) vues.add(new Vue(e, situations.get(e.getId())));
        return vues;
    }

    @Transactional(readOnly = true)
    public List<Vue> listerDuPartenaire(Long partenaireId) {
        exigerGerant();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        Map<Long, SituationEmprunt> situations = situations(boutiqueId);
        List<Vue> vues = new ArrayList<>();
        for (Emprunt e : emprunts.listerDuPartenaire(boutiqueId, partenaireId)) vues.add(new Vue(e, situations.get(e.getId())));
        return vues;
    }

    /** Situation de tous les emprunts de la boutique en trois requêtes. */
    Map<Long, SituationEmprunt> situations(Long boutiqueId) {
        LocalDate aujourdhui = horloge.aujourdHui();
        Map<Long, List<EcheanceEmprunt>> parEmprunt = new HashMap<>();
        for (EcheanceEmprunt e : echeances.deLaBoutique(boutiqueId)) parEmprunt.computeIfAbsent(e.getEmprunt().getId(), id -> new ArrayList<>()).add(e);
        Map<Long, List<ImputationEcheance>> imputes = new HashMap<>();
        for (ImputationEcheance i : imputations.activesDeLaBoutique(boutiqueId)) {
            imputes.computeIfAbsent(i.getEcheance().getEmprunt().getId(), id -> new ArrayList<>()).add(i);
        }
        Map<Long, SituationEmprunt> resultat = new HashMap<>();
        for (Emprunt e : emprunts.lister(boutiqueId)) {
            resultat.put(e.getId(), SituationEmprunt.de(e, parEmprunt.getOrDefault(e.getId(), List.of()), imputes.getOrDefault(e.getId(), List.of()), aujourdhui));
        }
        return resultat;
    }

    private SituationEmprunt situation(Emprunt emprunt) {
        return SituationEmprunt.de(emprunt, echeances.deLEmprunt(emprunt.getId()), imputations.activesDeLEmprunt(emprunt.getId()), horloge.aujourdHui());
    }

    @Transactional(readOnly = true)
    public Vue vue(Long id) {
        exigerGerant();
        Emprunt emprunt = emprunts.findByIdAndBoutiqueId(id, boutiques.idBoutiqueCourante())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Emprunt introuvable"));
        return new Vue(emprunt, situation(emprunt));
    }

    @Transactional(readOnly = true)
    public Detail detail(Long id) {
        Vue vue = vue(id);
        Long boutiqueId = boutiques.idBoutiqueCourante();
        List<OperationTresorerie> ops = operations.deLEmprunt(boutiqueId, id);
        Set<Long> annulees = new HashSet<>(ops.isEmpty() ? List.of() : operations.annulees(ops.stream().map(OperationTresorerie::getId).toList()));
        Map<Long, BigDecimal[]> parts = new HashMap<>();
        if (!ops.isEmpty()) for (ImputationEcheance i : imputations.deLOperation(ops.stream().map(OperationTresorerie::getId).toList())) {
            BigDecimal[] s = parts.computeIfAbsent(i.getOperation().getId(), k -> new BigDecimal[]{Montants.ZERO, Montants.ZERO, Montants.ZERO});
            s[0] = s[0].add(i.getCapital()); s[1] = s[1].add(i.getInteret()); s[2] = s[2].add(i.getInteretRemis());
        }
        Long dernier = ops.stream().filter(o -> o.getType() == TypeOperationTresorerie.REMBOURSEMENT && !annulees.contains(o.getId()))
                .map(OperationTresorerie::getId).max(Long::compare).orElse(null);
        List<Remboursement> remboursements = new ArrayList<>();
        OperationTresorerie deblocage = null, frais = null;
        for (OperationTresorerie o : ops) {
            if (o.getType() == TypeOperationTresorerie.DEBLOCAGE) deblocage = o;
            else if (o.getType() == TypeOperationTresorerie.FRAIS_DOSSIER) frais = o;
            else if (o.getType() == TypeOperationTresorerie.REMBOURSEMENT) {
                BigDecimal[] s = parts.getOrDefault(o.getId(), new BigDecimal[]{Montants.ZERO, Montants.ZERO, Montants.ZERO});
                boolean annule = annulees.contains(o.getId());
                remboursements.add(new Remboursement(o, s[0], s[1], s[2], annule, !annule && o.getId().equals(dernier)));
            }
        }
        return new Detail(vue, remboursements, deblocage, frais);
    }

    /** Emprunts et prêts en retard (au moins une échéance échue non soldée), du plus ancien retard au plus récent. */
    @Transactional(readOnly = true)
    public Retards retards(int limite) {
        exigerGerant();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        Map<Long, SituationEmprunt> situations = situations(boutiqueId);
        List<Vue> enRetard = new ArrayList<>();
        for (Emprunt e : emprunts.lister(boutiqueId)) {
            SituationEmprunt s = situations.get(e.getId());
            if (s.enRetardMaintenant()) enRetard.add(new Vue(e, s));
        }
        enRetard.sort(Comparator.comparing((Vue v) -> v.situation().plusGrandRetardEnJours()).reversed().thenComparing(v -> v.emprunt().getId()));
        BigDecimal montant = Montants.ZERO;
        int nombre = 0;
        for (Vue v : enRetard) { montant = montant.add(v.situation().montantEnRetard()); nombre += v.situation().nombreRetards(); }
        return new Retards(enRetard.size(), nombre, montant, enRetard.stream().limit(Math.max(0, limite)).toList());
    }

    // ---- Création -----------------------------------------------------------------------------------------------------

    /** Identifiant de l'emprunt créé et, pour la répétition exacte d'un formulaire déjà traité, l'indication qu'il existait. */
    public record Creation(Long id, boolean repetition) { }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Creation creer(EmpruntForm f) {
        verrous.verrouillerBoutiqueCourante();
        Utilisateur acteur = caisse.gerantPourEcriture();
        if (f == null || !validator.validate(f).isEmpty()) throw new IllegalArgumentException("Les champs de l'emprunt doivent être valides.");
        Long boutiqueId = boutiques.idBoutiqueCourante();
        Long compteId = comptes.resoudreId(f.getCompte());
        BigDecimal capital = Montants.positifOuNul(f.getCapital(), "Le capital");
        BigDecimal taux = f.getTauxAnnuel().setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal frais = f.getFraisDossier() == null ? Montants.ZERO : Montants.positifOuNul(f.getFraisDossier(), "Les frais de dossier");
        Emprunt existant = emprunts.findByJeton(f.getJetonOperation()).orElse(null);
        if (existant != null) {
            if (!existant.getBoutique().getId().equals(boutiqueId) || !existant.getUtilisateur().getId().equals(acteur.getId())
                    || existant.getSens() != f.getSens() || existant.getCapital().compareTo(capital) != 0
                    || !existant.getPartenaire().getId().equals(f.getPartenaireId()) || !existant.getCompte().getId().equals(compteId)
                    || existant.getTauxAnnuel().compareTo(taux) != 0 || existant.getNombreEcheances() != f.getNombreEcheances()) {
                throw new OperationTresorerieConflitException();
            }
            return new Creation(existant.getId(), true);
        }
        LocalDate aujourdhui = horloge.aujourdHui();
        if (f.getDateEmprunt().isAfter(aujourdhui)) throw new IllegalArgumentException("La date de l'emprunt ne peut pas être dans le futur.");
        if (f.getDatePremiereEcheance().isBefore(f.getDateEmprunt())) {
            throw new IllegalArgumentException("La première échéance ne peut pas précéder la date de l'emprunt.");
        }
        if (f.getSens() == SensEmprunt.PRET && frais.signum() > 0) throw new IllegalArgumentException("Les frais de dossier ne concernent que les emprunts.");
        if (frais.compareTo(capital) > 0) throw new IllegalArgumentException("Les frais de dossier ne peuvent pas dépasser le capital emprunté.");
        List<EcheancierCalcul.Ligne> plan = EcheancierCalcul.generer(capital, taux, f.getNombreEcheances(), f.getPeriodicite(), f.getDatePremiereEcheance());
        initialisation.verrouillerEtClorePourActivite();
        PartenaireFinancier partenaire = verrous.verrouillerPartenaireFinancier(f.getPartenaireId());
        if (!partenaire.isActif()) throw new IllegalArgumentException("Ce partenaire est archivé : réactivez-le avant d'enregistrer un emprunt.");
        CompteTresorerieBoutique compte = comptes.verrouiller(boutiqueId, acteur, false, true, List.of(compteId)).get(compteId);
        boolean emprunt = f.getSens() == SensEmprunt.EMPRUNT;
        if (!emprunt) ecritures.exigerSolde(boutiqueId, compte, capital);

        Emprunt e = new Emprunt();
        e.setBoutique(boutiques.boutiqueCourante()); e.setPartenaire(partenaire); e.setUtilisateur(acteur); e.setCompte(compte);
        e.setNumero(emprunt ? numerotation.attribuerNumeroEmprunt() : numerotation.attribuerNumeroPret());
        e.setSens(f.getSens()); e.setDateEmprunt(f.getDateEmprunt()); e.setCapital(capital); e.setTauxAnnuel(taux);
        e.setNombreEcheances(f.getNombreEcheances()); e.setPeriodicite(f.getPeriodicite()); e.setDatePremiereEcheance(f.getDatePremiereEcheance());
        e.setFraisDossier(frais); e.setNote(f.getNote()); e.setJeton(f.getJetonOperation()); e.setDateCreation(horloge.maintenant());
        e = emprunts.saveAndFlush(e);
        for (EcheancierCalcul.Ligne ligne : plan) {
            EcheanceEmprunt echeance = new EcheanceEmprunt();
            echeance.setEmprunt(e); echeance.setRang(ligne.rang()); echeance.setDateEcheance(ligne.date());
            echeance.setCapital(ligne.capital()); echeance.setInteret(ligne.interet());
            echeances.save(echeance);
        }
        String motif = (emprunt ? "Emprunt " : "Prêt ") + e.getNumero() + " - " + partenaire.getNom();
        var date = ecritures.dateDe(f.getDateEmprunt());
        OperationTresorerie deblocage = ecritures.operation(e.getBoutique(), acteur, f.getJetonOperation(), TypeOperationTresorerie.DEBLOCAGE, compte,
                capital, motif, date, partenaire, e);
        ecritures.mouvement(deblocage, compte, emprunt ? capital : capital.negate(), emprunt ? SourceMouvement.DEBLOCAGE_EMPRUNT : SourceMouvement.DEBLOCAGE_PRET, motif);
        if (frais.signum() > 0) {
            String motifFrais = "Frais de dossier " + e.getNumero();
            OperationTresorerie operationFrais = ecritures.operation(e.getBoutique(), acteur, jetonDerive(f.getJetonOperation(), "frais"),
                    TypeOperationTresorerie.FRAIS_DOSSIER, compte, frais, motifFrais, date, partenaire, e);
            ecritures.mouvement(operationFrais, compte, frais.negate(), SourceMouvement.FRAIS_DOSSIER, motifFrais);
        }
        mouvements.flush();
        return new Creation(e.getId(), false);
    }

    static String jetonDerive(String jeton, String suffixe) {
        return UUID.nameUUIDFromBytes((jeton + "/" + suffixe).getBytes(StandardCharsets.UTF_8)).toString();
    }

    // ---- Modification de l'échéancier -------------------------------------------------------------------------------------

    /**
     * Change les dates et les capitaux des échéances tant qu'aucun remboursement non annulé n'existe ; les intérêts sont recalculés.
     * Le nombre d'échéances ne change pas ; le total des capitaux reste le capital emprunté.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void modifierEcheancier(Long id, EcheancierForm f) {
        verrous.verrouillerBoutiqueCourante();
        caisse.gerantPourEcriture();
        Emprunt emprunt = verrous.verrouillerEmprunt(id);
        verrous.verrouillerPartenaireFinancier(emprunt.getPartenaire().getId());
        if (imputations.existeActive(emprunt.getId())) {
            throw new IllegalArgumentException("L'échéancier est figé : un remboursement est enregistré. Annulez les remboursements pour le modifier.");
        }
        List<EcheanceEmprunt> actuelles = echeances.deLEmprunt(emprunt.getId());
        if (f == null || f.getDates() == null || f.getCapitaux() == null || f.getDates().size() != actuelles.size() || f.getCapitaux().size() != actuelles.size()) {
            throw new IllegalArgumentException("Indiquez la date et le capital de chacune des " + actuelles.size() + " échéances.");
        }
        List<EcheancierCalcul.Ligne> plan = EcheancierCalcul.recalculer(emprunt.getCapital(), emprunt.getTauxAnnuel(), emprunt.getPeriodicite(), f.getDates(), f.getCapitaux());
        for (int k = 0; k < actuelles.size(); k++) {
            EcheanceEmprunt e = actuelles.get(k);
            e.setDateEcheance(plan.get(k).date()); e.setCapital(plan.get(k).capital()); e.setInteret(plan.get(k).interet());
        }
        emprunt.setDatePremiereEcheance(plan.getFirst().date());
        echeances.saveAll(actuelles);
        emprunts.save(emprunt);
        echeances.flush();
    }

    // ---- Remboursement ----------------------------------------------------------------------------------------------------

    /** Remboursement d'une partie ou de la totalité de l'emprunt ; retourne vrai pour la répétition exacte d'un formulaire déjà traité. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean rembourser(Long id, RemboursementForm f) {
        verrous.verrouillerBoutiqueCourante();
        Utilisateur acteur = caisse.gerantPourEcriture();
        if (f == null || !validator.validate(f).isEmpty()) throw new IllegalArgumentException("Le montant, le compte et l'état affiché doivent être valides.");
        Long boutiqueId = boutiques.idBoutiqueCourante();
        Emprunt emprunt = verrous.verrouillerEmprunt(id);
        Long compteId = comptes.resoudreId(f.getCompte());
        BigDecimal montant = Montants.positifOuNul(f.getMontant(), "Le montant");
        if (montant.signum() <= 0) throw new IllegalArgumentException("Le montant doit être supérieur à zéro.");
        String motif = f.getNote() != null ? f.getNote() : (emprunt.estEmprunt() ? "Remboursement de l'emprunt " : "Remboursement reçu du prêt ") + emprunt.getNumero();
        if (ecritures.repetition(f.getJetonOperation(), boutiqueId, acteur.getId(), TypeOperationTresorerie.REMBOURSEMENT, compteId, montant, motif,
                emprunt.getPartenaire().getId(), emprunt.getId())) return true;
        verrous.verrouillerPartenaireFinancier(emprunt.getPartenaire().getId());
        initialisation.verrouillerEtClorePourActivite();
        CompteTresorerieBoutique compte = comptes.verrouiller(boutiqueId, acteur, false, true, List.of(compteId)).get(compteId);
        SituationEmprunt situation = situation(emprunt);
        if (situation.clos()) throw new IllegalArgumentException("Cet " + (emprunt.estEmprunt() ? "emprunt" : "prêt") + " est déjà soldé.");
        // État affiché par la page : s'il a changé, un autre remboursement est passé entre-temps (409, rien n'est enregistré).
        if (f.getRangAttendu() != situation.prochaine().rang() || f.getResteAttendu().compareTo(situation.totalRestantDu()) != 0) {
            throw new EmpruntObsoleteException();
        }
        List<SituationEmprunt.Part> parts;
        if (f.isAnticipe()) {
            if (montant.compareTo(situation.soldeAnticipe()) != 0) {
                throw new IllegalArgumentException("Le remboursement anticipé du solde doit être de " + situation.soldeAnticipe().toPlainString() + ".");
            }
            parts = situation.imputerAnticipe();
        } else {
            parts = situation.imputer(montant);
        }
        BigDecimal capital = Montants.ZERO, interet = Montants.ZERO;
        for (SituationEmprunt.Part p : parts) { capital = capital.add(p.capital()); interet = interet.add(p.interet()); }
        boolean emprunte = emprunt.estEmprunt();
        if (emprunte) ecritures.exigerSolde(boutiqueId, compte, montant);
        OperationTresorerie operation = ecritures.operation(emprunt.getBoutique(), acteur, f.getJetonOperation(), TypeOperationTresorerie.REMBOURSEMENT, compte,
                montant, motif, horloge.maintenant(), emprunt.getPartenaire(), emprunt);
        ecritures.mouvement(operation, compte, emprunte ? capital.negate() : capital,
                emprunte ? SourceMouvement.REMBOURSEMENT_EMPRUNT : SourceMouvement.REMBOURSEMENT_PRET, motif + " (capital)");
        ecritures.mouvement(operation, compte, emprunte ? interet.negate() : interet,
                emprunte ? SourceMouvement.INTERETS_EMPRUNT : SourceMouvement.INTERETS_PRET, motif + " (intérêts)");
        for (SituationEmprunt.Part p : parts) {
            ImputationEcheance imputation = new ImputationEcheance();
            imputation.setOperation(operation); imputation.setEcheance(p.echeance());
            imputation.setCapital(p.capital()); imputation.setInteret(p.interet()); imputation.setInteretRemis(p.interetRemis());
            imputations.save(imputation);
        }
        mouvements.flush();
        return false;
    }

    /** Annule un remboursement par contre-passation de ses mouvements, du plus récent au plus ancien, avec un motif de 3 à 255 caractères. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void annulerRemboursement(Long empruntId, Long operationId, String motifSaisi) {
        verrous.verrouillerBoutiqueCourante();
        Utilisateur acteur = caisse.gerantPourEcriture();
        String motif = motifSaisi == null ? "" : motifSaisi.strip();
        if (motif.length() < AnnulationReglementService.MOTIF_MINIMUM || motif.length() > AnnulationReglementService.MOTIF_MAXIMUM) {
            throw new IllegalArgumentException("Le motif de l'annulation doit comporter de 3 à 255 caractères.");
        }
        Long boutiqueId = boutiques.idBoutiqueCourante();
        Emprunt emprunt = verrous.verrouillerEmprunt(empruntId);
        verrous.verrouillerPartenaireFinancier(emprunt.getPartenaire().getId());
        OperationTresorerie operation = operations.findByIdAndBoutiqueId(operationId, boutiqueId)
                .filter(o -> o.getType() == TypeOperationTresorerie.REMBOURSEMENT && o.getEmprunt() != null && o.getEmprunt().getId().equals(empruntId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Remboursement introuvable"));
        List<MouvementCaisse> origines = mouvements.findByOperationTresorerieIdOrderByIdAsc(operation.getId());
        if (origines.stream().anyMatch(m -> SourceMouvement.ANNULATION.equals(m.getSource()))) {
            throw new AnnulationDejaFaiteException("Ce remboursement a déjà été annulé. Rien n'a été enregistré une seconde fois : vérifiez le journal de caisse.");
        }
        List<OperationTresorerie> autres = operations.deLEmprunt(boutiqueId, empruntId).stream()
                .filter(o -> o.getType() == TypeOperationTresorerie.REMBOURSEMENT && o.getId() > operation.getId()).toList();
        if (!autres.isEmpty() && !operations.annulees(autres.stream().map(OperationTresorerie::getId).toList()).containsAll(autres.stream().map(OperationTresorerie::getId).toList())) {
            throw new IllegalArgumentException("Annulez d'abord le remboursement le plus récent : les remboursements s'annulent du dernier au premier.");
        }
        for (MouvementCaisse origine : origines) caisse.enregistrerAnnulation(origine, motif, acteur);
    }

    /** Périodicités proposées par les formulaires. */
    public List<Periodicite> periodicites() { return List.of(Periodicite.values()); }
}
