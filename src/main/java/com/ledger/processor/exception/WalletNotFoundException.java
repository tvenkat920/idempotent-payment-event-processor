package com.ledger.processor.exception;

import java.util.UUID;

public class WalletNotFoundException extends RuntimeException {
    private final UUID userId;

    public WalletNotFoundException(UUID userId) {
        super("Wallet not found for userId: " + userId);
        this.userId = userId;
    }

    public UUID getUserId() {
        return userId;
    }
}
