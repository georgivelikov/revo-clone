package com.revolutclone.card.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.card.domain.AuthorizationState;
import com.revolutclone.card.domain.CardAuthorization;
import com.revolutclone.card.domain.CardState;
import com.revolutclone.card.domain.LimitPeriod;
import com.revolutclone.card.pan.PanGenerator;
import com.revolutclone.card.testsupport.SettlementTestSupport;
import com.revolutclone.messaging.outbox.OutboxEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * plans/phase-05-cards.md's Tests table: <em>"AuthExpiryTest - Unsettled hold released after 7
 * simulated days."</em> The phase's settlement table: <em>"Expiry (7 days, no settlement): sweep
 * releases the hold."</em>
 *
 * <p><strong>"Simulated" is the load-bearing word.</strong> Seven days are reached by handing
 * {@link AuthorizationExpirySweep#sweepDueAsOf} the instant they would have been reached at, the same
 * way ledger-service's {@code ReservationExpiryTest} and fx-service's {@code QuoteExpiryTest} drive
 * their own sweeps. Nothing here waits, and no clock bean is mutated - the sweep is a function of one
 * instant and the {@code authorized_at} column, so a test that supplies the instant controls the
 * deadline exactly, including the boundary either side of it.
 *
 * <p>The assertions are about three different things that all have to be true together, because any
 * two of them without the third is a bug the holder would see: the money is genuinely back (available
 * restored, total never moved, and spendable again), the row stops being pending without ever
 * acquiring a posting, and a settlement that turns up late is refused rather than posting against a
 * hold that no longer exists.
 */
class AuthExpiryTest extends SettlementTestSupport {

    /** The window the phase fixes and {@code application.yml} defaults to. */
    private static final Duration HOLD_TTL = Duration.ofDays(7);

    private static final long SPEND_MINOR = 4_250L;

    @Autowired
    private AuthorizationExpirySweep sweep;

    /** The moment the seven days are up for a hold taken now. */
    private static Instant sevenDaysOn() {
        return Instant.now().plus(HOLD_TTL);
    }

    @Test
    void anUnsettledHoldIsReleasedAfterSevenSimulatedDays() {
        tap("net-5701", pan, SPEND_MINOR);
        UUID reservation = reservationOf("net-5701");
        assertThat(ledger.availableMinor(ledgerAccountId)).isEqualTo(WALLET_BALANCE_MINOR - SPEND_MINOR);

        int expired = sweep.sweepDueAsOf(sevenDaysOn());

        assertThat(expired).isEqualTo(1);
        assertThat(ledger.expiries())
                .as("the ledger was asked to give the hold back - the sweep does not merely assume it will")
                .containsExactly(reservation);
        assertThat(ledger.heldMinor(reservation)).isZero();
        assertThat(ledger.availableMinor(ledgerAccountId))
                .as("available is exactly what it was before the tap")
                .isEqualTo(WALLET_BALANCE_MINOR);
        assertThat(ledger.totalMinor(ledgerAccountId))
                .as("total never moved; no money ever left, which is the whole point of an expiry")
                .isEqualTo(WALLET_BALANCE_MINOR);
    }

    @Test
    void theExpiredRowStopsBeingPendingAndNeverAcquiresAPosting() {
        tap("net-5702", pan, SPEND_MINOR);

        sweep.sweepDueAsOf(sevenDaysOn());

        CardAuthorization expired = authorizationOf("net-5702");
        assertThat(expired.getState()).isEqualTo(AuthorizationState.EXPIRED);
        assertThat(expired.getLedgerEntryId())
                .as("an expiry is the absence of an accounting event, exactly as a reversal is")
                .isNull();
        assertThat(expired.getSettledMinor()).isNull();
        assertThat(expired.getExcessMinor()).isNull();
        assertThat(expired.getSettledAt()).isNull();
        assertThat(ledger.settlements())
                .as("nothing was captured at any amount")
                .isEmpty();
    }

    @Test
    void aHoldOneHourShortOfSevenDaysIsLeftAlone() {
        tap("net-5703", pan, SPEND_MINOR);

        int expired = sweep.sweepDueAsOf(sevenDaysOn().minus(Duration.ofHours(1)));

        assertThat(expired).isZero();
        assertThat(stateOf("net-5703"))
                .as("the deadline is seven days, not approximately seven days")
                .isEqualTo(AuthorizationState.APPROVED);
        assertThat(ledger.expiries()).isEmpty();
        assertThat(ledger.availableMinor(ledgerAccountId))
                .as("and the funds are still held while it stands")
                .isEqualTo(WALLET_BALANCE_MINOR - SPEND_MINOR);
    }

    @Test
    void expiryPublishesAuthorizationExpiredWithNoEntryToName() {
        tap("net-5704", pan, SPEND_MINOR);
        outbox.deleteAll();

        sweep.sweepDueAsOf(sevenDaysOn());

        List<OutboxEvent> rows = outbox.findAll();
        assertThat(rows).hasSize(1);
        OutboxEvent row = rows.getFirst();
        assertThat(row.getTopic()).isEqualTo("card.authorization.v1");
        assertThat(row.getPartitionKey())
                .as("keyed by cardId like every other decision about this card")
                .isEqualTo(card.getId().toString());
        assertThat(row.getType()).isEqualTo("AuthorizationExpiredV1");

        JsonNode payload = jsonMapper.readTree(row.getPayload()).get("payload");
        assertThat(payload.get("networkRef").asString()).isEqualTo("net-5704");
        assertThat(payload.get("releasedMinor").asLong()).isEqualTo(SPEND_MINOR);
        assertThat(payload.get("releasedCurrency").asString()).isEqualTo(EUR);
        assertThat(payload.get("panLast4").asString()).isEqualTo(card.getPanLast4());
        assertThat(payload.has("ledgerEntryId"))
                .as("the event has no such field at all - there is no posting to name")
                .isFalse();
    }

    @Test
    void aSecondSweepExpiresNothingAndCreditsNothingTwice() {
        tap("net-5705", pan, SPEND_MINOR);
        sweep.sweepDueAsOf(sevenDaysOn());
        outbox.deleteAll();

        int expiredAgain = sweep.sweepDueAsOf(sevenDaysOn().plus(Duration.ofDays(1)));

        assertThat(expiredAgain).isZero();
        assertThat(ledger.expiries())
                .as("the row is no longer APPROVED, so the ledger is not asked a second time")
                .hasSize(1);
        assertThat(ledger.availableMinor(ledgerAccountId)).isEqualTo(WALLET_BALANCE_MINOR);
        assertThat(outbox.findAll()).isEmpty();
    }

    @Test
    void theReleasedFundsAreSpendableAndNoLongerCountAgainstTheDailyLimit() {
        // A hold marked expired but not returned would pass every balance assertion above and still
        // decline this tap - and spend that stayed in the rolling window would decline it too, for a
        // purchase that never happened. Spending the whole balance under a limit equal to it is the
        // one check that cannot be fooled by either.
        setLimit(LimitPeriod.DAILY, WALLET_BALANCE_MINOR);
        tap("net-5706", pan, SPEND_MINOR);
        sweep.sweepDueAsOf(sevenDaysOn());

        tap("net-5707", pan, WALLET_BALANCE_MINOR);

        assertThat(stateOf("net-5707")).isEqualTo(AuthorizationState.APPROVED);
        assertThat(ledger.availableMinor(ledgerAccountId)).isZero();
    }

    @Test
    void aSettlementArrivingAfterTheExpiryIsIgnoredRatherThanPosted() {
        // The scheme can and does submit a batch late. By then the hold is gone, and capturing
        // against it would post money the holder was already told they had back.
        tap("net-5708", pan, SPEND_MINOR);
        sweep.sweepDueAsOf(sevenDaysOn());
        outbox.deleteAll();

        settle("net-5708", SPEND_MINOR);

        assertThat(authorizationOf("net-5708").getState()).isEqualTo(AuthorizationState.EXPIRED);
        assertThat(ledger.settlements()).isEmpty();
        assertThat(ledger.totalMinor(ledgerAccountId))
                .as("no money moved, then or now")
                .isEqualTo(WALLET_BALANCE_MINOR);
        assertThat(outbox.findAll())
                .as("ignored and committed - a redelivery could never make this settle")
                .isEmpty();
    }

    @Test
    void concludedAndRefusedAuthorizationsAreNeverSwept() {
        // Everything with no hold left to give back, in one sweep: a settlement took its money, a
        // reversal already gave it back, and a decline never held any. A sweep that touched the first
        // would credit a wallet twice; one that touched the last would ask the ledger about a
        // reservation that does not exist.
        tap("net-5709", pan, SPEND_MINOR);
        settle("net-5709", SPEND_MINOR);
        tap("net-5710", pan, SPEND_MINOR);
        reverse("net-5710");
        String frozenPan = new PanGenerator().generate();
        saveCard(frozenPan, CardState.FROZEN, YearMonth.now().plusYears(3));
        tap("net-5711", frozenPan, SPEND_MINOR);

        int expired = sweep.sweepDueAsOf(sevenDaysOn());

        assertThat(expired).isZero();
        assertThat(ledger.expiries()).isEmpty();
        assertThat(stateOf("net-5709")).isEqualTo(AuthorizationState.SETTLED);
        assertThat(stateOf("net-5710")).isEqualTo(AuthorizationState.REVERSED);
        assertThat(stateOf("net-5711")).isEqualTo(AuthorizationState.DECLINED);
    }
}
