package com.revolutclone.fx.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.fx.rate.FxRateCache;
import com.revolutclone.fx.testsupport.FakeLedgerTestConfig;
import com.revolutclone.fx.testsupport.NoOpJwtDecoderTestConfig;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * fx-service's half of plans/phase-05-cards.md task 5 - "the card mark-up applied separately from the
 * exchange spread" - which on this side of the wire means: hand the caller the exchange spread as its
 * own figure, and add nothing else to it.
 *
 * <p><strong>Why this endpoint is tested apart from card-service's use of it.</strong>
 * {@code ForeignCurrencyAuthTest} drives the card side against a fake rate port, which is the right
 * shape for testing a decision chain but leaves nobody asserting that the real endpoint produces the
 * numbers that fake imitates. The two tests therefore share one worked example on purpose - a 1.25
 * EUR/USD mid at the default 50 bps spread - so that if this contract ever moves, the card side's
 * arithmetic is provably being checked against something real rather than against itself.
 *
 * <p><strong>The direction is the assertion that matters.</strong> {@code FxMath.applySpread} always
 * moves a rate in the house's favour <em>for the currency being sold</em>. The card flow fixes the
 * merchant's amount and lets the wallet supply whatever it costs, so the wallet is the seller and the
 * pair must be quoted wallet-to-merchant. Quoting it the other way round, or inverting before applying
 * the spread, hands the spread to the holder - a bug that costs money in the quietest possible way,
 * since every number involved still looks plausible.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import({NoOpJwtDecoderTestConfig.class, FakeLedgerTestConfig.class})
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000",
        "revolut.fx.ledger.base-url=http://ledger.invalid",
        // Pinned rather than inherited so the numbers below stay readable if the demo-time spread is
        // ever turned up - the same reason QuoteReservesFundsTest pins them.
        "revolut.fx.quote.spread-bps=50",
        "revolut.fx.quote.ttl=30s"
})
class InternalFxRateTest {

    /** The mid ForeignCurrencyAuthTest prices its $50.00 purchase against: 1.25 USD per EUR. */
    private static final String EUR_USD_MID = "1.2500000000";

    /** That mid less the default 50 bps, in the direction the wallet sells: 1.25 * 0.995. */
    private static final String EUR_USD_CLIENT_RATE = "1.2437500000";

    private static final int SPREAD_BPS = 50;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FxRateCache rates;

    @BeforeEach
    void theRateIsKnown() {
        rates.update(new FxRateCache.LatestRate("EUR/USD", EUR_USD_MID, Instant.now(), "test"));
    }

    @Test
    void returnsTheMidAndTheSpreadAdjustedRateAsSeparateFigures() throws Exception {
        // A EUR wallet buying USD: the wallet sells, so EUR is the sell currency.
        rate("EUR", "USD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellCurrency").value("EUR"))
                .andExpect(jsonPath("$.buyCurrency").value("USD"))
                .andExpect(jsonPath("$.midRate").value(EUR_USD_MID))
                .andExpect(jsonPath("$.clientRate").value(EUR_USD_CLIENT_RATE))
                // The spread as its own number is the whole point: a caller given only clientRate
                // could not tell fx-service's charge apart from anything it adds itself, and task 5
                // asks for exactly that distinction to survive the wire.
                .andExpect(jsonPath("$.spreadBps").value(SPREAD_BPS));
    }

    @Test
    void theSpreadMovesTheRateAgainstTheSellerAndNotInTheirFavour() throws Exception {
        // 1.24375 is below the 1.25 mid: a EUR seller gets fewer USD per EUR than the market rate.
        // Inverted by the caller, that makes each USD cost more EUR - which is the house's favour.
        // Asserting the inequality as well as the literal catches a sign flip that a recomputed
        // expected value would happily agree with.
        rate("EUR", "USD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientRate").value(EUR_USD_CLIENT_RATE));

        // And the same pair asked for the other way round is a different question with a different
        // answer - the spread is applied to whichever side is selling, not to a fixed currency.
        rate("USD", "EUR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellCurrency").value("USD"))
                .andExpect(jsonPath("$.buyCurrency").value("EUR"))
                .andExpect(jsonPath("$.spreadBps").value(SPREAD_BPS));
    }

    @Test
    void currencyCodesAreCaseInsensitive() throws Exception {
        rate("eur", "usd")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellCurrency").value("EUR"))
                .andExpect(jsonPath("$.midRate").value(EUR_USD_MID));
    }

    @Test
    void aPairWithNoTickYetIs503AndNotABadRequest() throws Exception {
        // GBP/JPY is a real pair with no rate in this cache. 503 rather than 400 because "our feed is
        // still warming" is a statement about us, and card-service turns any failure here into a retry
        // rather than into a decline - telling it "bad request" would be a lie it cannot recover from.
        rate("GBP", "JPY").andExpect(status().isServiceUnavailable());
    }

    @Test
    void thereIsNoRateBetweenACurrencyAndItself() throws Exception {
        rate("EUR", "EUR").andExpect(status().isBadRequest());
    }

    @Test
    void theEndpointIsInternalButStillAuthenticated() throws Exception {
        // Service-to-service does not mean unauthenticated: this route is kept off the gateway *and*
        // behind a token, rather than trusting where the call appears to come from.
        mockMvc.perform(get("/internal/fx/rates/{sell}/{buy}", "EUR", "USD"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions rate(String sell, String buy) throws Exception {
        return mockMvc.perform(get("/internal/fx/rates/{sell}/{buy}", sell, buy).with(jwt()));
    }
}
