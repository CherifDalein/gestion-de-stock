package org.example.stock.service;

import org.example.stock.export.DroitsExport;
import org.example.stock.model.LigneReleve;
import org.example.stock.model.Montants;
import org.example.stock.model.MouvementCaisse;
import org.example.stock.model.ReleveBancaire;
import org.example.stock.repository.LigneReleveRepository;
import org.example.stock.repository.MouvementCaisseRepository;
import org.example.stock.repository.RapprochementMouvementRepository;
import org.example.stock.repository.ReleveBancaireRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * État de rapprochement d'un relevé (étape 3c).
 *
 * <pre>
 *   solde du compte selon l'appli à la date de clôture                      A
 *   + mouvements rapprochés mais datés après la clôture                     P
 *   − mouvements de l'appli non pointés (jusqu'à la clôture)                M
 *   + lignes du relevé non pointées (celles des relevés précédents aussi)   L
 *   = solde du relevé calculé             A + P − M + L
 *   écart = solde calculé − solde de clôture du relevé ; zéro attendu
 * </pre>
 *
 * <p>Les mouvements non pointés comptés sont ceux datés à partir du début du <b>premier relevé du compte</b> : avant, ils sont supposés compris
 * dans le solde d'ouverture de ce premier relevé, que l'état contrôle ainsi (une ouverture erronée donne un écart). Les lignes non pointées d'un
 * relevé précédent restent comptées tant qu'elles ne sont pas enregistrées dans l'appli, comme les mouvements non pointés d'une période
 * antérieure (chèque émis non encore débité).</p>
 */
@Service
public class EtatRapprochementService {
    private final ReleveBancaireRepository releves;
    private final LigneReleveRepository lignes;
    private final MouvementCaisseRepository mouvements;
    private final RapprochementMouvementRepository liens;
    private final BoutiqueService boutiques;

    public EtatRapprochementService(ReleveBancaireRepository releves, LigneReleveRepository lignes, MouvementCaisseRepository mouvements,
                                    RapprochementMouvementRepository liens, BoutiqueService boutiques) {
        this.releves = releves; this.lignes = lignes; this.mouvements = mouvements; this.liens = liens; this.boutiques = boutiques;
    }

    /**
     * @param annules identifiants des mouvements non pointés qui ont été annulés (le mouvement d'origine) ; une contre-passation se reconnaît à
     *                {@code annulationDe}
     */
    public record Etat(ReleveBancaire releve, LocalDate depuis, BigDecimal soldeAppli, BigDecimal pointesApresCloture,
                       List<MouvementCaisse> mouvementsNonPointes, BigDecimal totalMouvementsNonPointes,
                       List<LigneReleve> lignesNonPointees, BigDecimal totalLignesNonPointees, BigDecimal dontLignesAnterieures,
                       BigDecimal soldeCalcule, ControleSoldes controle, Set<Long> annules) {
        /** Solde calculé moins solde de clôture du relevé. */
        public BigDecimal ecart() { return soldeCalcule.subtract(controle.cloture()); }

        public boolean ecartNul() { return ecart().signum() == 0; }
    }

    @Transactional(readOnly = true)
    public Etat calculer(Long releveId) {
        DroitsExport.exigerGerant();
        return calculer(releves.findByIdAndBoutiqueId(releveId, boutiques.idBoutiqueCourante()).orElseThrow(ReleveBancaireService::introuvable));
    }

    @Transactional(readOnly = true)
    public Etat calculer(ReleveBancaire releve) {
        Long boutiqueId = releve.getBoutique().getId(), compteId = releve.getCompte().getId();
        LocalDate depuis = releves.premierDebut(compteId);
        if (depuis == null || depuis.isAfter(releve.getDateDebut())) depuis = releve.getDateDebut();
        var apresCloture = releve.getDateFin().plusDays(1).atStartOfDay();
        BigDecimal soldeAppli = Montants.ouZero(mouvements.calculerSoldeCompteAvant(boutiqueId, compteId, apresCloture));
        BigDecimal pointesApres = Montants.ouZero(liens.sommeRapprochesApres(compteId, releve.getDateFin(), apresCloture));
        List<MouvementCaisse> nonPointes = mouvements.nonRapproches(boutiqueId, compteId, depuis.atStartOfDay(), apresCloture);
        BigDecimal totalMouvements = nonPointes.stream().map(MouvementCaisse::getMontant).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<LigneReleve> libres = new ArrayList<>(lignes.nonRapprochees(releve.getId()));
        List<LigneReleve> anterieures = lignes.nonRapprocheesAnterieures(compteId, releve.getId(), releve.getDateFin());
        libres.addAll(0, anterieures);
        BigDecimal dontAnterieures = anterieures.stream().map(LigneReleve::getMontant).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalLignes = libres.stream().map(LigneReleve::getMontant).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal calcule = soldeAppli.add(pointesApres).subtract(totalMouvements).add(totalLignes);
        Set<Long> annules = new HashSet<>();
        if (!nonPointes.isEmpty()) {
            annules = mouvements.findAnnulationsDe(boutiqueId, nonPointes.stream().map(MouvementCaisse::getId).toList()).stream()
                    .map(a -> a.getAnnulationDe().getId()).collect(Collectors.toSet());
        }
        ControleSoldes controle = new ControleSoldes(releve.getSoldeOuverture(), Montants.ouZero(lignes.somme(releve.getId())), releve.getSoldeCloture());
        return new Etat(releve, depuis, soldeAppli, pointesApres, nonPointes, totalMouvements, libres, totalLignes, dontAnterieures, calcule, controle, annules);
    }
}
