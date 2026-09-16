package com.revolutclone.web.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;

class StructuredLoggingDefaultsEnvironmentPostProcessorTest {

    private final StructuredLoggingDefaultsEnvironmentPostProcessor processor =
            new StructuredLoggingDefaultsEnvironmentPostProcessor();

    @Test
    void defaultsConsoleLoggingToLogstashFormat() {
        StandardEnvironment environment = new StandardEnvironment();

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("logstash");
    }

    @Test
    void isAddedAtLowestPrecedenceSoAnExplicitPropertyWins() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "explicit-override", java.util.Map.of("logging.structured.format.console", "")));

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("logging.structured.format.console")).isEmpty();
    }
}
