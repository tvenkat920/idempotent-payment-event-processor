package com.ledger.processor;

import com.ledger.processor.domain.TransactionRecord;
import com.ledger.processor.domain.TransactionStatus;
import com.ledger.processor.domain.TransactionType;
import com.ledger.processor.domain.Wallet;
import com.ledger.processor.dto.ErrorResponse;
import com.ledger.processor.dto.TransactionRequest;
import com.ledger.processor.dto.TransactionResponse;
import com.ledger.processor.repository.TransactionRecordRepository;
import com.ledger.processor.repository.WalletRepository;
import com.ledger.processor.service.PaymentProcessorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class TransactionProcessorIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TransactionProcessorIntegrationTest.class);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransactionRecordRepository transactionRecordRepository;

    @Autowired
    private PaymentProcessorService paymentProcessorService;

    @BeforeEach
    void setUp() {
        transactionRecordRepository.deleteAll();
        walletRepository.deleteAll();
    }

    private void printHeader(String testName, String intent) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("TEST CASE: " + testName);
        System.out.println("INTENT   : " + intent);
        System.out.println("-".repeat(80));
    }

    private void printFooter(String result) {
        System.out.println("RESULT   : " + result);
        System.out.println("=".repeat(80) + "\n");
    }

    @Test
    @DisplayName("Processes a single valid debit transaction successfully.")
    void testHappyPathSingleDebit() {
        printHeader(
            "Processes a single valid debit transaction successfully.",
            "Verify that a single valid debit transaction decreases the wallet balance and records the transaction in the ledger."
        );

        UUID userId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        BigDecimal initialBalance = new BigDecimal("500.00");
        BigDecimal debitAmount = new BigDecimal("150.00");

        // Seed wallet
        paymentProcessorService.createOrUpdateWallet(userId, initialBalance);
        System.out.println("1. Initialized wallet for user " + userId + " with balance: ₹" + initialBalance);

        // Prepare request
        TransactionRequest request = new TransactionRequest(
            transactionId,
            userId,
            debitAmount,
            TransactionType.DEBIT
        );

        System.out.println("2. Sending POST /api/v1/transactions/process with amount: ₹" + debitAmount);
        ResponseEntity<TransactionResponse> response = restTemplate.postForEntity(
            "/api/v1/transactions/process",
            request,
            TransactionResponse.class
        );

        System.out.println("3. Response received - Status: " + response.getStatusCode() + ", Body: " + response.getBody());

        // Assertions
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(response.getBody().remainingBalance()).isEqualByComparingTo(new BigDecimal("350.00"));

        BigDecimal finalBalance = paymentProcessorService.getWalletBalance(userId);
        assertThat(finalBalance).isEqualByComparingTo(new BigDecimal("350.00"));

        Optional<TransactionRecord> recordOpt = transactionRecordRepository.findByTransactionId(transactionId);
        assertThat(recordOpt).isPresent();
        assertThat(recordOpt.get().getAmount()).isEqualByComparingTo(debitAmount);
        assertThat(recordOpt.get().getStatus()).isEqualTo(TransactionStatus.SUCCESS);

        printFooter("PASSED - Wallet balance successfully reduced from ₹" + initialBalance + " to ₹" + finalBalance);
    }

    @Test
    @DisplayName("Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once.")
    void testIdempotencyConcurrentDuplicateRequests() throws InterruptedException {
        printHeader(
            "Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once.",
            "Send 3 concurrent HTTP requests with the exact same transactionId. Exactly 1 must succeed (HTTP 200) and the other 2 must be rejected with HTTP 409 Conflict without duplicate balance deduction."
        );

        UUID userId = UUID.randomUUID();
        UUID sharedTransactionId = UUID.randomUUID();
        BigDecimal initialBalance = new BigDecimal("1000.00");
        BigDecimal debitAmount = new BigDecimal("250.00");

        // Seed wallet
        paymentProcessorService.createOrUpdateWallet(userId, initialBalance);
        System.out.println("1. Initialized wallet for user " + userId + " with balance: ₹" + initialBalance);

        int concurrentRequests = 3;
        ExecutorService executorService = Executors.newFixedThreadPool(concurrentRequests);
        CountDownLatch readyLatch = new CountDownLatch(concurrentRequests);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);

        List<ResponseEntity<String>> responses = Collections.synchronizedList(new ArrayList<>());

        TransactionRequest duplicateRequest = new TransactionRequest(
            sharedTransactionId,
            userId,
            debitAmount,
            TransactionType.DEBIT
        );

        System.out.println("2. Dispatching " + concurrentRequests + " simultaneous requests with transactionId=" + sharedTransactionId);

        for (int i = 0; i < concurrentRequests; i++) {
            final int threadIndex = i + 1;
            executorService.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // Wait for trigger to fire all requests concurrently
                    long startTime = System.currentTimeMillis();
                    ResponseEntity<String> resp = restTemplate.postForEntity(
                        "/api/v1/transactions/process",
                        duplicateRequest,
                        String.class
                    );
                    long duration = System.currentTimeMillis() - startTime;
                    System.out.printf("   [Thread %d] Completed in %d ms with HTTP Status: %s%n",
                            threadIndex, duration, resp.getStatusCode());
                    responses.add(resp);
                } catch (Exception e) {
                    System.err.printf("   [Thread %d] Error: %s%n", threadIndex, e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown(); // Fire all 3 threads simultaneously
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();

        assertThat(finished).isTrue();
        assertThat(responses).hasSize(concurrentRequests);

        long successCount = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).count();
        long conflictCount = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count();

        System.out.println("3. Aggregate results: Success (200 OK) = " + successCount + ", Conflict (409) = " + conflictCount);

        assertThat(successCount).as("Exactly 1 request must succeed").isEqualTo(1);
        assertThat(conflictCount).as("The other 2 duplicate requests must return 409 Conflict").isEqualTo(2);

        // Verify wallet balance was only debited ONCE
        BigDecimal expectedBalance = initialBalance.subtract(debitAmount);
        BigDecimal actualBalance = paymentProcessorService.getWalletBalance(userId);
        System.out.println("4. Expected Balance: ₹" + expectedBalance + ", Actual Balance in DB: ₹" + actualBalance);
        assertThat(actualBalance).isEqualByComparingTo(expectedBalance);

        // Verify only 1 transaction record exists in DB
        List<TransactionRecord> records = transactionRecordRepository.findAll();
        System.out.println("5. Total transaction records in database: " + records.size());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getTransactionId()).isEqualTo(sharedTransactionId);

        printFooter("PASSED - 1 request succeeded (200 OK), 2 returned 409 Conflict, balance debited exactly once.");
    }

    @Test
    @DisplayName("Sends 10 concurrent debit requests of ₹100 for a wallet with a ₹500 balance. Ensures the final balance is exactly ₹0 and 5 requests fail with insufficient funds.")
    void testRaceConditionConcurrentDebits() throws InterruptedException {
        printHeader(
            "Sends 10 concurrent debit requests of ₹100 for a wallet with a ₹500 balance. Ensures the final balance is exactly ₹0 and 5 requests fail with insufficient funds.",
            "Test database-level pessimistic locking against race conditions. 10 simultaneous debit requests of ₹100 against ₹500 balance must result in exactly 5 successes, 5 failures (422 Unprocessable Entity), and ₹0 remaining balance without going negative."
        );

        UUID userId = UUID.randomUUID();
        BigDecimal initialBalance = new BigDecimal("500.00");
        BigDecimal debitAmount = new BigDecimal("100.00");
        int totalRequests = 10;

        paymentProcessorService.createOrUpdateWallet(userId, initialBalance);
        System.out.println("1. Initialized wallet for user " + userId + " with balance: ₹" + initialBalance);

        ExecutorService executorService = Executors.newFixedThreadPool(totalRequests);
        CountDownLatch readyLatch = new CountDownLatch(totalRequests);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);

        AtomicInteger successCounter = new AtomicInteger(0);
        AtomicInteger insufficientFundsCounter = new AtomicInteger(0);
        List<ResponseEntity<String>> responses = Collections.synchronizedList(new ArrayList<>());

        System.out.println("2. Launching " + totalRequests + " concurrent debit requests of ₹" + debitAmount + " each...");

        for (int i = 0; i < totalRequests; i++) {
            final int requestId = i + 1;
            final UUID txId = UUID.randomUUID();
            TransactionRequest request = new TransactionRequest(
                txId,
                userId,
                debitAmount,
                TransactionType.DEBIT
            );

            executorService.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    long start = System.currentTimeMillis();
                    ResponseEntity<String> resp = restTemplate.postForEntity(
                        "/api/v1/transactions/process",
                        request,
                        String.class
                    );
                    long elapsed = System.currentTimeMillis() - start;

                    if (resp.getStatusCode() == HttpStatus.OK) {
                        successCounter.incrementAndGet();
                        System.out.printf("   [Request #%02d] SUCCESS (200 OK) in %d ms%n", requestId, elapsed);
                    } else if (resp.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                        insufficientFundsCounter.incrementAndGet();
                        System.out.printf("   [Request #%02d] INSUFFICIENT_FUNDS (422 Unprocessable) in %d ms%n", requestId, elapsed);
                    } else {
                        System.out.printf("   [Request #%02d] UNEXPECTED STATUS: %s in %d ms%n", requestId, resp.getStatusCode(), elapsed);
                    }
                    responses.add(resp);
                } catch (Exception e) {
                    System.err.printf("   [Request #%02d] Exception: %s%n", requestId, e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown(); // Release all 10 threads simultaneously
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        executorService.shutdown();

        assertThat(completed).isTrue();
        assertThat(responses).hasSize(totalRequests);

        System.out.println("3. Summary of results:");
        System.out.println("   - Successful Transactions (200 OK)       : " + successCounter.get());
        System.out.println("   - Rejected (422 Insufficient Funds)       : " + insufficientFundsCounter.get());

        // Assert exactly 5 successes and 5 failures
        assertThat(successCounter.get())
            .as("Exactly 5 debit requests must succeed before funds are depleted")
            .isEqualTo(5);

        assertThat(insufficientFundsCounter.get())
            .as("Exactly 5 debit requests must fail due to insufficient funds")
            .isEqualTo(5);

        // Verify final wallet balance is exactly ₹0.00
        BigDecimal finalBalance = paymentProcessorService.getWalletBalance(userId);
        System.out.println("4. Final wallet balance in database: ₹" + finalBalance);
        assertThat(finalBalance).isEqualByComparingTo(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));

        // Verify exactly 5 transaction records were saved
        List<TransactionRecord> savedRecords = transactionRecordRepository.findAll();
        System.out.println("5. Total successful ledger entries: " + savedRecords.size());
        assertThat(savedRecords).hasSize(5);

        printFooter("PASSED - Database lock strictly prevented race conditions. 5 succeeded, 5 failed, final balance ₹0.00.");
    }

    @Test
    @DisplayName("Processes a credit transaction successfully and increases wallet balance.")
    void testCreditTransaction() {
        printHeader(
            "Processes a credit transaction successfully and increases wallet balance.",
            "Verify that a CREDIT transaction increases the user wallet balance accordingly."
        );

        UUID userId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        BigDecimal initialBalance = new BigDecimal("200.00");
        BigDecimal creditAmount = new BigDecimal("350.00");

        paymentProcessorService.createOrUpdateWallet(userId, initialBalance);
        System.out.println("1. Initialized wallet with ₹" + initialBalance);

        TransactionRequest request = new TransactionRequest(
            txId,
            userId,
            creditAmount,
            TransactionType.CREDIT
        );

        ResponseEntity<TransactionResponse> response = restTemplate.postForEntity(
            "/api/v1/transactions/process",
            request,
            TransactionResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().remainingBalance()).isEqualByComparingTo(new BigDecimal("550.00"));

        BigDecimal updatedBalance = paymentProcessorService.getWalletBalance(userId);
        assertThat(updatedBalance).isEqualByComparingTo(new BigDecimal("550.00"));

        printFooter("PASSED - Balance successfully increased from ₹" + initialBalance + " to ₹" + updatedBalance);
    }

    @Test
    @DisplayName("Rejects invalid transaction requests with 400 Bad Request.")
    void testValidationFailure() {
        printHeader(
            "Rejects invalid transaction requests with 400 Bad Request.",
            "Verify that negative amounts or null fields are rejected with 400 Bad Request."
        );

        UUID userId = UUID.randomUUID();
        paymentProcessorService.createOrUpdateWallet(userId, new BigDecimal("100.00"));

        // Negative amount
        TransactionRequest invalidRequest = new TransactionRequest(
            UUID.randomUUID(),
            userId,
            new BigDecimal("-50.00"),
            TransactionType.DEBIT
        );

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
            "/api/v1/transactions/process",
            invalidRequest,
            ErrorResponse.class
        );

        System.out.println("Response for negative amount: " + response.getStatusCode() + " - " + response.getBody());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        printFooter("PASSED - Invalid request was rejected with 400 Bad Request.");
    }
}
