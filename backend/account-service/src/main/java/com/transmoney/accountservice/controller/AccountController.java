package com.transmoney.accountservice.controller;

import com.transmoney.accountservice.dto.AccountResponse;
import com.transmoney.accountservice.dto.CreateAccountRequest;
import com.transmoney.accountservice.service.AccountService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/accounts")
@Slf4j
@AllArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(
            @Valid @RequestBody CreateAccountRequest request,
            @RequestHeader(value = "X-User-Email", required = false) String headerEmail,
            Authentication authentication) {

        String userEmail = resolveEmail(authentication, headerEmail);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(accountService.createAccount(request, userEmail));
    }

    @GetMapping("/{accountNumber}")
    public ResponseEntity<AccountResponse> getAccount(
            @PathVariable String accountNumber,
            @RequestHeader(value = "X-User-Email", required = false) String headerEmail,
            Authentication authentication) {

        String userEmail = resolveEmail(authentication, headerEmail);
        log.info("ACCOUNT OWNERSHIP CHECK -> account: {}, userEmail: {}", accountNumber, userEmail);

        AccountResponse account = accountService.getAccount(accountNumber);

        if (userEmail == null || !account.getEmail().equalsIgnoreCase(userEmail)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        return ResponseEntity.ok(account);
    }

    @GetMapping("/me")
    public ResponseEntity<AccountResponse> getAccountByEmail(
            @RequestHeader(value = "X-User-Email", required = false) String headerEmail,
            Authentication authentication) {

        String userEmail = resolveEmail(authentication, headerEmail);
        return ResponseEntity.ok(accountService.getAccountByEmail(userEmail));
    }

    @GetMapping("/{accountNumber}/balance")
    public ResponseEntity<BigDecimal> getBalance(@PathVariable String accountNumber) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(accountService.getBalance(accountNumber));
    }

    @PutMapping("/{accountNumber}/block")
    public ResponseEntity<String> blockAccount(@PathVariable String accountNumber) {
        accountService.blockAccount(accountNumber);
        return ResponseEntity.ok("ACCOUNT BLOCKED SUCCESSFULLY.");
    }

    /*
     * SAGA Step 1 - Deduct Balance
     * Called by Transaction Service when transfer is initiated.
     */
    @PutMapping("/{accountNumber}/deduct")
    public ResponseEntity<String> deductBalance(
            @PathVariable String accountNumber,
            @RequestParam BigDecimal amount,
            @RequestParam String operationId) {

        accountService.deductBalance(accountNumber, amount, operationId);
        return ResponseEntity.ok("AMOUNT DEDUCTED SUCCESSFULLY");
    }

    /*
     * SAGA Step 4: compensating transaction endpoints.
     * CALLED BY TRANSACTION SERVICE IN TWO SCENARIOS:
     * 1- Fraud detected -> refund sender (undo step 1)
     * 2- Transaction completed -> credit receiver's account
     */
    @PutMapping("/{accountNumber}/credit")
    public ResponseEntity<String> creditBalance(
            @PathVariable String accountNumber,
            @RequestParam BigDecimal amount) {

        accountService.creditBalance(accountNumber, amount);
        return ResponseEntity.ok("BALANCE CREDITED SUCCESSFULLY.");
    }

    private String resolveEmail(Authentication authentication, String headerEmail) {
        if (authentication != null && authentication.isAuthenticated() && !authentication.getName().equals("anonymousUser")) {
            return authentication.getName();
        }
        return headerEmail;
    }
}
