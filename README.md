# Idempotent Payment/Wallet Event Processor

An enterprise-grade, zero-configuration internal transaction ledger service built with **Java 17**, **Spring Boot 3.3.4**, and an in-memory **H2 Database**. Designed to safely process concurrent payment gateway webhook events, prevent double-debits through database-level pessimistic locking, and guarantee strict idempotency.

---

## Key Features

- **Idempotent Webhook Ingestion**: Double-checked transactional idempotency with unique database constraints. Concurrent duplicate webhook payloads are rejected with `409 Conflict` without duplicate balance deduction.
- **Database-Level Pessimistic Locking**: `SELECT ... FOR UPDATE` row-level locks on the `Wallet` entity serialize concurrent debit operations per account, preventing race conditions and negative balances.
- **Zero-Config In-Memory Execution**: Runs instantly in IntelliJ or CLI using embedded H2 in PostgreSQL compatibility mode—no external database setup required.
- **Explicit Test Suite with JUnit 5 `@DisplayName`**: Tests with descriptive console logs printing intent, execution duration, and results.
- **Precision Ledger Accounting**: Strict `BigDecimal` arithmetic for all monetary values.

---

## Tech Stack

| Component | Technology |
| :--- | :--- |
| **Language** | Java 17 (LTS) |
| **Framework** | Spring Boot 3.3.4 |
| **Persistence** | Spring Data JPA / Hibernate 6 |
| **Database** | In-Memory H2 Database (`MODE=PostgreSQL`) |
| **Validation** | Jakarta Bean Validation (`spring-boot-starter-validation`) |
| **Testing** | JUnit 5, AssertJ, Spring Boot Test (`RANDOM_PORT`) |
| **Build Tool** | Apache Maven & Maven Wrapper (`mvnw`) |

---

## API Specification

### Endpoint: Process Transaction
`POST /api/v1/transactions/process`

#### Request Payload
```json
{
  "transactionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "userId": "7d2e8e21-5a3b-489e-a0e2-892f397cb312",
  "amount": 250.00,
  "type": "DEBIT"
}
```

#### Supported Types
- `DEBIT`: Deducts the specified amount from the wallet.
- `CREDIT`: Adds the specified amount to the wallet.

#### Success Response (`200 OK`)
```json
{
  "transactionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "userId": "7d2e8e21-5a3b-489e-a0e2-892f397cb312",
  "amount": 250.00,
  "type": "DEBIT",
  "status": "SUCCESS",
  "remainingBalance": 750.00,
  "message": "Transaction completed successfully",
  "timestamp": "2026-09-02T07:07:32.581Z"
}
```

#### Error Responses

| Status Code | Reason | Example Response Body |
| :--- | :--- | :--- |
| **`409 Conflict`** | Duplicate transaction ID detected (in-flight or historical) | `{"status": 409, "error": "Conflict", "message": "Duplicate transaction detected...", "transactionId": "..."}` |
| **`422 Unprocessable Entity`** | Insufficient funds for debit operation | `{"status": 422, "error": "Unprocessable Entity", "message": "Insufficient funds for user..."}` |
| **`400 Bad Request`** | Validation failure (negative amount, null fields) | `{"status": 400, "error": "Bad Request", "message": "Amount must be strictly positive"}` |
| **`404 Not Found`** | Wallet does not exist for specified `userId` | `{"status": 404, "error": "Not Found", "message": "Wallet not found for userId..."}` |

---

## Concurrency & Idempotency Architecture

```
                    Concurrent Webhook Events
                               │
                               ▼
                  POST /transactions/process
                               │
            ┌──────────────────┴──────────────────┐
            ▼                                     ▼
 [Filter duplicate txId]               [Acquire PESSIMISTIC_WRITE Lock]
  Exists? ──► Return 409 Conflict       (SELECT ... FOR UPDATE on Wallet)
                                                  │
                                                  ▼
                                       [In-Lock Duplicate Check]
                                        Exists? ──► Return 409 Conflict
                                                  │
                                                  ▼
                                       [Validate Balance & Debit]
                                        balance < amount? ──► Return 422
                                                  │
                                                  ▼
                                       [Insert Transaction Record]
                                        (uk_transaction_id unique key)
                                                  │
                                                  ▼
                                         Commit & Return 200 OK
```

---

## Test Suite & Verification

The integration test suite (`TransactionProcessorIntegrationTest.java`) exercises the full web and database stack:

| Test Case | JUnit 5 `@DisplayName` | Verification Description |
| :--- | :--- | :--- |
| **Happy Path** | `Processes a single valid debit transaction successfully.` | Verifies single debit decreases wallet balance and logs transaction in ledger. |
| **Idempotency** | `Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once.` | Dispatches 3 concurrent requests with the identical `transactionId` via thread pool; ensures 1 succeeds (200 OK), 2 fail (409 Conflict), and balance is deducted only once. |
| **Race Condition** | `Sends 10 concurrent debit requests of ₹100 for a wallet with a ₹500 balance. Ensures the final balance is exactly ₹0 and 5 requests fail with insufficient funds.` | Dispatches 10 concurrent debits across 10 threads using `CountDownLatch`; verifies exactly 5 succeed, exactly 5 return 422, and final balance is ₹0.00. |
| **Credit Operation** | `Processes a credit transaction successfully and increases wallet balance.` | Verifies balance increments correctly on credit events. |
| **Validation** | `Rejects invalid transaction requests with 400 Bad Request.` | Verifies Bean Validation on negative amounts and missing required fields. |

---

## Running the Tests

### Option 1: In IntelliJ IDEA
1. Open the project root folder in IntelliJ IDEA.
2. Allow Maven to import dependencies (automatic).
3. Navigate to `src/test/java/com/ledger/processor/TransactionProcessorIntegrationTest.java`.
4. Right-click the test class and select **Run 'TransactionProcessorIntegrationTest'**.
5. View the formatted console output and green test indicators.

### Option 2: Command Line (Maven Wrapper)

On Linux / macOS:
```bash
./mvnw clean test
```

On Windows (PowerShell / Command Prompt):
```powershell
.\mvnw.cmd clean test
```
Or with Maven directly:
```powershell
mvn clean test
```

---

## Decision Log

For detailed technical analysis on concurrency race conditions, trade-offs (Pessimistic vs. Optimistic vs. Distributed Locks), and AI recommendation critiques, refer to [DECISIONS.md](file:///c:/Users/tvenk/OneDrive/Documents/idempotent-payment-event-processor/DECISIONS.md).
