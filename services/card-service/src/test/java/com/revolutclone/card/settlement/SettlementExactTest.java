package com.revolutclone.card.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.card.domain.AuthorizationState;
import com.revolutclone.card.domain.CardAuthorization;
import com.revolutclone.card.ledger.SettleFundsCommand;
import com.revolutclone.card.testsupport.SettlementTestSupport;
import com.revolutclone.messaging.outbox.OutboxEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * plans/phase-05-cards.md's Tests table: <em>"SettlementExactTest - Hold captured; posting created;
 * available and total now agree."</em>
 *
 * <p>That last clause is the phase in one sentence. An approval left the two figures apart - available
 * down, total untouched - because a hold is a promise and no accounting had happened. A settlement is
 * what makes them agree again, and the reason they can is that the money finally left {@code total}.
 * Every assertion below is checked before <em>and</em> after the settlement, because the interesting
 * fact is the change and not either state on its own.
 */
class SettlementExactTest extends SettlementTestSupport {

    /** 42.50 EUR - the phase's own Verification amount. */
    private static final long SPEND_MINOR = 4_250L;

    @Test
    void anExactSettlementCapturesTheHoldAndMakesAvailableAndTotalAgreeAgain() {
        tap("net-5001", pan, SPEND_MINOR);

        // The pending state: the holder's available balance has dropped and their total has not.
        assertThat(ledger.availableMinor(ledgerAccountId)).isEqualTo(WALLET_BALANCE_MINOR - SPEND_MINOR);
        assertThat(ledger.totalMinor(ledgerAccountId))
                .as("a hold is not an accounting event - total is untouched while the auth is pending")
                .isEqualTo(WALLET_BALANCE_MINOR);
        assertThat(ledger.heldMinor(reservationOf("net-5001"))).isEqualTo(SPEND_MINOR);

        settle("net-5001", SPEND_MINOR);

        long expected = WALLET_BALANCE_MINOR - SPEND_MINOR;
        assertThat(ledger.totalMinor(ledgerAccountId))
                .as("the money has now actually moved, so total falls for the first time")
                .isEqualTo(expected);
        assertThat(ledger.availableMinor(ledgerAccountId))
                .as("available and total agree again - there is nothing pending left to explain the gap")
                .isEqualTo(expected);
        assertThat(ledger.heldMinor(reservationOf("net-5001")))
                .as("the hold was captured, not left standing")
                .isZero();
    }

    @Test
    void theAuthorizationRowGainsThePostingItDidNotHaveWhenItWasApproved() {
        tap("net-5002", pan, SPEND_MINOR);

        CardAuthorization approved = authorizationOf("net-5002");
        assertThat(approved.getState()).isEqualTo(AuthorizationState.APPROVED);
        assertThat(approved.getLedgerEntryId()).as("an approval holds money and posts nothing").isNull();
        assertThat(approved.getSettledMinor()).isNull();
        assertThat(approved.getSettledAt()).isNull();

        settle("net-5002", SPEND_MINOR);

        CardAuthorization settled = authorizationOf("net-5002");
        assertThat(settled.getState()).isEqualTo(AuthorizationState.SETTLED);
        assertThat(settled.getLedgerEntryId())
                .as("the CARD_SETTLEMENT entry - the first posting this authorization has ever had")
                .isNotNull();
        assertThat(settled.getSettledMinor()).isEqualTo(SPEND_MINOR);
        assertThat(settled.getSettledAt()).isNotNull();
        assertThat(settled.getExcessMinor())
                .as("zero, not null: this settled and took nothing beyond the hold, which is a different "
                        + "fact from not having settled")
                .isZero();
        assertThat(settled.getExcessLedgerEntryId()).isNull();
        // The hold is still named. It is the audit trail of what the posting captured, not a live claim.
        assertThat(settled.getReservationId()).isNotNull();
    }

    @Test
    void theLedgerIsAskedToSettleTheBillingAmountUnderTheNetworksOwnReference() {
        tap("net-5003", pan, SPEND_MINOR);
        settle("net-5003", SPEND_MINOR);

        assertThat(ledger.settlements()).hasSize(1);
        SettleFundsCommand command = ledger.settlements().getFirst();
        assertThat(command.settledMinor())
                .as("a domestic merchant bills what it charges, so the conversion is the identity")
                .isEqualTo(SPEND_MINOR);
        assertThat(command.feeMinor())
                .as("no conversion happened, so there is no mark-up to route to FEE_INCOME")
                .isZero();
        assertThat(command.networkRef())
                .as("the network's reference, from which the ledger derives the cardauth: entry key")
                .isEqualTo("net-5003");
        assertThat(ledger.releases()).as("a settlement captures; only a reversal releases").isEmpty();
    }

