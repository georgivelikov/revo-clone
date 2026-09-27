package com.revolutclone.fx.web;

import com.revolutclone.fx.quote.CreateQuoteCommand;
import com.revolutclone.fx.quote.FxQuote;
import com.revolutclone.fx.quote.QuoteService;
import com.revolutclone.fx.quote.error.BuyWalletNotFoundException;
import com.revolutclone.fx.readmodel.WalletResolver;
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

/**
 * plans/phase-04-fx-multicurrency.md task 8's "payment-service obtains a quote": service-to-service
 * only, not routed by the gateway - same precedent as ledger-service's {@code /internal/ledger/**}
 * - and still a JWT resource server route rather than trusting network placement, since no service
 * identity exists yet (a client-credentials identity for service-to-service calls is a Phase 07
 * concern, per {@code LedgerReservationsRestClient}'s own note). payment-service forwards the
 * paying user's own bearer token, exactly as that same client forwards it onward to ledger-service.
 *
 * <p>The sell leg still reserves the <em>caller's</em> wallet - the JWT subject is the payer, same
 * as the public endpoint. What differs is the buy leg: a self-service exchange resolves it at
 * execute time from that same caller, but a transfer's recipient is a different person, so this
 * resolves {@code recipientUserId}'s wallet <strong>before</strong> any funds are reserved and
 * refuses the quote if it does not exist, rather than discovering that after the hold is already
 * placed and having to release it again.
 */
@RestController
@RequestMapping("/internal/fx/quotes")
public class InternalFxQuoteController {

    public static final String IDEMPOTENCY_KEY_HEADER = FxQuoteController.IDEMPOTENCY_KEY_HEADER;

    private final QuoteService quotes;
    private final WalletResolver wallets;

    public InternalFxQuoteController(QuoteService quotes, WalletResolver wallets) {
        this.quotes = quotes;
        this.wallets = wallets;
    }

    @PostMapping
    public ResponseEntity<InternalQuoteResponse> quote(
            @CurrentUser UserPrincipal principal,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody InternalCreateQuoteRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException();
        }

        UUID buyLedgerAccountId = wallets.resolveWalletLedgerAccount(request.recipientUserId(), request.buyCurrency())
                .orElseThrow(() -> new BuyWalletNotFoundException(request.buyCurrency()));

        FxQuote quote = quotes.quote(new CreateQuoteCommand(
                UUID.fromString(principal.userId()),
                idempotencyKey.strip(),
                request.sellCurrency(),
                request.buyCurrency(),
                request.sellMinor(),
                request.buyMinor()));

        return ResponseEntity.created(URI.create("/internal/fx/quotes/" + quote.id()))
                .body(InternalQuoteResponse.of(quote, buyLedgerAccountId));
    }
}
