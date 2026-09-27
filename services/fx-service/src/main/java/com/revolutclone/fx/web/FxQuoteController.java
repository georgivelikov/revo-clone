package com.revolutclone.fx.web;

import com.revolutclone.fx.quote.CreateQuoteCommand;
import com.revolutclone.fx.quote.FxQuote;
import com.revolutclone.fx.quote.QuoteService;
import com.revolutclone.security.api.CurrentUser;
import com.revolutclone.security.api.UserPrincipal;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
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

    public FxQuoteController(QuoteService quotes) {
        this.quotes = quotes;
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
}
