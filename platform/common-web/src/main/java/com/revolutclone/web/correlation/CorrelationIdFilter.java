package com.revolutclone.web.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads {@code X-Correlation-Id} from the incoming request, or mints one, then makes it
 * available two ways: in MDC (so every log line for this request carries it as a field, not
 * just a pattern token) and echoed back on the response header.
 *
 * <p>Also logs one line per request. Without it, {@code correlationId} sits in MDC but is
 * never actually emitted anywhere on a plain request whose controller logs nothing - which
 * defeats the point (CLAUDE.md rule 10: "correlationId as a field, not merely in a pattern"),
 * found live while verifying task 01.7's phase-closing "correlation id appears in both
 * services' logs" check.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        response.setHeader(HEADER, correlationId);
        MDC.put(MDC_KEY, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            log.info("{} {} -> {}", request.getMethod(), request.getRequestURI(), response.getStatus());
            MDC.remove(MDC_KEY);
        }
    }
}
