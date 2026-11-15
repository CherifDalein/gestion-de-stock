package org.example.stock.compta;

import org.example.stock.compta.DonneesComptables.*;
import org.example.stock.compta.Ecriture.TypeTiers;
import org.example.stock.enums.TypeOperationTresorerie;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Calcul pur des écritures : une opération, une écriture attendue écrite à la main (compte, sens, montant). */
class ConstructeurEcrituresTests {
    static final LocalDateTime T0 = LocalDateTime.of(2026, 6, 1, 9, 0);
    static final Map<Long, String> TRESO = Map.of(1L, "5711", 2L, "5521", 3L, "5212");

    /** Constructeur de jeux de données minimaux. */
    static final class Jeu {
        final List<Vente> ventes = new ArrayList<>(); final List<Achat> achats = new ArrayList<>(); final List<Retour> retours = new ArrayList<>();
        final List<Mouvement> mouvements = new ArrayList<>(); final Map<Long, Operation> operations = new HashMap<>();
        final List<Partenaire> clients = new ArrayList<>(), fournisseurs = new ArrayList<>(); final List<Ajustement> ajustements = new ArrayList<>();
        BigDecimal stockDepart = BigDecimal.ZERO; List<ValeurStock> valeurs = List.of();
        long suivant = 1;

        Jeu client(long id, String nom, String solde) { clients.add(new Partenaire(id, nom, new BigDecimal(solde))); return this; }
        Jeu fournisseur(long id, String nom, String solde) { fournisseurs.add(new Partenaire(id, nom, new BigDecimal(solde))); return this; }
        Jeu vente(long id, String total, String verse, String avance, Long client, String services) {
            ventes.add(new Vente(id, "V" + id, T0.plusMinutes(id), new BigDecimal(total), new BigDecimal(verse), new BigDecimal(avance), client, client == null ? null : "Client " + client, new BigDecimal(services))); return this;
        }
        Jeu achat(long id, String total, String verse, String avance, Long fournisseur) {
            achats.add(new Achat(id, "A" + id, T0.plusMinutes(id), new BigDecimal(total), new BigDecimal(verse), new BigDecimal(avance), fournisseur, fournisseur == null ? null : "Fournisseur " + fournisseur)); return this;
        }
        Jeu retourVente(long id, String total, String rembourse, String avoir, Long client, String services) {
            retours.add(new Retour(id, "RV" + id, true, T0.plusMinutes(100 + id), new BigDecimal(total), new BigDecimal(rembourse), new BigDecimal(avoir), client, "Client " + client, new BigDecimal(services))); return this;
        }
        Jeu retourAchat(long id, String total, String rembourse, String avoir, Long fournisseur) {
            retours.add(new Retour(id, "RA" + id, false, T0.plusMinutes(100 + id), new BigDecimal(total), new BigDecimal(rembourse), new BigDecimal(avoir), fournisseur, "Fournisseur " + fournisseur, BigDecimal.ZERO)); return this;
        }
        Jeu mouvement(long compte, String montant, String source, String motif, Long vente, Long achat, Long client, Long fournisseur, Long retour, Long operation, Long comptage, Long annule) {
            mouvements.add(new Mouvement(suivant++, T0.plusMinutes(200 + suivant), compte, new BigDecimal(montant), source, motif, vente, achat, client, fournisseur, retour, operation, comptage, annule)); return this;
        }
        Jeu simple(long compte, String montant, String source, String motif) { return mouvement(compte, montant, source, motif, null, null, null, null, null, null, null, null); }
        Jeu operation(long id, TypeOperationTresorerie type, String motif, Long partenaire) {
            operations.put(id, new Operation(id, type, motif, partenaire, partenaire == null ? null : "Partenaire " + partenaire, null)); return this;
        }
        List<Ecriture> ecritures() { return ecritures(Correspondance.parDefaut(TRESO)); }
        List<Ecriture> ecritures(Correspondance c) {
            return new ConstructeurEcritures(new DonneesComptables(T0.toLocalDate().atStartOfDay(), ventes, achats, retours, mouvements, operations, clients, fournisseurs, ajustements, stockDepart, valeurs), c).construire();
        }
    }

