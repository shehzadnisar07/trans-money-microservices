package com.transmoney.transactionservice.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignInternalAuthConfig {

    @Bean
    public RequestInterceptor internalServiceAuthRequestInterceptor(
            @Value("${internal.service.token}") String internalServiceToken) {
        return requestTemplate -> requestTemplate.header("X-Internal-Service-Token", internalServiceToken);
    }
}
