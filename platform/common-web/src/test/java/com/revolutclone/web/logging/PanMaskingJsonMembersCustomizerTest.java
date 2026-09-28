package com.revolutclone.web.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;

/**
 * Proves {@link PanMaskingJsonMembersCustomizer} actually engages in the real pipeline every
 * service runs - {@code StructuredLogEncoder} configured for the {@code logstash} format this
 * project defaults to (see {@code StructuredLoggingDefaultsEnvironmentPostProcessor}), discovered
 * as an SPI implementation via {@code META-INF/spring.factories} exactly as it is in production,
 * not invoked directly. {@code PanMaskingTest} already proves the masking function itself is
 * correct; this is what proves the wiring is not merely present but load-bearing.
 */
class PanMaskingJsonMembersCustomizerTest {

    private LoggerContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.stop();
        }
    }

    @Test
    void aPanLoggedThroughTheRealLogstashEncoderComesOutMaskedInTheJson() throws Exception {
        context = new LoggerContext();
        // A hand-built LoggerContext (rather than one obtained through LoggerFactory's own
        // SLF4J binding) has no MDCAdapter wired, which OutputStreamAppender needs even when
        // nothing in this test actually puts anything into MDC.
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        context.start();
        // StructuredLogEncoder.start() looks up the Environment this way itself - the real
        // LogbackLoggingSystem puts it there during application startup; this replicates that
        // one piece of wiring rather than the whole of it.
        context.putObject(Environment.class.getName(), new StandardEnvironment());

        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("logstash");
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(captured);
        appender.start();

        Logger logger = context.getLogger(PanMaskingJsonMembersCustomizerTest.class);
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);

        String pan = "9999994579309822";
        logger.info("authorization requested for pan {}", pan);

        appender.stop();
        String json = captured.toString(StandardCharsets.UTF_8);

        assertThat(json)
                .as("the encoded log line")
                .contains("authorization requested for pan")
                .contains("************9822")
                .doesNotContain(pan);
    }
}
