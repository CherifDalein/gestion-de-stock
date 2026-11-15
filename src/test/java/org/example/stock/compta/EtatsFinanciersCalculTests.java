package org.example.stock.compta;

import org.example.stock.compta.Ecriture.Ligne;
import org.example.stock.compta.Ecriture.TypeTiers;
import org.example.stock.compta.EtatsFinanciersCalcul.Exercice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calcul des états financiers sur des écritures construites à la main : chaque jeu est équilibré, et les invariants du bilan (actif = passif),
 * du résultat (bilan = compte de résultat), de la trésorerie (tableau des flux = balance) et du système minimal de trésorerie doivent tenir.
 * Un dernier test passe tout le plan officiel dans les rattachements.
 */
class EtatsFinanciersCalculTests {
    static final LocalDate DU = LocalDate.of(2026, 1, 1), AU = LocalDate.of(2026, 12, 31);
    static final LocalDateTime OUVERTURE = LocalDateTime.of(2025, 12, 31, 9, 0);

    // ---- Outils ---------------------------------------------------------------------------------------------------------

    static Ligne d(String compte, long montant) { return new Ligne(compte, BigDecimal.valueOf(montant), BigDecimal.ZERO, null, null, null); }
    static Ligne c(String compte, long montant) { return new Ligne(compte, BigDecimal.ZERO, BigDecimal.valueOf(montant), null, null, null); }
    static Ligne dt(String compte, long montant, TypeTiers t, long id) { return new Ligne(compte, BigDecimal.valueOf(montant), BigDecimal.ZERO, t, id, "Tiers " + id); }
    static Ligne ct(String compte, long montant, TypeTiers t, long id) { return new Ligne(compte, BigDecimal.ZERO, BigDecimal.valueOf(montant), t, id, "Tiers " + id); }

    static Ecriture e(LocalDateTime date, String piece, Ligne... lignes) {
        Ecriture ecriture = new Ecriture(date, Journal.OD, piece, piece, List.of(lignes), "TEST:" + piece, false);
        assertThat(ecriture.estEquilibree()).as("écriture " + piece).isTrue();
        return ecriture;
    }

    static LocalDateTime le(int mois, int jour) { return LocalDateTime.of(2026, mois, jour, 10, 0); }

    /** Écritures avant la période (le bilan d'ouverture) et pendant la période, calculées ensemble comme le service le fait. */
    static Exercice calculer(List<Ecriture> avant, List<Ecriture> periode) {
        List<Ecriture> tout = new ArrayList<>(avant);
        tout.addAll(periode);
        return EtatsFinanciersCalcul.calculer(tout, avant, DU, AU, "12");
    }

    static void invariants(Exercice x) { invariants(x, true); }

    /** {@code avecSmt} faux pour une cession d'immobilisation : l'application n'en crée pas et le système minimal de trésorerie ne la traite pas (limite documentée). */
    static void invariants(Exercice x, boolean avecSmt) {
        assertThat(x.bilan().ecart()).as("actif - passif").isEqualByComparingTo("0");
        assertThat(x.bilan().passif("CJ")).as("résultat du bilan").isEqualByComparingTo(x.compteResultat().montant("XI"));
        assertThat(x.compteResultat().ecart()).as("compte de résultat - comptes de gestion").isEqualByComparingTo("0");
        assertThat(x.ecartFlux()).as("trésorerie des flux - balance").isEqualByComparingTo("0");
        if (avecSmt) {
            assertThat(x.smt().ecart()).as("bilan SMT").isEqualByComparingTo("0");
            assertThat(x.smt().resultat().get("G")).as("résultat SMT").isEqualByComparingTo(x.compteResultat().montant("XI"));
        }
        // La variation du tableau des flux est la variation réelle de la trésorerie.
        assertThat(x.flux().get("ZG")).as("variation").isEqualByComparingTo(x.flux().get("ZH").subtract(x.flux().get("ZA")));
        assertThat(x.flux().get("ZB").add(x.flux().get("ZC")).add(x.flux().get("ZF"))).isEqualByComparingTo(x.flux().get("ZG"));
    }

    /** Ouverture : fonds de 1 000 000 en caisse (571) et en banque (521), contre le report à nouveau (12). */
    static List<Ecriture> ouverture() {
        return List.of(e(OUVERTURE, "OUV", d("571", 400_000), d("521", 600_000), c("12", 1_000_000)));
    }

    // ---- Jeux de données ------------------------------------------------------------------------------------------------------

