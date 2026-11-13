package org.example.stock.service;

import org.example.stock.enums.OrigineLigneReleve;
import org.example.stock.enums.StatutReleve;
import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.export.DroitsExport;
import org.example.stock.export.Textes;
import org.example.stock.model.CompteTresorerieBoutique;
import org.example.stock.model.CorrespondanceImport;
import org.example.stock.model.LigneReleve;
import org.example.stock.model.Montants;
import org.example.stock.model.ReleveBancaire;
import org.example.stock.model.Utilisateur;
import org.example.stock.releve.AnalyseReleve;
import org.example.stock.releve.Correspondance;
import org.example.stock.releve.Empreinte;
import org.example.stock.releve.LigneAnalysee;
import org.example.stock.releve.Neutralisation;
import org.example.stock.repository.CompteTresorerieBoutiqueRepository;
import org.example.stock.repository.CorrespondanceImportRepository;
import org.example.stock.repository.EvenementReleveRepository;
import org.example.stock.repository.LigneReleveRepository;
import org.example.stock.repository.RapprochementMouvementRepository;
import org.example.stock.repository.RapprochementRepository;
import org.example.stock.repository.RefusPropositionRepository;
import org.example.stock.repository.ReleveBancaireRepository;
import org.example.stock.repository.StockLockRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Relevés bancaires et Orange Money (étape 3c), réservés au gérant : création, lignes saisies ou importées, correspondance des colonnes
 * mémorisée par compte, doublons. Tant qu'un relevé n'est pas validé il se modifie librement ; une fois validé il est figé.
 *
 * <p>Ordre des verrous des écritures : boutique, puis relevé (docs/lot-2.md, « Ordre des verrous »). Ces écritures ne touchent aucun
 * mouvement ni aucun solde ; elles ne verrouillent donc ni compte ni mouvement.</p>
 */
@Service
public class ReleveBancaireService {
    /** Lignes d'un relevé au plus : au-delà, l'écran de pointage devient inutilisable. */
    public static final int LIGNES_MAX = 5_000;
    /** Durée maximale d'un relevé, en jours. */
    public static final int JOURS_MAX = 366;
    private static final DateTimeFormatter MOIS = DateTimeFormatter.ofPattern("yyyyMM");

    private final StockLockRepository verrous;
    private final CaisseService caisse;
    private final BoutiqueService boutiques;
    private final EcheanceService horloge;
    private final ReleveBancaireRepository releves;
    private final LigneReleveRepository lignes;
    private final CompteTresorerieBoutiqueRepository comptes;
    private final CorrespondanceImportRepository correspondances;
    private final RapprochementRepository rapprochements;
    private final RapprochementMouvementRepository liens;
    private final RefusPropositionRepository refus;
    private final EvenementReleveRepository evenements;

    public ReleveBancaireService(StockLockRepository verrous, CaisseService caisse, BoutiqueService boutiques, EcheanceService horloge,
                                 ReleveBancaireRepository releves, LigneReleveRepository lignes, CompteTresorerieBoutiqueRepository comptes,
                                 CorrespondanceImportRepository correspondances, RapprochementRepository rapprochements,
                                 RapprochementMouvementRepository liens, RefusPropositionRepository refus, EvenementReleveRepository evenements) {
        this.verrous = verrous; this.caisse = caisse; this.boutiques = boutiques; this.horloge = horloge; this.releves = releves; this.lignes = lignes;
        this.comptes = comptes; this.correspondances = correspondances; this.rapprochements = rapprochements; this.liens = liens; this.refus = refus;
        this.evenements = evenements;
    }

    public static boolean rapprochable(CompteTresorerieBoutique compte) {
        return compte.getType() == TypeCompteTresorerie.BANQUE || compte.getType() == TypeCompteTresorerie.ORANGE_MONEY;
    }

