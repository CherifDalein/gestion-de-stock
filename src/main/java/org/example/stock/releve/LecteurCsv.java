package org.example.stock.releve;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lecture d'un relevé CSV : l'encodage est détecté (UTF-8 strict, sinon Windows-1252), le séparateur est choisi parmi {@code ;}, {@code ,} et
 * la tabulation, les guillemets (avec guillemets doublés et retours à la ligne) sont respectés. Rien n'est écrit sur le disque.
 */
final class LecteurCsv {
    static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final char[] SEPARATEURS = {';', ',', '\t'};

    private LecteurCsv() { }

    static TableauFichier lire(byte[] contenu, int lignesMax) {
        for (byte b : contenu) if (b == 0) throw new ImportReleveException("Ce fichier n'est pas un fichier texte CSV (contenu binaire).");
        String texte = decoder(contenu);
        if (!texte.isEmpty() && texte.charAt(0) == '﻿') texte = texte.substring(1);
        char separateur = detecterSeparateur(texte);
        List<List<String>> brut = decouper(texte, separateur, lignesMax);
        int largeur = brut.stream().mapToInt(List::size).max().orElse(0);
        if (largeur > LecteurFichierReleve.COLONNES_MAX) throw new ImportReleveException("Ce fichier a plus de " + LecteurFichierReleve.COLONNES_MAX + " colonnes.");
        List<List<Cellule>> lignes = new ArrayList<>();
        for (List<String> l : brut) {
            List<Cellule> cellules = new ArrayList<>(largeur);
            for (int i = 0; i < largeur; i++) cellules.add(i < l.size() ? Cellule.texte(l.get(i)) : Cellule.VIDE);
            lignes.add(cellules);
        }
        return new TableauFichier(lignes, largeur);
    }

    static String decoder(byte[] contenu) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(contenu)).toString();
        } catch (CharacterCodingException e) {
            return new String(contenu, WINDOWS_1252);
        }
    }

    /**
     * Le séparateur qui donne le plus de lignes de même largeur (au moins deux colonnes) sur les premières lignes non vides, hors guillemets :
     * dans un fichier à virgules décimales, la virgule découpe les lignes de façon irrégulière, le point-virgule de façon constante.
     */
    static char detecterSeparateur(String texte) {
        char meilleur = ';'; long meilleurScore = 0;
        for (char s : SEPARATEURS) {
            java.util.Map<Integer, Integer> parLargeur = new java.util.HashMap<>();
            for (List<String> l : decouperAvec(texte, s, 30)) if (l.stream().anyMatch(c -> !c.isBlank())) parLargeur.merge(l.size(), 1, Integer::sum);
            long score = 0;
            for (var e : parLargeur.entrySet()) if (e.getKey() > 1) score = Math.max(score, e.getValue() * 1000L + e.getKey());
            if (score > meilleurScore) { meilleurScore = score; meilleur = s; }
        }
        return meilleur;
    }

    /** Échantillon : les {@code max} premières lignes, sans erreur si le fichier en a davantage. */
    private static List<List<String>> decouperAvec(String texte, char separateur, int max) {
        return decouper(texte, separateur, max, false);
    }

    /** Découpe en lignes et cellules (RFC 4180 simplifiée). Lève une erreur au-delà de {@code max} lignes non vides utiles. */
    static List<List<String>> decouper(String texte, char separateur, int max) { return decouper(texte, separateur, max, true); }

    private static List<List<String>> decouper(String texte, char separateur, int max, boolean strict) {
        List<List<String>> lignes = new ArrayList<>();
        List<String> ligne = new ArrayList<>();
        StringBuilder cellule = new StringBuilder();
        boolean guillemets = false;
        int n = texte.length();
        for (int i = 0; i < n; i++) {
            char c = texte.charAt(i);
            if (guillemets) {
                if (c == '"') {
                    if (i + 1 < n && texte.charAt(i + 1) == '"') { cellule.append('"'); i++; } else guillemets = false;
                } else cellule.append(c);
            } else if (c == '"' && cellule.toString().isBlank()) {
                guillemets = true; cellule.setLength(0);
            } else if (c == separateur) {
                ligne.add(cellule.toString()); cellule.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && texte.charAt(i + 1) == '\n') i++;
                ligne.add(cellule.toString()); cellule.setLength(0);
                lignes.add(ligne); ligne = new ArrayList<>();
                if (!strict && lignes.size() >= max) return lignes;
                if (lignes.size() > max) throw new ImportReleveException("Ce fichier a plus de " + max + " lignes.");
            } else cellule.append(c);
        }
        if (cellule.length() > 0 || !ligne.isEmpty()) { ligne.add(cellule.toString()); lignes.add(ligne); }
        if (strict && lignes.size() > max) throw new ImportReleveException("Ce fichier a plus de " + max + " lignes.");
        return lignes;
    }
}
