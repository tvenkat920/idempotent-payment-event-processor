package com.ledger.processor.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
    name = "transaction_records",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_transaction_id", columnNames = {"transaction_id"})
    },
    indexes = {
        @Index(name = "idx_tx_user_id", columnList = "user_id"),
        @Index(name = "idx_tx_created_at", columnList = "created_at")
    }
)
public class TransactionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, unique = true, updatable = false)
    private UUID transactionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, updatable = false)
    private TransactionStatus status;

    @Column(name = "resulting_balance", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal resultingBalance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public TransactionRecord() {
    }

    public TransactionRecord(UUID transactionId, UUID userId, BigDecimal amount,
                             TransactionType type, TransactionStatus status,
                             BigDecimal resultingBalance) {
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId cannot be null");
        this.userId = Objects.requireNonNull(userId, "userId cannot be null");
        this.amount = Objects.requireNonNull(amount, "amount cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.status = Objects.requireNonNull(status, "status cannot be null");
        this.resultingBalance = Objects.requireNonNull(resultingBalance, "resultingBalance cannot be null");
        this.createdAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getUserId() {
        return userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public TransactionType getType() {
        return type;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public BigDecimal getResultingBalance() {
        return resultingBalance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
