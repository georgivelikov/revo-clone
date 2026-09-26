package com.revolutclone.ledger.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.contracts.DomainEvent;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.EntryPostedV1;
import com.revolutclone.contracts.events.EntryRejectedV1;
import com.revolutclone.contracts.events.PaymentInitiatedV1;
import com.revolutclone.ledger.domain.AccountBalance;
import com.revolutclone.ledger.domain.AccountBalanceRepository;
import com.revolutclone.ledger.domain.JournalEntry;
import com.revolutclone.ledger.domain.JournalEntryRepository;
import com.revolutclone.ledger.domain.JournalEntryType;
import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import com.revolutclone.ledger.domain.LedgerAccountType;
import com.revolutclone.ledger.domain.Posting;
import com.revolutclone.ledger.domain.PostingDirection;
import com.revolutclone.ledger.domain.PostingRepository;
import com.revolutclone.messaging.api.EventHandler;
import com.revolutclone.messaging.idempotent.IdempotentHandlerFactory;
import com.revolutclone.messaging.outbox.OutboxEvent;
import com.revolutclone.messaging.outbox.OutboxEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Step 2 of plans/phase-03-payments-and-ui.md's transfer saga, end to end inside this service:
 * {@code PaymentInitiatedV1} in, one balanced journal entry and its {@code EntryPostedV1} events
 * out - or an {@code EntryRejectedV1} with a reason and nothing written at all.
 *
 * <p>Drives {@link PaymentInitiatedEventHandler} through the same {@link IdempotentHandlerFactory}
 * wrapping {@link PaymentEventKafkaListener} uses, for the reason recorded in
 * docs/spring-boot-4-notes.md: Testcontainers' Kafka module does not start on this machine, and
 * the listener adds only the topic binding and the envelope-type routing.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000"
})
class PaymentInitiatedConsumerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void oidcIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://127.0.0.1:8180/realms/revolut");
    }

    @Autowired
    private PaymentInitiatedEventHandler delegate;
    @Autowired
    private IdempotentHandlerFactory idempotency;
    @Autowired
    private LedgerAccountRepository ledgerAccounts;
    @Autowired
    private AccountBalanceRepository balances;
    @Autowired
    private JournalEntryRepository entries;
    @Autowired
    private PostingRepository postings;
    @Autowired
    private OutboxEventRepository outbox;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JsonMapper jsonMapper;

    private UUID aliceAccount;
    private UUID bobAccount;
    private UUID aliceLedgerAccount;
    private UUID bobLedgerAccount;

    @BeforeEach
    void twoFundedWallets() {
        // Postings before entries - posting.entry_id is a foreign key. Balances and ledger accounts
        // are left in place: every test below opens its own pair with fresh ids and looks them up by
        // id, and V5's system accounts must survive.
        postings.deleteAll();
        entries.deleteAll();
        outbox.deleteAll();
        aliceAccount = UUID.randomUUID();
        bobAccount = UUID.randomUUID();
        aliceLedgerAccount = openFundedWallet(aliceAccount, "EUR", 50_000L);
        bobLedgerAccount = openFundedWallet(bobAccount, "EUR", 10_000L);
    }

    @Test
    void aTransferPostsOneEntryDebitingTheSenderAndCreditingTheReceiver() {
        UUID paymentId = UUID.randomUUID();

        handler().handle(envelope(new PaymentInitiatedV1(paymentId, aliceAccount, bobAccount, 1_000L, "EUR")));

        JournalEntry entry = entries.findByIdempotencyKey("payment:" + paymentId)
                .orElseThrow(() -> new AssertionError("no journal entry for payment " + paymentId));
        assertThat(entry.getType()).isEqualTo(JournalEntryType.TRANSFER);
        // reference_id is the payment id, which is the only link payment-service has back from an
        // EntryPostedV1 to the payment it settles.
        assertThat(entry.getReferenceId()).isEqualTo(paymentId);

        List<Posting> lines = postings.findByEntryId(entry.getId());
        assertThat(lines).hasSize(2);
        Posting debit = lineFor(lines, aliceLedgerAccount);
        Posting credit = lineFor(lines, bobLedgerAccount);
        // The sign convention: a user wallet is a liability, so the SENDER is debited.
        assertThat(debit.getDirection()).isEqualTo(PostingDirection.DEBIT);
        assertThat(credit.getDirection()).isEqualTo(PostingDirection.CREDIT);
        assertThat(debit.getAmountMinor()).isEqualTo(1_000L);
        assertThat(credit.getAmountMinor()).isEqualTo(1_000L);

        assertThat(balances.findById(aliceLedgerAccount).orElseThrow().getAvailableMinor()).isEqualTo(49_000L);
        assertThat(balances.findById(bobLedgerAccount).orElseThrow().getAvailableMinor()).isEqualTo(11_000L);

        // One EntryPostedV1 per affected account, per plans/phase-02-ledger.md's ### Events out.
        assertThat(outboxTypes()).containsExactly("EntryPostedV1", "EntryPostedV1");
        assertThat(outbox.findAll()).allSatisfy(row ->
                assertThat(payloadOf(row, EntryPostedV1.class).referenceId()).isEqualTo(paymentId.toString()));
    }

    @Test
    void anOverdrawIsRejectedWithAReasonAndLeavesTheSendersBalanceUntouched() {
        UUID paymentId = UUID.randomUUID();

        handler().handle(envelope(new PaymentInitiatedV1(paymentId, aliceAccount, bobAccount, 500_000L, "EUR")));

        assertThat(entries.findByIdempotencyKey("payment:" + paymentId)).isEmpty();
        assertThat(balances.findById(aliceLedgerAccount).orElseThrow().getAvailableMinor()).isEqualTo(50_000L);
        assertThat(balances.findById(bobLedgerAccount).orElseThrow().getAvailableMinor()).isEqualTo(10_000L);

        assertThat(outboxTypes()).containsExactly("EntryRejectedV1");
        EntryRejectedV1 rejection = payloadOf(outbox.findAll().getFirst(), EntryRejectedV1.class);
        assertThat(rejection.reason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(rejection.idempotencyKey()).isEqualTo("payment:" + paymentId);
        assertThat(rejection.referenceId()).isEqualTo(paymentId.toString());
    }

    /**
     * An account payment-service's read model knows about but this service's projection has not
     * caught up with. A rejection with a reason is the only outcome that does not leave the payment
     * PENDING forever, and the account id carried back is account-service's, because that is the
     * only name payment-service knows the account by.
     */
    @Test
    void anAccountWithNoLedgerAccountIsRejectedRatherThanRetriedForever() {
        UUID paymentId = UUID.randomUUID();
        UUID unknownAccount = UUID.randomUUID();

        handler().handle(envelope(new PaymentInitiatedV1(paymentId, aliceAccount, unknownAccount, 1_000L, "EUR")));

        assertThat(entries.findByIdempotencyKey("payment:" + paymentId)).isEmpty();
        assertThat(outboxTypes()).containsExactly("EntryRejectedV1");
        EntryRejectedV1 rejection = payloadOf(outbox.findAll().getFirst(), EntryRejectedV1.class);
        assertThat(rejection.reason()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(rejection.accountId()).isEqualTo(unknownAccount);
        assertThat(rejection.idempotencyKey()).isEqualTo("payment:" + paymentId);
    }

    @Test
    void theSameEventDeliveredTwicePostsOneEntry() {
        UUID paymentId = UUID.randomUUID();
        EventEnvelope<PaymentInitiatedV1> event =
                envelope(new PaymentInitiatedV1(paymentId, aliceAccount, bobAccount, 1_000L, "EUR"));

        EventHandler<PaymentInitiatedV1> handler = handler();
        handler.handle(event);
        handler.handle(event);

        assertThat(entriesFor(paymentId)).hasSize(1);
        assertThat(balances.findById(aliceLedgerAccount).orElseThrow().getAvailableMinor()).isEqualTo(49_000L);
        assertThat(outboxTypes()).containsExactly("EntryPostedV1", "EntryPostedV1");
    }

    /**
     * The repeat {@code processed_event} cannot see: a <em>different</em> {@code eventId} carrying
     * the same payment, which is what a republished outbox row on payment-service's side looks
     * like. The derived {@code payment:<paymentId>} key is what stops it - the same money is not
     * moved twice, and the balance proves it.
     */
    @Test
    void aSecondEventForTheSamePaymentMovesNoFurtherMoney() {
        UUID paymentId = UUID.randomUUID();

        handler().handle(envelope(new PaymentInitiatedV1(paymentId, aliceAccount, bobAccount, 1_000L, "EUR")));
        handler().handle(envelope(new PaymentInitiatedV1(paymentId, aliceAccount, bobAccount, 1_000L, "EUR")));

        assertThat(entriesFor(paymentId)).hasSize(1);
        assertThat(balances.findById(aliceLedgerAccount).orElseThrow().getAvailableMinor()).isEqualTo(49_000L);
        assertThat(balances.findById(bobLedgerAccount).orElseThrow().getAvailableMinor()).isEqualTo(11_000L);
    }

    /**
     * Every journal entry caused by one payment, by {@code reference_id}. Scoped to the payment
     * rather than counting the whole table: a duplicate entry for <em>this</em> payment is the bug
     * under test and still shows up here, while entries left behind by a sibling test are not
     * evidence of anything.
     */
    private List<JournalEntry> entriesFor(UUID paymentId) {
        return entries.findAll().stream().filter(entry -> paymentId.equals(entry.getReferenceId())).toList();
    }

    private EventHandler<PaymentInitiatedV1> handler() {
        return idempotency.wrap(delegate, "ledger-service");
    }

    private static Posting lineFor(List<Posting> lines, UUID ledgerAccountId) {
        return lines.stream().filter(line -> line.getLedgerAccountId().equals(ledgerAccountId)).findFirst()
                .orElseThrow(() -> new AssertionError("no posting for ledger account " + ledgerAccountId));
    }

    private List<String> outboxTypes() {
        return outbox.findAll().stream().map(OutboxEvent::getType).toList();
    }

    private <T> T payloadOf(OutboxEvent row, Class<T> payloadType) {
        return jsonMapper.treeToValue(jsonMapper.readTree(row.getPayload()).get("payload"), payloadType);
    }

    private UUID openFundedWallet(UUID externalAccountId, String currency, long availableMinor) {
        return transactionTemplate.execute(status -> {
            LedgerAccount account = new LedgerAccount(UUID.randomUUID(), "user:" + UUID.randomUUID(),
                    LedgerAccountType.USER_WALLET, currency, externalAccountId);
            ledgerAccounts.save(account);
            AccountBalance balance = new AccountBalance(account.getId(), currency);
            balance.creditAvailable(availableMinor);
            balances.save(balance);
            return account.getId();
        });
    }

    private static <T extends DomainEvent> EventEnvelope<T> envelope(T payload) {
        return new EventEnvelope<>(UUID.randomUUID(), Instant.now(), "corr-" + UUID.randomUUID(),
                payload.getClass().getSimpleName(), 1, payload);
    }
}
