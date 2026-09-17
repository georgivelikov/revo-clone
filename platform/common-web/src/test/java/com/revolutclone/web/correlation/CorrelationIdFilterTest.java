package com.revolutclone.web.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void mintsAnIdWhenHeaderIsAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String minted = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(minted).isNotBlank();
    }

    @Test
    void reusesTheIncomingHeaderWhenPresent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "given-correlation-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("given-correlation-id");
    }

    @Test
    void putsTheIdInMdcForTheDurationOfTheRequestOnly() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "mdc-correlation-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("mdc-correlation-id");
            }
        };

        filter.doFilter(request, response, chain);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void mintsANewIdWhenTheIncomingHeaderIsBlank() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isNotBlank().isNotEqualTo("   ");
    }

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(CorrelationIdFilter.class)).addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        ((Logger) LoggerFactory.getLogger(CorrelationIdFilter.class)).detachAppender(appender);
    }

    /**
     * Task 01.7: MDC alone is not observable - a request whose handler logs nothing means
     * {@code correlationId} sits in MDC and is never actually emitted anywhere, silently
     * defeating CLAUDE.md rule 10 ("correlationId as a field, not merely in a pattern"). The
     * filter must log something itself so every request has at least one line carrying it.
     */
    @Test
    void logsOneLineWithTheCorrelationIdStillInMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/whatever");
        request.addHeader(CorrelationIdFilter.HEADER, "logged-correlation-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getFormattedMessage()).contains("GET").contains("/api/whatever").contains("200");
        assertThat(event.getMDCPropertyMap()).containsEntry(CorrelationIdFilter.MDC_KEY, "logged-correlation-id");
    }
}
