package com.transmoney.transactionservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Component
public class ServiceAuthenticationFilter extends OncePerRequestFilter {

    public static final String GATEWAY_TOKEN_HEADER = "X-Gateway-Token";
    public static final String USER_EMAIL_HEADER = "X-User-Email";

    private final String gatewayAuthToken;

    public ServiceAuthenticationFilter(
            @Value("${gateway.auth.token}") String gatewayAuthToken) {
        this.gatewayAuthToken = gatewayAuthToken;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String incomingGatewayToken = request.getHeader(GATEWAY_TOKEN_HEADER);
        String incomingUserEmail = request.getHeader(USER_EMAIL_HEADER);

        if (isTokenValid(incomingGatewayToken, gatewayAuthToken) && StringUtils.hasText(incomingUserEmail)) {
            String principal = incomingUserEmail.trim();
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_USER"))
                    );
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        filterChain.doFilter(request, response);
    }

    private boolean isTokenValid(String providedToken, String expectedToken) {
        if (!StringUtils.hasText(providedToken) || !StringUtils.hasText(expectedToken)) {
            return false;
        }
        return MessageDigest.isEqual(
                providedToken.getBytes(StandardCharsets.UTF_8),
                expectedToken.getBytes(StandardCharsets.UTF_8)
        );
    }
}
