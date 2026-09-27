package com.revolutclone.card.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.card.domain.AuthorizationState;
import com.revolutclone.card.domain.CardAuthorization;
import com.revolutclone.card.ledger.HoldFundsCommand;
import com.revolutclone.card.testsupport.AuthorizationTestSupport;
import com.revolutclone.messaging.outbox.OutboxEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * plans/phase-05-cards.md's Tests table: "Hold created; available drops; total unchanged."
 *
 * <p>That one line is the whole phase in miniature. An approval is <em>not</em> an accounting event:
 * the merchant has no money, no journal entry exists, and the only thing that changed is that some of
 * the holder's balance is no longer spendable. If an authorization created a posting, the row in the
 * feed would be settled the instant it appeared, available and total would never differ, and the
 * distinction this phase exists to model would be gone.
 *
 * <p>The ledger is {@code FakeLedgerReservations}, which enforces the one rule this behaviour depends
 * on - a hold moves money out of {@code available} and leaves {@code total} alone. That the real ledger
 * enforces it against real balance rows is ledger-service's own
 * {@code ReservedFundsAreNotAvailableTest}; card-service's contribution is that it <em>asks</em>, asks
 * for the right amount on the right account, and records a hold rather than a posting.
 */
class AuthorizationApprovedTest extends AuthorizationTestSupport {

    /** 42.50 EUR - the phase's own worked example. */
    private static final long SPEND_MINOR = 4_250L;

    @Test
    void anApprovedAuthorizationHoldsFundsSoAvailableDropsAndTotalDoesNot() {
        tap("net-0001", pan, SPEND_MINOR);

        assertThat(ledger.availableMinor(ledgerAccountId))
                .as("the tap is no longer spendable")
                .isEqualTo(WALLET_BALANCE_MINOR - SPEND_MINOR);
        assertThat(ledger.totalMinor(ledgerAccountId))
                .as("but no money has moved: an authorization is a promise, not a posting")
                .isEqualTo(WALLET_BALANCE_MINOR);
    }

    @Test
    void theHoldIsPlacedOnTheCardsFundingWalletForTheBillingAmountAndExpiresInSevenDays() {
        tap("net-0002", pan, SPEND_MINOR);

        CardAuthorization authorization = authorizations.findByNetworkRef("net-0002").orElseThrow();
        assertThat(ledger.holds()).singleElement().satisfies(hold -> {
            assertThat(hold.ledgerAccountId()).isEqualTo(ledgerAccountId);
            assertThat(hold.amountMinor()).isEqualTo(SPEND_MINOR);
            assertThat(hold.currency()).isEqualTo(EUR);
            // Keyed on the network's own reference, never on anything this service invented: a resend
            // has to land on this hold rather than place a second one against the same balance.
            assertThat(hold.idempotencyKey()).isEqualTo("card-auth:net-0002");
            assertThat(hold.referenceId()).isEqualTo(authorization.getId());
            // "ledger: HOLD billing amount, 7-day expiry" - and the expiry is owned by the ledger, so
            // funds come back even if card-service never does.
            assertThat(hold.expiresAt()).isEqualTo(authorization.getAuthorizedAt().plus(Duration.ofDays(7)));
        });
    }

    @Test
    void theAuthorizationIsRecordedApprovedWithAReservationAndNoPosting() {
        tap("net-0003", pan, SPEND_MINOR);

        CardAuthorization authorization = authorizations.findByNetworkRef("net-0003").orElseThrow();
        assertThat(authorization.getState()).isEqualTo(AuthorizationState.APPROVED);
        assertThat(authorization.getDeclineReason()).isNull();
        assertThat(authorization.getCardId()).isEqualTo(card.getId());
        assertThat(authorization.getMerchantName()).isEqualTo(MERCHANT);
        assertThat(authorization.getMerchantMcc()).isEqualTo(MCC);
        assertThat(authorization.getMerchantCountry()).isEqualTo(COUNTRY);
        assertThat(authorization.getAmountMinor()).isEqualTo(SPEND_MINOR);
        assertThat(authorization.getCurrency()).isEqualTo(EUR);
        // A domestic merchant: billing and merchant amounts are the same number. Task 05.5 is what makes
        // them differ.
        assertThat(authorization.getBillingMinor()).isEqualTo(SPEND_MINOR);
        assertThat(authorization.getBillingCurrency()).isEqualTo(EUR);

        assertThat(authorization.getReservationId()).as("the hold behind it").isNotNull();
        assertThat(authorization.getLedgerEntryId())
                .as("no journal entry exists yet - that is what settlement is for")
                .isNull();
        assertThat(authorization.getSettledAt()).isNull();
    }

