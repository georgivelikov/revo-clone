package com.revolutclone.web.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Wires {@link PanMasking} into every service's structured JSON log line. Registered as an SPI
 * implementation in {@code META-INF/spring.factories} - {@code StructuredLogFormatterFactory}
 * (spring-boot 4.1.1) loads every {@code StructuredLoggingJsonMembersCustomizer} that way and
 * applies it to whichever common format a service configures ({@code logstash} here, per
 * {@link StructuredLoggingDefaultsEnvironmentPostProcessor}), not only to a hand-written custom
 * {@code StructuredLogFormatter}.
 *
 * <p>Boot 4.1 has no built-in message-masking hook in structured logging - only field
 * inclusion/exclusion/renaming ({@code logging.structured.json.*}). {@code applyingValueProcessor}
 * is the actual extension point: it runs across every {@code String}-valued member of the
 * envelope, not only {@code message}, because nothing here can guarantee a PAN would only ever
 * reach the message field and not, say, a logger name or an exception's own text.
 */
public class PanMaskingJsonMembersCustomizer implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {

    @Override
    public void customize(JsonWriter.Members<ILoggingEvent> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, PanMasking::mask));
    }
}
