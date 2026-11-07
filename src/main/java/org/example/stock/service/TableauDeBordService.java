package org.example.stock.service;

import org.example.stock.enums.NatureProduit;
import org.example.stock.enums.TypeOperationTresorerie;
import org.example.stock.model.Montants;
import org.example.stock.model.Produit;
import org.example.stock.repository.TableauDeBordRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Indicateurs du tableau de bord, calculés en BigDecimal par des requêtes agrégées, toujours pour
 * la boutique du compte connecté. Les périodes sont des intervalles semi-ouverts [début, fin) en
 * heure locale du serveur, la convention des ventes, de la caisse et du tableau de bord existant.
 */
@Service
@Transactional(readOnly = true)
public class TableauDeBordService {
    /** Première année proposée par l'évolution mensuelle. */
    public static final int ANNEE_MINIMALE = 2000;
    /** Nombre maximal de produits à surveiller affichés. */
    public static final int LIMITE_PRODUITS_A_SURVEILLER = 10;

    private static final List<String> MOIS = List.of("Janvier", "Février", "Mars", "Avril", "Mai", "Juin", "Juillet",
            "Août", "Septembre", "Octobre", "Novembre", "Décembre");
    private static final List<String> MOIS_COURTS = List.of("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.",
            "août", "sept.", "oct.", "nov.", "déc.");

    private final TableauDeBordRepository requetes;
    private final BoutiqueService boutiqueService;

    public TableauDeBordService(TableauDeBordRepository requetes, BoutiqueService boutiqueService) {
        this.requetes = requetes;
        this.boutiqueService = boutiqueService;
    }

    /** Chiffre d'affaires du jour : seul indicateur d'activité partagé avec le caissier. */
    public BigDecimal chiffreAffairesDuJour(LocalDate jour) {
        Periode periode = Periode.jour(jour);
        return montant(requetes.chiffreAffaires(boutiqueService.idBoutiqueCourante(), periode.debut(), periode.fin()));
    }

    public Indicateurs indicateursDuJour(LocalDate jour) {
        return indicateurs(Periode.jour(jour));
    }

    public Indicateurs indicateursDuMois(LocalDate jour) {
        return indicateurs(Periode.mois(jour));
    }

    public ValeurStock valeurStock() {
        List<Object[]> lignes = requetes.valeurStock(boutiqueService.idBoutiqueCourante(), NatureProduit.STOCK);
        Object[] ligne = lignes.isEmpty() ? new Object[]{null, 0L} : lignes.getFirst();
        return new ValeurStock(montant(ligne[0]), ligne[1] == null ? 0L : ((Number) ligne[1]).longValue());
    }

    public ProduitsASurveiller produitsASurveiller() {
        Long boutiqueId = boutiqueService.idBoutiqueCourante();
        List<ProduitASurveiller> produits = requetes.produitsASurveiller(boutiqueId, NatureProduit.STOCK,
                        Produit.SEUIL_ALERTE_PAR_DEFAUT, PageRequest.of(0, LIMITE_PRODUITS_A_SURVEILLER)).stream()
                .map(ProduitASurveiller::de).toList();
        long total = requetes.nombreProduitsASurveiller(boutiqueId, NatureProduit.STOCK, Produit.SEUIL_ALERTE_PAR_DEFAUT);
        return new ProduitsASurveiller(produits, total);
    }

    /**
     * Ramène une année demandée par le navigateur à une valeur autorisée : un texte non numérique,
     * une année antérieure à {@link #ANNEE_MINIMALE} ou postérieure à l'année courante donne l'année courante.
     */
    public static AnneeChoisie choisirAnnee(String demandee, LocalDate aujourdHui) {
        int courante = aujourdHui.getYear();
        if (demandee == null || demandee.isBlank()) return new AnneeChoisie(courante, false);
        String valeur = demandee.strip();
        if (!valeur.matches("\\d{4}")) return new AnneeChoisie(courante, true);
        int annee = Integer.parseInt(valeur);
        if (annee < ANNEE_MINIMALE || annee > courante) return new AnneeChoisie(courante, true);
        return new AnneeChoisie(annee, false);
    }

