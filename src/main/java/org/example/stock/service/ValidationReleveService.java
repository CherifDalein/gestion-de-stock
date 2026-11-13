package org.example.stock.service;

import org.example.stock.enums.StatutReleve;
import org.example.stock.enums.TypeEvenementReleve;
import org.example.stock.export.DroitsExport;
import org.example.stock.export.Textes;
import org.example.stock.model.EvenementReleve;
import org.example.stock.model.MouvementCaisse;
import org.example.stock.model.RapprochementMouvement;
import org.example.stock.model.ReleveBancaire;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.EvenementReleveRepository;
import org.example.stock.repository.MouvementCaisseRepository;
import org.example.stock.repository.RapprochementMouvementRepository;
import org.example.stock.repository.ReleveBancaireRepository;
import org.example.stock.repository.StockLockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validation et dévalidation d'un relevé par le gérant (étape 3c).
 *
 * <p><b>Validation</b> : seulement si les soldes sont cohérents (ouverture + lignes = clôture, ouverture = clôture du relevé précédent, qui doit
 * être validé), si l'écart de rapprochement est nul et si aucun mouvement rapproché n'a été annulé depuis le pointage. Le relevé et ses
 * rapprochements sont alors figés, et {@link CaisseService#enregistrerAnnulation} refuse d'annuler un de ses mouvements.</p>
 *
 * <p><b>Dévalidation</b> : motif obligatoire, uniquement pour le dernier relevé validé du compte ; l'historique garde les deux événements.</p>
 *
 * <p>Ordre des verrous : boutique, relevé, compte, mouvements rapprochés par identifiant croissant. Deux validations simultanées, ou une
 * validation et une annulation d'un mouvement rapproché, se sérialisent sur le verrou de la boutique puis du relevé : une seule passe,
 * l'autre reçoit 409 (validation) ou un refus explicite (annulation).</p>
 */
@Service
public class ValidationReleveService {
    private final StockLockRepository verrous;
    private final CaisseService caisse;
    private final ComptesTresorerieService comptes;
    private final BoutiqueService boutiques;
    private final EcheanceService horloge;
    private final EtatRapprochementService etats;
    private final ReleveBancaireRepository releves;
    private final RapprochementMouvementRepository liens;
    private final MouvementCaisseRepository mouvements;
    private final EvenementReleveRepository evenements;

    public ValidationReleveService(StockLockRepository verrous, CaisseService caisse, ComptesTresorerieService comptes, BoutiqueService boutiques,
                                   EcheanceService horloge, EtatRapprochementService etats, ReleveBancaireRepository releves,
                                   RapprochementMouvementRepository liens, MouvementCaisseRepository mouvements, EvenementReleveRepository evenements) {
        this.verrous = verrous; this.caisse = caisse; this.comptes = comptes; this.boutiques = boutiques; this.horloge = horloge; this.etats = etats;
        this.releves = releves; this.liens = liens; this.mouvements = mouvements; this.evenements = evenements;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void valider(Long releveId) {
        verrous.verrouillerBoutiqueCourante();
        Utilisateur gerant = caisse.gerantPourEcriture();
        ReleveBancaire r = verrous.verrouillerReleve(releveId);
        if (r.getStatut() != StatutReleve.BROUILLON) throw new ReleveDejaTraiteException(r.getNumero(), r.getStatut().getLibelle());
        Long boutiqueId = boutiques.idBoutiqueCourante(), compteId = r.getCompte().getId();
        comptes.verrouiller(boutiqueId, gerant, false, false, List.of(compteId));
        List<RapprochementMouvement> pointes = liens.duReleve(releveId);
        List<Long> ids = pointes.stream().map(rm -> rm.getMouvement().getId()).sorted().toList();
        if (!ids.isEmpty()) liens.verrouillerMouvements(ids);

        EtatRapprochementService.Etat etat = etats.calculer(r);
        if (!etat.controle().coherent()) {
            throw new IllegalArgumentException("Les soldes du relevé ne sont pas cohérents : ouverture " + Textes.montant(r.getSoldeOuverture()) + " + lignes "
                    + Textes.montant(etat.controle().sommeLignes()) + " = " + Textes.montant(etat.controle().attendu()) + " GNF, mais la clôture indiquée est "
                    + Textes.montant(r.getSoldeCloture()) + " GNF (écart " + Textes.montant(etat.controle().ecart()) + "). Complétez les lignes ou corrigez les soldes.");
        }
        exigerChainage(r);
        exigerAnnulationsEnsemble(boutiqueId, pointes);
        if (!etat.ecartNul()) {
            throw new IllegalArgumentException("L'écart de rapprochement n'est pas nul : " + Textes.montant(etat.ecart()) + " GNF (solde calculé "
                    + Textes.montant(etat.soldeCalcule()) + " GNF, solde du relevé " + Textes.montant(r.getSoldeCloture()) + " GNF). Pointez les lignes manquantes ou enregistrez-les dans l'appli.");
        }
        r.setStatut(StatutReleve.VALIDE); r.setValidePar(gerant); r.setDateValidation(horloge.maintenant()); r.setSoldeAppliValidation(etat.soldeAppli());
        evenement(r, gerant, TypeEvenementReleve.VALIDATION, null, etat.soldeAppli());
        releves.flush();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void devalider(Long releveId, String motifSaisi) {
        String motif = ComptageCaisseService.motifValide(motifSaisi, "de la dévalidation");
        verrous.verrouillerBoutiqueCourante();
        Utilisateur gerant = caisse.gerantPourEcriture();
        ReleveBancaire r = verrous.verrouillerReleve(releveId);
        if (r.getStatut() != StatutReleve.VALIDE) throw new ReleveDejaTraiteException(r.getNumero(), r.getStatut().getLibelle());
        comptes.verrouiller(boutiques.idBoutiqueCourante(), gerant, false, false, List.of(r.getCompte().getId()));
        ReleveBancaire dernier = releves.parStatut(r.getCompte().getId(), StatutReleve.VALIDE).stream().findFirst().orElse(r);
        if (!dernier.getId().equals(r.getId())) {
            throw new IllegalArgumentException("Seul le dernier relevé validé du compte peut être dévalidé : dévalidez d'abord le relevé " + dernier.getNumero() + ".");
        }
        BigDecimal soldeAvant = r.getSoldeAppliValidation();
        r.setStatut(StatutReleve.BROUILLON); r.setValidePar(null); r.setDateValidation(null); r.setSoldeAppliValidation(null);
        evenement(r, gerant, TypeEvenementReleve.DEVALIDATION, motif, soldeAvant);
        releves.flush();
    }

    /** Vrai si ce relevé est le dernier relevé validé de son compte (le seul qui puisse être dévalidé). */
    @Transactional(readOnly = true)
    public boolean peutEtreDevalide(ReleveBancaire r) {
        return r.estValide() && releves.parStatut(r.getCompte().getId(), StatutReleve.VALIDE).stream().findFirst().map(d -> d.getId().equals(r.getId())).orElse(false);
    }

    @Transactional(readOnly = true)
    public List<EvenementReleve> historique(Long releveId) {
        DroitsExport.exigerGerant();
        releves.findByIdAndBoutiqueId(releveId, boutiques.idBoutiqueCourante()).orElseThrow(ReleveBancaireService::introuvable);
        return evenements.findByReleveIdOrderByIdAsc(releveId);
    }

    /** Les relevés d'un compte se suivent : le précédent est validé et sa clôture est l'ouverture de celui-ci ; aucun autre ne le recoupe. */
    private void exigerChainage(ReleveBancaire r) {
        Long compteId = r.getCompte().getId();
        if (releves.chevauche(compteId, r.getId(), r.getDateDebut(), r.getDateFin())) {
            throw new IllegalArgumentException("La période de ce relevé chevauche celle d'un autre relevé du compte.");
        }
        ReleveBancaire precedent = releves.precedents(compteId, r.getId(), r.getDateDebut()).stream().findFirst().orElse(null);
        if (precedent == null) return;
        if (precedent.getStatut() != StatutReleve.VALIDE) {
            throw new IllegalArgumentException("Validez d'abord le relevé précédent du compte (" + precedent.getNumero() + ") : les relevés d'un compte se valident dans l'ordre.");
        }
        if (precedent.getSoldeCloture().compareTo(r.getSoldeOuverture()) != 0) {
            throw new IllegalArgumentException("Le solde d'ouverture (" + Textes.montant(r.getSoldeOuverture()) + " GNF) doit être la clôture du relevé précédent "
                    + precedent.getNumero() + " (" + Textes.montant(precedent.getSoldeCloture()) + " GNF).");
        }
    }

    /** Aucun mouvement rapproché ne doit avoir été annulé depuis le pointage sans que son annulation soit dans le même rapprochement. */
    private void exigerAnnulationsEnsemble(Long boutiqueId, List<RapprochementMouvement> pointes) {
        if (pointes.isEmpty()) return;
        java.util.Map<Long, Long> groupeDe = new java.util.HashMap<>();
        for (RapprochementMouvement rm : pointes) groupeDe.put(rm.getMouvement().getId(), rm.getRapprochement().getId());
        for (MouvementCaisse a : mouvements.findAnnulationsDe(boutiqueId, groupeDe.keySet())) {
            Long origine = a.getAnnulationDe().getId();
            if (!java.util.Objects.equals(groupeDe.get(a.getId()), groupeDe.get(origine))) {
                throw new IllegalArgumentException("Un mouvement rapproché a été annulé depuis le pointage (« " + a.getAnnulationDe().getMotif()
                        + " »). Défaites ce rapprochement, ou excluez ensemble le mouvement et son annulation, avant de valider.");
            }
        }
        for (RapprochementMouvement rm : pointes) {
            MouvementCaisse m = rm.getMouvement();
            if (m.getAnnulationDe() != null && !java.util.Objects.equals(groupeDe.get(m.getAnnulationDe().getId()), rm.getRapprochement().getId())) {
                throw new IllegalArgumentException("Un mouvement rapproché est l'annulation d'un autre mouvement qui n'est pas dans le même rapprochement. Défaites ce rapprochement avant de valider.");
            }
        }
    }

    private void evenement(ReleveBancaire r, Utilisateur gerant, TypeEvenementReleve type, String motif, BigDecimal soldeAppli) {
        EvenementReleve e = new EvenementReleve();
        e.setReleve(r); e.setType(type); e.setUtilisateur(gerant); e.setDateEvenement(horloge.maintenant()); e.setMotif(motif); e.setSoldeAppli(soldeAppli);
        evenements.save(e);
    }
}
