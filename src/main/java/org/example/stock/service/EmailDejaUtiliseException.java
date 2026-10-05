package org.example.stock.service;

public class EmailDejaUtiliseException extends RuntimeException {
    public EmailDejaUtiliseException() { super("Cet email est déjà utilisé."); }
}