    // ---- Lectures (gérant) ----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ReleveBancaire> lister() {
        DroitsExport.exigerGerant();
        return releves.lister(boutiques.idBoutiqueCourante());
    }

    @Transactional(readOnly = true)
    public ReleveBancaire trouver(Long id) {
        DroitsExport.exigerGerant();
        return releves.findByIdAndBoutiqueId(id, boutiques.idBoutiqueCourante()).orElseThrow(ReleveBancaireService::introuvable);
    }

    /** Comptes actifs de type banque ou Orange Money, dans l'ordre habituel. */
    @Transactional(readOnly = true)
    public List<CompteTresorerieBoutique> comptesRapprochables() {
        DroitsExport.exigerGerant();
        return comptes.listerActifs(boutiques.idBoutiqueCourante()).stream().filter(ReleveBancaireService::rapprochable).toList();
    }

    /** Solde d'ouverture proposé : la clôture du relevé qui précède ; {@code null} s'il n'y en a pas. */
    @Transactional(readOnly = true)
    public BigDecimal ouvertureProposee(Long compteId, LocalDate debut) {
        DroitsExport.exigerGerant();
        if (compteId == null || debut == null) return null;
        comptes.findByIdAndBoutiqueId(compteId, boutiques.idBoutiqueCourante()).orElseThrow(ReleveBancaireService::introuvable);
        return releves.precedents(compteId, -1L, debut).stream().findFirst().map(ReleveBancaire::getSoldeCloture).orElse(null);
    }

    /** Date de début proposée pour le prochain relevé d'un compte : le lendemain de la fin du dernier ; {@code null} s'il n'y en a pas. */
    @Transactional(readOnly = true)
    public LocalDate debutPropose(Long compteId) {
        DroitsExport.exigerGerant();
        comptes.findByIdAndBoutiqueId(compteId, boutiques.idBoutiqueCourante()).orElseThrow(ReleveBancaireService::introuvable);
        return releves.duCompte(compteId).stream().map(ReleveBancaire::getDateFin).max(LocalDate::compareTo).map(d -> d.plusDays(1)).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<LigneReleve> lignesDe(Long releveId) {
        trouver(releveId);
        return lignes.duReleve(releveId);
    }

    @Transactional(readOnly = true)
    public ControleSoldes controle(ReleveBancaire releve) {
        return new ControleSoldes(releve.getSoldeOuverture(), lignes.somme(releve.getId()), releve.getSoldeCloture());
    }

    @Transactional(readOnly = true)
    public Optional<CorrespondanceImport> correspondanceMemorisee(Long compteId) {
        DroitsExport.exigerGerant();
        return correspondances.findByCompteId(compteId);
    }

    // ---- Relevé ---------------------------------------------------------------------------------------------

    /** Crée un relevé. Un second envoi du même jeton rend le relevé déjà créé. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReleveBancaire creer(Long compteId, LocalDate debut, LocalDate fin, BigDecimal ouverture, BigDecimal cloture, String jeton) {
        if (jeton == null || jeton.isBlank() || jeton.length() > 36) throw new IllegalArgumentException("Rechargez le formulaire du relevé.");
        verrous.verrouillerBoutiqueCourante();
        Utilisateur gerant = caisse.gerantPourEcriture();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        Optional<ReleveBancaire> deja = releves.findByBoutiqueIdAndJeton(boutiqueId, jeton);
        if (deja.isPresent()) return deja.get();
        CompteTresorerieBoutique compte = compteRapprochable(compteId, boutiqueId, true);
        controlerPeriode(debut, fin);
        BigDecimal debutSolde = soldeValide(ouverture, "Le solde d'ouverture"), finSolde = soldeValide(cloture, "Le solde de clôture");
        exigerSansChevauchement(compteId, -1L, debut, fin);
        ReleveBancaire r = new ReleveBancaire();
        r.setBoutique(boutiques.boutiqueCourante()); r.setCompte(compte); r.setDateDebut(debut); r.setDateFin(fin);
        r.setSoldeOuverture(debutSolde); r.setSoldeCloture(finSolde); r.setStatut(StatutReleve.BROUILLON); r.setUtilisateur(gerant);
        r.setDateCreation(horloge.maintenant()); r.setJeton(jeton); r.setNumero(numeroLibre(boutiqueId, fin, compteId));
        return releves.saveAndFlush(r);
    }

    /** Modifie la période et les soldes d'un relevé non validé ; chaque ligne doit rester dans la nouvelle période. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void modifierEntete(Long id, LocalDate debut, LocalDate fin, BigDecimal ouverture, BigDecimal cloture) {
        verrous.verrouillerBoutiqueCourante();
        caisse.gerantPourEcriture();
        ReleveBancaire r = verrous.verrouillerReleve(id);
        exigerBrouillon(r);
        controlerPeriode(debut, fin);
        BigDecimal debutSolde = soldeValide(ouverture, "Le solde d'ouverture"), finSolde = soldeValide(cloture, "Le solde de clôture");
        exigerSansChevauchement(r.getCompte().getId(), r.getId(), debut, fin);
        for (LigneReleve l : lignes.duReleve(id)) {
            if (l.getDateOperation().isBefore(debut) || l.getDateOperation().isAfter(fin)) {
                throw new IllegalArgumentException("La ligne du " + Textes.date(l.getDateOperation()) + " (« " + l.getLibelle() + " ») serait hors de la nouvelle période. Supprimez-la ou gardez une période qui la contient.");
            }
        }
        r.setDateDebut(debut); r.setDateFin(fin); r.setSoldeOuverture(debutSolde); r.setSoldeCloture(finSolde);
    }

    /** Supprime un relevé non validé, ses lignes et ses rapprochements. Les mouvements créés depuis ses lignes restent dans le journal. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void supprimer(Long id) {
        verrous.verrouillerBoutiqueCourante();
        caisse.gerantPourEcriture();
        ReleveBancaire r = verrous.verrouillerReleve(id);
        exigerBrouillon(r);
        refus.supprimerDuReleve(id);
        lignes.detacherDuReleve(id);
        liens.supprimerDuReleve(id);
        rapprochements.supprimerDuReleve(id);
        lignes.supprimerDuReleve(id);
        evenements.supprimerDuReleve(id);
        releves.delete(r);
        releves.flush();
    }

    // ---- Lignes saisies à la main ---------------------------------------------------------------------------

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LigneReleve ajouterLigne(Long releveId, LocalDate date, String libelle, String reference, BigDecimal montant) {
        verrous.verrouillerBoutiqueCourante();
        caisse.gerantPourEcriture();
        ReleveBancaire r = verrous.verrouillerReleve(releveId);
        exigerBrouillon(r);
        if (lignes.countByReleveId(releveId) >= LIGNES_MAX) throw new IllegalArgumentException("Un relevé comporte au plus " + LIGNES_MAX + " lignes.");
        LigneReleve l = new LigneReleve();
        l.setReleve(r); l.setOrigine(OrigineLigneReleve.SAISIE);
        remplir(l, r, date, libelle, reference, montant);
        return lignes.saveAndFlush(l);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void modifierLigne(Long releveId, Long ligneId, LocalDate date, String libelle, String reference, BigDecimal montant) {
        verrous.verrouillerBoutiqueCourante();
        caisse.gerantPourEcriture();
        ReleveBancaire r = verrous.verrouillerReleve(releveId);
        exigerBrouillon(r);
        LigneReleve l = lignes.findByIdAndReleveId(ligneId, releveId).orElseThrow(ReleveBancaireService::introuvable);
        if (l.estRapprochee()) throw new IllegalArgumentException("Cette ligne est rapprochée : défaites d'abord le rapprochement pour la modifier.");
        remplir(l, r, date, libelle, reference, montant);
        refus.supprimerDeLaLigne(ligneId);
        lignes.flush();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void supprimerLigne(Long releveId, Long ligneId) {
        verrous.verrouillerBoutiqueCourante();
        caisse.gerantPourEcriture();
        ReleveBancaire r = verrous.verrouillerReleve(releveId);
        exigerBrouillon(r);
        LigneReleve l = lignes.findByIdAndReleveId(ligneId, releveId).orElseThrow(ReleveBancaireService::introuvable);
        if (l.estRapprochee()) throw new IllegalArgumentException("Cette ligne est rapprochée : défaites d'abord le rapprochement pour la supprimer.");
        refus.supprimerDeLaLigne(ligneId);
        lignes.delete(l);
        lignes.flush();
    }

    private void remplir(LigneReleve l, ReleveBancaire r, LocalDate date, String libelle, String reference, BigDecimal montant) {
        if (date == null) throw new IllegalArgumentException("La date de la ligne est obligatoire.");
        if (date.isBefore(r.getDateDebut()) || date.isAfter(r.getDateFin())) {
            throw new IllegalArgumentException("La date de la ligne doit être dans la période du relevé (du " + Textes.date(r.getDateDebut()) + " au " + Textes.date(r.getDateFin()) + ").");
        }
        String texte = Neutralisation.nettoyer(libelle, LigneReleve.TAILLE_LIBELLE);
        if (texte == null) throw new IllegalArgumentException("Le libellé de la ligne est obligatoire.");
        String ref = Neutralisation.nettoyer(reference, LigneReleve.TAILLE_REFERENCE);
        BigDecimal valeur = Montants.valider(montant, "Le montant");
        if (valeur.signum() == 0) throw new IllegalArgumentException("Le montant d'une ligne ne peut pas être nul (crédit positif, débit négatif).");
        l.setDateOperation(date); l.setLibelle(texte); l.setReference(ref); l.setMontant(valeur);
        l.setEmpreinte(Empreinte.calculer(date, valeur, texte, ref));
    }

    // ---- Import ---------------------------------------------------------------------------------------------

    /**
     * Marque les doublons d'une analyse : une ligne identique (date, montant, libellé, référence) déjà enregistrée sur ce compte, dans n'importe
     * quel relevé. Une ligne qui apparaît plusieurs fois dans le fichier n'est un doublon que pour les occurrences déjà couvertes par les lignes
     * existantes (deux frais identiques le même jour restent deux frais).
     */
    @Transactional(readOnly = true)
    public AnalyseReleve marquerDoublons(Long compteId, AnalyseReleve analyse) {
        DroitsExport.exigerGerant();
        comptes.findByIdAndBoutiqueId(compteId, boutiques.idBoutiqueCourante()).orElseThrow(ReleveBancaireService::introuvable);
        return marquer(compteId, analyse);
    }

    /** Enregistre les lignes de l'analyse (sans les doublons sauf demande contraire) et mémorise la correspondance pour le compte. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int importer(Long releveId, AnalyseReleve analyse, boolean avecDoublons, Correspondance choix) {
        verrous.verrouillerBoutiqueCourante();
        caisse.gerantPourEcriture();
        ReleveBancaire r = verrous.verrouillerReleve(releveId);
        exigerBrouillon(r);
        AnalyseReleve marquee = marquer(r.getCompte().getId(), analyse.verifierPeriode(r.getDateDebut(), r.getDateFin()));
        List<LigneAnalysee> aImporter = marquee.aImporter(avecDoublons);
        if (aImporter.isEmpty()) throw new IllegalArgumentException("Aucune ligne à importer : toutes les lignes sont en erreur ou déjà importées.");
        long deja = lignes.countByReleveId(releveId);
        if (deja + aImporter.size() > LIGNES_MAX) throw new IllegalArgumentException("Un relevé comporte au plus " + LIGNES_MAX + " lignes (" + deja + " existantes, " + aImporter.size() + " à importer).");
        for (LigneAnalysee a : aImporter) {
            LigneReleve l = new LigneReleve();
            l.setReleve(r); l.setOrigine(OrigineLigneReleve.IMPORT); l.setDateOperation(a.date()); l.setLibelle(a.libelle()); l.setReference(a.reference());
            l.setMontant(a.montant()); l.setEmpreinte(a.empreinte());
            lignes.save(l);
        }
        memoriser(r.getCompte(), choix);
        lignes.flush();
        return aImporter.size();
    }

    private AnalyseReleve marquer(Long compteId, AnalyseReleve analyse) {
        Map<String, Integer> restants = new HashMap<>();
        List<String> empreintes = analyse.valides().stream().map(LigneAnalysee::empreinte).distinct().toList();
        for (int i = 0; i < empreintes.size(); i += 500) {
            for (Object[] ligne : lignes.compterEmpreintes(compteId, empreintes.subList(i, Math.min(empreintes.size(), i + 500)))) {
                restants.put((String) ligne[0], ((Number) ligne[1]).intValue());
            }
        }
        List<LigneAnalysee> resultat = new ArrayList<>();
        for (LigneAnalysee l : analyse.lignes()) {
            if (!l.valide()) { resultat.add(l); continue; }
            int n = restants.getOrDefault(l.empreinte(), 0);
            if (n > 0) { restants.put(l.empreinte(), n - 1); resultat.add(l.comme(true)); } else resultat.add(l);
        }
        return new AnalyseReleve(resultat);
    }

    private void memoriser(CompteTresorerieBoutique compte, Correspondance c) {
        if (c == null) return;
        CorrespondanceImport m = correspondances.findByCompteId(compte.getId()).orElseGet(CorrespondanceImport::new);
        m.setCompte(compte); m.setLigneEntete(c.ligneEntete()); m.setColonneDate(c.colonneDate()); m.setColonneLibelle(c.colonneLibelle());
        m.setColonneReference(c.colonneReference()); m.setColonneMontant(c.colonneMontant()); m.setColonneDebit(c.colonneDebit());
        m.setColonneCredit(c.colonneCredit()); m.setFormatDate(c.formatDate()); m.setSeparateurDecimal(c.separateur());
        m.setDateModification(horloge.maintenant());
        correspondances.save(m);
    }

    // ---- Règles ---------------------------------------------------------------------------------------------

    private CompteTresorerieBoutique compteRapprochable(Long compteId, Long boutiqueId, boolean actif) {
        if (compteId == null) throw new IllegalArgumentException("Choisissez le compte du relevé.");
        CompteTresorerieBoutique compte = comptes.findByIdAndBoutiqueId(compteId, boutiqueId).orElseThrow(() -> new IllegalArgumentException("Compte introuvable."));
        if (!rapprochable(compte)) {
            throw new IllegalArgumentException("Le rapprochement bancaire concerne les comptes Banque et Orange Money. Pour la caisse en espèces, utilisez le comptage de caisse.");
        }
        if (actif && !compte.isActif()) throw new IllegalArgumentException("Ce compte est archivé : désarchivez-le pour créer un relevé.");
        return compte;
    }

    private void controlerPeriode(LocalDate debut, LocalDate fin) {
        if (debut == null || fin == null) throw new IllegalArgumentException("La période du relevé (du … au …) est obligatoire.");
        if (fin.isBefore(debut)) throw new IllegalArgumentException("La date de fin doit être égale ou postérieure à la date de début.");
        if (java.time.temporal.ChronoUnit.DAYS.between(debut, fin) >= JOURS_MAX) throw new IllegalArgumentException("Un relevé couvre au plus " + JOURS_MAX + " jours.");
        if (fin.isAfter(horloge.aujourdHui())) throw new IllegalArgumentException("La période d'un relevé ne peut pas se terminer dans le futur.");
        if (debut.getYear() < 2000) throw new IllegalArgumentException("La date de début est trop ancienne.");
    }

    private void exigerSansChevauchement(Long compteId, Long exclu, LocalDate debut, LocalDate fin) {
        if (releves.chevauche(compteId, exclu, debut, fin)) {
            throw new IllegalArgumentException("Cette période chevauche un autre relevé du même compte. Les relevés d'un compte se suivent sans se chevaucher.");
        }
    }

    private static BigDecimal soldeValide(BigDecimal solde, String libelle) {
        if (solde == null) throw new IllegalArgumentException(libelle + " est obligatoire (il peut être négatif ou nul).");
        return Montants.valider(solde, libelle);
    }

    private String numeroLibre(Long boutiqueId, LocalDate fin, Long compteId) {
        String base = "RB-" + MOIS.format(fin) + "-" + compteId;
        String numero = base;
        for (int i = 2; releves.existsByBoutiqueIdAndNumero(boutiqueId, numero); i++) numero = base + "-" + i;
        return numero;
    }

    static void exigerBrouillon(ReleveBancaire r) {
        if (r.estValide()) throw new IllegalArgumentException("Le relevé " + r.getNumero() + " est validé : il est figé. Dévalidez-le (dernier relevé validé du compte) pour le modifier.");
    }

    static ResponseStatusException introuvable() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Élément introuvable"); }
}
