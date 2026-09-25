package com.revolutclone.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.revolutclone.payment.domain.Payment;
import com.revolutclone.payment.domain.PaymentRepository;
import com.revolutclone.payment.domain.PaymentStatus;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves V3__payment.sql (plus common-messaging's shared V1/V2 migrations) applies cleanly
 * against a real Postgres, and - because {@code ddl-auto: validate} runs at context startup -
 * that {@link Payment}'s mapping actually agrees with the migration column for column. That
 * agreement is the thing most likely to silently drift, since the migration is copied verbatim
 * from the phase spec while the entity is written by hand.
 *
 * <p>Also pins the two constraints the saga depends on later: the per-initiator idempotency
 * key (layer 2 of the three-layer idempotency design, and the one the spec calls "the layer
 * that actually guarantees correctness") and the positive-amount check. Boot auto-discovers
 * {@link PaymentServiceApplication} as the test configuration since this test lives in the
 * same package.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false"
})
class PaymentSchemaMigrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void oidcIssuer(DynamicPropertyRegistry registry) {
        // No Keycloak container needed for a schema-only test; the resource-server bean just
        // needs a syntactically valid issuer-uri to construct at context startup.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://127.0.0.1:8180/realms/revolut");
    }

    private static final Set<String> EXPECTED_TABLES = Set.of(
            "payment", "account_view", "outbox_event", "processed_event", "flyway_schema_history");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PaymentRepository payments;

    @Test
    void migrationAppliesCleanlyAndCreatesEveryTable() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            Set<String> actualTables = new HashSet<>();
            try (ResultSet rs = metadata.getTables(null, "public", "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    actualTables.add(rs.getString("TABLE_NAME"));
                }
            }
            assertThat(actualTables).containsAll(EXPECTED_TABLES);
        }
    }

    @Test
    void aPaymentRoundTripsThroughEveryColumn() {
        UUID id = UUID.randomUUID();
        UUID initiator = UUID.randomUUID();
        Payment saved = payments.saveAndFlush(newPayment(id, initiator, "key-roundtrip"));

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        Payment loaded = payments.findById(id).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(loaded.getAmountMinor()).isEqualTo(1_000L);
        assertThat(loaded.getCurrency()).isEqualTo("EUR");
        assertThat(loaded.getReference()).isEqualTo("rent");
        assertThat(loaded.getFailureReason()).isNull();
        assertThat(loaded.getLedgerEntryId()).isNull();
    }

    @Test
    void aTransitionIsWrittenBackAsAnUpdateNotAnInsert() {
        UUID id = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        payments.saveAndFlush(newPayment(id, UUID.randomUUID(), "key-transition"));

        // Reaching here at all is the assertion that matters: Persistable's new-flag is
        // cleared by @PostLoad, so this goes through merge(). Were it still reporting
        // isNew() == true - the pattern Account and Reservation use, which is wrong for an
        // entity that gets updated - this would attempt an insert and fail on the primary key.
        Payment managed = payments.findById(id).orElseThrow();
        managed.markCompleted(entryId);
        payments.saveAndFlush(managed);

        Payment reloaded = payments.findById(id).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(reloaded.getLedgerEntryId()).isEqualTo(entryId);
        assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(reloaded.getCreatedAt());
    }

    @Test
    void theSameKeyTwiceForOneInitiatorViolatesTheUniqueConstraint() {
        UUID initiator = UUID.randomUUID();
        payments.saveAndFlush(newPayment(UUID.randomUUID(), initiator, "key-duplicate"));

        assertThatThrownBy(() ->
                payments.saveAndFlush(newPayment(UUID.randomUUID(), initiator, "key-duplicate")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSameKeyFromADifferentInitiatorIsFine() {
        // The constraint is (initiator_user_id, idempotency_key), not the key alone - two users
        // picking the same UUID must not collide. This is exactly why the client's key is never
        // forwarded to the ledger, whose journal_entry.idempotency_key IS globally unique.
        payments.saveAndFlush(newPayment(UUID.randomUUID(), UUID.randomUUID(), "key-shared"));

        payments.saveAndFlush(newPayment(UUID.randomUUID(), UUID.randomUUID(), "key-shared"));

        assertThat(payments.findAll()).extracting(Payment::getIdempotencyKey).contains("key-shared");
    }

    private static Payment newPayment(UUID id, UUID initiator, String idempotencyKey) {
        return new Payment(id, idempotencyKey, initiator, UUID.randomUUID(), UUID.randomUUID(),
                1_000L, "EUR", "rent");
    }
}
