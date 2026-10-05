package org.example.stock.service;

import org.springframework.http.HttpStatus;

public class CreationOperationException extends RuntimeException {
    private final HttpStatus status;

    public CreationOperationException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() { return status; }
}
