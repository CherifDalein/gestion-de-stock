package org.example.stock.pays;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-régression : aucune monnaie écrite en dur. Les gabarits, les scripts et le code n'écrivent « GNF » que dans la table des pays
 * ({@code Monnaie}) ; tout le reste passe par {@code @montants}, la variable {@code monnaie} des PDF ou l'attribut {@code data-monnaie}.
 */
class MonnaieEnDurTests {
    private static final Path GABARITS = Path.of("src/main/resources/templates"), SCRIPTS = Path.of("src/main/resources/static/js"), JAVA = Path.of("src/main/java");

    @Test void aucunGabaritNEcritGnfOuUneMonnaieEnDur() throws IOException {
        List<String> fautes = new java.util.ArrayList<>();
        try (Stream<Path> fichiers = Files.walk(GABARITS)) {
            for (Path fichier : fichiers.filter(f -> f.toString().endsWith(".html")).toList()) {
                String contenu = Files.readString(fichier);
                if (contenu.contains("GNF")) fautes.add(fichier + " : GNF");
                if (Pattern.compile("(?i)francs? guinéens?").matcher(contenu.replaceAll("(?m)<[^>]*>(Soit|Arrêté)[^<]*<", "")).find() && !fichier.toString().contains("/pdf/"))
                    fautes.add(fichier + " : franc guinéen");
                if (contenu.contains("formatsVue.gnf") || contenu.contains("formatsVue.signe")) fautes.add(fichier + " : ancien formateur");
                if (contenu.contains("ORANGE_MONEY")) fautes.add(fichier + " : ORANGE_MONEY");
                if (Pattern.compile("\\d,00\\b").matcher(contenu).find()) fautes.add(fichier + " : montant avec décimales en dur");
            }
        }
        assertThat(fautes).as("monnaie ou formatage en dur dans les gabarits").isEmpty();
    }

    @Test void aucunScriptNEcritGnfEnDurHorsCommentaire() throws IOException {
        List<String> fautes = new java.util.ArrayList<>();
        try (Stream<Path> fichiers = Files.walk(SCRIPTS)) {
            for (Path fichier : fichiers.filter(f -> f.toString().endsWith(".js")).toList()) {
                for (String ligne : Files.readAllLines(fichier)) {
                    String code = ligne.replaceAll("//.*$", "").replaceAll("/\\*.*?\\*/", "").replaceAll("^\\s*\\*.*$", "");
                    if (code.contains("GNF") || code.contains("FCFA")) fautes.add(fichier + " : " + ligne.strip());
                }
            }
        }
        assertThat(fautes).as("monnaie en dur dans les scripts").isEmpty();
    }

    @Test void leCodeJavaNeConnaitGnfQueDansLaTableDesPays() throws IOException {
        List<String> fautes = new java.util.ArrayList<>();
        try (Stream<Path> fichiers = Files.walk(JAVA)) {
            for (Path fichier : fichiers.filter(f -> f.toString().endsWith(".java")).toList()) {
                String chemin = fichier.toString();
                if (chemin.contains("/org/example/stock/pays/") || chemin.endsWith("MontantReleve.java")) continue;   // table des pays ; lecture tolérante des relevés
                for (String ligne : Files.readAllLines(fichier)) {
                    String code = ligne.replaceAll("//.*$", "").replaceAll("^\\s*(/\\*\\*|\\*|\\*/).*$", "");
                    if (code.matches(".*\"[^\"]*(GNF|FCFA|guinéen)[^\"]*\".*")) fautes.add(fichier + " : " + ligne.strip());
                }
            }
        }
        assertThat(fautes).as("monnaie en dur dans le code").isEmpty();
    }
}
