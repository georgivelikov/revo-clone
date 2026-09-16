package com.revolutclone.web.logging;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Defaults every service that depends on common-web to Boot's built-in structured JSON
 * logging on stdout (logstash format - {@code correlationId} lands as a real JSON field via
 * MDC, not just a pattern token). Added with lowest precedence, so a service's own
 * application.yml - in particular the {@code local} profile, which wants a readable console
 * pattern instead - overrides it without any extra wiring.
 */
public class StructuredLoggingDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(
                "common-web-structured-logging-defaults",
                Map.of("logging.structured.format.console", "logstash")));
    }
}
