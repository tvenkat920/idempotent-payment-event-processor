package com.ledger.processor.exception;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {
    private final UUID userId;
    private final BigDecimal currentBalance;
    private final BigDecimal requestedAmount;

    public InsufficientFundsException(UUID userId, BigDecimal currentBalance, BigDecimal requestedAmount) {
        super(String.format("Insufficient funds for user %s: current balance is ₹%s, requested debit is ₹%s",
                userId, currentBalance, requestedAmount));
        this.userId = userId;
        this.currentBalance = currentBalance;
        this.requestedAmount = requestedAmount;
    }

    public UUID getUserId() {
        return userId;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }
}
