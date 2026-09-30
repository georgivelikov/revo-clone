package com.revolutclone.card.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.revolutclone.card.domain.AuthorizationState;
import com.revolutclone.card.domain.CardAuthorization;
import com.revolutclone.card.domain.DeclineReason;
import com.revolutclone.card.domain.LimitPeriod;
import com.revolutclone.card.fx.CardBillingDestination;
import com.revolutclone.card.fx.CardFxPricer;
import com.revolutclone.card.fx.CardFxPricing;
import com.revolutclone.card.fx.CardFxProperties;
import com.revolutclone.card.fx.CardFxRateUnavailableException;
import com.revolutclone.card.testsupport.AuthorizationTestSupport;
import com.revolutclone.card.testsupport.FakeCardFxRates;
import com.revolutclone.messaging.api.TransientMessagingException;
import com.revolutclone.messaging.outbox.OutboxEvent;
import com.revolutclone.money.Money;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * plans/phase-05-cards.md's Tests table: "USD merchant on a EUR wallet: FX applied, mark-up in
 * {@code FEE_INCOME}."
 *
 * <p><strong>The worked example, checkable by hand.</strong> A $50.00 purchase, a mid of 1.25 USD per
 * EUR, fx-service's default 50 bps exchange spread and card-service's default 100 bps mark-up:
 *
 * <pre>
 *   exchange rate   1.25 * 0.995      = 1.2437500000  USD per EUR   (fx-service's spread)
 *   inverted        1 / 1.24375       = 0.8040201005  EUR per USD
 *   exchanged       50.00 * 0.8040201005 = EUR 40.201005... -> EUR 40.20   (4020 minor)
 *   mark-up         100 bps of 4020   = EUR 0.40                  (card-service's charge)
 *   billing         4020 + 40         = EUR 40.60                 <- held
 * </pre>
 *
 * <p><strong>Where the mark-up goes, and why this task can assert it without a posting.</strong> An
 * authorization creates no journal entry at all - that is the whole point of the phase - so "mark-up in
 * {@code FEE_INCOME}" cannot mean a posting here; task 05.6's settlement is what posts. What this task
 * owes is that the mark-up is a <em>separate, exact, recorded</em> figure earmarked for that account,
 * and that is what is asserted: {@link CardBillingDestination} names the two accounts the billing
 * amount splits across, the split sums to the billing amount to the minor unit, and
 * {@code fx_markup_minor} carries the {@code FEE_INCOME} share on the row so that a settlement days
 * later has nothing left to work out.
 *
 * <p><strong>The two charges are separate on purpose and separately visible.</strong> The exchange
 * spread is fx-service's, applied to the rate; the mark-up is card-service's, applied to the converted
 * amount. The row records both basis-point figures, so an operator can tell which of the two made a
 * purchase cost what it did - which is the substance of task 5's "applied separately from the exchange
 * spread".
 */
class ForeignCurrencyAuthTest extends AuthorizationTestSupport {

    /** $50.00 - the phase's own example of a foreign-currency spend. */
    private static final long MERCHANT_MINOR = 5_000L;

    /** The three figures the arithmetic above produces, in EUR minor units. */
    private static final long EXCHANGED_MINOR = 4_020L;
    private static final long MARK_UP_MINOR = 40L;
    private static final long BILLING_MINOR = 4_060L;

    @Autowired
    private CardFxPricer pricer;

    @Autowired
    private CardFxProperties fxProperties;

    @Test
    void aUsdMerchantOnAEurWalletIsChargedTheConvertedAmountPlusTheMarkUp() {
        tap("net-fx-01", pan, MERCHANT_MINOR, USD);

        CardAuthorization authorization = authorizations.findByNetworkRef("net-fx-01").orElseThrow();
        assertThat(authorization.getState()).isEqualTo(AuthorizationState.APPROVED);

        // What the merchant asked for, untouched: this number is shown to the holder and is never
        // held, never compared against a limit and never posted.
        assertThat(authorization.getAmountMinor()).isEqualTo(MERCHANT_MINOR);
        assertThat(authorization.getCurrency()).isEqualTo(USD);

        // What the wallet is charged. The "you spent $50.00, we charged you EUR 40.60" line.
        assertThat(authorization.getBillingMinor()).isEqualTo(BILLING_MINOR);
        assertThat(authorization.getBillingCurrency()).isEqualTo(EUR);
    }

    @Test
    void theHoldIsPlacedInTheWalletsCurrencyForTheBillingAmountAndNotTheMerchants() {
        tap("net-fx-02", pan, MERCHANT_MINOR, USD);

        assertThat(ledger.holds()).singleElement().satisfies(hold -> {
            assertThat(hold.ledgerAccountId()).isEqualTo(ledgerAccountId);
            // The one mistake this whole step exists to prevent: holding 5000 of something on a EUR
            // wallet, which would be wrong money rather than a rounding error.
            assertThat(hold.amountMinor()).isEqualTo(BILLING_MINOR);
            assertThat(hold.currency()).isEqualTo(EUR);
        });

        assertThat(ledger.availableMinor(ledgerAccountId))
                .isEqualTo(WALLET_BALANCE_MINOR - BILLING_MINOR);
        assertThat(ledger.totalMinor(ledgerAccountId))
                .as("still a hold and not a posting, foreign currency or not")
                .isEqualTo(WALLET_BALANCE_MINOR);
    }

    @Test
    void theRowRecordsTheExchangeSpreadAndTheCardMarkUpAsTwoSeparateFigures() {
        tap("net-fx-03", pan, MERCHANT_MINOR, USD);

        CardAuthorization authorization = authorizations.findByNetworkRef("net-fx-03").orElseThrow();
        assertThat(authorization.getFxMidRate()).isEqualTo(EUR_USD_MID);
        assertThat(authorization.getFxExchangeRate())
                .as("the mid less fx-service's spread, in the direction the wallet sells")
                .isEqualTo("1.2437500000");
        assertThat(authorization.getFxSpreadBps())
                .as("fx-service's exchange spread")
                .isEqualTo(FakeCardFxRates.DEFAULT_SPREAD_BPS);
        assertThat(authorization.getFxMarkupBps())
                .as("card-service's own mark-up, a different number from a different service")
                .isEqualTo(fxProperties.markUpBps());
        assertThat(authorization.getFxMarkupMinor()).isEqualTo(MARK_UP_MINOR);
    }

    @Test
    void theMarkUpIsTheFeeIncomeShareOfTheBillingAmountAndTheTwoSharesAreExact() {
        CardFxPricing pricing = pricer.price(Money.of(MERCHANT_MINOR, USD), EUR);

        assertThat(pricing.amountFor(CardBillingDestination.FEE_INCOME))
                .as("the mark-up is revenue; this is the leg settlement credits to FEE_INCOME")
                .isEqualTo(Money.of(MARK_UP_MINOR, EUR));
        assertThat(pricing.amountFor(CardBillingDestination.CARD_SETTLEMENT))
                .as("and the converted purchase is the merchant's money passing through")
                .isEqualTo(Money.of(EXCHANGED_MINOR, EUR));

        // Exact, not approximately: the settlement entry debits the wallet once for the billing amount
        // and credits these two, so a sub-unit discrepancy here would be a journal entry that cannot
        // balance. This is why the mark-up is basis points of whole converted minor units rather than
        // the gap between two independently rounded conversions.
        assertThat(pricing.amountFor(CardBillingDestination.CARD_SETTLEMENT)
                .plus(pricing.amountFor(CardBillingDestination.FEE_INCOME)))
                .isEqualTo(pricing.billing());
        assertThat(pricing.billing()).isEqualTo(Money.of(BILLING_MINOR, EUR));
    }

    @Test
    void theMarkUpScalesWithItsOwnBasisPointsAndLeavesTheExchangeAlone() {
        CardFxPricing atDefault = pricer.price(Money.of(MERCHANT_MINOR, USD), EUR);

        // Turn fx-service's spread up and the exchange moves; the mark-up follows only because it is a
        // percentage of the exchange, never because the two numbers are the same number.
        fxRates.setSpreadBps(200);
        CardFxPricing atWiderSpread = pricer.price(Money.of(MERCHANT_MINOR, USD), EUR);

        assertThat(atWiderSpread.spreadBps()).isEqualTo(200);
        assertThat(atWiderSpread.markUpBps())
                .as("card-service's mark-up is not fx-service's to change")
                .isEqualTo(atDefault.markUpBps());
        assertThat(atWiderSpread.exchanged().minor())
                .as("a wider spread buys the holder fewer EUR per USD, so the purchase costs more")
                .isGreaterThan(atDefault.exchanged().minor());
        assertThat(atWiderSpread.exchanged().plus(atWiderSpread.markUp()))
                .isEqualTo(atWiderSpread.billing());
    }

    @Test
    void limitsAreMeasuredAgainstTheConvertedAmountIncludingTheMarkUp() {
        // EUR 45.00. The merchant's own 5000 would breach this if it were compared unconverted, and the
        // conversion alone (4020) would pass a ceiling the mark-up pushes it over - so this pins down
        // both halves of step 4 sitting before step 5.
        setLimit(LimitPeriod.PER_TRANSACTION, 4_500L);
        tap("net-fx-04", pan, MERCHANT_MINOR, USD);
        assertThat(stateOf("net-fx-04")).isEqualTo(AuthorizationState.APPROVED);

        limits.deleteAll();
        setLimit(LimitPeriod.PER_TRANSACTION, EXCHANGED_MINOR + MARK_UP_MINOR - 1);
        tap("net-fx-05", pan, MERCHANT_MINOR, USD);
        assertThat(stateOf("net-fx-05")).isEqualTo(AuthorizationState.DECLINED);
        assertThat(declineReasonOf("net-fx-05")).isEqualTo(DeclineReason.LIMIT_EXCEEDED);
    }

    @Test
    void aForeignDeclineStillRecordsWhatTheHolderWouldHaveBeenCharged() {
        setLimit(LimitPeriod.PER_TRANSACTION, 1_000L);
        tap("net-fx-06", pan, MERCHANT_MINOR, USD);

        CardAuthorization declined = authorizations.findByNetworkRef("net-fx-06").orElseThrow();
        assertThat(declined.getState()).isEqualTo(AuthorizationState.DECLINED);
        assertThat(declined.getReservationId()).isNull();
        // The decline was reached after step 4, so the conversion is on the row: a holder asking why a
        // foreign purchase failed is owed the amount it would have cost them.
        assertThat(declined.getBillingMinor()).isEqualTo(BILLING_MINOR);
        assertThat(declined.getBillingCurrency()).isEqualTo(EUR);
        assertThat(declined.getFxMarkupMinor()).isEqualTo(MARK_UP_MINOR);
        assertThat(declined.getFxExchangeRate()).isEqualTo("1.2437500000");
    }

    @Test
    void theApprovalEventCarriesBothAmountsSoTheFeedCanShowTheOriginalAsASubline() {
        tap("net-fx-07", pan, MERCHANT_MINOR, USD);

        List<OutboxEvent> rows = outbox.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getType()).isEqualTo("AuthorizationApprovedV1");

        JsonNode payload = jsonMapper.readTree(rows.getFirst().getPayload()).get("payload");
        assertThat(payload.get("amountMinor").asLong()).isEqualTo(MERCHANT_MINOR);
        assertThat(payload.get("currency").asString()).isEqualTo(USD);
        assertThat(payload.get("billingMinor").asLong()).isEqualTo(BILLING_MINOR);
        assertThat(payload.get("billingCurrency").asString()).isEqualTo(EUR);
    }

    @Test
    void aDomesticMerchantTakesTheSamePathAndRecordsNoFxAtAll() {
        tap("net-fx-08", pan, 4_250L);

        CardAuthorization authorization = authorizations.findByNetworkRef("net-fx-08").orElseThrow();
        assertThat(authorization.getBillingMinor()).isEqualTo(4_250L);
        // Null and not zero: "no conversion happened" is a different fact from "the mark-up came to
        // nothing", and a feed that showed a 0.00 mark-up line on every domestic purchase would be
        // telling the holder something untrue.
        assertThat(authorization.getFxMidRate()).isNull();
        assertThat(authorization.getFxExchangeRate()).isNull();
        assertThat(authorization.getFxSpreadBps()).isNull();
        assertThat(authorization.getFxMarkupBps()).isNull();
        assertThat(authorization.getFxMarkupMinor()).isNull();
    }

    @Test
    void aRateWeCannotGetIsNotADeclineAndDecidesNothing() {
        fxRates.reset();

        // The five decline reasons are all statements about the card or the holder. "Our rate feed is
        // behind" is a statement about us, and a decline would be final and visible to the holder - so
        // this throws, the message is retried, and no row is written either way. The consumer converts
        // the raw CardFxRateUnavailableException to TransientMessagingException at the Kafka boundary
        // (plans/phase-07-resilience-and-scale.md task 3) so DefaultErrorClassifier - which does not
        // recognise the raw type - sends it into the retry chain instead of straight to the DLT.
        assertThatThrownBy(() -> tap("net-fx-09", pan, MERCHANT_MINOR, USD))
                .isInstanceOf(TransientMessagingException.class)
                .hasCauseInstanceOf(CardFxRateUnavailableException.class);

        assertThat(authorizations.findByNetworkRef("net-fx-09")).isEmpty();
        assertThat(ledger.holds()).isEmpty();
        assertThat(outbox.findAll()).isEmpty();
    }
}
