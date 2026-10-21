package com.transmoney.apigateway.filter;

import com.transmoney.apigateway.service.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Collections;

@Component
public class JwtAuthenticationFilter implements WebFilter {

    private final JwtService jwtService;
    private final String gatewayAuthToken;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            @Value("${gateway.auth.token}") String gatewayAuthToken) {
        this.jwtService = jwtService;
        this.gatewayAuthToken = gatewayAuthToken;
    }

    @Override
    public Mono<Void> filter(
            ServerWebExchange exchange,
            WebFilterChain chain) {

        // Strip sensitive internal/identity headers from any client request
        ServerWebExchange sanitizedExchange = exchange.mutate()
                .request(builder -> builder.headers(headers -> {
                    headers.remove("X-User-Email");
                    headers.remove("X-Gateway-Token");
                    headers.remove("X-Internal-Service-Token");
                }))
                .build();

        String authHeader =
                sanitizedExchange.getRequest()
                        .getHeaders()
                        .getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return chain.filter(sanitizedExchange);
        }

        String token = authHeader.substring(7);

        if (!jwtService.isTokenValid(token)) {
            sanitizedExchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return sanitizedExchange.getResponse().setComplete();
        }

        String email = jwtService.extractEmail(token);

        Authentication authentication =
                new UsernamePasswordAuthenticationToken(
                        email,
                        null,
                        Collections.emptyList()
                );

        ServerWebExchange authenticatedExchange =
                sanitizedExchange.mutate()
                        .request(request -> request
                                .headers(headers -> {
                                    headers.set("X-User-Email", email);
                                    headers.set("X-Gateway-Token", gatewayAuthToken);
                                    headers.remove("X-Internal-Service-Token");
                                }))
                        .build();

        return chain.filter(authenticatedExchange)
                .contextWrite(
                        ReactiveSecurityContextHolder
                                .withAuthentication(authentication)
                );
    }
}
