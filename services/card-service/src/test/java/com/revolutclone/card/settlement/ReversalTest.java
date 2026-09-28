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
 * plans/phase-05-cards.md's Tests table: <em>"ReversalTest - Hold released; no posting; balance
 * restored."</em> The phase's settlement table: <em>"Reversal: release the hold, no posting."</em>
 *
 * <p>A reversal is the one settlement outcome that is the <strong>absence</strong> of an accounting
 * event, and the middle clause is therefore the load-bearing one. It is easy to release a hold and then
 * also write a zero-amount entry, or an entry and a reversing entry, "for the audit trail" - both of
 * which would put a transaction in the holder's feed for a purchase that never happened. So the
 * assertions here are as much about what does not exist as about what does.
 */
class ReversalTest extends SettlementTestSupport {

    private static final long SPEND_MINOR = 4_250L;

    @Test
    void reversingReleasesTheHoldAndRestoresTheBalanceExactly() {
        tap("net-5301", pan, SPEND_MINOR);
        assertThat(ledger.availableMinor(ledgerAccountId)).isEqualTo(WALLET_BALANCE_MINOR - SPEND_MINOR);

        reverse("net-5301");

        assertThat(ledger.availableMinor(ledgerAccountId))
                .as("the balance is exactly what it was before the tap - not approximately, and not the "
                        + "settled figure")
                .isEqualTo(WALLET_BALANCE_MINOR);
        assertThat(ledger.totalMinor(ledgerAccountId))
                .as("total never moved in the first place; no money ever left")
                .isEqualTo(WALLET_BALANCE_MINOR);
        assertThat(ledger.heldMinor(reservationOf("net-5301"))).isZero();
        assertThat(ledger.releases())
                .as("released, and released once")
                .containsExactly(reservationOf("net-5301"));
    }

    @Test
    void noPostingIsEverCreated() {
        tap("net-5302", pan, SPEND_MINOR);

        reverse("net-5302");

        CardAuthorization reversed = authorizationOf("net-5302");
        assertThat(reversed.getState()).isEqualTo(AuthorizationState.REVERSED);
        assertThat(reversed.getLedgerEntryId())
                .as("the assertion this test exists for - a reversal is the absence of an accounting "
                        + "event, not a compensating one")
                .isNull();
        assertThat(reversed.getExcessLedgerEntryId()).isNull();
        assertThat(reversed.getSettledMinor()).as("nothing was settled").isNull();
        assertThat(reversed.getExcessMinor()).isNull();
        assertThat(reversed.getSettledAt()).isNull();
        assertThat(ledger.settlements())
                .as("the ledger was never asked to capture anything, at any amount")
                .isEmpty();
    }

    @Test
    void aReversalPublishesAuthorizationReversedWithNoEntryToName() {
        tap("net-5303", pan, SPEND_MINOR);
        outbox.deleteAll();

        reverse("net-5303");

        List<OutboxEvent> rows = outbox.findAll();
        assertThat(rows).hasSize(1);
        OutboxEvent row = rows.getFirst();
        assertThat(row.getTopic()).isEqualTo("card.authorization.v1");
        assertThat(row.getPartitionKey()).isEqualTo(card.getId().toString());
        assertThat(row.getType()).isEqualTo("AuthorizationReversedV1");

        JsonNode payload = jsonMapper.readTree(row.getPayload()).get("payload");
        assertThat(payload.get("networkRef").asString()).isEqualTo("net-5303");
        assertThat(payload.get("releasedMinor").asLong())
                .as("what became available again, so a subscriber can say so without a second lookup")
                .isEqualTo(SPEND_MINOR);
        assertThat(payload.get("releasedCurrency").asString()).isEqualTo(EUR);
        assertThat(payload.has("ledgerEntryId"))
                .as("the event has no such field at all - there is no posting to name, and a null one "
                        + "would invite a consumer to wonder whether it was merely not populated yet")
                .isFalse();
    }

    @Test
    void theReleasedFundsAreGenuinelySpendableAgain() {
        // A hold marked released but not returned to `available` would pass the balance assertions and
        // still decline this tap. Spending the whole balance is the only check that cannot be fooled.
        tap("net-5304", pan, SPEND_MINOR);
        reverse("net-5304");

        tap("net-5305", pan, WALLET_BALANCE_MINOR);

        assertThat(stateOf("net-5305")).isEqualTo(AuthorizationState.APPROVED);
        assertThat(ledger.availableMinor(ledgerAccountId)).isZero();
    }

    @Test
    void reversingTwiceReleasesOnceAndRepublishesNothing() {
        tap("net-5306", pan, SPEND_MINOR);
        reverse("net-5306");
        outbox.deleteAll();

        // The network resends reversals as readily as it resends everything else.
        reverse("net-5306");

        assertThat(ledger.releases())
                .as("the row was already REVERSED, so the ledger is not asked a second time")
                .hasSize(1);
        assertThat(ledger.availableMinor(ledgerAccountId))
                .as("and the balance was not credited twice")
                .isEqualTo(WALLET_BALANCE_MINOR);
        assertThat(outbox.findAll()).isEmpty();
    }

    @Test
    void aSettledAuthorizationCannotBeReversedAwayAfterTheFact() {
        // Once the money has moved, a reversal message is too late: undoing it would mean crediting the
        // wallet with no posting to balance the credit. The scheme's way of undoing a settled purchase
        // is a refund, which is a new authorization in the other direction - not this message.
        tap("net-5307", pan, SPEND_MINOR);
        settle("net-5307", SPEND_MINOR);
        outbox.deleteAll();

        reverse("net-5307");

        assertThat(authorizationOf("net-5307").getState()).isEqualTo(AuthorizationState.SETTLED);
        assertThat(ledger.releases()).isEmpty();
        assertThat(ledger.totalMinor(ledgerAccountId)).isEqualTo(WALLET_BALANCE_MINOR - SPEND_MINOR);
        assertThat(outbox.findAll())
                .as("ignored and committed, not retried - no amount of redelivery could make this work")
                .isEmpty();
    }
}
