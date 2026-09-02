package com.ledger.processor.repository;

import com.ledger.processor.domain.TransactionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRecordRepository extends JpaRepository<TransactionRecord, Long> {

    Optional<TransactionRecord> findByTransactionId(UUID transactionId);

    boolean existsByTransactionId(UUID transactionId);
}