    /** Lignes d'une écriture sous la forme « D 4111 100 » ou « C 701 100 », dans l'ordre. */
    static List<String> lignes(Ecriture e) {
        List<String> r = new ArrayList<>();
        for (Ecriture.Ligne l : e.lignes()) r.add(l.debit().signum() > 0 ? "D " + l.compte() + " " + l.debit().stripTrailingZeros().toPlainString() : "C " + l.compte() + " " + l.credit().stripTrailingZeros().toPlainString());
        return r;
    }

    static Ecriture de(List<Ecriture> es, String origine) { return es.stream().filter(e -> e.origine().equals(origine)).findFirst().orElseThrow(() -> new AssertionError("Aucune écriture " + origine + " dans " + es)); }

    static void toutesEquilibrees(List<Ecriture> es) { assertThat(es).allSatisfy(e -> assertThat(e.estEquilibree()).as(e.toString()).isTrue()); }

    @Test
    void uneVenteAuComptantEtSonEncaissement() {
        Jeu j = new Jeu().client(1, "A", "0").vente(1, "10000", "10000", "0", 1L, "0")
                .mouvement(1, "10000", "VENTE", "Vente V1", 1L, null, null, null, null, null, null, null);
        List<Ecriture> es = j.ecritures();
        assertThat(lignes(de(es, "VENTE:1"))).containsExactly("D 4111 10000", "C 701 10000");
        assertThat(de(es, "VENTE:1").journal()).isEqualTo(Journal.VE);
        assertThat(lignes(de(es, "MOUVEMENT:1"))).containsExactly("D 5711 10000", "C 4111 10000");
        assertThat(de(es, "MOUVEMENT:1").journal()).isEqualTo(Journal.TR);
        toutesEquilibrees(es);
    }

    @Test
    void uneVenteDeServicesEtDeMarchandisesSepareLesProduits() {
        List<Ecriture> es = new Jeu().vente(1, "10000", "10000", "0", null, "3000").ecritures();
        assertThat(lignes(de(es, "VENTE:1"))).containsExactly("D 4111 10000", "C 701 7000", "C 706 3000");
        assertThat(de(es, "VENTE:1").lignes().getFirst().tiersId()).isNull();
    }

    @Test
    void uneVenteAvecAvanceUtiliseeDebiteLesAvances() {
        List<Ecriture> es = new Jeu().client(1, "A", "0").vente(1, "20000", "0", "20000", 1L, "0").ecritures();
        assertThat(lignes(de(es, "VENTE:1"))).containsExactly("D 4191 20000", "C 701 20000");
    }

    @Test
    void unTropPayeALaVenteDevientUneAvanceDuClient() {
        // Vente 30 000 payée 80 000 : 30 000 règlent la créance, 50 000 sont une avance.
        Jeu j = new Jeu().client(1, "A", "0").vente(1, "30000", "80000", "0", 1L, "0")
                .mouvement(1, "80000", "VENTE", "", 1L, null, null, null, null, null, null, null);
        List<Ecriture> es = j.ecritures();
        assertThat(lignes(de(es, "MOUVEMENT:1"))).containsExactly("D 5711 80000", "C 4191 50000", "C 4111 30000");
        toutesEquilibrees(es);
    }

    @Test
    void unAchatAvecAvanceEtSonReglement() {
        Jeu j = new Jeu().fournisseur(1, "F", "0").achat(1, "100000", "40000", "10000", 1L)
                .mouvement(3, "-40000", "ACHAT", "Achat A1", null, 1L, null, null, null, null, null, null);
        List<Ecriture> es = j.ecritures();
        assertThat(lignes(de(es, "ACHAT:1"))).containsExactly("D 601 100000", "C 4011 90000", "C 4091 10000");
        assertThat(de(es, "ACHAT:1").journal()).isEqualTo(Journal.AC);
        assertThat(lignes(de(es, "MOUVEMENT:1"))).containsExactly("C 5212 40000", "D 4011 40000");
    }

