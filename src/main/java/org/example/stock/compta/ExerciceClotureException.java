package org.example.stock.compta;

/**
 * Une opération datée dans un exercice clôturé est refusée côté serveur. C'est une {@link IllegalArgumentException} : les écrans qui affichent déjà
 * les refus de saisie montrent ce message tel quel.
 */
public class ExerciceClotureException extends IllegalArgumentException {
    public ExerciceClotureException(String libelleExercice) {
        super("L'exercice " + libelleExercice + " est clôturé : cette opération ne peut plus être enregistrée à cette date.");
    }
}
