package com.revolutclone.contracts;

import com.revolutclone.contracts.events.AccountOpenedV1;
import com.revolutclone.contracts.events.EntryPostedV1;
import com.revolutclone.contracts.events.EntryRejectedV1;
import com.revolutclone.contracts.events.FxRateTickV1;
import com.revolutclone.contracts.events.LedgerAccountCreatedV1;
import com.revolutclone.contracts.events.LedgerDriftDetectedV1;
import com.revolutclone.contracts.events.PaymentCompletedV1;
import com.revolutclone.contracts.events.PaymentFailedV1;
import com.revolutclone.contracts.events.PaymentInitiatedV1;
import com.revolutclone.contracts.events.ReservationCapturedV1;
import com.revolutclone.contracts.events.ReservationHeldV1;
import com.revolutclone.contracts.events.ReservationReleasedV1;
import com.revolutclone.contracts.events.UserRegisteredV1;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trips {@link EventEnvelope} and every contract event record through Jackson 3. Boot
 * 4.1's default JSON library is Jackson 3 under {@code tools.jackson} - see
 * docs/spring-boot-4-notes.md - so this is deliberately {@code JsonMapper}, not the Jackson 2
 * {@code ObjectMapper}.
 */
class EventEnvelopeSerdeTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void roundTripsAnEnvelopeWrappingAPayload() {
        EventEnvelope<UserRegisteredV1> envelope = new EventEnvelope<>(
                UUID.randomUUID(),
                Instant.now(),
                UUID.randomUUID().toString(),
                "UserRegisteredV1",
                1,
                new UserRegisteredV1(UUID.randomUUID(), "alice@demo.io", "Alice", "Doe", "IE"));

        String json = mapper.writeValueAsString(envelope);
        EventEnvelope<UserRegisteredV1> roundTripped =
                mapper.readValue(json, new TypeReference<EventEnvelope<UserRegisteredV1>>() {});

        assertThat(roundTripped).isEqualTo(envelope);
    }

    @ParameterizedTest
    @MethodSource("eventSamples")
    void roundTripsEveryContractEvent(DomainEvent event) {
        String json = mapper.writeValueAsString(event);
        Object roundTripped = mapper.readValue(json, event.getClass());

        assertThat(roundTripped).isEqualTo(event);
    }

    static Stream<DomainEvent> eventSamples() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        return Stream.of(
                new UserRegisteredV1(a, "alice@demo.io", "Alice", "Doe", "IE"),
                new AccountOpenedV1(a, b, "EUR", "WALLET"),
                new LedgerAccountCreatedV1(a, b),
                new EntryPostedV1(a, b, "DEBIT", -1234L, "EUR", 5000L, "TRANSFER", "ref-1"),
                new EntryRejectedV1("idem-1", a, "INSUFFICIENT_FUNDS", "ref-1"),
                new ReservationHeldV1(a, b, 1000L, "EUR", "ref-1"),
                new ReservationCapturedV1(a, b, 500L, "EUR"),
                new ReservationReleasedV1(a, b, "EXPIRED"),
                new LedgerDriftDetectedV1(a, 1000L, 990L, "EUR"),
                new PaymentInitiatedV1(a, a, b, 2500L, "EUR"),
                new PaymentCompletedV1(a),
                new PaymentFailedV1(a, "INSUFFICIENT_FUNDS"),
                new FxRateTickV1("EUR/GBP", "0.86000000", Instant.now(), "simulator-service"));
    }
}