    public EvolutionAnnuelle evolutionMensuelle(int annee, LocalDate aujourdHui) {
        if (annee < ANNEE_MINIMALE || annee > aujourdHui.getYear()) {
            throw new IllegalArgumentException("Année hors de la plage autorisée.");
        }
        Long boutiqueId = boutiqueService.idBoutiqueCourante();
        LocalDateTime debut = LocalDate.of(annee, 1, 1).atStartOfDay();
        LocalDateTime fin = debut.plusYears(1);
        BigDecimal[] ventes = parMois(requetes.chiffreAffairesParMois(boutiqueId, debut, fin));
        BigDecimal[] brut = parMois(requetes.beneficeBrutParMois(boutiqueId, debut, fin));
        BigDecimal[] depenses = parMois(requetes.depensesParMois(boutiqueId, TypeOperationTresorerie.DEPENSE, debut, fin));

        BigDecimal maxPositif = Montants.ZERO;
        BigDecimal maxNegatif = Montants.ZERO;
        for (int i = 0; i < 12; i++) {
            BigDecimal resultat = brut[i].subtract(depenses[i]);
            maxPositif = maxPositif.max(ventes[i]).max(resultat);
            maxNegatif = maxNegatif.max(resultat.negate());
        }
        BigDecimal echelle = maxPositif.add(maxNegatif);

        List<MoisActivite> mois = new ArrayList<>(12);
        BigDecimal totalVentes = Montants.ZERO, totalBrut = Montants.ZERO, totalDepenses = Montants.ZERO;
        for (int i = 0; i < 12; i++) {
            BigDecimal resultat = brut[i].subtract(depenses[i]);
            mois.add(new MoisActivite(i + 1, MOIS.get(i), MOIS_COURTS.get(i), ventes[i], brut[i], depenses[i], resultat,
                    pourcentage(ventes[i], maxPositif), pourcentage(resultat.max(Montants.ZERO), maxPositif),
                    pourcentage(resultat.min(Montants.ZERO).negate(), maxNegatif)));
            totalVentes = totalVentes.add(ventes[i]);
            totalBrut = totalBrut.add(brut[i]);
            totalDepenses = totalDepenses.add(depenses[i]);
        }
        boolean vide = mois.stream().allMatch(m -> m.ventes().signum() == 0 && m.beneficeBrut().signum() == 0
                && m.depenses().signum() == 0);
        int partPositive = echelle.signum() == 0 ? 100 : pourcentage(maxPositif, echelle);
        int partNegative = maxNegatif.signum() > 0 ? 100 - partPositive : 0;
        return new EvolutionAnnuelle(annee, annee > ANNEE_MINIMALE ? annee - 1 : null,
                annee < aujourdHui.getYear() ? annee + 1 : null, List.copyOf(mois),
                totalVentes, totalBrut, totalDepenses, totalBrut.subtract(totalDepenses), vide,
                partPositive, partNegative);
    }

    private Indicateurs indicateurs(Periode periode) {
        Long boutiqueId = boutiqueService.idBoutiqueCourante();
        BigDecimal chiffreAffaires = montant(requetes.chiffreAffaires(boutiqueId, periode.debut(), periode.fin()));
        BigDecimal brut = montant(requetes.beneficeBrut(boutiqueId, periode.debut(), periode.fin()));
        long sansCout = requetes.lignesSansCout(boutiqueId, periode.debut(), periode.fin());
        BigDecimal depenses = montant(requetes.depenses(boutiqueId, TypeOperationTresorerie.DEPENSE,
                periode.debut(), periode.fin()));
        return new Indicateurs(chiffreAffaires, brut, depenses, brut.subtract(depenses), sansCout);
    }

