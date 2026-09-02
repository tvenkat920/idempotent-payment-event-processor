package com.ledger.processor.service;

import com.ledger.processor.domain.TransactionRecord;
import com.ledger.processor.domain.TransactionStatus;
import com.ledger.processor.domain.TransactionType;
import com.ledger.processor.domain.Wallet;
import com.ledger.processor.dto.TransactionRequest;
import com.ledger.processor.dto.TransactionResponse;
import com.ledger.processor.exception.DuplicateTransactionException;
import com.ledger.processor.exception.InsufficientFundsException;
import com.ledger.processor.exception.WalletNotFoundException;
import com.ledger.processor.repository.TransactionRecordRepository;
import com.ledger.processor.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class PaymentProcessorService {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessorService.class);

    private final WalletRepository walletRepository;
    private final TransactionRecordRepository transactionRecordRepository;

    public PaymentProcessorService(WalletRepository walletRepository,
                                  TransactionRecordRepository transactionRecordRepository) {
        this.walletRepository = walletRepository;
        this.transactionRecordRepository = transactionRecordRepository;
    }

    /**
     * Processes a payment transaction (DEBIT or CREDIT) idempotently with database-level pessimistic locking.
     *
     * @param request the transaction payload
     * @return TransactionResponse with resulting balance
     * @throws DuplicateTransactionException if the transactionId was already processed
     * @throws InsufficientFundsException    if debit amount exceeds available balance
     * @throws WalletNotFoundException       if wallet does not exist
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResponse processTransaction(TransactionRequest request) {
        log.info("Incoming transaction request: id={}, user={}, type={}, amount=₹{}",
                request.transactionId(), request.userId(), request.type(), request.amount());

        // 1. Check for duplicate transactionId before locking
        if (transactionRecordRepository.existsByTransactionId(request.transactionId())) {
            log.warn("Duplicate transaction ID rejected prior to lock: transactionId={}", request.transactionId());
            throw new DuplicateTransactionException(request.transactionId(),
                    "Duplicate transaction detected: transactionId " + request.transactionId() + " has already been processed.");
        }

        // 2. Acquire pessimistic write lock on the Wallet row (SELECT ... FOR UPDATE)
        // This serializes all operations on this user's wallet across concurrent threads.
        Wallet wallet = walletRepository.findByUserIdWithLock(request.userId())
                .orElseThrow(() -> new WalletNotFoundException(request.userId()));

        // 3. Double-check idempotency inside the lock boundary to handle racing requests
        if (transactionRecordRepository.existsByTransactionId(request.transactionId())) {
            log.warn("Duplicate transaction ID detected within lock boundary: transactionId={}", request.transactionId());
            throw new DuplicateTransactionException(request.transactionId(),
                    "Duplicate transaction detected: transactionId " + request.transactionId() + " has already been processed.");
        }

        // 4. Validate and execute balance modification
        if (request.type() == TransactionType.DEBIT) {
            if (wallet.getBalance().compareTo(request.amount()) < 0) {
                log.warn("Insufficient funds for debit: userId={}, currentBalance=₹{}, requestedAmount=₹{}",
                        wallet.getUserId(), wallet.getBalance(), request.amount());
                throw new InsufficientFundsException(wallet.getUserId(), wallet.getBalance(), request.amount());
            }
            wallet.debit(request.amount());
        } else if (request.type() == TransactionType.CREDIT) {
            wallet.credit(request.amount());
        } else {
            throw new IllegalArgumentException("Unsupported transaction type: " + request.type());
        }

        // 5. Persist updated wallet balance
        Wallet updatedWallet = walletRepository.save(wallet);

        // 6. Record transaction in the ledger
        TransactionRecord record = new TransactionRecord(
                request.transactionId(),
                request.userId(),
                request.amount(),
                request.type(),
                TransactionStatus.SUCCESS,
                updatedWallet.getBalance()
        );

        try {
            transactionRecordRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException dive) {
            log.error("DataIntegrityViolationException on transaction_records insertion for txId={}: {}",
                    request.transactionId(), dive.getMessage());
            throw new DuplicateTransactionException(request.transactionId(),
                    "Duplicate transaction detected: unique constraint violation for " + request.transactionId());
        }

        log.info("Transaction processed successfully: txId={}, user={}, type={}, amount=₹{}, newBalance=₹{}",
                record.getTransactionId(), record.getUserId(), record.getType(), record.getAmount(), updatedWallet.getBalance());

        return TransactionResponse.success(
                record.getTransactionId(),
                record.getUserId(),
                record.getAmount(),
                record.getType(),
                updatedWallet.getBalance(),
                "Transaction completed successfully"
        );
    }

    /**
     * Initializes or updates a wallet with a given balance (useful for test seeding & account creation).
     */
    @Transactional
    public Wallet createOrUpdateWallet(UUID userId, BigDecimal initialBalance) {
        return walletRepository.findByUserId(userId)
                .map(w -> {
                    w.setBalance(initialBalance);
                    return walletRepository.save(w);
                })
                .orElseGet(() -> walletRepository.save(new Wallet(userId, initialBalance)));
    }

    /**
     * Retrieves current balance for a user's wallet without locking.
     */
    @Transactional(readOnly = true)
    public BigDecimal getWalletBalance(UUID userId) {
        return walletRepository.findByUserId(userId)
                .map(Wallet::getBalance)
                .orElseThrow(() -> new WalletNotFoundException(userId));
    }
}
