
package com.transmoney.transactionservice.service;

import com.transmoney.transactionservice.entity.IdempotencyRecord;
import com.transmoney.transactionservice.repository.IdempotencyRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyReservationService {

    private final IdempotencyRecordRepository repository;

    public IdempotencyReservationService(
            IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecord reserve(
            String userEmail,
            String idempotencyKey,
            String reqHash) {

        IdempotencyRecord record = new IdempotencyRecord();
        record.setUserEmail(userEmail);
        record.setIdempotencyKey(idempotencyKey);
        record.setReqHash(reqHash);
        record.setStatus("PROCESSING");

        return repository.saveAndFlush(record);
    }
}
