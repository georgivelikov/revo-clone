package com.revolutclone.web;

import com.revolutclone.web.correlation.CorrelationIdFilter;
import com.revolutclone.web.problem.ApiExceptionHandler;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * Wires common-web's shared HTTP concerns into any service that depends on this module -
 * problem-detail mapping, correlation-id propagation, and a base OpenAPI description. A
 * service overrides any of these by declaring its own bean of the same type.
 */
@AutoConfiguration
public class WebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ApiExceptionHandler apiExceptionHandler() {
        return new ApiExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Integer.MIN_VALUE);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenAPI openApi(org.springframework.core.env.Environment environment) {
        String appName = environment.getProperty("spring.application.name", "revolut-clone-service");
        return new OpenAPI().info(new Info().title(appName).version("v1"));
    }
}
