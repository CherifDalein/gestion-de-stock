package org.example.stock.service;

public class OperationTresorerieConflitException extends IllegalArgumentException {
    public OperationTresorerieConflitException() {
        super("Ce formulaire a déjà servi à une autre opération. Ouvrez un nouveau formulaire.");
    }
}
