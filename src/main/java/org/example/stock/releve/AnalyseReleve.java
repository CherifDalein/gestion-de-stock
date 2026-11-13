package org.example.stock.releve;

import java.util.List;

/** Résultat de l'application d'une correspondance à un fichier : toutes les lignes non vides, valides ou en erreur. */
public record AnalyseReleve(List<LigneAnalysee> lignes) {
    public List<LigneAnalysee> valides() { return lignes.stream().filter(LigneAnalysee::valide).toList(); }

    public List<LigneAnalysee> erreurs() { return lignes.stream().filter(l -> !l.valide()).toList(); }

    public long doublons() { return lignes.stream().filter(l -> l.valide() && l.doublon()).count(); }

    /** Lignes à enregistrer : les valides, sans les doublons sauf demande contraire. */
    public List<LigneAnalysee> aImporter(boolean avecDoublons) {
        return lignes.stream().filter(l -> l.valide() && (avecDoublons || !l.doublon())).toList();
    }

    /** Les lignes valides dont la date sort de [debut, fin] deviennent des lignes en erreur (elles ne seront pas importées). */
    public AnalyseReleve verifierPeriode(java.time.LocalDate debut, java.time.LocalDate fin) {
        java.util.List<LigneAnalysee> resultat = new java.util.ArrayList<>();
        for (LigneAnalysee l : lignes) {
            if (l.valide() && (l.date().isBefore(debut) || l.date().isAfter(fin))) {
                resultat.add(new LigneAnalysee(l.numeroLigne(), l.date(), l.libelle(), l.reference(), l.montant(),
                        "date hors de la période du relevé (" + l.date().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ")", false));
            } else resultat.add(l);
        }
        return new AnalyseReleve(resultat);
    }

    /** Aperçu : les {@code n} premières lignes non vides. */
    public List<LigneAnalysee> apercu(int n) { return lignes.stream().limit(n).toList(); }
}