    private static BigDecimal[] parMois(List<Object[]> lignes) {
        BigDecimal[] valeurs = new BigDecimal[12];
        java.util.Arrays.fill(valeurs, Montants.ZERO);
        for (Object[] ligne : lignes) {
            int mois = ((Number) ligne[0]).intValue();
            if (mois >= 1 && mois <= 12) valeurs[mois - 1] = valeurs[mois - 1].add(montant(ligne[1]));
        }
        return valeurs;
    }

    /** Montant décimal à deux décimales ; les agrégats vides valent 0. */
    static BigDecimal montant(Object valeur) {
        BigDecimal resultat;
        if (valeur == null) resultat = Montants.ZERO;
        else if (valeur instanceof BigDecimal decimal) resultat = decimal;
        else if (valeur instanceof Number nombre) resultat = new BigDecimal(nombre.toString());
        else throw new IllegalStateException("Agrégat inattendu.");
        return resultat.setScale(2, RoundingMode.HALF_UP);
    }

    /** Part entière (0 à 100) de {@code valeur} dans {@code maximum}, arrondie vers le haut pour rester visible. */
    private static int pourcentage(BigDecimal valeur, BigDecimal maximum) {
        if (maximum.signum() <= 0 || valeur.signum() <= 0) return 0;
        return valeur.multiply(BigDecimal.valueOf(100)).divide(maximum, 0, RoundingMode.CEILING).min(BigDecimal.valueOf(100)).intValue();
    }

    private record Periode(LocalDateTime debut, LocalDateTime fin) {
        static Periode jour(LocalDate jour) {
            return new Periode(jour.atStartOfDay(), jour.plusDays(1).atStartOfDay());
        }

        static Periode mois(LocalDate jour) {
            LocalDate premier = jour.withDayOfMonth(1);
            return new Periode(premier.atStartOfDay(), premier.plusMonths(1).atStartOfDay());
        }
    }

    /** Chiffre d'affaires, bénéfice brut, dépenses et bénéfice net d'une période. */
    public record Indicateurs(BigDecimal chiffreAffaires, BigDecimal beneficeBrut, BigDecimal depenses,
                              BigDecimal beneficeNet, long lignesSansCout) {}

    /** Valeur au coût moyen des articles en stock, services exclus. */
    public record ValeurStock(BigDecimal valeur, long nombreProduits) {}

    /** Article à surveiller, sans prix ni coût : la liste est aussi montrée au caissier. */
    public record ProduitASurveiller(Long id, String nom, String reference, Long quantite, Long seuilAlerte,
                                     String unite, String etat) {
        static ProduitASurveiller de(Produit produit) {
            return new ProduitASurveiller(produit.getId(), produit.getNom(), produit.getReference(), produit.getQuantite(),
                    Objects.requireNonNullElse(produit.getSeuilAlerte(), Produit.SEUIL_ALERTE_PAR_DEFAUT),
                    produit.getUnite(), produit.etatStock());
        }

        public boolean enRupture() { return "rupture".equals(etat); }
    }

    public record ProduitsASurveiller(List<ProduitASurveiller> produits, long total) {
        public long autres() { return Math.max(0, total - produits.size()); }
    }

    public record AnneeChoisie(int annee, boolean corrigee) {}

    /**
     * Activité d'un mois. Les hauteurs des barres (0 à 100) sont calculées par le serveur : ventes et
     * résultat positif dans la zone haute, résultat négatif dans la zone basse.
     */
    public record MoisActivite(int numero, String libelle, String libelleCourt, BigDecimal ventes,
                               BigDecimal beneficeBrut, BigDecimal depenses, BigDecimal resultat,
                               int hauteurVentes, int hauteurResultat, int hauteurPerte) {}

    /**
     * Évolution d'une année. {@code partPositive} et {@code partNegative} répartissent la hauteur du
     * graphique entre les valeurs positives et les pertes.
     */
    public record EvolutionAnnuelle(int annee, Integer anneePrecedente, Integer anneeSuivante, List<MoisActivite> mois,
                                    BigDecimal totalVentes, BigDecimal totalBeneficeBrut, BigDecimal totalDepenses,
                                    BigDecimal totalResultat, boolean vide, int partPositive, int partNegative) {}
}
