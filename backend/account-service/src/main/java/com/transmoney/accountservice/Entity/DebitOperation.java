
package com.transmoney.accountservice.Entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "debit_operations",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_debit_operation_id",
                        columnNames = "operation_id"
                )
        }
)
@Getter
@Setter
public class DebitOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "operation_id", nullable = false, updatable = false)
    private String operationId;

    @Column(name = "account_number", nullable = false, updatable = false)
    private String accountNumber;

    @Column(nullable = false, precision = 15, scale = 2)
    private java.math.BigDecimal amount;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
