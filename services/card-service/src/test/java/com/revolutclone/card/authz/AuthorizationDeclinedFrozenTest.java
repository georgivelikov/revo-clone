package com.revolutclone.card.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.card.domain.AuthorizationState;
import com.revolutclone.card.domain.Card;
import com.revolutclone.card.domain.CardAuthorization;
import com.revolutclone.card.domain.CardState;
import com.revolutclone.card.domain.DeclineReason;
import com.revolutclone.card.pan.PanGenerator;
import com.revolutclone.card.testsupport.AuthorizationTestSupport;
import com.revolutclone.messaging.outbox.OutboxEvent;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * plans/phase-05-cards.md's Tests table: "Frozen card declines; no hold."
 *
 * <p>"No hold" is the assertion that matters. A decline that still reserved funds would take money out
 * of a holder's available balance for a purchase that never happened, and nothing downstream would ever
 * release it - the settlement path only ever hears about approvals. Step 2 of the chain sits before step
 * 6 precisely so that cannot happen, and this asserts the ordering rather than merely the answer.
 *
 * <p>Step 3 (expiry) is tested here beside it: the flow names the frozen case because that is the one
 * the UI drives ("Freeze the card and spend again - an instant decline with the reason"), but "this card
 * cannot be used" is one shape of decline with three causes, and they have to agree that none of them
 * touches a balance.
 */
class AuthorizationDeclinedFrozenTest extends AuthorizationTestSupport {

    private static final long SPEND_MINOR = 4_250L;

    @Test
    void aFrozenCardIsDeclinedAndNothingIsHeld() {
        String frozenPan = issue(CardState.FROZEN, YearMonth.now().plusYears(3));

        tap("net-frozen-1", frozenPan, SPEND_MINOR);

        CardAuthorization declined = authorizations.findByNetworkRef("net-frozen-1").orElseThrow();
        assertThat(declined.getState()).isEqualTo(AuthorizationState.DECLINED);
        assertThat(declined.getDeclineReason()).isEqualTo(DeclineReason.CARD_FROZEN);
        assertThat(declined.getReservationId()).as("a decline never holds funds").isNull();
        assertThat(declined.getLedgerEntryId()).isNull();
        // The chain stopped at step 2, before the merchant amount had been converted to a billing amount,
        // so there is no billing amount to record.
        assertThat(declined.getBillingMinor()).isNull();
        assertThat(declined.getBillingCurrency()).isNull();

        assertThat(ledger.holds()).as("the ledger was never even asked").isEmpty();
        assertThat(ledger.availableMinor(ledgerAccountId)).isEqualTo(WALLET_BALANCE_MINOR);
        assertThat(ledger.totalMinor(ledgerAccountId)).isEqualTo(WALLET_BALANCE_MINOR);
    }

    @Test
    void theDeclinePublishesItsReasonKeyedByCardId() {
        String frozenPan = issue(CardState.FROZEN, YearMonth.now().plusYears(3));
        Card frozen = cards.findByPanHash(panHasher.hash(frozenPan)).orElseThrow();

        tap("net-frozen-2", frozenPan, SPEND_MINOR);

        List<OutboxEvent> rows = outbox.findAll();
        assertThat(rows).hasSize(1);

        OutboxEvent row = rows.getFirst();
        assertThat(row.getTopic()).isEqualTo("card.authorization.v1");
        assertThat(row.getPartitionKey()).isEqualTo(frozen.getId().toString());
        assertThat(row.getType()).isEqualTo("AuthorizationDeclinedV1");

        JsonNode envelope = jsonMapper.readTree(row.getPayload());
        assertThat(UUID.fromString(envelope.get("eventId").asString())).isNotNull();
        assertThat(envelope.get("correlationId").asString()).isNotBlank();

        JsonNode payload = envelope.get("payload");
        // The reason travels with the event: notification-service turns this into the push the holder
        // sees, and "declined" with no reason is exactly what makes a real card feel broken.
        assertThat(payload.get("declineReason").asString()).isEqualTo("CARD_FROZEN");
        assertThat(UUID.fromString(payload.get("cardId").asString())).isEqualTo(frozen.getId());
        assertThat(UUID.fromString(payload.get("userId").asString())).isEqualTo(userId);
        assertThat(payload.get("amountMinor").asLong()).isEqualTo(SPEND_MINOR);
        assertThat(payload.get("merchantName").asString()).isEqualTo(MERCHANT);
        assertThat(row.getPayload()).doesNotContain(frozenPan);
    }

    @Test
    void freezingOneCardDoesNotAffectAnotherOnTheSameWallet() {
        String frozenPan = issue(CardState.FROZEN, YearMonth.now().plusYears(3));

        tap("net-frozen-3", frozenPan, SPEND_MINOR);
        tap("net-active-1", pan, SPEND_MINOR);

        // State is a property of the card, not of the wallet behind it - the seeded card is still ACTIVE
        // and still spends.
        assertThat(authorizations.findByNetworkRef("net-active-1").orElseThrow().getState())
                .isEqualTo(AuthorizationState.APPROVED);
        assertThat(ledger.holds()).hasSize(1);
        assertThat(ledger.availableMinor(ledgerAccountId)).isEqualTo(WALLET_BALANCE_MINOR - SPEND_MINOR);
    }

    @Test
    void aCancelledCardIsDeclinedAsCancelledRatherThanAsFrozen() {
        String cancelledPan = issue(CardState.CANCELLED, YearMonth.now().plusYears(3));

        tap("net-cancelled-1", cancelledPan, SPEND_MINOR);

        // Reporting this as CARD_FROZEN would invite the holder to unfreeze a card that can never be
        // unfrozen.
        assertThat(authorizations.findByNetworkRef("net-cancelled-1").orElseThrow().getDeclineReason())
                .isEqualTo(DeclineReason.CARD_CANCELLED);
        assertThat(ledger.holds()).isEmpty();
    }

    @Test
    void aCardWhoseExpiryMonthHasPassedIsDeclinedAsExpiredAndHoldsNothing() {
        String expiredPan = issue(CardState.ACTIVE, YearMonth.now().minusMonths(1));

        tap("net-expired-1", expiredPan, SPEND_MINOR);

        CardAuthorization declined = authorizations.findByNetworkRef("net-expired-1").orElseThrow();
        assertThat(declined.getDeclineReason()).isEqualTo(DeclineReason.CARD_EXPIRED);
        assertThat(declined.getReservationId()).isNull();
        assertThat(ledger.holds()).isEmpty();
        assertThat(ledger.availableMinor(ledgerAccountId)).isEqualTo(WALLET_BALANCE_MINOR);
    }

    @Test
    void aCardInItsExpiryMonthIsStillGood() {
        // "Valid thru 12/30" means the whole of December 2030, not the first instant of it - an embossed
        // card works on the last day of its expiry month, and so must this one.
        String expiringThisMonthPan = issue(CardState.ACTIVE, YearMonth.now());

        tap("net-expiring-1", expiringThisMonthPan, SPEND_MINOR);

        assertThat(authorizations.findByNetworkRef("net-expiring-1").orElseThrow().getState())
                .isEqualTo(AuthorizationState.APPROVED);
        assertThat(ledger.holds()).hasSize(1);
    }

    /** A second card on the same wallet, in the given state, and its PAN. */
    private String issue(CardState state, YearMonth expiry) {
        String issuedPan = new PanGenerator().generate();
        saveCard(issuedPan, state, expiry);
        return issuedPan;
    }
}
