package com.revolutclone.simulator.card;

import com.revolutclone.contracts.events.AuthorizationRequestedV1;
import com.revolutclone.contracts.events.ReversalRequestedV1;
import com.revolutclone.contracts.events.SettlementRequestedV1;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * plans/phase-05-cards.md task 8: the merchant network simulator's spend endpoints and its
 * controllable clock, wired together. Everything this class does ends in a Kafka publish - it holds
 * no database, only {@link SpendRegistry}'s in-memory bookkeeping of what it has told the network
 * about and not yet heard the end of.
 *
 * <p><strong>A settlement batch is next-day, not next-instant.</strong> A single spend settles on
 * {@code currentDay + 1} - the realistic T+1 a real scheme batches on - so the first
 * {@code /simulator/clock/advance-day} call after a spend is always the one that concludes it,
 * matching the phase's own Verification walkthrough exactly. A batch spreads its authorizations
 * over {@code currentDay + 1} through {@code currentDay + 3} instead of settling them all at once,
 * so repeated {@code advance-day} calls settle a growing feed gradually rather than all together -
 * plans/phase-05-cards.md's "N auths over a date range."
 *
 * <p><strong>Settlement here is always exact.</strong> Partial, over-settlement and reversal are
 * already exhaustively covered by card-service's own {@code SettlementService} tests (task 05.6);
 * this class's job is only to be able to trigger the settlement and reversal paths at all, which an
 * exact settlement and an explicit {@link #reverse} both do without adding variability nobody asked
 * this class to get right.
 */
@Component
public class CardSpendService {

    private final CardNetworkPanClient panClient;
    private final CardNetworkEventPublisher publisher;
    private final SpendRegistry registry;
    private final SimulatedClock clock;
    private final RandomGenerator random;

    @Autowired
    public CardSpendService(CardNetworkPanClient panClient, CardNetworkEventPublisher publisher,
            SpendRegistry registry, SimulatedClock clock) {
        this(panClient, publisher, registry, clock, new Random());
    }

    CardSpendService(CardNetworkPanClient panClient, CardNetworkEventPublisher publisher,
            SpendRegistry registry, SimulatedClock clock, RandomGenerator random) {
        this.panClient = panClient;
        this.publisher = publisher;
        this.registry = registry;
        this.clock = clock;
        this.random = random;
    }

    /** One authorization, right now. Merchant, amount and currency default from the catalogue. */
    public SpendResult spend(SpendCommand command) {
        return spendOne(command, clock.currentDay() + 1);
    }

    /**
     * {@code count} independent authorizations for one card, random merchants, spread across a
     * settlement date range rather than all due on the same {@code advance-day} call.
     */
    public List<SpendResult> spendBatch(UUID cardId, int count) {
        List<SpendResult> results = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int settleOnDay = clock.currentDay() + 1 + random.nextInt(3); // +1..+3
            results.add(spendOne(new SpendCommand(cardId, null, null, null), settleOnDay));
        }
        return results;
    }

    private SpendResult spendOne(SpendCommand command, int settleOnDay) {
        String pan = panClient.panFor(command.cardId());
        Merchant merchant = resolveMerchant(command.merchantName(), random);
        long amountMinor = command.amountMinor() != null ? command.amountMinor() : merchant.randomAmountMinor(random);
        String currency = command.currency() != null ? command.currency() : merchant.currency();
        String networkRef = "sim-" + UUID.randomUUID();

        registry.record(networkRef, new PendingSpend(command.cardId(), merchant.name(), merchant.mcc(),
                merchant.country(), amountMinor, currency, settleOnDay));

        publisher.authorizationRequested(command.cardId(), new AuthorizationRequestedV1(
                networkRef, pan, amountMinor, currency, merchant.name(), merchant.mcc(), merchant.country()));

        return new SpendResult(networkRef, command.cardId(), merchant.name(), merchant.mcc(),
                merchant.country(), amountMinor, currency, settleOnDay);
    }

    /** The merchant will not be claiming this hold after all - see {@code ReversalRequestedV1}. */
    public void reverse(String networkRef) {
        PendingSpend spend = registry.conclude(networkRef)
                .orElseThrow(() -> new UnknownNetworkRefException(networkRef));
        publisher.reversalRequested(spend.cardId(), new ReversalRequestedV1(networkRef));
    }

    /**
     * Settles every authorization due by the new day - exactly the authorized amount, per this
     * class's javadoc. Idempotent by construction: a settled entry leaves {@link SpendRegistry}
     * the instant it is collected, so it cannot be found, let alone settled, a second time.
     */
    public AdvanceDayResult advanceDay() {
        int day = clock.advance();
        int settled = 0;
        for (Map.Entry<String, PendingSpend> entry : registry.dueBy(day)) {
            String networkRef = entry.getKey();
            PendingSpend spend = entry.getValue();
            publisher.settlementRequested(spend.cardId(),
                    new SettlementRequestedV1(networkRef, spend.amountMinor(), spend.currency()));
            settled++;
        }
        return new AdvanceDayResult(day, settled);
    }

    private static Merchant resolveMerchant(String merchantName, RandomGenerator random) {
        if (merchantName == null) {
            return MerchantCatalogue.random(random);
        }
        return MerchantCatalogue.byName(merchantName)
                // An ad-hoc name (not in the catalogue) is still allowed - misc/5999, home country -
                // rather than refused, so a caller can name any merchant it likes.
                .orElseGet(() -> new Merchant(merchantName, "5999", "IE", "EUR", 500, 20_000));
    }
}
