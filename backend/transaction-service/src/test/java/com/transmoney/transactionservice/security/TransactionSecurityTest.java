package com.transmoney.transactionservice.security;

import com.transmoney.transactionservice.client.AccountServiceClient;
import com.transmoney.transactionservice.dto.AccountResponse;
import com.transmoney.transactionservice.entity.Transaction;
import com.transmoney.transactionservice.entity.TransactionStatus;
import com.transmoney.transactionservice.entity.TransactionType;
import com.transmoney.transactionservice.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TransactionSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionRepository transactionRepository;

    @MockitoBean
    private AccountServiceClient accountServiceClient;

    private static final String GATEWAY_TOKEN = "transmoney-local-dev-gateway-auth-token-2026";

    @Test
    void directCallWithoutGatewayToken_isRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/TX-100")
                        .header("X-User-Email", "user@test.local"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void transactionHistory_unauthorizedAccountAccessReturns403() throws Exception {
        // Victim account does not belong to attacker
        when(accountServiceClient.getAccount(eq("111122223333"), eq("attacker@test.local")))
                .thenThrow(new RuntimeException("Account ownership check failed"));

        mockMvc.perform(get("/api/v1/transactions/account/111122223333")
                        .header("X-Gateway-Token", GATEWAY_TOKEN)
                        .header("X-User-Email", "attacker@test.local"))
                .andExpect(status().isForbidden());
    }

    @Test
    void transactionHistory_authorizedAccountAccessReturns200() throws Exception {
        AccountResponse myAccount = new AccountResponse();
        myAccount.setAccountNumber("111122223333");
        myAccount.setEmail("owner@test.local");

        when(accountServiceClient.getAccount(eq("111122223333"), eq("owner@test.local")))
                .thenReturn(myAccount);

        Transaction tx = new Transaction();
        tx.setId("TX-100");
        tx.setSenderAccountNumber("111122223333");
        tx.setReceiverAccountNumber("999988887777");
        tx.setAmount(new BigDecimal("500.00"));
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setType(TransactionType.TRANSFER);

        when(transactionRepository.findBySenderAccountNumberOrReceiverAccountNumberOrderByCreatedAtDesc(
                eq("111122223333"), eq("111122223333")))
                .thenReturn(List.of(tx));

        mockMvc.perform(get("/api/v1/transactions/account/111122223333")
                        .header("X-Gateway-Token", GATEWAY_TOKEN)
                        .header("X-User-Email", "owner@test.local"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("TX-100"));
    }

    @Test
    void getTransaction_crossUserAccessReturns403() throws Exception {
        Transaction tx = new Transaction();
        tx.setId("TX-999");
        tx.setSenderAccountNumber("111122223333");
        tx.setReceiverAccountNumber("999988887777");
        tx.setAmount(new BigDecimal("250.00"));
        tx.setStatus(TransactionStatus.COMPLETED);

        when(transactionRepository.findById("TX-999")).thenReturn(Optional.of(tx));

        // Attacker owns neither sender nor receiver
        when(accountServiceClient.getAccount(any(), eq("attacker@test.local")))
                .thenThrow(new RuntimeException("Account ownership check failed"));

        mockMvc.perform(get("/api/v1/transactions/TX-999")
                        .header("X-Gateway-Token", GATEWAY_TOKEN)
                        .header("X-User-Email", "attacker@test.local"))
                .andExpect(status().isForbidden());
    }

    @Test
    void verifyOTP_crossUserAttemptReturns403() throws Exception {
        Transaction tx = new Transaction();
        tx.setId("TX-OTP-1");
        tx.setSenderAccountNumber("111122223333");
        tx.setReceiverAccountNumber("999988887777");
        tx.setAmount(new BigDecimal("6000.00"));
        tx.setStatus(TransactionStatus.PENDING_VERIFICATION);

        when(transactionRepository.findById("TX-OTP-1")).thenReturn(Optional.of(tx));

        // Attacker is not the sender
        when(accountServiceClient.getAccount(eq("111122223333"), eq("attacker@test.local")))
                .thenThrow(new RuntimeException("Account ownership check failed"));

        mockMvc.perform(post("/api/v1/transactions/verify/TX-OTP-1")
                        .param("otp", "123456")
                        .header("X-Gateway-Token", GATEWAY_TOKEN)
                        .header("X-User-Email", "attacker@test.local"))
                .andExpect(status().isForbidden());
    }
}
