package com.revolutclone.card.testsupport;

import com.revolutclone.card.consumer.AuthorizationRequestedEventHandler;
import com.revolutclone.card.crypto.PanCipher;
import com.revolutclone.card.crypto.PanHasher;
import com.revolutclone.card.domain.Card;
import com.revolutclone.card.domain.CardAuthorizationRepository;
import com.revolutclone.card.domain.CardRepository;
import com.revolutclone.card.domain.CardState;
import com.revolutclone.card.domain.CardType;
import com.revolutclone.card.pan.PanGenerator;
import com.revolutclone.card.readmodel.FundingAccount;
import com.revolutclone.card.readmodel.FundingAccountRepository;
import com.revolutclone.card.readmodel.LedgerAccountRef;
import com.revolutclone.card.readmodel.LedgerAccountRefRepository;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AuthorizationRequestedV1;
import com.revolutclone.messaging.outbox.OutboxEventRepository;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fixture the three authorization tests share: a real Postgres, a fake ledger that genuinely holds
 * funds, and the two projections the chain reads.
 *
 * <p>Each test drives {@link AuthorizationRequestedEventHandler} directly rather than through a broker -
 * that split is exactly what {@code common-messaging}'s thin-listener design exists for, and
 * {@code testcontainers-kafka} cannot start on this machine anyway (docs/spring-boot-4-notes.md). What
 * is <em>not</em> faked is the part this task is about: the decision chain, the {@code card_authorization}
 * row, and the outbox row that announces it.
 */
@SpringBootTest
@Import({NoOpJwtDecoderTestConfig.class, FakeLedgerTestConfig.class})
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        // No real broker here - the listeners would otherwise spend the whole test retrying a
        // connection nothing is listening on.
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000",
        // Never called - the ledger port is substituted by FakeLedgerTestConfig - but it still has to
        // bind, because application.yml takes it from an environment variable with no default.
        "revolut.card.ledger.base-url=http://ledger.invalid"
})
public abstract class AuthorizationTestSupport {

    protected static final String MERCHANT = "Tesco";
    protected static final String MCC = "5411";
    protected static final String COUNTRY = "IE";
    protected static final String EUR = "EUR";

    /** 100.00 EUR, and the only money this card's wallet has. */
    protected static final long WALLET_BALANCE_MINOR = 10_000L;

    /**
     * One container for all three test classes, started here and deliberately never stopped - the
     * singleton-container pattern, and not {@code @Testcontainers} + {@code @Container}.
     *
     * <p>That annotation pair manages a static container per <em>test class</em>: declared on a shared
     * base class it is started for the first subclass and <strong>stopped when that subclass
     * finishes</strong>, while Spring's context cache quite correctly hands the next subclass the same
     * context - now pointing at a database that no longer exists. The symptom is a 30-second Hikari
     * timeout in {@code @BeforeEach} rather than anything resembling the real cause. Ryuk reaps this
     * container when the JVM exits, which is what makes "never stopped" safe.
     */
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseAndCryptoKeys(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        String key = Base64.getEncoder().encodeToString(new byte[32]);
        registry.add("revolut.card.crypto.encryption-key", () -> key);
        registry.add("revolut.card.crypto.pan-hash-key", () -> key);
    }

    @Autowired
    protected AuthorizationRequestedEventHandler consumer;

    @Autowired
    protected CardRepository cards;

    @Autowired
    protected CardAuthorizationRepository authorizations;

    @Autowired
    protected FundingAccountRepository fundingAccounts;

    @Autowired
    protected LedgerAccountRefRepository ledgerAccountRefs;

    @Autowired
    protected OutboxEventRepository outbox;

    @Autowired
    protected FakeLedgerReservations ledger;

    @Autowired
    protected PanHasher panHasher;

    @Autowired
    protected PanCipher panCipher;

    @Autowired
    protected JsonMapper jsonMapper;

    protected UUID userId;
    protected UUID accountId;
    protected UUID ledgerAccountId;
    protected String pan;
    protected Card card;

    @BeforeEach
    void seedAnActiveCardOnAFundedEurWallet() {
        authorizations.deleteAll();
        cards.deleteAll();
        fundingAccounts.deleteAll();
        ledgerAccountRefs.deleteAll();
        outbox.deleteAll();
        ledger.reset();

        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        ledgerAccountId = UUID.randomUUID();

        seedWallet(accountId, ledgerAccountId, userId, EUR);
        ledger.fund(ledgerAccountId, WALLET_BALANCE_MINOR);

        pan = new PanGenerator().generate();
        card = saveCard(pan, CardState.ACTIVE, YearMonth.now().plusYears(3));
    }

    /** Seeds the two projections the chain reads, exactly as the Kafka consumers would have. */
    protected void seedWallet(UUID accountId, UUID ledgerAccountId, UUID ownerId, String currency) {
        FundingAccount account = new FundingAccount(accountId);
        account.apply(ownerId, currency, FundingAccount.WALLET, Instant.now());
        fundingAccounts.saveAndFlush(account);

        LedgerAccountRef ref = new LedgerAccountRef(ledgerAccountId);
        ref.apply(accountId, Instant.now());
        ledgerAccountRefs.saveAndFlush(ref);
    }

    protected Card saveCard(String pan, CardState state, YearMonth expiry) {
        Card saved = new Card(
                UUID.randomUUID(),
                userId,
                accountId,
                panHasher.hash(pan),
                pan.substring(pan.length() - 4),
                panCipher.encrypt(pan),
                expiry.getMonthValue(),
                expiry.getYear(),
                panCipher.encrypt("123"),
                "$2a$10$not-a-real-bcrypt-hash-just-a-fixture-value...........",
                CardType.VIRTUAL,
                state,
                "Groceries");
        return cards.saveAndFlush(saved);
    }

    /** One tap, delivered to the consumer exactly as the simulator's message would arrive. */
    protected void tap(String networkRef, String pan, long amountMinor) {
        consumer.handle(envelope(new AuthorizationRequestedV1(
                networkRef, pan, amountMinor, EUR, MERCHANT, MCC, COUNTRY)));
    }

    protected static EventEnvelope<AuthorizationRequestedV1> envelope(AuthorizationRequestedV1 payload) {
        return new EventEnvelope<>(UUID.randomUUID(), Instant.now(), UUID.randomUUID().toString(),
                AuthorizationRequestedV1.class.getSimpleName(), 1, payload);
    }
}
