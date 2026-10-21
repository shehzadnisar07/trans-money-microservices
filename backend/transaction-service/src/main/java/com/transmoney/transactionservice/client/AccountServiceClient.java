package com.transmoney.transactionservice.client;

import com.transmoney.transactionservice.dto.AccountResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@FeignClient(
        value = "account-service",
        url = "${account.service.url}")
public interface AccountServiceClient {

    @GetMapping("/api/v1/accounts/{accountNumber}")
    AccountResponse getAccount(
            @PathVariable String accountNumber,
            @RequestHeader("X-User-Email") String userEmail);

    @PutMapping("/api/v1/accounts/{accountNumber}/deduct")
    String deductBalance(
            @PathVariable String accountNumber,
            @RequestParam BigDecimal amount,
            @RequestParam String operationId);

    @PutMapping("/api/v1/accounts/{accountNumber}/credit")
    String creditBalance(
            @PathVariable String accountNumber,
            @RequestParam BigDecimal amount);
}