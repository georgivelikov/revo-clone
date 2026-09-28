package com.revolutclone.card.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.card.domain.AuthorizationState;
import com.revolutclone.card.domain.CardAuthorization;
import com.revolutclone.card.testsupport.SettlementTestSupport;
import com.revolutclone.messaging.outbox.OutboxEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * plans/phase-05-cards.md's Tests table: <em>"SettlementPartialTest - Lesser capture; difference
 * released; balances exact."</em> The phase's settlement table: <em>"Partial (&lt; auth): capture the
 * lesser amount, release the difference."</em>
 *
 * <p>This is the hotel and the fuel pump: an authorization for a notional amount, settled for what was
 * actually used. The holder must end up out of pocket by the settled amount and not the authorized one,
 * and - the part that is easy to get wrong - the difference has to come back to {@code available}
 * rather than simply being forgotten, or the hold silently becomes a permanent charge on the balance.
 *
 * <p><strong>"Balances exact" is why every assertion is an exact figure rather than a comparison.</strong>
 * A partial settlement touches both balance columns in different directions at once, which is precisely
 * the shape of arithmetic that passes a "did it go down?" check while being wrong by the remainder.
 */
class SettlementPartialTest extends SettlementTestSupport {

    /** 50.00 EUR authorized, 30.00 EUR actually taken, so 20.00 EUR has to come back. */
    private static final long AUTHORIZED_MINOR = 5_000L;
    private static final long SETTLED_MINOR = 3_000L;
    private static final long RELEASED_MINOR = AUTHORIZED_MINOR - SETTLED_MINOR;

    @Test
    void theLesserAmountIsCapturedAndTheDifferenceGoesBackToAvailable() {
        tap("net-5101", pan, AUTHORIZED_MINOR);
        assertThat(ledger.availableMinor(ledgerAccountId)).isEqualTo(WALLET_BALANCE_MINOR - AUTHORIZED_MINOR);

        settle("net-5101", SETTLED_MINOR);

        assertThat(ledger.totalMinor(ledgerAccountId))
                .as("total falls by what was taken, not by what was authorized")
                .isEqualTo(WALLET_BALANCE_MINOR - SETTLED_MINOR);
        assertThat(ledger.availableMinor(ledgerAccountId))
                .as("available is the same figure: the unused " + RELEASED_MINOR + " came back, so the two "
                        + "agree again exactly as they do after an exact settlement")
                .isEqualTo(WALLET_BALANCE_MINOR - SETTLED_MINOR);
        assertThat(ledger.heldMinor(reservationOf("net-5101")))
                .as("nothing is still held - the hold is fully resolved, part captured and part released")
                .isZero();
    }

    @Test
    void theRowSaysPartiallySettledAndRecordsWhatWasActuallyTaken() {
        tap("net-5102", pan, AUTHORIZED_MINOR);

        settle("net-5102", SETTLED_MINOR);

        CardAuthorization settled = authorizationOf("net-5102");
        assertThat(settled.getState())
                .as("PARTIALLY_SETTLED, not SETTLED: the difference between what was authorized and what "
                        + "was taken is a fact the feed and any later dispute both need")
                .isEqualTo(AuthorizationState.PARTIALLY_SETTLED);
        assertThat(settled.getSettledMinor()).isEqualTo(SETTLED_MINOR);
        assertThat(settled.getBillingMinor())
                .as("the authorized amount is not overwritten - both figures stay on the row")
                .isEqualTo(AUTHORIZED_MINOR);
        assertThat(settled.getLedgerEntryId()).as("a lesser capture is still a capture").isNotNull();
        assertThat(settled.getExcessMinor()).isZero();
        assertThat(settled.getExcessLedgerEntryId()).isNull();
    }

    @Test
    void theEventNamesTheReleasedDifferenceSoTheFeedCanExplainTheChange() {
        tap("net-5103", pan, AUTHORIZED_MINOR);
        outbox.deleteAll();

        settle("net-5103", SETTLED_MINOR);

        List<OutboxEvent> rows = outbox.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getType()).isEqualTo("AuthorizationSettledV1");

        JsonNode payload = jsonMapper.readTree(rows.getFirst().getPayload()).get("payload");
        assertThat(payload.get("settledMinor").asLong()).isEqualTo(SETTLED_MINOR);
        assertThat(payload.get("releasedMinor").asLong())
                .as("what went back to the holder - the number that explains why a pending 50.00 became "
                        + "a settled 30.00 rather than simply vanishing")
                .isEqualTo(RELEASED_MINOR);
        assertThat(payload.get("excessMinor").asLong()).isZero();
        assertThat(payload.get("state").asString()).isEqualTo("PARTIALLY_SETTLED");
    }

    @Test
    void aPartialSettlementLeavesTheFreedFundsGenuinelySpendable() {
        // The release is only real if the money can be used. A hold that was marked released but never
        // returned to `available` would pass every balance assertion above and still decline this tap.
        tap("net-5104", pan, AUTHORIZED_MINOR);
        settle("net-5104", SETTLED_MINOR);

        tap("net-5105", pan, WALLET_BALANCE_MINOR - SETTLED_MINOR);

        assertThat(stateOf("net-5105"))
                .as("the whole remaining balance, to the minor unit, is available to spend")
                .isEqualTo(AuthorizationState.APPROVED);
        assertThat(ledger.availableMinor(ledgerAccountId)).isZero();
    }

    @Test
    void aSettlementForTheWholeAuthorizedAmountIsNotPartial() {
        // The boundary: "less than" is strict, so settling exactly what was authorized must not land in
        // the partial branch, and there is nothing to release.
        tap("net-5106", pan, AUTHORIZED_MINOR);
        outbox.deleteAll();

        settle("net-5106", AUTHORIZED_MINOR);

        assertThat(authorizationOf("net-5106").getState()).isEqualTo(AuthorizationState.SETTLED);
        JsonNode payload = jsonMapper.readTree(outbox.findAll().getFirst().getPayload()).get("payload");
        assertThat(payload.get("releasedMinor").asLong()).isZero();
    }
}
