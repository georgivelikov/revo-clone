package com.revolutclone.fx.web;

import com.revolutclone.fx.quote.CreateQuoteCommand;
import com.revolutclone.fx.quote.FxQuote;
import com.revolutclone.fx.quote.QuoteExecutionService;
import com.revolutclone.fx.quote.QuoteService;
import com.revolutclone.security.api.CurrentUser;
import com.revolutclone.security.api.UserPrincipal;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fx/quotes")
public class FxQuoteController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final QuoteService quotes;
    private final QuoteExecutionService execution;

    public FxQuoteController(QuoteService quotes, QuoteExecutionService execution) {
        this.quotes = quotes;
        this.execution = execution;
    }

    /**
     * <strong>201, not 202.</strong> Unlike {@code POST /api/payments}, this request is finished when
     * it returns: a quote exists, its rate is firm, and the funds behind it are already held. Nothing
     * about it is still in flight - the asynchronous half of an exchange begins at
     * {@code POST /api/fx/quotes/{id}/execute} (task 04.5).
     *
     * <p>The {@code Idempotency-Key} header is required even though no money moves here, because a
     * hold does move the caller's available balance: CLAUDE.md rule 3 covers this endpoint precisely
     * because a retried quote request that reserved twice would decline the retry of a request that
     * succeeded.
     */
    @PostMapping
    public ResponseEntity<QuoteResponse> quote(
            @CurrentUser UserPrincipal principal,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateQuoteRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException();
        }

        FxQuote quote = quotes.quote(new CreateQuoteCommand(
                UUID.fromString(principal.userId()),
                idempotencyKey.strip(),
                request.sellCurrency(),
                request.buyCurrency(),
                request.sellMinor(),
                request.buyMinor()));

        return ResponseEntity.created(URI.create("/api/fx/quotes/" + quote.id()))
                .body(QuoteResponse.from(quote));
    }

    /**
     * <strong>202, not 200.</strong> The quote is {@code CONSUMED} the moment this returns and the
     * exchange will happen - the funds are already held - but it has not happened yet: the ledger
     * captures the hold and posts the entry off the back of the event this appends. That gap is the
     * phase's "optimistic pending → settled" and is exactly the same reconciliation the Phase 03
     * transfer flow made visible.
     *
     * <p>The {@code Idempotency-Key} header is required by CLAUDE.md rule 3 - this is the request
     * that moves the money. It is honoured through the quote itself rather than a second key space:
     * a quote is a one-shot resource, so re-executing one that is already {@code CONSUMED} returns
     * the same outcome instead of posting a second entry. Sending a different key for the same
     * quote therefore cannot execute it twice either, which a key-keyed store alone would allow.
     */
    @PostMapping("/{id}/execute")
    public ResponseEntity<QuoteResponse> execute(
            @CurrentUser UserPrincipal principal,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable UUID id) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException();
        }

        FxQuote executed = execution.execute(id, UUID.fromString(principal.userId()));
        return ResponseEntity.accepted().body(QuoteResponse.from(executed));
    }
}