    @Test
    void unNegoceSimpleAvecClientEtFournisseurEquilibreEtLesFluxSuiventLaTresorerie() {
        List<Ecriture> p = List.of(
                e(le(2, 1), "ACH", d("601", 300_000), c("4011", 300_000)),
                e(le(2, 5), "PAI", d("4011", 100_000), c("521", 100_000)),
                e(le(3, 1), "VEN", d("4111", 500_000), c("701", 500_000)),
                e(le(3, 10), "ENC", d("571", 200_000), c("4111", 200_000)),
                e(le(4, 1), "LOY", d("622", 50_000), c("571", 50_000)),
                e(le(12, 31), "INV", d("31", 120_000), c("6031", 120_000)));
        Exercice x = calculer(ouverture(), p);
        invariants(x);
        assertThat(x.compteResultat().montant("XA")).isEqualByComparingTo("320000");   // 500 000 - 300 000 + 120 000
        assertThat(x.compteResultat().montant("XI")).isEqualByComparingTo("270000");   // - 50 000 de loyer
        assertThat(x.bilan().netActif("BZ")).isEqualByComparingTo("1470000");   // trésorerie 1 050 000 + stock 120 000 + clients 300 000
        assertThat(x.flux().get("ZA")).isEqualByComparingTo("1000000");
        assertThat(x.flux().get("ZH")).isEqualByComparingTo("1050000");
        assertThat(x.compteResultat().nonRattaches()).isEmpty();
        assertThat(x.bilan().nonRattaches()).isEmpty();
    }

    @Test
    void unClientCrediteurVaAuPassifEtUnFournisseurDebiteurALActifSansCompensation() {
        List<Ecriture> p = List.of(
                // Client 1 doit 100 000 ; client 2 a versé 40 000 d'avance (créditeur) sur le même compte 4111.
                e(le(2, 1), "V1", dt("4111", 100_000, TypeTiers.CLIENT, 1), c("701", 100_000)),
                e(le(2, 2), "V2", d("571", 40_000), ct("4111", 40_000, TypeTiers.CLIENT, 2)),
                // Fournisseur 1 est payé de 70 000 d'avance sur un achat de 30 000 : débiteur de 40 000 sur le compte 4011 ; fournisseur 2 est dû de 25 000.
                e(le(3, 1), "A1", d("601", 30_000), ct("4011", 30_000, TypeTiers.FOURNISSEUR, 1)),
                e(le(3, 2), "P1", dt("4011", 70_000, TypeTiers.FOURNISSEUR, 1), c("521", 70_000)),
                e(le(3, 3), "A2", d("601", 25_000), ct("4011", 25_000, TypeTiers.FOURNISSEUR, 2)));
        Exercice x = calculer(ouverture(), p);
        invariants(x);
        var b = x.bilan();
        assertThat(b.brut("BI")).as("clients débiteurs à l'actif").isEqualByComparingTo("100000");
        assertThat(b.passif("DI")).as("client créditeur (avance reçue) au passif").isEqualByComparingTo("40000");
        assertThat(b.brut("BH")).as("fournisseur débiteur (avance versée) à l'actif").isEqualByComparingTo("40000");
        assertThat(b.passif("DJ")).as("fournisseur créditeur au passif").isEqualByComparingTo("25000");
        // Pas de compensation : le client créditeur n'a pas réduit les créances (100 000 - 40 000 serait 60 000).
        assertThat(b.brut("BI")).isNotEqualByComparingTo("60000");
    }

    @Test
    void unDecouvertBancaireVaALaTresoreriePassifSansEtreCompenseParLaCaisse() {
        List<Ecriture> p = List.of(
                e(le(2, 1), "ACH", d("601", 800_000), c("521", 800_000)),            // la banque passe de 600 000 à -200 000
                e(le(3, 1), "VEN", d("571", 100_000), c("701", 100_000)));
        Exercice x = calculer(ouverture(), p);
        invariants(x);
        assertThat(x.bilan().passif("DR")).isEqualByComparingTo("200000");
        assertThat(x.bilan().brut("BS")).isEqualByComparingTo("500000");
        assertThat(x.bilan().tresorerieNette()).isEqualByComparingTo("300000");
        assertThat(x.flux().get("ZH")).isEqualByComparingTo("300000");
        assertThat(x.tresorerieBalance()).isEqualByComparingTo("300000");
    }

