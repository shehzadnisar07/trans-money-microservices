package com.transmoney.accountservice.repository;

import com.transmoney.accountservice.Entity.DebitOperation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DebitOperationRepository
        extends JpaRepository<DebitOperation, String> {

    Optional<DebitOperation> findByOperationId(String operationId);
}