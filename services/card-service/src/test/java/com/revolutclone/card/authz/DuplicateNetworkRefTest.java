package com.revolutclone.card.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.card.domain.AuthorizationState;
import com.revolutclone.card.domain.CardAuthorization;
import com.revolutclone.card.domain.CardState;
import com.revolutclone.card.domain.DeclineReason;
import com.revolutclone.card.pan.PanGenerator;
import com.revolutclone.card.testsupport.AuthorizationTestSupport;
import com.revolutclone.contracts.events.AuthorizationRequestedV1;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/**
 * plans/phase-05-cards.md's Tests table: "Same {@code networkRef} twice → one authorization", and its
 * Risks section on why: "{@code networkRef} is your idempotency key for the whole card flow. The network
 * will genuinely resend. Unique-constrain it and test the redelivery."
 *
 * <p><strong>The resend is not the same message.</strong> Each tap below arrives with its own
 * {@code eventId} and its own {@code correlationId} - which is what a real network resend looks like, and
 * exactly why {@code common-messaging}'s {@code IdempotentHandler} is not what guards this path: an
 * {@code eventId} dedupe would see two distinct events and decide twice. The anchor has to be the
 * network's own reference, and it is enforced twice over - read first, unique index second.
 *
 * <p>Two authorizations for one tap would be twice the funds held, twice the push, and a second pending
 * row in the feed that no settlement will ever arrive for.
 */
class DuplicateNetworkRefTest extends AuthorizationTestSupport {

    private static final long SPEND_MINOR = 4_250L;

    @Test
    void theSameNetworkRefDeliveredTwiceProducesOneAuthorizationAndOneHold() {
        tapAsFreshDelivery("net-dup-1", pan, SPEND_MINOR);
        tapAsFreshDelivery("net-dup-1", pan, SPEND_MINOR);

        assertThat(authorizations.findAll()).hasSize(1);
        assertThat(ledger.holds()).as("one tap, one hold").hasSize(1);
        assertThat(ledger.availableMinor(ledgerAccountId))
                .as("available fell once, not twice")
                .isEqualTo(WALLET_BALANCE_MINOR - SPEND_MINOR);
        assertThat(ledger.totalMinor(ledgerAccountId)).isEqualTo(WALLET_BALANCE_MINOR);
    }

    @Test
    void theResendAnnouncesNothing() {
        tapAsFreshDelivery("net-dup-2", pan, SPEND_MINOR);
        assertThat(outbox.findAll()).hasSize(1);

        tapAsFreshDelivery("net-dup-2", pan, SPEND_MINOR);

        // The row and its event were written in one transaction, so an existing row means the event
        // already exists. Republishing would push a second notification and a second feed row at the
        // holder for a purchase they made once.
        assertThat(outbox.findAll()).hasSize(1);
    }

    @Test
    void aResendOfADeclineIsAlsoDecidedOnlyOnce() {
        String frozenPan = new PanGenerator().generate();
        saveCard(frozenPan, CardState.FROZEN, YearMonth.now().plusYears(3));

        tapAsFreshDelivery("net-dup-3", frozenPan, SPEND_MINOR);
        tapAsFreshDelivery("net-dup-3", frozenPan, SPEND_MINOR);

        assertThat(authorizations.findAll()).hasSize(1);
        assertThat(outbox.findAll()).hasSize(1);
        CardAuthorization declined = authorizations.findByNetworkRef("net-dup-3").orElseThrow();
        assertThat(declined.getDeclineReason()).isEqualTo(DeclineReason.CARD_FROZEN);
    }

    @Test
    void aResendCannotChangeADecisionEvenIfItsAmountDiffers() {
        tapAsFreshDelivery("net-dup-4", pan, SPEND_MINOR);

        // A network that resends with a different amount is a network bug, but the decision is already
        // made and already announced: the reference is the identity of the authorization, so the second
        // message is a duplicate of it whatever else it says.
        tapAsFreshDelivery("net-dup-4", pan, SPEND_MINOR * 2);

        CardAuthorization only = authorizations.findByNetworkRef("net-dup-4").orElseThrow();
        assertThat(only.getAmountMinor()).isEqualTo(SPEND_MINOR);
        assertThat(only.getState()).isEqualTo(AuthorizationState.APPROVED);
        assertThat(ledger.holds()).hasSize(1);
        assertThat(ledger.availableMinor(ledgerAccountId)).isEqualTo(WALLET_BALANCE_MINOR - SPEND_MINOR);
    }

    @Test
    void twoGenuinelyDifferentReferencesAreTwoAuthorizations() {
        tapAsFreshDelivery("net-dup-5", pan, SPEND_MINOR);
        tapAsFreshDelivery("net-dup-6", pan, SPEND_MINOR);

        // The guard is the reference, not the card or the amount: a holder buying the same coffee twice
        // must be charged twice.
        assertThat(authorizations.findAll()).hasSize(2);
        assertThat(ledger.holds()).hasSize(2);
        assertThat(ledger.availableMinor(ledgerAccountId)).isEqualTo(WALLET_BALANCE_MINOR - 2 * SPEND_MINOR);
    }

    /**
     * One delivery, with its own envelope. Distinct {@code eventId}/{@code correlationId} per call is the
     * point: this is a resend from the network, not a redelivery of one message by the broker.
     */
    private void tapAsFreshDelivery(String networkRef, String pan, long amountMinor) {
        consumer.handle(envelope(new AuthorizationRequestedV1(
                networkRef, pan, amountMinor, EUR, MERCHANT, MCC, COUNTRY)));
    }
}