    @Test
    void uneImmobilisationAmortieEtUnEmpruntAvecInteretsPassentParLesFluxDInvestissementEtDeFinancement() {
        List<Ecriture> p = List.of(
                e(le(1, 15), "IMM", d("2441", 300_000), c("521", 300_000)),         // matériel acheté comptant
                e(le(2, 1), "EMP", d("521", 500_000), c("162", 500_000)),
                e(le(8, 1), "REM", d("162", 100_000), d("6712", 15_000), c("521", 115_000)),
                e(le(12, 31), "AMO", d("6811", 60_000), c("2841", 60_000)),
                e(le(3, 1), "VEN", d("571", 250_000), c("701", 250_000)));
        Exercice x = calculer(ouverture(), p);
        invariants(x);
        var b = x.bilan();
        assertThat(b.brut("AM")).isEqualByComparingTo("300000");
        assertThat(b.amort("AM")).isEqualByComparingTo("60000");
        assertThat(b.netActif("AM")).isEqualByComparingTo("240000");
        assertThat(b.passif("DA")).isEqualByComparingTo("400000");
        assertThat(x.compteResultat().montant("RL")).isEqualByComparingTo("-60000");
        assertThat(x.compteResultat().montant("RM")).isEqualByComparingTo("-15000");
        assertThat(x.compteResultat().montant("XI")).isEqualByComparingTo("175000");
        var f = x.flux();
        assertThat(f.get("FG")).as("acquisition d'immobilisations corporelles").isEqualByComparingTo("-300000");
        assertThat(f.get("ZC")).isEqualByComparingTo("-300000");
        assertThat(f.get("FO")).as("emprunts").isEqualByComparingTo("400000");
        assertThat(f.get("FA")).as("CAFG : l'EBE (250 000) moins les intérêts payés ; la dotation non décaissée n'y est pas").isEqualByComparingTo("235000");
        assertThat(f.get("ZB")).isEqualByComparingTo("235000");
        // 1 000 000 + 250 000 - 300 000 + 500 000 - 115 000 = 1 335 000
        assertThat(f.get("ZH")).isEqualByComparingTo("1335000");
    }

    @Test
    void uneEntrepriseEnPerteAvecApportEtRetraitEquilibreSonBilan() {
        List<Ecriture> p = List.of(
                e(le(1, 10), "APP", d("571", 200_000), c("104", 200_000)),
                e(le(1, 11), "CC", d("521", 100_000), c("4621", 100_000)),
                e(le(2, 1), "SAL", d("66", 900_000), c("521", 700_000), c("571", 200_000)),
                e(le(3, 1), "RET", d("104", 50_000), c("571", 50_000)));
        Exercice x = calculer(ouverture(), p);
        invariants(x);
        assertThat(x.compteResultat().montant("XI")).isEqualByComparingTo("-900000");
        assertThat(x.bilan().passif("CJ")).isEqualByComparingTo("-900000");
        assertThat(x.bilan().passif("CA")).isEqualByComparingTo("150000");
        assertThat(x.bilan().passif("DM")).as("compte courant d'associé créditeur").isEqualByComparingTo("100000");
        // Les flux de capital se lisent sur la variation du compte sur l'exercice (apport 200 000 moins retrait 50 000) : limite documentée.
        assertThat(x.flux().get("FK")).isEqualByComparingTo("150000");
        assertThat(x.flux().get("FM")).isEqualByComparingTo("0");
        assertThat(x.flux().get("FP")).as("compte courant d'associé").isEqualByComparingTo("100000");
    }

    @Test
    void lePremierExerciceSansBilanDOuvertureEtUneBoutiqueSansEcrituresRestentCoherents() {
        // Tout dans la période : les soldes d'ouverture sont dans la période elle-même (contrepartie 12), comme au démarrage de l'application.
        Exercice x = EtatsFinanciersCalcul.calculer(List.of(e(le(1, 2), "OUV", d("571", 80_000), c("12", 80_000)), e(le(1, 3), "VEN", d("571", 20_000), c("701", 20_000))),
                List.of(), DU, AU, "12");
        invariants(x);
        assertThat(x.flux().get("ZA")).as("la trésorerie d'ouverture est celle posée dans la période").isEqualByComparingTo("80000");
        assertThat(x.flux().get("ZH")).isEqualByComparingTo("100000");
        Exercice vide = EtatsFinanciersCalcul.calculer(List.of(), List.of(), DU, AU, "12");
        invariants(vide);
        assertThat(vide.aDesDonnees()).isFalse();
        assertThat(vide.bilan().totalActif()).isEqualByComparingTo("0");
    }

