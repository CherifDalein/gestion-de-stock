package org.example.stock.export;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lot 4, tâche B : outils communs des exports de listes (recherche, tri, paramètres, format du papier), sans base de données. */
class ListeExportOutilsTests {

    @Test
    void laRechercheIgnoreAccentsCasseLigaturesEtEspacesInsecables() {
        assertThat(RechercheListe.correspond("ELEVE ca", "Élève Ça’ñ")).isTrue();
        assertThat(RechercheListe.correspond("oeuvre", "12 rue de l'Œuvre")).isTrue();
        assertThat(RechercheListe.correspond("1 500 000", "Total 1 500 000")).isTrue();
        assertThat(RechercheListe.correspond("1 500 000", "Total 1 500 000")).isTrue();
        assertThat(RechercheListe.correspond("", "n'importe quoi")).isTrue();
        assertThat(RechercheListe.correspond("   ", "n'importe quoi")).isTrue();
        assertThat(RechercheListe.correspond("riz sucre", "Riz 50kg")).isFalse();
        assertThat(RechercheListe.correspond("riz", null)).isFalse();
    }

    @Test
    void lesMontantsSeRetrouventAvecEtSansSeparateurDeMilliers() {
        String texte = RechercheListe.montantRecherchable(new java.math.BigDecimal("1500000.50"));
        assertThat(RechercheListe.correspond("1500000.50", texte)).isTrue();
        assertThat(RechercheListe.correspond("1 500 000,50", texte)).isTrue();
        assertThat(RechercheListe.correspond("1500000", texte)).isTrue();
        assertThat(RechercheListe.correspond("2500000", texte)).isFalse();
    }

    @Test
    void leTriNaturelCompareLesNombresCommeDesNombresEtMetLesVidesALaFin() {
        List<String> valeurs = new ArrayList<>(java.util.Arrays.asList("REF-10", "ref-2", null, "REF-1", "", "Élan", "Zèbre", "REF-020"));
        valeurs.sort(RechercheListe.ordreNaturel());
        assertThat(valeurs.subList(0, 6)).containsExactly("Élan", "REF-1", "ref-2", "REF-10", "REF-020", "Zèbre");
        assertThat(valeurs.subList(6, 8)).containsExactlyInAnyOrder(null, "");
    }

    @Test
    void lesParametresInvalidesDonnentUne400() {
        assertThat(ParametresListe.recherche(null)).isEmpty();
        assertThat(ParametresListe.recherche(List.of("  riz  "))).isEqualTo("riz");
        assertThatThrownBy(() -> ParametresListe.recherche(List.of("a", "b"))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        assertThatThrownBy(() -> ParametresListe.recherche(List.of("x".repeat(101)))).isInstanceOf(ResponseStatusException.class);
        assertThat(ParametresListe.choix("stock", List.of("faible"), Set.of("faible", "rupture"))).isEqualTo("faible");
        assertThat(ParametresListe.choix("stock", List.of(" "), Set.of("faible"))).isNull();
        assertThat(ParametresListe.choix("stock", null, Set.of("faible"))).isNull();
        assertThatThrownBy(() -> ParametresListe.choix("stock", List.of("plein"), Set.of("faible"))).isInstanceOf(ResponseStatusException.class);
        assertThat(ParametresListe.identifiant("categorie", List.of("42"))).isEqualTo(42L);
        assertThat(ParametresListe.identifiant("categorie", List.of(""))).isNull();
        assertThatThrownBy(() -> ParametresListe.identifiant("categorie", List.of("-1"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> ParametresListe.identifiant("categorie", List.of("1", "2"))).isInstanceOf(ResponseStatusException.class);
        assertThat(ParametresListe.date(List.of("2026-10-05"))).isEqualTo(java.time.LocalDate.of(2026, 10, 5));
        assertThatThrownBy(() -> ParametresListe.date(List.of("2026-02-30"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> ParametresListe.periodeCoherente(java.time.LocalDate.of(2026, 11, 2), java.time.LocalDate.of(2026, 11, 1))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void lePdfEstEnPaysageAuDelaDeSeptColonnes() {
        TableauExport sept = TableauExport.titre("Sept").colonnes(Colonne.texte("1"), Colonne.texte("2"), Colonne.texte("3"), Colonne.texte("4"), Colonne.texte("5"),
                Colonne.texte("6"), Colonne.texte("7"));
        TableauExport huit = TableauExport.titre("Huit").colonnes(Colonne.texte("1"), Colonne.texte("2"), Colonne.texte("3"), Colonne.texte("4"), Colonne.texte("5"),
                Colonne.texte("6"), Colonne.texte("7"), Colonne.texte("8"));
        assertThat(LivraisonExport.formatPour(sept)).isEqualTo(FormatPapier.A4_PORTRAIT);
        assertThat(LivraisonExport.formatPour(huit)).isEqualTo(FormatPapier.A4_PAYSAGE);
    }
}