    @Test
    void aSettlementPublishesExactlyOneAuthorizationSettledEventKeyedByCardId() {
        tap("net-5004", pan, SPEND_MINOR);
        outbox.deleteAll();

        settle("net-5004", SPEND_MINOR);

        List<OutboxEvent> rows = outbox.findAll();
        assertThat(rows).hasSize(1);

        OutboxEvent row = rows.getFirst();
        assertThat(row.getTopic()).isEqualTo("card.authorization.v1");
        // CLAUDE.md hard rule 5: the settlement is keyed the same way the authorization was, so one
        // partition carries a card's whole history in order.
        assertThat(row.getPartitionKey()).isEqualTo(card.getId().toString());
        assertThat(row.getType()).isEqualTo("AuthorizationSettledV1");

        JsonNode envelope = jsonMapper.readTree(row.getPayload());
        // CLAUDE.md hard rule 4: every event carries these four.
        assertThat(UUID.fromString(envelope.get("eventId").asString())).isNotNull();
        assertThat(Instant.parse(envelope.get("occurredAt").asString())).isNotNull();
        assertThat(envelope.get("correlationId").asString()).isNotBlank();
        assertThat(envelope.get("version").asInt()).isEqualTo(1);

        CardAuthorization settled = authorizationOf("net-5004");
        JsonNode payload = envelope.get("payload");
        assertThat(UUID.fromString(payload.get("authorizationId").asString())).isEqualTo(settled.getId());
        assertThat(UUID.fromString(payload.get("cardId").asString())).isEqualTo(card.getId());
        assertThat(UUID.fromString(payload.get("userId").asString())).isEqualTo(userId);
        assertThat(UUID.fromString(payload.get("accountId").asString())).isEqualTo(accountId);
        assertThat(payload.get("panLast4").asString()).isEqualTo(card.getPanLast4());
        assertThat(payload.get("networkRef").asString()).isEqualTo("net-5004");
        assertThat(payload.get("merchantSettledMinor").asLong()).isEqualTo(SPEND_MINOR);
        assertThat(payload.get("settledMinor").asLong()).isEqualTo(SPEND_MINOR);
        assertThat(payload.get("settledCurrency").asString()).isEqualTo(EUR);
        assertThat(payload.get("releasedMinor").asLong()).isZero();
        assertThat(payload.get("excessMinor").asLong()).isZero();
        assertThat(payload.get("state").asString()).isEqualTo("SETTLED");
        // This field is the whole difference from AuthorizationApprovedV1, which has no posting to name.
        assertThat(UUID.fromString(payload.get("ledgerEntryId").asString()))
                .isEqualTo(settled.getLedgerEntryId());
        assertThat(payload.get("excessLedgerEntryId").isNull()).isTrue();
    }

    @Test
    void theSameSettlementTwiceSettlesOnceAndPostsOnce() {
        tap("net-5005", pan, SPEND_MINOR);
        settle("net-5005", SPEND_MINOR);
        UUID entryId = authorizationOf("net-5005").getLedgerEntryId();
        outbox.deleteAll();

        // The network genuinely resends settlements, and networkRef is the anchor for all of it.
        settle("net-5005", SPEND_MINOR);

        assertThat(ledger.settlements())
                .as("the row was already SETTLED, so the ledger is not asked a second time")
                .hasSize(1);
        assertThat(authorizationOf("net-5005").getLedgerEntryId())
                .as("still the first posting - a second capture would have taken the money twice")
                .isEqualTo(entryId);
        assertThat(ledger.totalMinor(ledgerAccountId)).isEqualTo(WALLET_BALANCE_MINOR - SPEND_MINOR);
        assertThat(outbox.findAll())
                .as("and nothing is republished: a second push would tell the holder they paid twice")
                .isEmpty();
    }

    @Test
    void aForeignCurrencySettlementRoutesTheCardMarkUpToFeeIncomeAndTheRestToCardSettlement() {
        // 50.00 USD on a EUR wallet: the FX path task 05.5 built, settling for the first time here.
        tap("net-5006", pan, 5_000L, USD);

        CardAuthorization approved = authorizationOf("net-5006");
        long billingMinor = approved.getBillingMinor();
        long markupMinor = approved.getFxMarkupMinor();
        assertThat(markupMinor).as("a conversion happened, so there is a mark-up to account for")
                .isGreaterThan(0L);

        settle("net-5006", 5_000L, USD);

        SettleFundsCommand command = ledger.settlements().getFirst();
        assertThat(command.settledMinor())
                .as("settled at the authorization's own rate - the amount the holder saw at the till, "
                        + "not a rate re-quoted days later")
                .isEqualTo(billingMinor);
        assertThat(command.feeMinor())
                .as("the phase's \"mark-up in FEE_INCOME\": this is the leg that puts it there, and it "
                        + "is exactly what task 05.5 recorded on the row")
                .isEqualTo(markupMinor);
        // The two credit legs partition the debit, which is what keeps the three-leg entry balanced.
        assertThat(command.settledMinor() - command.feeMinor())
                .as("the merchant's share, bound for system:card-settlement")
                .isEqualTo(billingMinor - markupMinor);

        assertThat(authorizationOf("net-5006").getState()).isEqualTo(AuthorizationState.SETTLED);
        assertThat(authorizationOf("net-5006").getSettledMinor()).isEqualTo(billingMinor);
    }
}
