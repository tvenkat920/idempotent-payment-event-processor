package com.ledger.processor.controller;

import com.ledger.processor.dto.TransactionRequest;
import com.ledger.processor.dto.TransactionResponse;
import com.ledger.processor.service.PaymentProcessorService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class TransactionController {

    private final PaymentProcessorService paymentProcessorService;

    public TransactionController(PaymentProcessorService paymentProcessorService) {
        this.paymentProcessorService = paymentProcessorService;
    }

    /**
     * Endpoint for idempotent payment/wallet transaction ingestion.
     *
     * POST /api/v1/transactions/process
     * Payload: { "transactionId": "UUID", "userId": "UUID", "amount": 250.00, "type": "DEBIT" }
     */
    @PostMapping("/transactions/process")
    public ResponseEntity<TransactionResponse> processTransaction(@Valid @RequestBody TransactionRequest request) {
        TransactionResponse response = paymentProcessorService.processTransaction(request);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    /**
     * Optional helper endpoint for querying a wallet balance.
     */
    @GetMapping("/wallets/{userId}/balance")
    public ResponseEntity<Map<String, Object>> getWalletBalance(@PathVariable UUID userId) {
        BigDecimal balance = paymentProcessorService.getWalletBalance(userId);
        return ResponseEntity.ok(Map.of(
            "userId", userId,
            "balance", balance
        ));
    }
}
