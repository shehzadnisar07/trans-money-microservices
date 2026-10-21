package com.transmoney.accountservice.security;

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
import java.util.ArrayList;
import java.util.List;

@Component
public class ServiceAuthenticationFilter extends OncePerRequestFilter {

    public static final String GATEWAY_TOKEN_HEADER = "X-Gateway-Token";
    public static final String INTERNAL_SERVICE_TOKEN_HEADER = "X-Internal-Service-Token";
    public static final String USER_EMAIL_HEADER = "X-User-Email";

    private final String gatewayAuthToken;
    private final String internalServiceToken;

    public ServiceAuthenticationFilter(
            @Value("${gateway.auth.token}") String gatewayAuthToken,
            @Value("${internal.service.token}") String internalServiceToken) {
        this.gatewayAuthToken = gatewayAuthToken;
        this.internalServiceToken = internalServiceToken;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String incomingGatewayToken = request.getHeader(GATEWAY_TOKEN_HEADER);
        String incomingInternalToken = request.getHeader(INTERNAL_SERVICE_TOKEN_HEADER);
        String incomingUserEmail = request.getHeader(USER_EMAIL_HEADER);

        boolean isInternalService = isTokenValid(incomingInternalToken, internalServiceToken);
        boolean isGateway = isTokenValid(incomingGatewayToken, gatewayAuthToken);

        if (isInternalService) {
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE"));

            String principal = "internal-service";
            if (StringUtils.hasText(incomingUserEmail)) {
                principal = incomingUserEmail.trim();
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            }

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(principal, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        } else if (isGateway && StringUtils.hasText(incomingUserEmail)) {
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
