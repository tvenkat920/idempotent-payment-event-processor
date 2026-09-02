package com.ledger.processor.dto;

import com.ledger.processor.domain.TransactionStatus;
import com.ledger.processor.domain.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
    UUID transactionId,
    UUID userId,
    BigDecimal amount,
    TransactionType type,
    TransactionStatus status,
    BigDecimal remainingBalance,
    String message,
    Instant timestamp
) {
    public static TransactionResponse success(UUID transactionId, UUID userId, BigDecimal amount,
                                             TransactionType type, BigDecimal remainingBalance, String message) {
        return new TransactionResponse(
            transactionId,
            userId,
            amount,
            type,
            TransactionStatus.SUCCESS,
            remainingBalance,
            message,
            Instant.now()
        );
    }

    public static TransactionResponse duplicate(UUID transactionId, UUID userId, BigDecimal amount,
                                               TransactionType type, BigDecimal remainingBalance, String message) {
        return new TransactionResponse(
            transactionId,
            userId,
            amount,
            type,
            TransactionStatus.DUPLICATE,
            remainingBalance,
            message,
            Instant.now()
        );
    }
}
