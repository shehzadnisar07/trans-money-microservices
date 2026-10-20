package com.transmoney.transactionservice.repository;

import com.transmoney.transactionservice.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord,Long> {

    Optional<IdempotencyRecord> findByUserEmailAndIdempotencyKey(
            String userEmail,
            String idempotencyKey
    );
}
