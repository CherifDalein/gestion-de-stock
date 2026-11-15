package org.example.stock.compta;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plan officiel embarqué, normalisation des textes et clés par défaut. */
class PlanEtCorrespondanceTests {
    static final PlanOfficiel PLAN = PlanOfficiel.charger();

    @Test
    void lePlanOfficielContientLes1401ComptesDuClient() {
        assertThat(PLAN.taille()).isEqualTo(1401);
        assertThat(PLAN.trouver("4111")).get().satisfies(c -> { assertThat(c.intitule()).isEqualToIgnoringCase("Clients"); assertThat(c.classe()).isEqualTo(4); });
        assertThat(PLAN.existe("10")).isTrue();
        assertThat(PLAN.existe("99999")).isFalse();
        assertThat(PLAN.comptes()).extracting(CompteOfficiel::numero).doesNotHaveDuplicates();
        assertThat(PLAN.comptes()).allSatisfy(c -> { assertThat(c.numero()).matches("\\d{2,8}"); assertThat(c.intitule()).isNotBlank(); assertThat(c.classe()).isBetween(1, 9); });
    }

    @Test
    void unIntituleAvecUnPointVirguleEntreGuillemetsEstLuEntier() throws Exception {
        var comptes = PlanOfficiel.lire(new ByteArrayInputStream(("\uFEFFClasse;Libellé de la classe;Numéro;Intitulé;Niveau\n"
                + "5;Trésorerie;5024;\"Chèques; à encaisser\";Compte\n5;Trésorerie;571;Caisse;Compte principal\n").getBytes(StandardCharsets.UTF_8)));
        assertThat(comptes).hasSize(2);
        assertThat(comptes.getFirst().intitule()).isEqualTo("Chèques; à encaisser");
        assertThat(comptes.getFirst().numero()).isEqualTo("5024");
    }

    @Test
    void unFichierSansEnteteOuAvecUneLigneCasseeEstRefuse() {
        assertThatThrownBy(() -> PlanOfficiel.lire(new ByteArrayInputStream("n'importe quoi\n1;2\n".getBytes(StandardCharsets.UTF_8)))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void leRepliSurLeParentDonneUnIntitule() {
        assertThat(PLAN.intituleApproche("5711")).isNotNull();
        assertThat(PLAN.intituleApproche("5719999")).isNotNull();
        assertThat(PLAN.intituleApproche("0012")).isNull();
    }

    @Test
    void chaqueCompteParDefautDeLaCorrespondanceExisteDansLePlanOfficiel() {
        for (CleCompta cle : CleCompta.values()) assertThat(PLAN.existe(cle.getCompteParDefaut())).as(cle.name() + " -> " + cle.getCompteParDefaut()).isTrue();
    }

    @Test
    void laNormalisationIgnoreAccentsMajusculesEtPonctuation() {
        assertThat(Normalisation.mots("  Électricité-de   JUILLET, (STEG) ")).isEqualTo("electricite de juillet steg");
        assertThat(Normalisation.mots(null)).isEmpty();
    }

    @Test
    void lesMotsClesCorrespondentAuDebutDUnMotJamaisAuMilieu() {
        Correspondance c = Correspondance.parDefaut(Map.of());
        assertThat(c.categorieDepense("Facture d'eau")).isEqualTo(CleCompta.DEPENSE_EAU);
        assertThat(c.categorieDepense("Achat de papier pour le bureau")).isNotEqualTo(CleCompta.DEPENSE_EAU);
        assertThat(c.categorieDepense("ELECTRICITE du mois")).isEqualTo(CleCompta.DEPENSE_ELECTRICITE);
        assertThat(c.categorieDepense("Électricité")).isEqualTo(CleCompta.DEPENSE_ELECTRICITE);
        assertThat(c.categorieDepense("Salaires de juin")).isEqualTo(CleCompta.DEPENSE_PERSONNEL);
        assertThat(c.categorieDepense("")).isEqualTo(CleCompta.DEPENSE_AUTRE);
        assertThat(c.categorieDepense(null)).isEqualTo(CleCompta.DEPENSE_AUTRE);
    }

    @Test
    void lesMotsClesSaisisSontNettoyesSansDoublon() {
        assertThat(Correspondance.decouperMotsCles(" Loyer, bail ,LOYER,, Éléctricité ")).containsExactly("loyer", "bail", "eleictricite".replace("eleictricite", "electricite"));
    }

    @Test
    void lesClesSontRegroupeesEtRetrouveesParCode() {
        assertThat(CleCompta.depuisCode("CREANCES_CLIENTS")).isEqualTo(CleCompta.CREANCES_CLIENTS);
        assertThat(CleCompta.depuisCode("inconnu")).isNull();
        assertThat(CleCompta.depuisCode(null)).isNull();
        assertThat(CleCompta.du(CleCompta.Groupe.DEPENSES)).containsExactlyElementsOf(CleCompta.categoriesDepense());
        assertThat(CleCompta.categoriesDepense().getLast()).isEqualTo(CleCompta.DEPENSE_AUTRE);
        assertThat(Journal.depuisCode("VE")).isEqualTo(Journal.VE);
        assertThat(Journal.depuisCode("zz")).isNull();
    }
}
