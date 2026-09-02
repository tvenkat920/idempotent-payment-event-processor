package com.ledger.processor.dto;

import com.ledger.processor.domain.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record TransactionRequest(
    @NotNull(message = "transactionId is required")
    UUID transactionId,

    @NotNull(message = "userId is required")
    UUID userId,

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be strictly positive (min 0.01)")
    @Digits(integer = 15, fraction = 2, message = "Amount cannot have more than 2 decimal places")
    BigDecimal amount,

    @NotNull(message = "type is required (DEBIT or CREDIT)")
    TransactionType type
) {
}