    @Test
    void unRetourDeVenteRembourseEnArgent() {
        Jeu j = new Jeu().client(1, "A", "0").vente(1, "100000", "100000", "0", 1L, "0").retourVente(1, "20000", "20000", "0", 1L, "0")
                .mouvement(1, "-20000", "RETOUR_VENTE", "Retour", null, null, null, null, 1L, null, null, null);
        List<Ecriture> es = j.ecritures();
        assertThat(lignes(de(es, "RETOUR:1"))).containsExactly("D 701 20000", "C 4111 20000");
        assertThat(lignes(de(es, "MOUVEMENT:1"))).containsExactly("C 5711 20000", "D 4111 20000");
        toutesEquilibrees(es);
    }

    @Test
    void unRetourDeVenteGardeEnAvoirCreditLesAvances() {
        List<Ecriture> es = new Jeu().client(1, "A", "0").retourVente(1, "10000", "0", "10000", 1L, "0").ecritures();
        assertThat(lignes(de(es, "RETOUR:1"))).containsExactly("D 701 10000", "C 4191 10000");
    }

    @Test
    void unRetourDeVenteImputeSurLeResteEtSepareLesServices() {
        List<Ecriture> es = new Jeu().client(1, "A", "0").retourVente(1, "20000", "0", "0", 1L, "5000").ecritures();
        assertThat(lignes(de(es, "RETOUR:1"))).containsExactly("D 701 15000", "D 706 5000", "C 4111 20000");
    }

    @Test
    void desRetoursDAchatEnArgentEnAvoirEtImputes() {
        Jeu j = new Jeu().fournisseur(1, "F", "0").retourAchat(1, "10000", "10000", "0", 1L).retourAchat(2, "5000", "0", "5000", 1L).retourAchat(3, "20000", "0", "0", 1L)
                .mouvement(3, "10000", "RETOUR_ACHAT", "Retour", null, null, null, null, 1L, null, null, null);
        List<Ecriture> es = j.ecritures();
        assertThat(lignes(de(es, "RETOUR:1"))).containsExactly("D 4011 10000", "C 601 10000");
        assertThat(lignes(de(es, "RETOUR:2"))).containsExactly("D 4091 5000", "C 601 5000");
        assertThat(lignes(de(es, "RETOUR:3"))).containsExactly("D 4011 20000", "C 601 20000");
        assertThat(lignes(de(es, "MOUVEMENT:1"))).containsExactly("D 5212 10000", "C 4011 10000");
        assertThat(de(es, "RETOUR:1").journal()).isEqualTo(Journal.AC);
    }

    @Test
    void desReglementsPlusTardEtDeSoldesDeDepart() {
        Jeu j = new Jeu().client(1, "A", "50000").fournisseur(1, "F", "-30000")
                .simple(1, "20000", "SOLDE_CLIENT", "Règlement du solde").simple(2, "-10000", "SOLDE_FOURNISSEUR", "Règlement");
        j.mouvements.set(0, new Mouvement(1L, T0.plusMinutes(300), 1L, new BigDecimal("20000"), "SOLDE_CLIENT", "R", null, null, 1L, null, null, null, null, null));
        j.mouvements.set(1, new Mouvement(2L, T0.plusMinutes(301), 2L, new BigDecimal("-10000"), "SOLDE_FOURNISSEUR", "R", null, null, null, 1L, null, null, null, null));
        List<Ecriture> es = j.ecritures();
        assertThat(lignes(de(es, "REPORT:OUV-C1"))).containsExactly("D 4111 50000", "C 12 50000");
        assertThat(lignes(de(es, "REPORT:OUV-F1"))).containsExactly("D 12 30000", "C 4011 30000");
        assertThat(lignes(de(es, "MOUVEMENT:1"))).containsExactly("D 5711 20000", "C 4111 20000");
        assertThat(lignes(de(es, "MOUVEMENT:2"))).containsExactly("C 5521 10000", "D 4011 10000");
        assertThat(de(es, "REPORT:OUV-C1").journal()).isEqualTo(Journal.OD);
    }

