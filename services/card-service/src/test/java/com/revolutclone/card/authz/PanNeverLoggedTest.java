package com.revolutclone.card.authz;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.revolutclone.card.testsupport.AuthorizationTestSupport;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.logback.StructuredLogEncoder;

/**
 * plans/phase-05-cards.md's Tests table: "Full log capture across an auth contains no PAN," and
 * the PAN handling note: "Never log the PAN; add a logback masking converter and a test that
 * asserts a PAN never appears in log output."
 *
 * <p>Attaches a second appender to this application's own, already-running root logger context -
 * the real one Boot configured from {@code logging.structured.format.console: logstash} in
 * application.yml, encoding with the same {@code StructuredLogEncoder} and therefore the same
 * {@code PanMaskingJsonMembersCustomizer} (common-web) production traffic goes through - rather
 * than asserting against a hand-built one, so this proves what this service's real logging
 * pipeline does, not what a test's approximation of it would do.
 */
class PanNeverLoggedTest extends AuthorizationTestSupport {

    private static final long SPEND_MINOR = 4_250L;

    private OutputStreamAppender<ILoggingEvent> appender;
    private ByteArrayOutputStream captured;
    private Logger root;

    private void startCapturing() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("logstash");
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        captured = new ByteArrayOutputStream();
        appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(captured);
        appender.start();

        root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);
    }

    @AfterEach
    void stopCapturing() {
        if (root != null && appender != null) {
            root.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void aFullAuthorizationNeverLogsThePanInTheClear() {
        startCapturing();

        tap("net-pan-check", pan, SPEND_MINOR);

        appender.stop();
        String log = captured.toString(StandardCharsets.UTF_8);

        assertThat(log)
                .as("the authorization flow's own log output")
                .isNotEmpty()
                .doesNotContain(pan);
    }

    @Test
    void anythingThatDidTryToLogThePanWouldComeOutMasked() {
        // Defense in depth, proven directly: nothing in this service's own code logs a PAN today
        // (see AuthorizationRequestedEventHandler's own comment on why not), so the test above
        // passing is also consistent with the masking never being exercised. This drives the same
        // real pipeline with a message that names one on purpose, to prove the mechanism itself -
        // not merely the discipline - is what stands between a future bug and a leaked PAN.
        startCapturing();

        org.slf4j.Logger probe = LoggerFactory.getLogger(PanNeverLoggedTest.class);
        probe.info("simulated leak attempt, pan={}", pan);

        appender.stop();
        String log = captured.toString(StandardCharsets.UTF_8);

        assertThat(log)
                .doesNotContain(pan)
                .contains("*".repeat(pan.length() - 4) + pan.substring(pan.length() - 4));
    }
}
