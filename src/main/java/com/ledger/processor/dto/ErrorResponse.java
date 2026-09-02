package com.ledger.processor.dto;

import java.time.Instant;
import java.util.UUID;

public record ErrorResponse(
    int status,
    String error,
    String message,
    UUID transactionId,
    Instant timestamp
) {
    public static ErrorResponse of(int status, String error, String message, UUID transactionId) {
        return new ErrorResponse(status, error, message, transactionId, Instant.now());
    }
}