    @Test
    void desSoldesDeDepartInversesVontAuxAvances() {
        List<Ecriture> es = new Jeu().client(1, "A", "-20000").fournisseur(1, "F", "30000").ecritures();
        assertThat(lignes(de(es, "REPORT:OUV-C1"))).containsExactly("D 12 20000", "C 4191 20000");
        assertThat(lignes(de(es, "REPORT:OUV-F1"))).containsExactly("D 4091 30000", "C 12 30000");
    }

    @Test
    void unAjustementDeSoldeDeDepartEcritLaDifference() {
        Jeu j = new Jeu().client(1, "A", "80000");
        j.ajustements.add(new Ajustement(1L, T0.plusDays(5), 1L, null, new BigDecimal("50000"), new BigDecimal("80000"), "Correction"));
        List<Ecriture> es = j.ecritures();
        // Le report d'origine est l'ancien solde (50 000), l'ajustement ajoute 30 000.
        assertThat(lignes(de(es, "REPORT:OUV-C1"))).containsExactly("D 4111 50000", "C 12 50000");
        assertThat(lignes(de(es, "REPORT:AJ-1"))).containsExactly("D 4111 30000", "C 12 30000");
        assertThat(de(es, "REPORT:AJ-1").date()).isEqualTo(T0.plusDays(5));
    }

    @Test
    void uneDepenseEstClasseParMotsCles() {
        Jeu j = new Jeu().operation(1, TypeOperationTresorerie.DEPENSE, "Loyer de juin", null).operation(2, TypeOperationTresorerie.DEPENSE, "Achat de papier pour le bureau", null)
                .mouvement(1, "-50000", "DEPENSE", "Loyer de juin", null, null, null, null, null, 1L, null, null)
                .mouvement(1, "-3000", "DEPENSE", "Achat de papier pour le bureau", null, null, null, null, null, 2L, null, null);
        List<Ecriture> es = j.ecritures();
        assertThat(lignes(de(es, "MOUVEMENT:1"))).containsExactly("C 5711 50000", "D 622 50000");
        // « bureau » ne contient pas le mot-clé « eau » : la dépense va aux fournitures, pas à l'eau.
        assertThat(lignes(de(es, "MOUVEMENT:2"))).containsExactly("C 5711 3000", "D 6055 3000");
    }

    @Test
    void uneDepenseInconnueVaAuxAutresCharges() {
        Jeu j = new Jeu().operation(1, TypeOperationTresorerie.DEPENSE, "Cadeau", null).mouvement(1, "-1000", "DEPENSE", "Cadeau", null, null, null, null, null, 1L, null, null);
        assertThat(lignes(de(j.ecritures(), "MOUVEMENT:1"))).containsExactly("C 5711 1000", "D 658 1000");
    }

    @Test
    void unTransfertPasseParLeCompteDeVirement() {
        Jeu j = new Jeu().operation(1, TypeOperationTresorerie.TRANSFERT, "Dépôt en banque", null)
                .mouvement(1, "-100000", "TRANSFERT", "Dépôt", null, null, null, null, null, 1L, null, null)
                .mouvement(3, "100000", "TRANSFERT", "Dépôt", null, null, null, null, null, 1L, null, null);
        List<Ecriture> es = j.ecritures();
        assertThat(es).hasSize(1);
        assertThat(lignes(es.getFirst())).containsExactly("D 585 100000", "C 5711 100000", "D 5212 100000", "C 585 100000");
        assertThat(es.getFirst().estEquilibree()).isTrue();
    }

