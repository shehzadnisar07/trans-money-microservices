package com.transmoney.transactionservice.controller;

import com.transmoney.transactionservice.dto.TransactionResponse;
import com.transmoney.transactionservice.dto.TransferReq;
import com.transmoney.transactionservice.service.TransactionService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@AllArgsConstructor
@Slf4j
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(
            @Valid @RequestBody TransferReq req,
            @RequestHeader(value = "X-User-Email", required = false) String headerEmail,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication authentication) {

        String userEmail = resolveEmail(authentication, headerEmail);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.transfer(req, userEmail, idempotencyKey));
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionResponse> getTransaction(
            @PathVariable String transactionId,
            @RequestHeader(value = "X-User-Email", required = false) String headerEmail,
            Authentication authentication) {

        String userEmail = resolveEmail(authentication, headerEmail);
        return ResponseEntity.ok(
                transactionService.getTransaction(transactionId, userEmail)
        );
    }

    @GetMapping("/account/{accountNumber}")
    public ResponseEntity<List<TransactionResponse>> getTransactionHistory(
            @PathVariable String accountNumber,
            @RequestHeader(value = "X-User-Email", required = false) String headerEmail,
            Authentication authentication) {

        String userEmail = resolveEmail(authentication, headerEmail);
        return ResponseEntity.ok(
                transactionService.getTransactionHistory(accountNumber, userEmail)
        );
    }

    @PostMapping("/verify/{transactionId}")
    public ResponseEntity<TransactionResponse> verifyOTP(
            @PathVariable String transactionId,
            @RequestParam String otp,
            @RequestHeader(value = "X-User-Email", required = false) String headerEmail,
            Authentication authentication) {

        String userEmail = resolveEmail(authentication, headerEmail);
        return ResponseEntity.ok(
                transactionService.verifyOTP(transactionId, otp, userEmail)
        );
    }

    private String resolveEmail(Authentication authentication, String headerEmail) {
        if (authentication != null && authentication.isAuthenticated() && !authentication.getName().equals("anonymousUser")) {
            return authentication.getName();
        }
        return headerEmail;
    }
}
