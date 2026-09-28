package com.revolutclone.card.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.card.domain.AuthorizationState;
import com.revolutclone.card.domain.CardAuthorization;
import com.revolutclone.card.testsupport.SettlementTestSupport;
import com.revolutclone.messaging.outbox.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * plans/phase-05-cards.md's Tests table: <em>"OverSettlementTest - Excess posted; wallet may go
 * negative; flagged, not silently swallowed."</em> The phase's settlement table: <em>"Over-settlement
 * (tip): capture the hold, post the excess directly - may overdraw; allow it and flag."</em>
 *
 * <p>This is the restaurant: authorize the bill, settle the bill plus a tip. The phase is explicit
 * about why it is worth building - <em>"it is the one path that can legitimately push a wallet negative,
 * and it forces you to confront the fact that 'never negative' is a policy, not a law"</em> - and about
 * the trap: <em>"the ledger must allow this specific path to overdraw, which means the rule is a policy
 * parameter on the operation, not a hard constraint on the account type. Get this right or you will be
 * tempted to weaken the constraint globally, which undoes Phase 02."</em>
 *
 * <p>So the assertions come in two halves. The excess must be posted and the wallet must be allowed
 * below zero; and the very next ordinary authorization on that same wallet must still be refused for
 * insufficient funds, which is what proves the rule was bent for one entry rather than repealed.
 */
class OverSettlementTest extends SettlementTestSupport {

    /** 90.00 EUR authorized on a 100.00 EUR wallet, 120.00 EUR settled - a 30.00 EUR tip. */
    private static final long AUTHORIZED_MINOR = 9_000L;
    private static final long SETTLED_MINOR = 12_000L;
    private static final long EXCESS_MINOR = SETTLED_MINOR - AUTHORIZED_MINOR;

    @Test
    void theExcessIsPostedAndTheWalletIsAllowedToGoNegative() {
        tap("net-5201", pan, AUTHORIZED_MINOR);
        assertThat(ledger.availableMinor(ledgerAccountId))
                .isEqualTo(WALLET_BALANCE_MINOR - AUTHORIZED_MINOR);

        settle("net-5201", SETTLED_MINOR);

        // 100.00 held 90.00 and settled 120.00, so the wallet is 20.00 overdrawn. Nothing about that is
        // an error: the merchant has already taken the money, and refusing to record it would only make
        // the bank's books disagree with the scheme's.
        long expected = WALLET_BALANCE_MINOR - SETTLED_MINOR;
        assertThat(expected).as("the fixture has to actually produce an overdraw or this proves nothing")
                .isNegative();
        assertThat(ledger.totalMinor(ledgerAccountId))
                .as("total falls by the whole settled amount, hold or no hold")
                .isEqualTo(expected);
        assertThat(ledger.availableMinor(ledgerAccountId))
                .as("and so does available - the excess is an ordinary debit, not a capture, because no "
                        + "hold covers it")
                .isEqualTo(expected);
    }

    @Test
    void theExcessIsFlaggedOnTheRowAndNotSilentlySwallowed() {
        tap("net-5202", pan, AUTHORIZED_MINOR);

        settle("net-5202", SETTLED_MINOR);

        CardAuthorization settled = authorizationOf("net-5202");
        assertThat(settled.getExcessMinor())
                .as("the flag the phase asks for: a queryable column, so the one path that can overdraw "
                        + "a wallet is not something only a log line remembers")
                .isEqualTo(EXCESS_MINOR);
        assertThat(settled.getExcessLedgerEntryId())
                .as("a second entry, because the excess is not a capture of anything - the hold was for "
                        + "less than this")
                .isNotNull();
        assertThat(settled.getLedgerEntryId())
                .as("and the capture is still its own entry")
                .isNotNull();
        assertThat(settled.getExcessLedgerEntryId()).isNotEqualTo(settled.getLedgerEntryId());
        assertThat(settled.getSettledMinor()).isEqualTo(SETTLED_MINOR);
        assertThat(settled.getState())
                .as("SETTLED, not PARTIALLY_SETTLED: the authorization was settled in full and then some")
                .isEqualTo(AuthorizationState.SETTLED);
    }

    @Test
    void theEventCarriesTheExcessSoASubscriberCanSeeItToo() {
        tap("net-5203", pan, AUTHORIZED_MINOR);
        outbox.deleteAll();

        settle("net-5203", SETTLED_MINOR);

        List<OutboxEvent> rows = outbox.findAll();
        assertThat(rows).hasSize(1);
        JsonNode payload = jsonMapper.readTree(rows.getFirst().getPayload()).get("payload");
        assertThat(payload.get("excessMinor").asLong())
                .as("on the wire, where a push notification or an analytics projection can react to it - "
                        + "the other half of \"flagged, not silently swallowed\"")
                .isEqualTo(EXCESS_MINOR);
        assertThat(UUID.fromString(payload.get("excessLedgerEntryId").asString()))
                .isEqualTo(authorizationOf("net-5203").getExcessLedgerEntryId());
        assertThat(payload.get("settledMinor").asLong()).isEqualTo(SETTLED_MINOR);
        assertThat(payload.get("releasedMinor").asLong())
                .as("nothing was released - the whole hold was captured and then exceeded")
                .isZero();
    }

    @Test
    void overdrawingOnceDoesNotMakeTheWalletOverdrawable() {
        // The assertion the phase's gotcha is really about. If the excess had been posted by relaxing
        // the never-negative rule for USER_WALLET, or by making the account type overdraftable, this
        // next tap would be approved - and Phase 02's constraint would be gone.
        tap("net-5204", pan, AUTHORIZED_MINOR);
        settle("net-5204", SETTLED_MINOR);
        assertThat(ledger.availableMinor(ledgerAccountId)).isNegative();

        tap("net-5205", pan, 100L);

        assertThat(stateOf("net-5205"))
                .as("an ordinary authorization on an overdrawn wallet is still refused - the policy was "
                        + "set on one entry, not on the account")
                .isEqualTo(AuthorizationState.DECLINED);
        assertThat(declineReasonOf("net-5205").name()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void anOrdinarySettlementCarriesNoFlagAtAll() {
        // The flag has to mean something, which it only does if it is absent when nothing unusual
        // happened. A column that read 0 on every settlement would be noise, not a flag.
        tap("net-5206", pan, AUTHORIZED_MINOR);

        settle("net-5206", AUTHORIZED_MINOR);

        CardAuthorization settled = authorizationOf("net-5206");
        assertThat(settled.getExcessMinor()).isZero();
        assertThat(settled.getExcessLedgerEntryId()).isNull();
        assertThat(ledger.availableMinor(ledgerAccountId)).isNotNegative();
    }
}
