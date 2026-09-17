package com.revolutclone.gateway.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

/**
 * Task 01.7: proves {@link CorrelationWebFilter} actually logs one line per request, with the
 * correlation id in MDC for that line - found live that without this, MDC held
 * {@code correlationId} but nothing on this gateway ever emitted it anywhere.
 */
class CorrelationWebFilterTest {

    private final CorrelationWebFilter filter = new CorrelationWebFilter();
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(CorrelationWebFilter.class)).addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        ((Logger) LoggerFactory.getLogger(CorrelationWebFilter.class)).detachAppender(appender);
    }

    @Test
    void logsOneLineWithTheCorrelationIdStillInMdcOnceTheChainCompletes() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/whatever")
                .header(CorrelationWebFilter.HEADER, "gateway-correlation-id")
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getResponse().setStatusCode(HttpStatus.OK);

        filter.filter(exchange, ex -> reactor.core.publisher.Mono.empty()).block();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getFormattedMessage()).contains("GET").contains("/api/whatever").contains("200");
        assertThat(event.getMDCPropertyMap()).containsEntry("correlationId", "gateway-correlation-id");
    }

    @Test
    void mintsAndEchoesAnIdWhenAbsent() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/whatever").build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, ex -> reactor.core.publisher.Mono.empty()).block();

        String echoed = exchange.getResponse().getHeaders().getFirst(CorrelationWebFilter.HEADER);
        assertThat(echoed).isNotBlank();
    }
}