    @Test
    void anApprovalPublishesExactlyOneAuthorizationApprovedEventKeyedByCardId() {
        tap("net-0004", pan, SPEND_MINOR);

        List<OutboxEvent> rows = outbox.findAll();
        assertThat(rows).hasSize(1);

        OutboxEvent row = rows.getFirst();
        assertThat(row.getTopic()).isEqualTo("card.authorization.v1");
        // CLAUDE.md hard rule 5, and the phase's own justification for it: keyed by cardId so two taps
        // on one card are evaluated in order against the same limits.
        assertThat(row.getPartitionKey()).isEqualTo(card.getId().toString());
        assertThat(row.getType()).isEqualTo("AuthorizationApprovedV1");

        JsonNode envelope = jsonMapper.readTree(row.getPayload());
        // CLAUDE.md hard rule 4: every event carries these four.
        assertThat(UUID.fromString(envelope.get("eventId").asString())).isNotNull();
        assertThat(Instant.parse(envelope.get("occurredAt").asString())).isNotNull();
        assertThat(envelope.get("correlationId").asString()).isNotBlank();
        assertThat(envelope.get("version").asInt()).isEqualTo(1);

        CardAuthorization authorization = authorizations.findByNetworkRef("net-0004").orElseThrow();
        JsonNode payload = envelope.get("payload");
        assertThat(UUID.fromString(payload.get("authorizationId").asString())).isEqualTo(authorization.getId());
        assertThat(UUID.fromString(payload.get("cardId").asString())).isEqualTo(card.getId());
        assertThat(UUID.fromString(payload.get("userId").asString())).isEqualTo(userId);
        assertThat(UUID.fromString(payload.get("accountId").asString())).isEqualTo(accountId);
        assertThat(payload.get("networkRef").asString()).isEqualTo("net-0004");
        assertThat(payload.get("merchantName").asString()).isEqualTo(MERCHANT);
        assertThat(payload.get("amountMinor").asLong()).isEqualTo(SPEND_MINOR);
        assertThat(payload.get("billingMinor").asLong()).isEqualTo(SPEND_MINOR);
        assertThat(payload.get("billingCurrency").asString()).isEqualTo(EUR);
        assertThat(UUID.fromString(payload.get("reservationId").asString()))
                .isEqualTo(authorization.getReservationId());
        assertThat(payload.get("panLast4").asString()).isEqualTo(card.getPanLast4());

        // The event names the card; it never carries the number. PanNeverLoggedTest (task 05.11) makes
        // the same assertion about log output.
        assertThat(row.getPayload()).doesNotContain(pan);
    }

    @Test
    void twoTapsOnOneCardBothHoldAndAvailableFallsTwice() {
        tap("net-0005", pan, SPEND_MINOR);
        tap("net-0006", pan, SPEND_MINOR);

        assertThat(authorizations.findAll()).hasSize(2);
        assertThat(ledger.holds()).hasSize(2);
        assertThat(ledger.availableMinor(ledgerAccountId)).isEqualTo(WALLET_BALANCE_MINOR - 2 * SPEND_MINOR);
        assertThat(ledger.totalMinor(ledgerAccountId)).isEqualTo(WALLET_BALANCE_MINOR);
    }

    @Test
    void aTapForMoreThanIsAvailableIsDeclinedForInsufficientFundsAndHoldsNothing() {
        tap("net-0007", pan, WALLET_BALANCE_MINOR - 1);

        // The available-funds check is the ledger's, not this service's - card-service holds no balances
        // and must not. What it does is turn the answer into the decline the network understands.
        tap("net-0008", pan, SPEND_MINOR);

        CardAuthorization declined = authorizations.findByNetworkRef("net-0008").orElseThrow();
        assertThat(declined.getState()).isEqualTo(AuthorizationState.DECLINED);
        assertThat(declined.getDeclineReason().name()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(declined.getReservationId()).isNull();
        // The amount it would have been charged is still recorded: the decline happened at step 6, after
        // the billing amount was known.
        assertThat(declined.getBillingMinor()).isEqualTo(SPEND_MINOR);

        assertThat(ledger.holds()).as("only the first tap held anything").hasSize(1);
        assertThat(ledger.availableMinor(ledgerAccountId)).isEqualTo(1L);
    }

    @Test
    void aPanThatMatchesNoIssuedCardIsDeclinedWithNoRowToRecordItAgainst() {
        tap("net-0009", "4000000000000002", SPEND_MINOR);

        // card_authorization.card_id is not null and references card(id), so there is nothing to insert
        // - but the decline is still published, keyed by the network's reference.
        assertThat(authorizations.findAll()).isEmpty();
        assertThat(ledger.holds()).isEmpty();

        List<OutboxEvent> rows = outbox.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getType()).isEqualTo("AuthorizationDeclinedV1");
        assertThat(rows.getFirst().getPartitionKey()).isEqualTo("net-0009");

        JsonNode payload = jsonMapper.readTree(rows.getFirst().getPayload()).get("payload");
        assertThat(payload.get("declineReason").asString()).isEqualTo("CARD_NOT_FOUND");
        assertThat(payload.get("cardId").isNull()).isTrue();
    }

    @Test
    void theHoldCarriesTheSameFieldsTheLedgersOwnHoldRequestDeclares() {
        tap("net-0010", pan, SPEND_MINOR);

        HoldFundsCommand hold = ledger.holds().getFirst();
        assertThat(hold.referenceId()).isNotNull();
        assertThat(hold.idempotencyKey()).isNotBlank();
        assertThat(hold.expiresAt()).isAfter(Instant.now());
    }
}
