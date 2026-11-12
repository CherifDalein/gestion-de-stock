package org.example.stock.service;

import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.model.CompteTresorerieBoutique;
import org.example.stock.repository.CompteTresorerieBoutiqueRepository;
import org.example.stock.export.Colonne;
import org.example.stock.export.FormatPapier;
import org.example.stock.export.TableauExport;
import org.example.stock.export.Textes;
import org.example.stock.model.Montants;
import org.example.stock.model.SourceMouvement;
import org.example.stock.repository.EtatsRepository;
import org.example.stock.repository.TableauDeBordRepository;
import org.example.stock.service.TableauDeBordService.CreancesDettes;
import org.example.stock.service.TableauDeBordService.Indicateurs;
import org.example.stock.service.TableauDeBordService.ValeurStock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Bilan de la période (gérant seulement). Rien n'est recalculé à part : le résultat vient de
 * {@link TableauDeBordService#indicateursDeLaPeriode}, les créances et les dettes de
 * {@link TableauDeBordService#creancesEtDettes()}, la valeur du stock de {@link TableauDeBordService#valeurStock()} ; ce bilan
 * donne donc exactement les chiffres du tableau de bord. Seule la trésorerie est propre à l'état : elle se lit dans le journal
 * de caisse ({@code mouvement_caisse}), seule source du solde d'un compte.
 */
@Service
@Transactional(readOnly = true)
public class BilanService {
    /** Sources de mouvement qui ne sont ni des encaissements ni des décaissements d'activité. */
    static final String SOLDE_INITIAL = "SOLDE_INITIAL", TRANSFERT = "TRANSFERT", ANNULATION = "ANNULATION";

    private final TableauDeBordService tableau;
    private final TableauDeBordRepository requetes;
    private final EtatsRepository etats;
    private final BoutiqueService boutiques;
    private final EcheanceService echeances;

    private final CompteTresorerieBoutiqueRepository comptes;

    public BilanService(TableauDeBordService tableau, TableauDeBordRepository requetes, EtatsRepository etats,
                        BoutiqueService boutiques, EcheanceService echeances, CompteTresorerieBoutiqueRepository comptes) {
        this.tableau = tableau; this.requetes = requetes; this.etats = etats; this.boutiques = boutiques; this.echeances = echeances;
        this.comptes = comptes;
    }

    /** Trésorerie d'un type de compte sur la période : début + fonds + encaissements + décaissements + annulations + transferts = fin. */
    public record TresorerieCompte(TypeCompteTresorerie type, BigDecimal debut, BigDecimal fondsDeDepart, BigDecimal encaissements,
                                   BigDecimal decaissements, BigDecimal annulations, BigDecimal transferts, BigDecimal fin) {
        public static TresorerieCompte de(TypeCompteTresorerie type, BigDecimal debut, BigDecimal fonds, BigDecimal encaissements,
                                          BigDecimal decaissements, BigDecimal annulations, BigDecimal transferts) {
            return new TresorerieCompte(type, debut, fonds, encaissements, decaissements, annulations, transferts,
                    debut.add(fonds).add(encaissements).add(decaissements).add(annulations).add(transferts));
        }

        TresorerieCompte plus(TresorerieCompte autre) {
            return new TresorerieCompte(type, debut.add(autre.debut), fondsDeDepart.add(autre.fondsDeDepart), encaissements.add(autre.encaissements),
                    decaissements.add(autre.decaissements), annulations.add(autre.annulations), transferts.add(autre.transferts), fin.add(autre.fin));
        }
    }

    /** Même décomposition pour un compte précis ; la somme des comptes d'un type donne la ligne du type. */
    public record TresorerieDuCompte(CompteTresorerieBoutique compte, TresorerieCompte chiffres) { }

    /** Chiffres du bilan, pour contrôle et pour l'écran ; {@link #etat} en fait des tableaux. */
    public record Bilan(PeriodeEtat periode, LocalDate jourEdition, BigDecimal ventes, BigDecimal retoursDeVente, Indicateurs indicateurs,
                        BigDecimal coutDesVentes, List<TresorerieCompte> tresorerie, List<TresorerieDuCompte> tresorerieParCompte,
                        Map<String, Map<TypeCompteTresorerie, BigDecimal>> encaissementsParNature,
                        Map<String, Map<TypeCompteTresorerie, BigDecimal>> decaissementsParNature,
                        CreancesDettes creancesDettes, ValeurStock stock,
                        Map<String, BigDecimal> financements, BigDecimal variationTresorerie, BigDecimal autresFlux) {
        public BigDecimal total(java.util.function.Function<TresorerieCompte, BigDecimal> champ) {
            return tresorerie.stream().map(champ).reduce(Montants.ZERO, BigDecimal::add);
        }
    }

    public Bilan calculer(PeriodeEtat periode) {
        DroitsEtats.exigerGerant();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        // Chiffres communs avec le tableau de bord : mêmes méthodes, mêmes bornes.
        Indicateurs indicateurs = tableau.indicateursDeLaPeriode(periode.du(), periode.au());
        BigDecimal ventes = Montants.ouZero(requetes.chiffreAffaires(boutiqueId, periode.debut(), periode.fin()));
        BigDecimal retours = Montants.ouZero(requetes.retoursDeVente(boutiqueId, periode.debut(), periode.fin()));
        BigDecimal cout = indicateurs.chiffreAffaires().subtract(indicateurs.beneficeBrut());

        Map<Long, BigDecimal> avant = new java.util.HashMap<>();
        for (Object[] ligne : etats.soldesAvant(boutiqueId, periode.debut())) avant.put((Long) ligne[0], montant(ligne[1]));
        Map<Long, Map<String, BigDecimal[]>> flux = new java.util.HashMap<>();
        for (Object[] ligne : etats.fluxParCompteEtSource(boutiqueId, periode.debut(), periode.fin())) {
            String source = ligne[1] == null ? "" : String.valueOf(ligne[1]);
            flux.computeIfAbsent((Long) ligne[0], c -> new java.util.HashMap<>())
                    .merge(source, new BigDecimal[]{montant(ligne[2]), montant(ligne[3])},
                            (a, b) -> new BigDecimal[]{a[0].add(b[0]), a[1].add(b[1])});
        }

        Map<String, Map<TypeCompteTresorerie, BigDecimal>> encaisse = new java.util.LinkedHashMap<>(), decaisse = new java.util.LinkedHashMap<>();
        for (String nature : ORDRE_ENCAISSEMENTS) encaisse.put(nature, new EnumMap<>(TypeCompteTresorerie.class));
        for (String nature : ORDRE_DECAISSEMENTS) decaisse.put(nature, new EnumMap<>(TypeCompteTresorerie.class));
        List<TresorerieDuCompte> parCompte = new ArrayList<>();
        Map<TypeCompteTresorerie, TresorerieCompte> parType = new EnumMap<>(TypeCompteTresorerie.class);
        for (TypeCompteTresorerie type : TypeCompteTresorerie.values()) {
            parType.put(type, TresorerieCompte.de(type, Montants.ZERO, Montants.ZERO, Montants.ZERO, Montants.ZERO, Montants.ZERO, Montants.ZERO));
        }
        for (CompteTresorerieBoutique compte : comptes.lister(boutiqueId)) {
            BigDecimal debut = avant.getOrDefault(compte.getId(), Montants.ZERO);
            BigDecimal fonds = Montants.ZERO, entrees = Montants.ZERO, sorties = Montants.ZERO, annulations = Montants.ZERO, transferts = Montants.ZERO;
            for (var source : flux.getOrDefault(compte.getId(), Map.of()).entrySet()) {
                BigDecimal entree = source.getValue()[0], sortie = source.getValue()[1];
                switch (source.getKey()) {
                    case SOLDE_INITIAL -> fonds = fonds.add(entree).add(sortie);
                    case TRANSFERT -> transferts = transferts.add(entree).add(sortie);
                    case ANNULATION -> annulations = annulations.add(entree).add(sortie);
                    default -> {
                        entrees = entrees.add(entree); sorties = sorties.add(sortie);
                        if (entree.signum() != 0) encaisse.get(NATURES_ENCAISSEMENTS.getOrDefault(source.getKey(), AUTRES_ENCAISSEMENTS))
                                .merge(compte.getType(), entree, BigDecimal::add);
                        if (sortie.signum() != 0) decaisse.get(NATURES_DECAISSEMENTS.getOrDefault(source.getKey(), AUTRES_DECAISSEMENTS))
                                .merge(compte.getType(), sortie, BigDecimal::add);
                    }
                }
            }
            TresorerieCompte chiffres = TresorerieCompte.de(compte.getType(), debut, fonds, entrees, sorties, annulations, transferts);
            parType.merge(compte.getType(), chiffres, TresorerieCompte::plus);
            // Un compte archivé sans solde ni mouvement sur la période n'apporte qu'une ligne vide : il est omis du détail.
            boolean vide = !compte.isActif() && chiffres.debut().signum() == 0 && chiffres.fin().signum() == 0 && flux.get(compte.getId()) == null;
            if (!vide) parCompte.add(new TresorerieDuCompte(compte, chiffres));
        }
        // Financements et flux hors ventes et achats, nets de leurs annulations ; le reste de la variation de trésorerie est l'activité.
        Map<String, BigDecimal> financements = tableau.fluxNetsDesFinancements(boutiqueId, periode.debut(), periode.fin());
        BigDecimal debutTotal = parType.values().stream().map(TresorerieCompte::debut).reduce(Montants.ZERO, BigDecimal::add);
        BigDecimal finTotal = parType.values().stream().map(TresorerieCompte::fin).reduce(Montants.ZERO, BigDecimal::add);
        BigDecimal variation = finTotal.subtract(debutTotal);
        BigDecimal autres = variation.subtract(financements.values().stream().reduce(Montants.ZERO, BigDecimal::add));
        return new Bilan(periode, echeances.aujourdHui(), ventes, retours, indicateurs, cout, List.copyOf(parType.values()), List.copyOf(parCompte),
                encaisse, decaisse, tableau.creancesEtDettes(), tableau.valeurStock(), financements, variation, autres);
    }

    static final String AUTRES_ENCAISSEMENTS = "Autres encaissements", AUTRES_DECAISSEMENTS = "Autres décaissements";
    static final Map<String, String> NATURES_ENCAISSEMENTS = Map.ofEntries(
            Map.entry("VENTE", "Ventes payées à la création"),
            Map.entry("REGLEMENT_VENTE", "Règlements de ventes"),
            Map.entry("SOLDE_CLIENT", "Règlements de soldes de départ des clients"),
            Map.entry("RETOUR_ACHAT", "Remboursements reçus des fournisseurs"),
            Map.entry(SourceMouvement.APPORT_CAPITAL, "Apports en capital"),
            Map.entry(SourceMouvement.APPORT_COMPTE_COURANT, "Apports en compte courant"),
            Map.entry(SourceMouvement.DEBLOCAGE_EMPRUNT, "Emprunts reçus"),
            Map.entry(SourceMouvement.REMBOURSEMENT_PRET, "Remboursements de capital de prêts reçus"),
            Map.entry(SourceMouvement.INTERETS_PRET, "Intérêts de prêts reçus"),
            Map.entry(SourceMouvement.PRODUIT_FINANCIER, "Produits financiers"),
            Map.entry(SourceMouvement.AUTRE_PRODUIT, "Autres produits"));
    static final Map<String, String> NATURES_DECAISSEMENTS = Map.ofEntries(
            Map.entry("ACHAT", "Achats payés à la création"),
            Map.entry("REGLEMENT_ACHAT", "Règlements d'achats"),
            Map.entry("SOLDE_FOURNISSEUR", "Règlements de soldes de départ des fournisseurs"),
            Map.entry("RETOUR_VENTE", "Remboursements aux clients"),
            Map.entry("DEPENSE", "Dépenses"),
            Map.entry(SourceMouvement.RETRAIT_CAPITAL, "Retraits de capital"),
            Map.entry(SourceMouvement.RETRAIT_COMPTE_COURANT, "Retraits en compte courant"),
            Map.entry(SourceMouvement.DEBLOCAGE_PRET, "Prêts accordés"),
            Map.entry(SourceMouvement.REMBOURSEMENT_EMPRUNT, "Remboursements de capital d'emprunts"),
            Map.entry(SourceMouvement.INTERETS_EMPRUNT, "Intérêts d'emprunts payés"),
            Map.entry(SourceMouvement.FRAIS_DOSSIER, "Frais de dossier d'emprunts"),
            Map.entry(SourceMouvement.CHARGE_FINANCIERE, "Charges financières"),
            Map.entry(SourceMouvement.AUTRE_CHARGE, "Autres charges"));

    /** Natures dans l'ordre d'affichage ; les « autres » closent chaque liste. */
    private static final List<String> ORDRE_ENCAISSEMENTS = List.of("Ventes payées à la création", "Règlements de ventes",
            "Règlements de soldes de départ des clients", "Remboursements reçus des fournisseurs", "Apports en capital", "Apports en compte courant",
            "Emprunts reçus", "Remboursements de capital de prêts reçus", "Intérêts de prêts reçus", "Produits financiers", "Autres produits", AUTRES_ENCAISSEMENTS);
    private static final List<String> ORDRE_DECAISSEMENTS = List.of("Achats payés à la création", "Règlements d'achats",
            "Règlements de soldes de départ des fournisseurs", "Remboursements aux clients", "Dépenses", "Retraits de capital", "Retraits en compte courant",
            "Prêts accordés", "Remboursements de capital d'emprunts", "Intérêts d'emprunts payés", "Frais de dossier d'emprunts", "Charges financières",
            "Autres charges", AUTRES_DECAISSEMENTS);

    @Transactional(readOnly = true)
    public Etat etat(PeriodeEtat periode) {
        Bilan bilan = calculer(periode);
        return enEtat(bilan);
    }

    /** Met les chiffres du bilan en tableaux (page, PDF et Excel). */
    Etat enEtat(Bilan b) {
        String parametres = b.periode().texte();
        String edition = Textes.date(b.jourEdition());
        List<TableauExport> sections = new ArrayList<>();

        // 1. Résultat : les montants à retrancher sont négatifs, la colonne s'additionne de haut en bas.
        Indicateurs i = b.indicateurs();
        TableauExport resultat = TableauExport.titre("Résultat de la période").nomFeuille("Résultat").filtres(parametres)
                .colonnes(Colonne.texte("Rubrique"), Colonne.montant("Montant"))
                .ligne("Ventes de la période", b.ventes())
                .ligne("Retours de vente (à la date du retour)", b.retoursDeVente().negate())
                .sousTotal("Chiffre d'affaires net des retours", i.chiffreAffaires())
                .ligne("Coût des ventes (coût moyen figé à la vente, net des retours)", b.coutDesVentes().negate())
                .sousTotal("Bénéfice brut", i.beneficeBrut())
                .ligne("Dépenses (loyer, transport, frais…)", i.depenses().negate())
                .sousTotal("Résultat d'exploitation", i.resultatExploitation())
                .ligne("Produits financiers (dont intérêts de prêts reçus)", i.horsExploitation().produitsFinanciers())
                .ligne("Intérêts et charges financières (dont frais de dossier)", i.horsExploitation().chargesFinancieres().negate())
                .ligne("Autres produits", i.horsExploitation().autresProduits())
                .ligne("Autres charges", i.horsExploitation().autresCharges().negate())
                .totaux("Bénéfice net", i.beneficeNet())
                .note("Les achats de marchandises ne sont pas des dépenses : leur coût entre dans le bénéfice brut au moment de la vente. "
                        + "Les transferts entre comptes ne sont pas des dépenses.");
        if (i.lignesSansCout() > 0) {
            resultat.note(i.lignesSansCout() + (i.lignesSansCout() == 1 ? " ligne de vente sans coût connu, comptée à 0" : " lignes de vente sans coût connu, comptées à 0")
                    + " : le bénéfice brut est surestimé d'autant.");
        }
        resultat.note("Le bénéfice net inclut les produits et charges financiers et les autres produits et charges, nets de leurs annulations, à la date du mouvement "
                + "(intérêts comptés quand ils sont payés ou reçus). Il n'inclut ni le capital des emprunts et des prêts ni les apports et retraits des associés : "
                + "ces flux changent la trésorerie sans être un produit ni une charge (tableau « Financements et autres flux »).");
        sections.add(resultat);

        // 2. Trésorerie par compte (transposée : une colonne par compte).
        TableauExport tresorerie = TableauExport.titre("Trésorerie par compte").nomFeuille("Trésorerie").filtres(parametres)
                .colonnes(comptesColonnes("Rubrique"))
                .ligne(ligneCompte("Trésorerie au début de la période", b, TresorerieCompte::debut))
                .ligne(ligneCompte("Fonds de départ saisis pendant la période", b, TresorerieCompte::fondsDeDepart))
                .ligne(ligneCompte("Encaissements", b, TresorerieCompte::encaissements))
                .ligne(ligneCompte("Décaissements", b, TresorerieCompte::decaissements))
                .ligne(ligneCompte("Annulations de règlements (net)", b, TresorerieCompte::annulations))
                .ligne(ligneCompte("Transferts entre comptes (net)", b, TresorerieCompte::transferts))
                .totaux(ligneCompte("Trésorerie à la fin de la période", b, TresorerieCompte::fin))
                .note("Début + fonds de départ + encaissements + décaissements + annulations + transferts = fin, pour chaque compte. "
                        + "Les décaissements sont en négatif. Un transfert déplace l'argent d'un compte à l'autre : son total est nul.");
        sections.add(tresorerie);

        // 2 bis. Détail par compte (une ligne par compte, sous-total par type) : seulement si un type compte plusieurs comptes ;
        //        une boutique qui n'a que ses comptes de base garde les mêmes feuilles et les mêmes en-têtes qu'avant.
        if (b.tresorerieParCompte().stream().collect(java.util.stream.Collectors.groupingBy(c -> c.compte().getType(), java.util.stream.Collectors.counting()))
                .values().stream().anyMatch(n -> n > 1)) {
            sections.add(detailParCompte(b, parametres));
        }

        // 3. Détail par nature.
        TableauExport nature = TableauExport.titre("Encaissements et décaissements par nature").nomFeuille("Par nature").filtres(parametres)
                .colonnes(comptesColonnes("Nature"));
        for (String libelle : ORDRE_ENCAISSEMENTS) nature.ligne(ligneNature(libelle, b.encaissementsParNature().get(libelle)));
        nature.sousTotal(ligneCompte("Total des encaissements", b, TresorerieCompte::encaissements));
        for (String libelle : ORDRE_DECAISSEMENTS) nature.ligne(ligneNature(libelle, b.decaissementsParNature().get(libelle)));
        nature.totaux(ligneCompte("Total des décaissements", b, TresorerieCompte::decaissements));
        nature.note("Les annulations de règlements, les transferts et les fonds de départ sont à part (tableau précédent).");
        sections.add(nature);

        // 3 bis. Financements et autres flux, nets des annulations, et variation de trésorerie.
        sections.add(financements(b, parametres));

        // 4. Créances et dettes.
        CreancesDettes cd = b.creancesDettes();
        TableauExport creances = TableauExport.titre("Créances et dettes (situation du " + edition + ")").nomFeuille("Créances et dettes")
                .filtres(parametres).colonnes(Colonne.texte("Rubrique"), Colonne.montant("Montant"))
                .ligne("Créances sur les clients (restes à payer, ventes sans client comprises)", cd.creances())
                .ligne("   dont en retard", cd.creancesEnRetard())
                .ligne("Dettes envers les fournisseurs (restes à payer, achats sans fournisseur compris)", cd.dettes())
                .ligne("   dont en retard", cd.dettesEnRetard())
                .ligne("Avances reçues des clients", cd.avancesClients())
                .ligne("Avances versées aux fournisseurs", cd.avancesFournisseurs())
                .totaux("Position nette (créances − dettes − avances des clients + avances chez les fournisseurs)", cd.positionNette())
                .note("Situation au jour de l'édition (" + edition + "), calculée comme au tableau de bord. L'application ne conserve pas l'historique "
                        + "des restes à payer : pour une période terminée avant le " + edition + ", ces chiffres ne sont pas ceux de la date de fin.");
        sections.add(creances);

        // 5. Stock.
        ValeurStock stock = b.stock();
        TableauExport valeur = TableauExport.titre("Valeur du stock (au jour de l'édition)").nomFeuille("Stock").filtres(parametres)
                .colonnes(Colonne.texte("Rubrique"), Colonne.montant("Montant"))
                .sousTotal("Valeur du stock au " + edition + " (" + stock.nombreProduits() + (stock.nombreProduits() > 1 ? " articles" : " article") + ")", stock.valeur())
                .note("Valeur au jour de l'édition (" + edition + ") : quantité en stock × coût moyen actuel, services exclus. La valeur à la fin de la période "
                        + "n'est pas calculable de façon fiable (le coût moyen n'est pas conservé jour par jour) : elle n'est donc pas affichée.");
        sections.add(valeur);

        return new Etat("bilan", "Bilan de la période", parametres, List.of(), sections, FormatPapier.A4_PORTRAIT,
                "bilan", b.periode().du(), b.periode().au());
    }

    /**
     * Flux de financement de la période, en montants nets de leurs annulations (le tableau par nature garde les flux bruts, les annulations
     * restant dans leur ligne), et variation de trésorerie : capital et apports sont ici, hors du bénéfice net.
     */
    private static TableauExport financements(Bilan b, String parametres) {
        Map<String, BigDecimal> f = b.financements();
        TableauExport t = TableauExport.titre("Financements et autres flux").nomFeuille("Financements").filtres(parametres)
                .colonnes(Colonne.texte("Rubrique"), Colonne.montant("Montant"));
        t.ligne("Apports en capital", f.get(SourceMouvement.APPORT_CAPITAL))
                .ligne("Apports en compte courant", f.get(SourceMouvement.APPORT_COMPTE_COURANT))
                .ligne("Retraits de capital", f.get(SourceMouvement.RETRAIT_CAPITAL))
                .ligne("Retraits en compte courant", f.get(SourceMouvement.RETRAIT_COMPTE_COURANT))
                .ligne("Emprunts reçus", f.get(SourceMouvement.DEBLOCAGE_EMPRUNT))
                .ligne("Prêts accordés", f.get(SourceMouvement.DEBLOCAGE_PRET))
                .ligne("Remboursements de capital d'emprunts", f.get(SourceMouvement.REMBOURSEMENT_EMPRUNT))
                .ligne("Remboursements de capital de prêts reçus", f.get(SourceMouvement.REMBOURSEMENT_PRET));
        BigDecimal capital = Montants.ZERO;
        for (String source : List.of(SourceMouvement.APPORT_CAPITAL, SourceMouvement.APPORT_COMPTE_COURANT, SourceMouvement.RETRAIT_CAPITAL,
                SourceMouvement.RETRAIT_COMPTE_COURANT, SourceMouvement.DEBLOCAGE_EMPRUNT, SourceMouvement.DEBLOCAGE_PRET,
                SourceMouvement.REMBOURSEMENT_EMPRUNT, SourceMouvement.REMBOURSEMENT_PRET)) capital = capital.add(f.get(source));
        t.sousTotal("Flux de financement (hors résultat)", capital);
        BigDecimal resultat = Montants.ZERO;
        for (String source : List.of(SourceMouvement.INTERETS_EMPRUNT, SourceMouvement.INTERETS_PRET, SourceMouvement.FRAIS_DOSSIER,
                SourceMouvement.PRODUIT_FINANCIER, SourceMouvement.CHARGE_FINANCIERE, SourceMouvement.AUTRE_PRODUIT, SourceMouvement.AUTRE_CHARGE)) resultat = resultat.add(f.get(source));
        t.ligne("Intérêts d'emprunts payés", f.get(SourceMouvement.INTERETS_EMPRUNT))
                .ligne("Intérêts de prêts reçus", f.get(SourceMouvement.INTERETS_PRET))
                .ligne("Frais de dossier d'emprunts", f.get(SourceMouvement.FRAIS_DOSSIER))
                .ligne("Produits financiers", f.get(SourceMouvement.PRODUIT_FINANCIER))
                .ligne("Charges financières", f.get(SourceMouvement.CHARGE_FINANCIERE))
                .ligne("Autres produits", f.get(SourceMouvement.AUTRE_PRODUIT))
                .ligne("Autres charges", f.get(SourceMouvement.AUTRE_CHARGE));
        t.sousTotal("Produits et charges hors activité courante (déjà dans le bénéfice net)", resultat)
                .ligne("Ventes, achats, règlements, dépenses, retours, fonds de départ et transferts", b.autresFlux())
                .totaux("Variation de trésorerie de la période (fin − début)", b.variationTresorerie())
                .note("Montants nets des annulations de la période ; entrées en positif, sorties en négatif. Le capital emprunté et les apports augmentent la "
                        + "trésorerie sans augmenter le bénéfice ; les remboursements de capital et les retraits la diminuent sans le réduire.");
        return t;
    }

    /** Une ligne par compte (nom du compte, type), les comptes d'un type étant suivis de leur sous-total. */
    private static TableauExport detailParCompte(Bilan b, String parametres) {
        TableauExport detail = TableauExport.titre("Trésorerie détaillée par compte").nomFeuille("Détail par compte").filtres(parametres)
                .colonnes(Colonne.texte("Compte"), Colonne.texte("Type"), Colonne.montant("Début"), Colonne.montant("Fonds de départ"),
                        Colonne.montant("Encaissements"), Colonne.montant("Décaissements"), Colonne.montant("Annulations"),
                        Colonne.montant("Transferts"), Colonne.montant("Fin"));
        for (TresorerieCompte type : b.tresorerie()) {
            for (TresorerieDuCompte c : b.tresorerieParCompte()) {
                if (c.compte().getType() != type.type()) continue;
                TresorerieCompte t = c.chiffres();
                detail.ligne(c.compte().getNom() + (c.compte().isActif() ? "" : " (archivé)"), type.type().getLibelle(), t.debut(), t.fondsDeDepart(),
                        t.encaissements(), t.decaissements(), t.annulations(), t.transferts(), t.fin());
            }
            detail.sousTotal("Total " + type.type().getLibelle(), "", type.debut(), type.fondsDeDepart(), type.encaissements(),
                    type.decaissements(), type.annulations(), type.transferts(), type.fin());
        }
        return detail.note("Chaque ligne suit la même égalité que le tableau précédent. Les comptes archivés sont indiqués ; ceux sans solde ni mouvement sur la période sont omis.");
    }

    private static Colonne[] comptesColonnes(String premiere) {
        List<Colonne> colonnes = new ArrayList<>();
        colonnes.add(Colonne.texte(premiere));
        for (TypeCompteTresorerie type : TypeCompteTresorerie.values()) colonnes.add(Colonne.montant(type.getLibelle()));
        colonnes.add(Colonne.montant("Total"));
        return colonnes.toArray(Colonne[]::new);
    }

    private static Object[] ligneCompte(String libelle, Bilan b, java.util.function.Function<TresorerieCompte, BigDecimal> champ) {
        List<Object> cellules = new ArrayList<>();
        cellules.add(libelle);
        for (TresorerieCompte t : b.tresorerie()) cellules.add(champ.apply(t));
        cellules.add(b.total(champ));
        return cellules.toArray();
    }

    private static Object[] ligneNature(String libelle, Map<TypeCompteTresorerie, BigDecimal> parCompte) {
        List<Object> cellules = new ArrayList<>();
        cellules.add(libelle);
        BigDecimal total = Montants.ZERO;
        for (TypeCompteTresorerie type : TypeCompteTresorerie.values()) {
            BigDecimal valeur = parCompte == null ? Montants.ZERO : parCompte.getOrDefault(type, Montants.ZERO);
            cellules.add(valeur); total = total.add(valeur);
        }
        cellules.add(total);
        return cellules.toArray();
    }

    private static BigDecimal montant(Object valeur) {
        return TableauDeBordService.montant(valeur);
    }
}
