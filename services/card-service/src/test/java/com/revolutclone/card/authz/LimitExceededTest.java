package com.revolutclone.card.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.card.domain.AuthorizationState;
import com.revolutclone.card.domain.CardAuthorization;
import com.revolutclone.card.domain.DeclineReason;
import com.revolutclone.card.domain.LimitPeriod;
import com.revolutclone.card.testsupport.AuthorizationTestSupport;
import com.revolutclone.messaging.outbox.OutboxEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * plans/phase-05-cards.md's Tests table: "Daily limit enforced across multiple auths in the window."
 *
 * <p>"Across multiple auths" is the whole assertion. A per-transaction ceiling is a comparison against
 * one number and needs no history; a daily limit is a statement about a <em>set</em> of authorizations,
 * which means the evaluator has to read what the card has already done and the tests have to pin down
 * exactly which rows count and which have fallen out.
 *
 * <p>The phase's Risks section warns that "rolling-window limits are subtly hard" and instructs: pick
 * rolling or calendar, write it down, and test the boundary. Rolling is written down in
 * {@link CardLimitProperties}; the boundary is tested here, from both sides and at the instant itself.
 *
 * <p>Every test funds the wallet far above the limit under test, so nothing here can pass for the wrong
 * reason: an {@code INSUFFICIENT_FUNDS} decline would satisfy "the tap was refused" while proving
 * nothing at all about limits.
 */
class LimitExceededTest extends AuthorizationTestSupport {

    /** 50.00 EUR - comfortably under the wallet's 100.00, so funds are never the binding constraint. */
    private static final long DAILY_LIMIT_MINOR = 5_000L;

    /** 20.00 EUR: two fit under the daily limit, three do not. */
    private static final long TAP_MINOR = 2_000L;

    @Test
    void theDailyLimitIsEnforcedAcrossSeveralTapsInTheWindowRatherThanPerTap() {
        setLimit(LimitPeriod.DAILY, DAILY_LIMIT_MINOR);

        tap("net-limit-1", pan, TAP_MINOR);
        tap("net-limit-2", pan, TAP_MINOR);
        // 40.00 already spent today, so this one would take the card to 60.00 against a 50.00 ceiling.
        // Every individual tap is well within the limit; only their sum is not.
        tap("net-limit-3", pan, TAP_MINOR);

        assertThat(stateOf("net-limit-1")).isEqualTo(AuthorizationState.APPROVED);
        assertThat(stateOf("net-limit-2")).isEqualTo(AuthorizationState.APPROVED);

        CardAuthorization declined = authorizations.findByNetworkRef("net-limit-3").orElseThrow();
        assertThat(declined.getState()).isEqualTo(AuthorizationState.DECLINED);
        assertThat(declined.getDeclineReason()).isEqualTo(DeclineReason.LIMIT_EXCEEDED);

        // The refusal moved nothing: step 5 sits before step 6 precisely so a declined purchase cannot
        // touch a balance.
        assertThat(declined.getReservationId()).isNull();
        assertThat(ledger.holds()).hasSize(2);
        assertThat(ledger.availableMinor(ledgerAccountId)).isEqualTo(WALLET_BALANCE_MINOR - 2 * TAP_MINOR);
        assertThat(ledger.totalMinor(ledgerAccountId)).isEqualTo(WALLET_BALANCE_MINOR);
    }

    @Test
    void aTapThatTakesSpendExactlyToTheLimitIsApprovedBecauseALimitIsACeilingNotABarrier() {
        setLimit(LimitPeriod.DAILY, DAILY_LIMIT_MINOR);

        tap("net-exact-1", pan, DAILY_LIMIT_MINOR);

        assertThat(stateOf("net-exact-1")).isEqualTo(AuthorizationState.APPROVED);

        // ...and the next cent is not. "Limit 50.00" means 50.00 is allowed; 50.01 is not.
        tap("net-exact-2", pan, 1L);
        assertThat(stateOf("net-exact-2")).isEqualTo(AuthorizationState.DECLINED);
    }

    @Test
    void spendStillInsideTheRollingDayCountsAgainstTheLimit() {
        setLimit(LimitPeriod.DAILY, DAILY_LIMIT_MINOR);
        recordPastSpend("net-yesterday", 4_000L, Instant.now().minus(Duration.ofHours(23)));

        tap("net-today", pan, TAP_MINOR);

        assertThat(declineReasonOf("net-today")).isEqualTo(DeclineReason.LIMIT_EXCEEDED);
        assertThat(ledger.holds()).as("nothing was held for a refused tap").isEmpty();
    }

    @Test
    void spendOlderThanTheRollingDayHasLeftTheWindowAndFreesTheLimitAgain() {
        setLimit(LimitPeriod.DAILY, DAILY_LIMIT_MINOR);
        // The same 40.00 as the test above, two hours older. Nothing about the row changed; the window
        // moved past it - which is what "rolling" means, and why a holder who hit their limit at 14:00
        // can spend again from 14:00 the next day rather than at midnight.
        recordPastSpend("net-two-days-ago", 4_000L, Instant.now().minus(Duration.ofHours(25)));

        tap("net-today", pan, TAP_MINOR);

        assertThat(stateOf("net-today")).isEqualTo(AuthorizationState.APPROVED);
    }

