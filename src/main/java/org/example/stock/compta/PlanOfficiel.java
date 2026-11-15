package org.example.stock.compta;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Plan de comptes officiel du SYSCOHADA révisé, chargé une fois au démarrage depuis {@code comptabilite/plan-comptes-syscohada-revise.csv}
 * (UTF-8 avec BOM, séparateur « ; », colonnes Classe, Libellé de la classe, Numéro, Intitulé, Niveau). Référentiel en lecture seule, commun
 * à toutes les boutiques : rien n'en est copié par boutique et les intitulés officiels ne se modifient pas.
 */
@Component
public class PlanOfficiel {
    public static final String RESSOURCE = "comptabilite/plan-comptes-syscohada-revise.csv";

    private final List<CompteOfficiel> comptes;
    private final Map<String, CompteOfficiel> parNumero;

    public PlanOfficiel() { this(lireRessource()); }

    PlanOfficiel(List<CompteOfficiel> comptes) {
        this.comptes = List.copyOf(comptes);
        Map<String, CompteOfficiel> index = new LinkedHashMap<>();
        for (CompteOfficiel c : this.comptes) index.put(c.numero(), c);
        this.parNumero = Collections.unmodifiableMap(index);
    }

    /** Charge le plan embarqué dans l'application. */
    public static PlanOfficiel charger() { return new PlanOfficiel(); }

    public List<CompteOfficiel> comptes() { return comptes; }

    public Optional<CompteOfficiel> trouver(String numero) { return Optional.ofNullable(parNumero.get(numero)); }

    public boolean existe(String numero) { return parNumero.containsKey(numero); }

    public int taille() { return comptes.size(); }

    /** Intitulé officiel du compte, ou du plus long compte officiel dont le numéro est un début du numéro donné ; {@code null} sinon. */
    public String intituleApproche(String numero) {
        for (int n = Math.min(numero.length(), 4); n >= 2; n--) {
            CompteOfficiel c = parNumero.get(numero.substring(0, n));
            if (c != null) return c.intitule();
        }
        return null;
    }

    private static List<CompteOfficiel> lireRessource() {
        try (InputStream flux = PlanOfficiel.class.getClassLoader().getResourceAsStream(RESSOURCE)) {
            if (flux == null) throw new IllegalStateException("Plan de comptes officiel introuvable : " + RESSOURCE);
            return lire(flux);
        } catch (IOException e) {
            throw new IllegalStateException("Plan de comptes officiel illisible : " + RESSOURCE, e);
        }
    }

    /** Lit le CSV du plan officiel ; la première ligne (en-têtes) est ignorée, une ligne vide aussi. */
    static List<CompteOfficiel> lire(InputStream flux) throws IOException {
        List<CompteOfficiel> resultat = new ArrayList<>();
        try (BufferedReader lecteur = new BufferedReader(new InputStreamReader(flux, StandardCharsets.UTF_8))) {
            String ligne = lecteur.readLine();
            boolean premiere = true;
            int rang = 1;
            while (ligne != null) {
                if (premiere) { ligne = ligne.startsWith("﻿") ? ligne.substring(1) : ligne; premiere = false; }
                else if (!ligne.isBlank()) {
                    List<String> champs = decouper(ligne);
                    if (champs.size() != 5 || !champs.get(2).matches("[0-9]{2,4}") || !champs.get(0).matches("[1-9]"))
                        throw new IllegalStateException("Ligne " + rang + " du plan de comptes officiel invalide.");
                    resultat.add(new CompteOfficiel(champs.get(2), champs.get(3).strip(), Integer.parseInt(champs.get(0)), champs.get(1).strip(), champs.get(4).strip()));
                }
                rang++;
                ligne = lecteur.readLine();
            }
        }
        return resultat;
    }

    /** Découpe une ligne sur « ; » en respectant les champs entre guillemets (un guillemet doublé est un guillemet). */
    static List<String> decouper(String ligne) {
        List<String> champs = new ArrayList<>();
        StringBuilder courant = new StringBuilder();
        boolean guillemets = false;
        for (int i = 0; i < ligne.length(); i++) {
            char c = ligne.charAt(i);
            if (guillemets) {
                if (c == '"') {
                    if (i + 1 < ligne.length() && ligne.charAt(i + 1) == '"') { courant.append('"'); i++; }
                    else guillemets = false;
                } else courant.append(c);
            } else if (c == '"') guillemets = true;
            else if (c == ';') { champs.add(courant.toString()); courant.setLength(0); }
            else courant.append(c);
        }
        champs.add(courant.toString());
        return champs;
    }
}
