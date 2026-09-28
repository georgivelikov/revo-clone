package com.revolutclone.simulator.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.revolutclone.contracts.events.AuthorizationRequestedV1;
import com.revolutclone.contracts.events.ReversalRequestedV1;
import com.revolutclone.contracts.events.SettlementRequestedV1;
import com.revolutclone.messaging.api.EventPublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * plans/phase-05-cards.md task 8: the spend/batch/reverse/advance-day orchestration, driven with no
 * broker and no card-service - {@link FakeCardNetworkPanClient} stands in for the one synchronous
 * hop, and {@link EventPublisher} is a Mockito mock so every published event can be inspected, same
 * style as {@code FxRateTickPublisherTest}.
 */
class CardSpendServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final EventPublisher eventPublisher = mock();
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
    private final CardNetworkEventPublisher publisher =
            new CardNetworkEventPublisher(eventPublisher, jsonMapper, CLOCK);
    private final FakeCardNetworkPanClient panClient = new FakeCardNetworkPanClient();
    private final SpendRegistry registry = new SpendRegistry();
    private final SimulatedClock clock = new SimulatedClock();

    private UUID cardId;
    private String pan;

    @BeforeEach
    void seed() {
        cardId = UUID.randomUUID();
        pan = "4000000000001091";
        panClient.register(cardId, pan);
    }

    private CardSpendService service(Random random) {
        return new CardSpendService(panClient, publisher, registry, clock, random);
    }

    @Test
    void spendPublishesAuthorizationRequestedKeyedByCardIdWithTheGivenFields() {
        CardSpendService service = service(new Random(1));

        SpendResult result = service.spend(new SpendCommand(cardId, "Tesco", 4_250L, "EUR"));

        assertThat(result.merchantName()).isEqualTo("Tesco");
        assertThat(result.amountMinor()).isEqualTo(4_250L);
        assertThat(result.currency()).isEqualTo("EUR");
        assertThat(result.settleOnDay()).isEqualTo(1);

        AuthorizationRequestedV1 event = capturePayload(AuthorizationRequestedV1.class, "card.authorization.v1",
                cardId.toString());
        assertThat(event.networkRef()).isEqualTo(result.networkRef());
        assertThat(event.pan()).isEqualTo(pan);
        assertThat(event.amountMinor()).isEqualTo(4_250L);
        assertThat(event.currency()).isEqualTo("EUR");
        assertThat(event.merchantName()).isEqualTo("Tesco");
        assertThat(event.merchantMcc()).isEqualTo("5411");
        assertThat(event.merchantCountry()).isEqualTo("IE");
    }

    @Test
    void spendWithNoMerchantAmountOrCurrencyFillsInFromTheCatalogue() {
        CardSpendService service = service(new Random(1));

        SpendResult result = service.spend(new SpendCommand(cardId, null, null, null));

        Merchant merchant = MerchantCatalogue.byName(result.merchantName()).orElseThrow();
        assertThat(result.currency()).isEqualTo(merchant.currency());
        assertThat(result.amountMinor()).isBetween(merchant.minAmountMinor(), merchant.maxAmountMinor());
    }

    @Test
    void spendOnAnUnknownCardPublishesNothing() {
        CardSpendService service = service(new Random(1));
        UUID unknownCard = UUID.randomUUID();

        assertThatThrownBy(() -> service.spend(new SpendCommand(unknownCard, "Tesco", 1_000L, "EUR")))
                .isInstanceOf(UnknownCardException.class);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void spendBatchCreatesCountIndependentResultsWithUniqueNetworkRefs() {
        CardSpendService service = service(new Random(1));

        List<SpendResult> results = service.spendBatch(cardId, 10);

        assertThat(results).hasSize(10);
        assertThat(results.stream().map(SpendResult::networkRef).distinct()).hasSize(10);
        assertThat(results).allSatisfy(result -> {
            assertThat(result.cardId()).isEqualTo(cardId);
            assertThat(result.settleOnDay()).isBetween(1, 3);
        });
        verify(eventPublisher, times(10)).publish(eq("card.authorization.v1"), eq(cardId.toString()), any());
    }

    @Test
    void reversePublishesReversalRequestedKeyedByTheOriginalCardId() {
        CardSpendService service = service(new Random(1));
        SpendResult spent = service.spend(new SpendCommand(cardId, "Tesco", 4_250L, "EUR"));

        service.reverse(spent.networkRef());

        ReversalRequestedV1 event = capturePayload(ReversalRequestedV1.class, "card.authorization.v1",
                cardId.toString());
        assertThat(event.networkRef()).isEqualTo(spent.networkRef());
    }

    @Test
    void reversingAnUnknownNetworkRefThrows() {
        CardSpendService service = service(new Random(1));

        assertThatThrownBy(() -> service.reverse("sim-" + UUID.randomUUID()))
                .isInstanceOf(UnknownNetworkRefException.class);
    }

    @Test
    void reversingTheSameNetworkRefTwiceThrowsTheSecondTime() {
        CardSpendService service = service(new Random(1));
        SpendResult spent = service.spend(new SpendCommand(cardId, "Tesco", 4_250L, "EUR"));

        service.reverse(spent.networkRef());

        assertThatThrownBy(() -> service.reverse(spent.networkRef()))
                .isInstanceOf(UnknownNetworkRefException.class);
    }

    @Test
    void advanceDaySettlesExactlyWhatsDueAndIsIdempotent() {
        CardSpendService service = service(new Random(1));
        SpendResult spent = service.spend(new SpendCommand(cardId, "Tesco", 4_250L, "EUR"));

        AdvanceDayResult firstAdvance = service.advanceDay();
        assertThat(firstAdvance.day()).isEqualTo(1);
        assertThat(firstAdvance.settledCount()).isEqualTo(1);

        SettlementRequestedV1 event = capturePayload(SettlementRequestedV1.class, "card.authorization.v1",
                cardId.toString());
        assertThat(event.networkRef()).isEqualTo(spent.networkRef());
        assertThat(event.settledAmountMinor()).isEqualTo(4_250L);
        assertThat(event.currency()).isEqualTo("EUR");

        AdvanceDayResult secondAdvance = service.advanceDay();
        assertThat(secondAdvance.day()).isEqualTo(2);
        assertThat(secondAdvance.settledCount()).isZero();
        // Exactly two publishes total across both calls: the original authorization and its one
        // settlement - the second advance-day adds nothing.
        verify(eventPublisher, times(2)).publish(eq("card.authorization.v1"), any(), any());
    }

    @Test
    void advanceDaySettlesOnlyTheEntriesDueByEachDayOfABatchsSpread() {
        // A batch settle-day is currentDay + 1..3 (CardSpendService's javadoc). Rather than assume
        // any particular seed produces a mixed spread, this drives the spread itself with a fake
        // RandomGenerator whose jitter is fully known, so every day's expected count is exact.
        CardSpendService service = new CardSpendService(panClient, publisher, registry, clock,
                new FixedJitterRandom(new int[] {0, 1, 2, 0, 1, 2, 0, 1, 2, 0}));

        List<SpendResult> results = service.spendBatch(cardId, 10);
        assertThat(results.stream().map(SpendResult::settleOnDay).toList())
                .containsExactly(1, 2, 3, 1, 2, 3, 1, 2, 3, 1);

        assertThat(service.advanceDay().settledCount()).as("day 1: the four settleOnDay=1 entries").isEqualTo(4);
        assertThat(service.advanceDay().settledCount()).as("day 2: the three settleOnDay=2 entries").isEqualTo(3);
        assertThat(service.advanceDay().settledCount()).as("day 3: the three settleOnDay=3 entries").isEqualTo(3);
        assertThat(service.advanceDay().settledCount()).as("day 4: nothing left").isZero();
    }

    /**
     * A {@link RandomGenerator} whose {@code nextInt(3)} draws (the settle-day jitter, and only
     * that - {@link MerchantCatalogue#random} draws with a different bound) come from a fixed
     * sequence instead of a real generator's, so a batch's exact settle-day spread is known rather
     * than assumed of whatever a seed happens to produce. Everything else still comes from a real
     * generator, via {@code nextLong()}.
     */
    private static final class FixedJitterRandom implements RandomGenerator {

        private final Random backing = new Random(1);
        private final int[] jitterSequence;
        private int jitterIndex;

        FixedJitterRandom(int[] jitterSequence) {
            this.jitterSequence = jitterSequence;
        }

        @Override
        public long nextLong() {
            return backing.nextLong();
        }

        @Override
        public int nextInt(int bound) {
            if (bound == 3) {
                return jitterSequence[jitterIndex++];
            }
            return RandomGenerator.super.nextInt(bound);
        }
    }

    /**
     * The most recent publish to {@code topic}/{@code key} whose envelope names {@code type} - not
     * simply the most recent publish, since several of these tests trigger more than one event
     * (a spend, then its own reversal or settlement) on the same topic and key.
     */
    private <T> T capturePayload(Class<T> type, String topic, String key) {
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventPublisher, atLeastOnce()).publish(eq(topic), eq(key), payloadCaptor.capture());

        return payloadCaptor.getAllValues().stream()
                .map(jsonMapper::readTree)
                .filter(envelope -> envelope.get("type").asString().equals(type.getSimpleName()))
                .reduce((first, second) -> second)
                .map(envelope -> jsonMapper.treeToValue(envelope.get("payload"), type))
                .orElseThrow();
    }
}