    @Test
    void spendExactlyOneRollingDayOldIsAlreadyOutOfTheWindow() {
        setLimit(LimitPeriod.DAILY, DAILY_LIMIT_MINOR);
        // The boundary itself, which the phase's Risks section asks for by name. The window is half-open
        // (authorized_at > decidedAt - 24h), so an authorization exactly one day old has left it: the
        // decision instant is strictly later than this row's, so 24 hours before the decision is strictly
        // later than 24 hours before now. A 24-hour window that included its own far edge would be 24
        // hours plus one instant wide, and a limit reached exactly on the boundary would never clear.
        recordPastSpend("net-exactly-a-day-ago", 4_000L, Instant.now().minus(Duration.ofHours(24)));

        tap("net-today", pan, TAP_MINOR);

        assertThat(stateOf("net-today")).isEqualTo(AuthorizationState.APPROVED);
    }

    @Test
    void theMonthlyWindowStillRemembersSpendTheDailyWindowHasForgotten() {
        setLimit(LimitPeriod.DAILY, DAILY_LIMIT_MINOR);
        setLimit(LimitPeriod.MONTHLY, 10_000L);
        // Ten days ago: invisible to the daily window, squarely inside the monthly one.
        recordPastSpend("net-last-week", 9_000L, Instant.now().minus(Duration.ofDays(10)));

        tap("net-today", pan, TAP_MINOR);

        // Declined on the monthly ceiling while the daily one had 50.00 of room - the two windows are
        // evaluated independently, and the first breach of any of them refuses the tap.
        assertThat(declineReasonOf("net-today")).isEqualTo(DeclineReason.LIMIT_EXCEEDED);
    }

    @Test
    void spendOlderThanTheRollingMonthHasLeftThatWindowToo() {
        setLimit(LimitPeriod.MONTHLY, 10_000L);
        recordPastSpend("net-last-month", 9_000L, Instant.now().minus(Duration.ofDays(31)));

        tap("net-today", pan, TAP_MINOR);

        assertThat(stateOf("net-today")).isEqualTo(AuthorizationState.APPROVED);
    }

    @Test
    void aPerTransactionCeilingRefusesOneLargeTapWhileTheDailyWindowStillHasRoom() {
        setLimit(LimitPeriod.PER_TRANSACTION, 2_500L);
        setLimit(LimitPeriod.DAILY, 9_000L);

        tap("net-too-big", pan, 3_000L);
        assertThat(declineReasonOf("net-too-big")).isEqualTo(DeclineReason.LIMIT_EXCEEDED);

        // The daily window had 90.00 of room throughout: this is a ceiling on one amount, not on a total,
        // and it reads no history at all.
        tap("net-small-enough", pan, TAP_MINOR);
        assertThat(stateOf("net-small-enough")).isEqualTo(AuthorizationState.APPROVED);
    }

    @Test
    void refusedTapsDoNotConsumeTheLimitTheyWereRefusedBy() {
        setLimit(LimitPeriod.DAILY, DAILY_LIMIT_MINOR);
        // A decline held nothing and moved nothing, so it must not count as spend - otherwise a holder
        // who mistyped an amount, or was declined for insufficient funds, would be locked out of their
        // own remaining limit by purchases that never happened.
        recordPastDecline("net-refused", 4_000L, DeclineReason.INSUFFICIENT_FUNDS,
                Instant.now().minus(Duration.ofHours(1)));

        tap("net-today", pan, TAP_MINOR);

        assertThat(stateOf("net-today")).isEqualTo(AuthorizationState.APPROVED);
    }

    @Test
    void aCardWithNoConfiguredLimitHasNoLimitRatherThanALimitOfZero() {
        tap("net-unlimited", pan, WALLET_BALANCE_MINOR);

        assertThat(stateOf("net-unlimited")).isEqualTo(AuthorizationState.APPROVED);
    }

    @Test
    void aLimitDeclineRecordsTheBillingAmountAndPublishesTheReasonKeyedByCardId() {
        setLimit(LimitPeriod.PER_TRANSACTION, 2_500L);

        tap("net-published", pan, 3_000L);

        CardAuthorization declined = authorizations.findByNetworkRef("net-published").orElseThrow();
        // The decline was reached at step 5, after the billing amount was known, so the row records what
        // the wallet would have been charged - the per-card feed needs to show the holder the amount they
        // were refused, not a blank.
        assertThat(declined.getAmountMinor()).isEqualTo(3_000L);
        assertThat(declined.getBillingMinor()).isEqualTo(3_000L);
        assertThat(declined.getBillingCurrency()).isEqualTo(EUR);
        assertThat(declined.getReservationId()).isNull();
        assertThat(declined.getLedgerEntryId()).isNull();

        List<OutboxEvent> rows = outbox.findAll();
        assertThat(rows).hasSize(1);
        OutboxEvent row = rows.getFirst();
        assertThat(row.getType()).isEqualTo("AuthorizationDeclinedV1");
        assertThat(row.getPartitionKey()).isEqualTo(card.getId().toString());

        JsonNode payload = jsonMapper.readTree(row.getPayload()).get("payload");
        assertThat(payload.get("declineReason").asString()).isEqualTo("LIMIT_EXCEEDED");
        // The network is told the issuer refused. Which ceiling, and how much of it was already spent,
        // stays on our side of the line - see LimitBreach.
        assertThat(row.getPayload()).doesNotContain("2500").doesNotContain("PER_TRANSACTION");
    }
}