    @Test
    void lesFinancementsEtLesApports() {
        Jeu j = new Jeu().operation(1, TypeOperationTresorerie.APPORT_CAPITAL, "Apport", null).operation(2, TypeOperationTresorerie.APPORT_COMPTE_COURANT, "Avance", 9L)
                .operation(3, TypeOperationTresorerie.DEBLOCAGE, "Emprunt", 9L)
                .simple(1, "500000", "APPORT_CAPITAL", "Apport").mouvement(1, "-200000", "RETRAIT_COMPTE_COURANT", "Retrait", null, null, null, null, null, 2L, null, null)
                .simple(3, "1000000", "DEBLOCAGE_EMPRUNT", "Emprunt").simple(3, "-100000", "REMBOURSEMENT_EMPRUNT", "Capital").simple(3, "-5000", "INTERETS_EMPRUNT", "Intérêts")
                .simple(3, "-2000", "FRAIS_DOSSIER", "Frais").simple(3, "-900000", "DEBLOCAGE_PRET", "Prêt").simple(3, "100000", "REMBOURSEMENT_PRET", "Capital reçu").simple(3, "4000", "INTERETS_PRET", "Intérêts reçus")
                .simple(1, "1000", "PRODUIT_FINANCIER", "").simple(1, "-1500", "CHARGE_FINANCIERE", "").simple(1, "700", "AUTRE_PRODUIT", "").simple(1, "-800", "AUTRE_CHARGE", "");
        List<Ecriture> es = j.ecritures();
        assertThat(lignes(de(es, "MOUVEMENT:1"))).containsExactly("D 5711 500000", "C 104 500000");
        assertThat(lignes(de(es, "MOUVEMENT:2"))).containsExactly("C 5711 200000", "D 4621 200000");
        assertThat(lignes(de(es, "MOUVEMENT:3"))).containsExactly("D 5212 1000000", "C 162 1000000");
        assertThat(lignes(de(es, "MOUVEMENT:4"))).containsExactly("C 5212 100000", "D 162 100000");
        assertThat(lignes(de(es, "MOUVEMENT:5"))).containsExactly("C 5212 5000", "D 6712 5000");
        assertThat(lignes(de(es, "MOUVEMENT:6"))).containsExactly("C 5212 2000", "D 631 2000");
        assertThat(lignes(de(es, "MOUVEMENT:7"))).containsExactly("C 5212 900000", "D 272 900000");
        assertThat(lignes(de(es, "MOUVEMENT:8"))).containsExactly("D 5212 100000", "C 272 100000");
        assertThat(lignes(de(es, "MOUVEMENT:9"))).containsExactly("D 5212 4000", "C 7712 4000");
        assertThat(lignes(de(es, "MOUVEMENT:10"))).containsExactly("D 5711 1000", "C 774 1000");
        assertThat(lignes(de(es, "MOUVEMENT:11"))).containsExactly("C 5711 1500", "D 6318 1500");
        assertThat(lignes(de(es, "MOUVEMENT:12"))).containsExactly("D 5711 700", "C 758 700");
        assertThat(lignes(de(es, "MOUVEMENT:13"))).containsExactly("C 5711 800", "D 658 800");
        assertThat(de(es, "MOUVEMENT:2").lignes().get(1).tiersType()).isEqualTo(TypeTiers.PARTENAIRE);
        toutesEquilibrees(es);
    }

    @Test
    void unEcartDeCaisseVaAuxComptesDEcart() {
        Jeu j = new Jeu().mouvement(1, "-2000", "AUTRE_CHARGE", "Écart", null, null, null, null, null, null, 7L, null).mouvement(1, "500", "AUTRE_PRODUIT", "Écart", null, null, null, null, null, null, 8L, null);
        List<Ecriture> es = j.ecritures();
        assertThat(lignes(de(es, "MOUVEMENT:1"))).containsExactly("C 5711 2000", "D 658 2000");
        assertThat(lignes(de(es, "MOUVEMENT:2"))).containsExactly("D 5711 500", "C 758 500");
    }

