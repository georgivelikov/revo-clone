package com.revolutclone.fx.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.revolutclone.fx.chaos.ChaosFxState;
import com.revolutclone.fx.chaos.FxDownChaosException;
import com.revolutclone.fx.quote.QuoteService;
import com.revolutclone.fx.readmodel.WalletResolver;
import com.revolutclone.security.api.UserPrincipal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * plans/phase-07-resilience-and-scale.md's {@code fx.down} lever, exercised directly against the
 * controller method - the same seam {@code FxQuoteRestClient} on the payment-service side
 * actually calls over HTTP, without standing up the full servlet/security stack just to prove
 * this one branch.
 */
class InternalFxQuoteControllerChaosTest {

    @Test
    void downStateRefusesTheQuoteBeforeTouchingAnythingElse() {
        QuoteService quotes = mock(QuoteService.class);
        WalletResolver wallets = mock(WalletResolver.class);
        ChaosFxState chaos = new ChaosFxState();
        chaos.setDown(true);
        InternalFxQuoteController controller = new InternalFxQuoteController(quotes, wallets, chaos);

        UserPrincipal principal = new UserPrincipal(UUID.randomUUID().toString(), "alice@example.com", Set.of());
        InternalCreateQuoteRequest request = new InternalCreateQuoteRequest("EUR", "USD", 1_000L, null,
                UUID.randomUUID());

        assertThatThrownBy(() -> controller.quote(principal, "idem-key", request))
                .isInstanceOf(FxDownChaosException.class);

        verifyNoInteractions(quotes, wallets);
    }
}
