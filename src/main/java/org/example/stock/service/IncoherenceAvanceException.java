package org.example.stock.service;

/** Une avance ou un reste calculé est négatif : les données du partenaire sont incohérentes, rien n'est enregistré. */
public class IncoherenceAvanceException extends IllegalStateException {
    public IncoherenceAvanceException(String message) {
        super(message);
    }
}
