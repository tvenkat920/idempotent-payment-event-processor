# Decision Log (DECISIONS.md)

This document provides architectural rationales, concurrency analysis, and critical evaluations for the **Idempotent Payment/Wallet Event Processor**.

---

## 1. How Did You Handle the Concurrency Race Condition?

### Architecture & Mechanics

Handling concurrent financial events requires strict linearizability per account to prevent double-spending, negative balances, and duplicate webhook ingestion. The solution implements a multi-tier concurrency control strategy:

```
                  Concurrent Webhook Requests (Duplicate or Simultaneous Debits)
                                              │
                                              ▼
                                 [POST /transactions/process]
                                              │
                                              ▼
                             ┌──────────────────────────────────┐
                             │ Pre-Lock Idempotency Check       │ ──(Duplicate)──► HTTP 409 Conflict
                             │ (existsByTransactionId)          │
                             └──────────────────────────────────┘
                                              │
                                              ▼
                             ┌──────────────────────────────────┐
                             │ Acquire Pessimistic Write Lock   │
                             │ (SELECT ... FOR UPDATE on Wallet)│
                             └──────────────────────────────────┘
                                              │
                                              ▼
                             ┌──────────────────────────────────┐
                             │ Double-Check Idempotency in Lock │ ──(Duplicate)──► HTTP 409 Conflict
                             └──────────────────────────────────┘
                                              │
                                              ▼
                             ┌──────────────────────────────────┐
                             │ Balance Validation & Debit       │ ──(Insufficient)─► HTTP 422 Unprocessable
                             │ (wallet.balance >= amount)       │
                             └──────────────────────────────────┘
                                              │
                                              ▼
                             ┌──────────────────────────────────┐
                             │ Insert Ledger Transaction Record │ ──(Unique Violation)─► HTTP 409 Conflict
                             │ (Unique uk_transaction_id)       │
                             └──────────────────────────────────┘
                                              │
                                              ▼
                                Commit Transaction & Return 200 OK
```

### Key Concurrency Mechanisms:

1. **Database-Level Pessimistic Locking (`PESSIMISTIC_WRITE`)**:
   - In `WalletRepository.findByUserIdWithLock()`, the query executes a `SELECT ... FOR UPDATE` row lock on the user's wallet record.
   - When multiple threads attempt to debit the same wallet concurrently (e.g., 10 threads requesting ₹100 from a ₹500 balance), the database serializes access to that specific wallet row.
   - Each thread executes within its own transactional boundary (`@Transactional(isolation = Isolation.READ_COMMITTED)`). It reads the latest committed balance, checks if `balance >= amount`, debits the wallet, persists the change, and commits.
   - Threads 1 to 5 deduct ₹100 each, bringing the balance to ₹0.00. Threads 6 to 10 acquire the lock, detect `balance (0.00) < requested (100.00)`, and immediately throw `InsufficientFundsException` (mapped to HTTP 422), ensuring the balance never goes below zero.

2. **Double-Checked Idempotency Pattern**:
   - **Fast Pre-Lock Filter**: Quickly drops duplicate requests if the transaction has already completed and committed.
   - **In-Lock Verification**: Handles duplicate requests arriving concurrently within milliseconds. While Thread 1 holds the wallet lock to process transaction `tx_123`, Threads 2 and 3 wait. Once Thread 1 commits and releases the lock, Thread 2 acquires the lock and immediately discovers `existsByTransactionId(tx_123) == true`, rejecting it with HTTP 409 Conflict without altering the balance.
   - **Database Unique Constraint Guard**: A database-level unique constraint (`uk_transaction_id`) on the `transaction_records` table acts as the ultimate safety net against race conditions across distributed instances.

3. **Financial Precision with `BigDecimal`**:
   - All balance fields and amounts use `BigDecimal` with fixed scale to eliminate IEEE-754 floating-point rounding errors common with `double`/`float`.

---

## 2. Where Did Your AI Assistant Give You an Incorrect or Sub-Optimal Suggestion?