    @Test
    void uneAnnulationEstLaContrePassationDeLEcritureAnnulee() {
        Jeu j = new Jeu().client(1, "A", "50000").mouvement(1, "10000", "REGLEMENT_VENTE", "R", null, null, 1L, null, null, null, null, null)
                .mouvement(1, "-10000", "ANNULATION", "Annulation", null, null, 1L, null, null, null, null, 1L);
        List<Ecriture> es = j.ecritures();
        Ecriture annulation = de(es, "MOUVEMENT:2");
        assertThat(annulation.contrePassation()).isTrue();
        assertThat(lignes(annulation)).containsExactly("C 5711 10000", "D 4111 10000");
        toutesEquilibrees(es);
    }

    @Test
    void uneAnnulationDOrigineInconnueResteEquilibree() {
        List<Ecriture> es = new Jeu().mouvement(1, "-10000", "ANNULATION", "Annulation", null, null, null, null, null, null, null, 99L).ecritures();
        assertThat(lignes(es.getFirst())).containsExactly("C 5711 10000", "D 471 10000");
    }

    @Test
    void unCompteDeTresorerieInconnuVaAuCompteDAttente() {
        List<Ecriture> es = new Jeu().simple(99, "1000", "AUTRE_PRODUIT", "?").ecritures();
        assertThat(lignes(es.getFirst())).containsExactly("D 471 1000", "C 758 1000");
    }

    @Test
    void leStockEstRegulariseALaDateDInventaire() {
        Jeu j = new Jeu().simple(1, "1000", "AUTRE_PRODUIT", "x");
        j.stockDepart = new BigDecimal("100000"); j.valeurs = List.of(new ValeurStock(T0.plusDays(30), new BigDecimal("160000")));
        List<Ecriture> es = j.ecritures();
        assertThat(lignes(de(es, "STOCK_DEPART"))).containsExactly("D 31 100000", "C 12 100000");
        assertThat(lignes(de(es, "INVENTAIRE"))).containsExactly("D 31 60000", "C 6031 60000");
        j.valeurs = List.of(new ValeurStock(T0.plusDays(30), new BigDecimal("40000")));
        assertThat(lignes(de(j.ecritures(), "INVENTAIRE"))).containsExactly("D 6031 60000", "C 31 60000");
        j.valeurs = List.of(new ValeurStock(T0.plusDays(30), new BigDecimal("100000")));
        assertThat(j.ecritures().stream().noneMatch(e -> e.origine().equals("INVENTAIRE"))).isTrue();
    }

    @Test
    void lesEcrituresSontTrieesParDateEtParJournal() {
        List<Ecriture> es = new Jeu().client(1, "A", "10").vente(2, "100", "0", "0", 1L, "0").vente(1, "100", "0", "0", 1L, "0").achat(3, "100", "0", "0", null).ecritures();
        assertThat(es).isSortedAccordingTo(java.util.Comparator.comparing(Ecriture::date));
        assertThat(es.getFirst().piece()).isEqualTo("OUV-C1");
    }

    @Test
    void sansOperationRienNEstEcrit() {
        DonneesComptables vide = new DonneesComptables(null, List.of(), List.of(), List.of(), List.of(), Map.of(), List.of(new Partenaire(1L, "A", new BigDecimal("500"))), List.of(), List.of(), BigDecimal.ZERO, List.of());
        assertThat(new ConstructeurEcritures(vide, Correspondance.parDefaut(TRESO)).construire()).isEmpty();
    }

    @Test
    void laCorrespondanceModifieeChangeLesComptes() {
        Correspondance c = Correspondance.avec(Map.of(CleCompta.VENTES_MARCHANDISES, "702", CleCompta.CREANCES_CLIENTS, "4112"), Map.of(CleCompta.DEPENSE_LOYER, "loyer, bail"), TRESO);
        List<Ecriture> es = new Jeu().vente(1, "1000", "0", "0", null, "0").ecritures(c);
        assertThat(lignes(es.getFirst())).containsExactly("D 4112 1000", "C 702 1000");
        assertThat(c.categorieDepense("Paiement du bail")).isEqualTo(CleCompta.DEPENSE_LOYER);
    }
}
