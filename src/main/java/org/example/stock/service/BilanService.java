package org.example.stock.service;

import org.example.stock.enums.CompteTresorerie;
import org.example.stock.export.Colonne;
import org.example.stock.export.FormatPapier;
import org.example.stock.export.TableauExport;
import org.example.stock.export.Textes;
import org.example.stock.model.Montants;
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

    public BilanService(TableauDeBordService tableau, TableauDeBordRepository requetes, EtatsRepository etats,
                        BoutiqueService boutiques, EcheanceService echeances) {
        this.tableau = tableau; this.requetes = requetes; this.etats = etats; this.boutiques = boutiques; this.echeances = echeances;
    }

    /** Trésorerie d'un compte sur la période : début + fonds + encaissements + décaissements + annulations + transferts = fin. */
    public record TresorerieCompte(CompteTresorerie compte, BigDecimal debut, BigDecimal fondsDeDepart, BigDecimal encaissements,
                                   BigDecimal decaissements, BigDecimal annulations, BigDecimal transferts, BigDecimal fin) {
        public static TresorerieCompte de(CompteTresorerie compte, BigDecimal debut, BigDecimal fonds, BigDecimal encaissements,
                                          BigDecimal decaissements, BigDecimal annulations, BigDecimal transferts) {
            return new TresorerieCompte(compte, debut, fonds, encaissements, decaissements, annulations, transferts,
                    debut.add(fonds).add(encaissements).add(decaissements).add(annulations).add(transferts));
        }
    }

    /** Chiffres du bilan, pour contrôle et pour l'écran ; {@link #etat} en fait des tableaux. */
    public record Bilan(PeriodeEtat periode, LocalDate jourEdition, BigDecimal ventes, BigDecimal retoursDeVente, Indicateurs indicateurs,
                        BigDecimal coutDesVentes, List<TresorerieCompte> tresorerie,
                        Map<String, Map<CompteTresorerie, BigDecimal>> encaissementsParNature,
                        Map<String, Map<CompteTresorerie, BigDecimal>> decaissementsParNature,
                        CreancesDettes creancesDettes, ValeurStock stock) {
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

        Map<CompteTresorerie, BigDecimal> avant = new EnumMap<>(CompteTresorerie.class);
        for (Object[] ligne : etats.soldesAvant(boutiqueId, periode.debut())) avant.put((CompteTresorerie) ligne[0], montant(ligne[1]));
        Map<CompteTresorerie, Map<String, BigDecimal[]>> flux = new EnumMap<>(CompteTresorerie.class);
        for (Object[] ligne : etats.fluxParCompteEtSource(boutiqueId, periode.debut(), periode.fin())) {
            String source = ligne[1] == null ? "" : String.valueOf(ligne[1]);
            flux.computeIfAbsent((CompteTresorerie) ligne[0], c -> new java.util.HashMap<>())
                    .merge(source, new BigDecimal[]{montant(ligne[2]), montant(ligne[3])},
                            (a, b) -> new BigDecimal[]{a[0].add(b[0]), a[1].add(b[1])});
        }

        List<TresorerieCompte> tresorerie = new ArrayList<>();
        Map<String, Map<CompteTresorerie, BigDecimal>> encaisse = new java.util.LinkedHashMap<>(), decaisse = new java.util.LinkedHashMap<>();
        for (String nature : ORDRE_ENCAISSEMENTS) encaisse.put(nature, new EnumMap<>(CompteTresorerie.class));
        for (String nature : ORDRE_DECAISSEMENTS) decaisse.put(nature, new EnumMap<>(CompteTresorerie.class));
        for (CompteTresorerie compte : CompteTresorerie.values()) {
            BigDecimal fonds = Montants.ZERO, entrees = Montants.ZERO, sorties = Montants.ZERO, annulations = Montants.ZERO, transferts = Montants.ZERO;
            for (var source : flux.getOrDefault(compte, Map.of()).entrySet()) {
                BigDecimal entree = source.getValue()[0], sortie = source.getValue()[1];
                switch (source.getKey()) {
                    case SOLDE_INITIAL -> fonds = fonds.add(entree).add(sortie);
                    case TRANSFERT -> transferts = transferts.add(entree).add(sortie);
                    case ANNULATION -> annulations = annulations.add(entree).add(sortie);
                    default -> {
                        entrees = entrees.add(entree); sorties = sorties.add(sortie);
                        if (entree.signum() != 0) encaisse.get(NATURES_ENCAISSEMENTS.getOrDefault(source.getKey(), AUTRES_ENCAISSEMENTS))
                                .merge(compte, entree, BigDecimal::add);
                        if (sortie.signum() != 0) decaisse.get(NATURES_DECAISSEMENTS.getOrDefault(source.getKey(), AUTRES_DECAISSEMENTS))
                                .merge(compte, sortie, BigDecimal::add);
                    }
                }
            }
            tresorerie.add(TresorerieCompte.de(compte, avant.getOrDefault(compte, Montants.ZERO), fonds, entrees, sorties, annulations, transferts));
        }
        return new Bilan(periode, echeances.aujourdHui(), ventes, retours, indicateurs, cout, List.copyOf(tresorerie), encaisse, decaisse,
                tableau.creancesEtDettes(), tableau.valeurStock());
    }

    static final String AUTRES_ENCAISSEMENTS = "Autres encaissements", AUTRES_DECAISSEMENTS = "Autres décaissements";
    static final Map<String, String> NATURES_ENCAISSEMENTS = Map.of(
            "VENTE", "Ventes payées à la création",
            "REGLEMENT_VENTE", "Règlements de ventes",
            "SOLDE_CLIENT", "Règlements de soldes de départ des clients",
            "RETOUR_ACHAT", "Remboursements reçus des fournisseurs");
    static final Map<String, String> NATURES_DECAISSEMENTS = Map.of(
            "ACHAT", "Achats payés à la création",
            "REGLEMENT_ACHAT", "Règlements d'achats",
            "SOLDE_FOURNISSEUR", "Règlements de soldes de départ des fournisseurs",
            "RETOUR_VENTE", "Remboursements aux clients",
            "DEPENSE", "Dépenses");

    /** Natures dans l'ordre d'affichage ; les « autres » closent chaque liste. */
    private static final List<String> ORDRE_ENCAISSEMENTS = List.of("Ventes payées à la création", "Règlements de ventes",
            "Règlements de soldes de départ des clients", "Remboursements reçus des fournisseurs", AUTRES_ENCAISSEMENTS);
    private static final List<String> ORDRE_DECAISSEMENTS = List.of("Achats payés à la création", "Règlements d'achats",
            "Règlements de soldes de départ des fournisseurs", "Remboursements aux clients", "Dépenses", AUTRES_DECAISSEMENTS);

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
                .totaux("Bénéfice net", i.beneficeNet())
                .note("Les achats de marchandises ne sont pas des dépenses : leur coût entre dans le bénéfice brut au moment de la vente. "
                        + "Les transferts entre comptes ne sont pas des dépenses.");
        if (i.lignesSansCout() > 0) {
            resultat.note(i.lignesSansCout() + (i.lignesSansCout() == 1 ? " ligne de vente sans coût connu, comptée à 0" : " lignes de vente sans coût connu, comptées à 0")
                    + " : le bénéfice brut est surestimé d'autant.");
        }
        resultat.note("Les apports, emprunts et autres produits et charges seront ajoutés au lot 3 : ils ne figurent pas dans ce bilan.");
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

        // 3. Détail par nature.
        TableauExport nature = TableauExport.titre("Encaissements et décaissements par nature").nomFeuille("Par nature").filtres(parametres)
                .colonnes(comptesColonnes("Nature"));
        for (String libelle : ORDRE_ENCAISSEMENTS) nature.ligne(ligneNature(libelle, b.encaissementsParNature().get(libelle)));
        nature.sousTotal(ligneCompte("Total des encaissements", b, TresorerieCompte::encaissements));
        for (String libelle : ORDRE_DECAISSEMENTS) nature.ligne(ligneNature(libelle, b.decaissementsParNature().get(libelle)));
        nature.totaux(ligneCompte("Total des décaissements", b, TresorerieCompte::decaissements));
        nature.note("Les annulations de règlements, les transferts et les fonds de départ sont à part (tableau précédent).");
        sections.add(nature);

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

    private static Colonne[] comptesColonnes(String premiere) {
        List<Colonne> colonnes = new ArrayList<>();
        colonnes.add(Colonne.texte(premiere));
        for (CompteTresorerie compte : CompteTresorerie.values()) colonnes.add(Colonne.montant(compte.getLibelle()));
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

    private static Object[] ligneNature(String libelle, Map<CompteTresorerie, BigDecimal> parCompte) {
        List<Object> cellules = new ArrayList<>();
        cellules.add(libelle);
        BigDecimal total = Montants.ZERO;
        for (CompteTresorerie compte : CompteTresorerie.values()) {
            BigDecimal valeur = parCompte == null ? Montants.ZERO : parCompte.getOrDefault(compte, Montants.ZERO);
            cellules.add(valeur); total = total.add(valeur);
        }
        cellules.add(total);
        return cellules.toArray();
    }

    private static BigDecimal montant(Object valeur) {
        return TableauDeBordService.montant(valeur);
    }
}
