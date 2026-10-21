package com.transmoney.accountservice.security;

import com.transmoney.accountservice.dto.AccountResponse;
import com.transmoney.accountservice.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AccountSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

    private static final String GATEWAY_TOKEN = "transmoney-local-dev-gateway-auth-token-2026";
    private static final String INTERNAL_TOKEN = "transmoney-local-dev-internal-service-token-2026";

    @Test
    void directCallWithoutGatewayOrInternalToken_isRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/me")
                        .header("X-User-Email", "attacker@test.local"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userWithGatewayToken_cannotCallInternalCreditEndpoint() throws Exception {
        mockMvc.perform(put("/api/v1/accounts/123456789012/credit")
                        .param("amount", "1000")
                        .header("X-Gateway-Token", GATEWAY_TOKEN)
                        .header("X-User-Email", "attacker@test.local"))
                .andExpect(status().isForbidden());
    }

    @Test
    void userWithGatewayToken_cannotCallInternalDeductEndpoint() throws Exception {
        mockMvc.perform(put("/api/v1/accounts/123456789012/deduct")
                        .param("amount", "1000")
                        .param("operationId", "OP-1")
                        .header("X-Gateway-Token", GATEWAY_TOKEN)
                        .header("X-User-Email", "attacker@test.local"))
                .andExpect(status().isForbidden());
    }

    @Test
    void userWithGatewayToken_cannotCallInternalBlockEndpoint() throws Exception {
        mockMvc.perform(put("/api/v1/accounts/123456789012/block")
                        .header("X-Gateway-Token", GATEWAY_TOKEN)
                        .header("X-User-Email", "attacker@test.local"))
                .andExpect(status().isForbidden());
    }

    @Test
    void userWithGatewayToken_cannotCallInternalBalanceEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/123456789012/balance")
                        .header("X-Gateway-Token", GATEWAY_TOKEN)
                        .header("X-User-Email", "attacker@test.local"))
                .andExpect(status().isForbidden());
    }

    @Test
    void internalServiceToken_canCallCreditAndDeduct() throws Exception {
        mockMvc.perform(put("/api/v1/accounts/123456789012/credit")
                        .param("amount", "1000")
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string("BALANCE CREDITED SUCCESSFULLY."));

        mockMvc.perform(put("/api/v1/accounts/123456789012/deduct")
                        .param("amount", "1000")
                        .param("operationId", "OP-SEC-1")
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string("AMOUNT DEDUCTED SUCCESSFULLY"));
    }

    @Test
    void userWithGatewayToken_authorizedAccountAccessSucceeds() throws Exception {
        AccountResponse response = new AccountResponse();
        response.setAccountNumber("123456789012");
        response.setEmail("owner@test.local");
        response.setBalance(new BigDecimal("5000.00"));

        when(accountService.getAccount("123456789012")).thenReturn(response);

        mockMvc.perform(get("/api/v1/accounts/123456789012")
                        .header("X-Gateway-Token", GATEWAY_TOKEN)
                        .header("X-User-Email", "owner@test.local"))
                .andExpect(status().isOk());
    }

    @Test
    void userWithGatewayToken_unauthorizedAccountAccessReturns403() throws Exception {
        AccountResponse response = new AccountResponse();
        response.setAccountNumber("123456789012");
        response.setEmail("victim@test.local");

        when(accountService.getAccount("123456789012")).thenReturn(response);

        mockMvc.perform(get("/api/v1/accounts/123456789012")
                        .header("X-Gateway-Token", GATEWAY_TOKEN)
                        .header("X-User-Email", "attacker@test.local"))
                .andExpect(status().isForbidden());
    }
}