During system design and iterative implementation, standard AI models typically suggest several sub-optimal or flawed patterns for concurrency and idempotency. The following analysis details these pitfalls and how they were corrected:

### A. Suggesting In-Memory Java Synchronization (`synchronized` / `ReentrantLock`)
- **AI Suggestion**: Using a Java-level `synchronized` block or `ConcurrentHashMap<UUID, ReentrantLock>` to lock by `userId`.
- **Why It Is Sub-Optimal / Defective**:
  - In-memory synchronization only works within a single JVM instance. In production environments where services run on multiple Kubernetes pods or autoscaled instances behind a load balancer, two concurrent requests for the same wallet routed to different pods will execute simultaneously, causing race conditions and negative balances.
- **Corrected Solution**: Enforced **Database-Level Pessimistic Locking** (`SELECT ... FOR UPDATE`). The database server serves as the single source of truth across all application nodes.

---

### B. Suggesting Optimistic Locking (`@Version`) for Hot Wallet Accounts
- **AI Suggestion**: Using JPA `@Version` column on the `Wallet` entity and retrying failed transactions.
- **Why It Is Sub-Optimal**:
  - Under high burst concurrency (e.g., 10 simultaneous debit requests), optimistic locking causes 9 out of 10 threads to fail with `OptimisticLockException` on commit.
  - Implementing retry loops introduces exponential backoff overhead, thread pool exhaustion, latency spikes, and unpredictable execution order.
- **Corrected Solution**: Used **Pessimistic Write Locking** (`PESSIMISTIC_WRITE`). For financial ledgers where operations are short-lived row updates, pessimistic locking queues requests cleanly at the row level without retry loops.

---

### C. Time-of-Check to Time-of-Use (TOCTOU) Flaw in Idempotency Check
- **AI Suggestion**: Performing a simple `if (!repository.existsByTransactionId(id))` before processing and then saving the transaction at the end, without row locking or unique constraints.
- **Why It Is Incorrect**:
  - If 3 duplicate requests arrive within 50 milliseconds, all 3 threads execute the check concurrently before any thread has committed. All 3 threads see `exists == false`, bypass the check, debit the balance 3 times, and cause duplicate charges.
- **Corrected Solution**:
  - Combined **Pessimistic Row Locking** with **In-Lock Verification** and a hard **Database `UNIQUE` Constraint** (`uk_transaction_id`). Any concurrent thread that passes the pre-lock filter is caught inside the lock boundary or intercepted by `DataIntegrityViolationException`.

---

### D. Defaulting to `double` or `float` for Monetary Calculations
- **AI Suggestion**: Using primitive `double` or `Double` for transaction amounts.
- **Why It Is Sub-Optimal**:
  - Binary floating point cannot accurately represent fractions like `0.1` or `0.01`, resulting in rounding drift (e.g., `500.00 - 100.00` yielding `399.99999999999994`), which violates accounting integrity.
- **Corrected Solution**: Standardized on `java.math.BigDecimal` with explicit scale and rounding rules across all layers (DTOs, entities, queries, and business logic).

---

## 3. Alternative Approaches & Trade-Offs

| Approach | Advantages | Disadvantages / Trade-offs | Verdict |
| :--- | :--- | :--- | :--- |
| **Database Pessimistic Locking (`SELECT FOR UPDATE`)** | Strict linearizability, zero external dependencies, robust across multiple pods | Holds row lock for duration of transaction (minimal for in-memory / fast DB) | **Chosen (Optimal for Zero-Config & High Consistency)** |
| **Distributed Locks (Redis / Redlock)** | Offloads lock contention from database | Introduces external infrastructure dependency, split-brain risks on network partitions | Overkill for single/moderate DB architectures; not zero-config |
| **Optimistic Locking (`@Version`)** | No DB row lock held | High failure/retry rate under concurrent bursts on the same wallet | Poor fit for high-frequency debit streams |
| **In-Memory Lock (`ConcurrentHashMap`)** | Extremely low latency | Fails completely across distributed microservice instances | Rejected (anti-pattern for distributed systems) |