    @Test
    void uneCessionAvecCreanceEstRangeeAuxPostesHaoEtAuxFluxDInvestissement() {
        List<Ecriture> avant = new ArrayList<>(ouverture());
        avant.add(e(OUVERTURE.plusMinutes(1), "IMM", d("2441", 200_000), c("12", 200_000)));
        avant.add(e(OUVERTURE.plusMinutes(2), "AMO", d("12", 20_000), c("2841", 20_000)));
        List<Ecriture> p = List.of(
                // Cession à 180 000 (150 000 encaissés, 30 000 à recevoir) d'un matériel brut de 200 000 amorti de 20 000 : valeur comptable 180 000.
                e(le(6, 1), "CES", d("571", 150_000), d("485", 30_000), c("82", 180_000)),
                e(le(6, 1), "SOR", d("2841", 20_000), d("81", 180_000), c("2441", 200_000)));
        Exercice x = calculer(avant, p);
        invariants(x, false);
        assertThat(x.compteResultat().montant("TN")).isEqualByComparingTo("180000");
        assertThat(x.compteResultat().montant("RO")).isEqualByComparingTo("-180000");
        assertThat(x.compteResultat().montant("XH")).isEqualByComparingTo("0");
        assertThat(x.bilan().brut("BA")).as("créance sur cession HAO").isEqualByComparingTo("30000");
        assertThat(x.bilan().brut("AM")).isEqualByComparingTo("0");
        assertThat(x.flux().get("FI")).as("encaissements liés aux cessions : prix moins créance").isEqualByComparingTo("150000");
    }

    // ---- Couverture du plan officiel -------------------------------------------------------------------------------------------

    @Test
    void toutCompteDuPlanOfficielAUnPosteDuBilanOuDuCompteDeResultat() {
        PlanOfficiel plan = PlanOfficiel.charger();
        List<String> sansPoste = new ArrayList<>();
        Map<String, String> reperes = new LinkedHashMap<>();
        for (CompteOfficiel compte : plan.comptes()) {
            String n = compte.numero();
            char classe = n.charAt(0);
            if (classe >= '1' && classe <= '5') {
                for (long montant : new long[] {1, -1}) {
                    SoldesComptables s = SoldesComptables.de(List.of(e(le(1, 1), "T", montant > 0 ? d(n, 1) : c(n, 1), montant > 0 ? c("999", 1) : d("999", 1))));
                    var b = EtatsFinanciersCalcul.classer(s.filtrer(x -> x.equals(n)));
                    long classes = b.brut().values().stream().filter(v -> v.signum() != 0).count() + b.amort().values().stream().filter(v -> v.signum() != 0).count()
                            + b.passif().values().stream().filter(v -> v.signum() != 0).count();
                    if (classes != 1) sansPoste.add(n + (montant > 0 ? " débiteur" : " créditeur") + " -> " + classes + " poste(s)");
                    b.nonRattaches().forEach(nr -> reperes.put(n + (montant > 0 ? "D" : "C"), nr.poste()));
                }
            } else if (classe >= '6' && classe <= '8') {
                SoldesComptables s = SoldesComptables.de(List.of(e(le(1, 1), "T", d(n, 1), c("999", 1)))).filtrer(x -> x.equals(n));
                var cr = EtatsFinanciersCalcul.compteResultat(s);
                if (!cr.nonRattaches().isEmpty() && cr.nonRattaches().get(0).poste().isEmpty()) sansPoste.add(n + " (compte de gestion sans poste)");
                reperes.put(n, cr.nonRattaches().isEmpty() ? "officiel" : cr.nonRattaches().get(0).poste());
            }
        }
        assertThat(sansPoste).as("comptes qui n'atterrissent pas dans exactement un poste : " + sansPoste).isEmpty();
        assertThat(plan.comptes().size()).isGreaterThan(1000);
        assertThat(new TreeSet<>(reperes.values())).isNotNull();
    }

    @Test
    void laTableDeRattachementNeCiteQueDesPostesDuModele() {
        var postesBilan = new TreeSet<String>();
        PostesOhada.BILAN_ACTIF.forEach(l -> postesBilan.add(l.ref())); PostesOhada.BILAN_PASSIF.forEach(l -> postesBilan.add(l.ref()));
        for (PostesOhada.Regle r : PostesOhada.REGLES_BILAN) assertThat(postesBilan).as("poste " + r.poste()).contains(r.poste());
        var postesCr = new TreeSet<String>();
        PostesOhada.COMPTE_RESULTAT.forEach(l -> postesCr.add(l.ref()));
        for (PostesOhada.RegleCr r : PostesOhada.REGLES_COMPTE_RESULTAT) assertThat(postesCr).as("poste " + r.poste()).contains(r.poste());
        // Chaque poste de bilan et de compte de résultat a une référence unique.
        assertThat(PostesOhada.BILAN_ACTIF.stream().map(PostesOhada.Ligne::ref).distinct().count()).isEqualTo(PostesOhada.BILAN_ACTIF.size());
        assertThat(PostesOhada.COMPTE_RESULTAT.stream().map(PostesOhada.Ligne::ref).distinct().count()).isEqualTo(PostesOhada.COMPTE_RESULTAT.size());
    }
}
