package com.revolutclone.card.testsupport;

import com.revolutclone.card.consumer.AuthorizationRequestedEventHandler;
import com.revolutclone.card.crypto.PanCipher;
import com.revolutclone.card.crypto.PanHasher;
import com.revolutclone.card.domain.AuthorizationState;
import com.revolutclone.card.domain.Card;
import com.revolutclone.card.domain.CardAuthorization;
import com.revolutclone.card.domain.CardAuthorizationRepository;
import com.revolutclone.card.domain.CardLimit;
import com.revolutclone.card.domain.CardLimitRepository;
import com.revolutclone.card.domain.CardRepository;
import com.revolutclone.card.domain.CardState;
import com.revolutclone.card.domain.CardType;
import com.revolutclone.card.domain.DeclineReason;
import com.revolutclone.card.domain.LimitPeriod;
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
 * The fixture the authorization tests share: a real Postgres, a fake ledger that genuinely holds funds,
 * and the two projections the chain reads.
 *
 * <p>Each test drives {@link AuthorizationRequestedEventHandler} directly rather than through a broker -
 * that split is exactly what {@code common-messaging}'s thin-listener design exists for, and
 * {@code testcontainers-kafka} cannot start on this machine anyway (docs/spring-boot-4-notes.md). What
 * is <em>not</em> faked is the part this task is about: the decision chain, the {@code card_authorization}
 * row, and the outbox row that announces it.
 */
@SpringBootTest
@Import({NoOpJwtDecoderTestConfig.class, FakeLedgerTestConfig.class, FakeCardFxTestConfig.class})
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        // No real broker here - the listeners would otherwise spend the whole test retrying a
        // connection nothing is listening on.
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000",
        // Same reason: the seven-day expiry sweep is driven explicitly by AuthExpiryTest, and a
        // background tick landing in the middle of another test would be noise at best.
        "revolut.card.authorization.expiry-sweep-delay-ms=300000",
        // Never called - both ports are substituted above - but they still have to bind, because
        // application.yml takes each from an environment variable with no default.
        "revolut.card.ledger.base-url=http://ledger.invalid",
        "revolut.card.fx.base-url=http://fx.invalid"
})
public abstract class AuthorizationTestSupport {

    protected static final String MERCHANT = "Tesco";
    protected static final String MCC = "5411";
    protected static final String COUNTRY = "IE";
    protected static final String EUR = "EUR";
    protected static final String USD = "USD";

    /** 100.00 EUR, and the only money this card's wallet has. */
    protected static final long WALLET_BALANCE_MINOR = 10_000L;

    /**
     * 1.25 USD per EUR - the mid every test starts with, seeded into {@link FakeCardFxRates} in the
     * direction fx-service is asked in (the wallet sells, the merchant's currency is bought).
     *
     * <p>Round on purpose: with fx-service's default 50 bps spread the exchange rate is exactly
     * 1.24375, whose inverse is 0.8040201005 to the ten decimal places a rate is stored at, so every
     * figure in {@code ForeignCurrencyAuthTest} can be checked by hand.
     */
    protected static final String EUR_USD_MID = "1.2500000000";

    /**
     * One container for every test class below, started here and deliberately never stopped - the
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
    protected CardLimitRepository limits;

    @Autowired
    protected FundingAccountRepository fundingAccounts;

    @Autowired
    protected LedgerAccountRefRepository ledgerAccountRefs;

    @Autowired
    protected OutboxEventRepository outbox;

    @Autowired
    protected FakeLedgerReservations ledger;

    @Autowired
    protected FakeCardFxRates fxRates;

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
        // Before the cards: card_limit references card(id).
        limits.deleteAll();
        cards.deleteAll();
        fundingAccounts.deleteAll();
        ledgerAccountRefs.deleteAll();
        outbox.deleteAll();
        ledger.reset();
        fxRates.reset();
        fxRates.setMid(EUR, USD, EUR_USD_MID);

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

    /** A ceiling on the seeded card, in the wallet's currency - which is where a limit is denominated. */
    protected void setLimit(LimitPeriod period, long amountMinor) {
        setLimit(card.getId(), period, amountMinor);
    }

    protected void setLimit(UUID cardId, LimitPeriod period, long amountMinor) {
        limits.saveAndFlush(new CardLimit(cardId, period, amountMinor, EUR));
    }

    /**
     * Spend already on the card's history, at an instant of the test's choosing.
     *
     * <p>Written straight to the table rather than by moving a clock and tapping, which is how
     * {@code fx-service}'s {@code ExecuteQuoteTest} backdates an expiry too: the rolling window is a
     * function of {@code authorized_at}, so a test that controls that column controls the window exactly,
     * and needs neither a mutable {@code Clock} bean nor a wait. The fake ledger is deliberately not told
     * about these holds - limits and funds are separate constraints, and a limit test that ran out of
     * money would be proving the wrong thing.
     */
    protected CardAuthorization recordPastSpend(String networkRef, long amountMinor, Instant authorizedAt) {
        // A domestic merchant: no conversion happened, so no FX detail - see CardFxDetail on why null
        // there means something different from zero.
        return authorizations.saveAndFlush(CardAuthorization.approved(UUID.randomUUID(), card.getId(),
                networkRef, MERCHANT, MCC, COUNTRY, amountMinor, EUR, amountMinor, EUR, null,
                UUID.randomUUID(), authorizedAt));
    }

    /** A refusal already on the card's history: recorded, but it moved nothing. */
    protected CardAuthorization recordPastDecline(String networkRef, long amountMinor, DeclineReason reason,
            Instant authorizedAt) {
        return authorizations.saveAndFlush(CardAuthorization.declined(UUID.randomUUID(), card.getId(),
                networkRef, MERCHANT, MCC, COUNTRY, amountMinor, EUR, amountMinor, EUR, null, reason,
                authorizedAt));
    }

    /** One tap, delivered to the consumer exactly as the simulator's message would arrive. */
    protected void tap(String networkRef, String pan, long amountMinor) {
        tap(networkRef, pan, amountMinor, EUR);
    }

    /**
     * One tap in a currency of the test's choosing. Anything other than the wallet's {@code EUR} takes
     * step 4's FX path, which is what {@code ForeignCurrencyAuthTest} is about.
     */
    protected void tap(String networkRef, String pan, long amountMinor, String currency) {
        consumer.handle(envelope(new AuthorizationRequestedV1(
                networkRef, pan, amountMinor, currency, MERCHANT, MCC, COUNTRY)));
    }

    /**
     * What the issuer decided about one tap. Reads the row rather than the returned decision, because the
     * row is what the holder's feed and every later settlement message are matched against - a decision
     * that was returned but not recorded would be no decision at all.
     */
    protected AuthorizationState stateOf(String networkRef) {
        return authorizations.findByNetworkRef(networkRef).orElseThrow().getState();
    }

    protected DeclineReason declineReasonOf(String networkRef) {
        return authorizations.findByNetworkRef(networkRef).orElseThrow().getDeclineReason();
    }

    protected static EventEnvelope<AuthorizationRequestedV1> envelope(AuthorizationRequestedV1 payload) {
        return new EventEnvelope<>(UUID.randomUUID(), Instant.now(), UUID.randomUUID().toString(),
                AuthorizationRequestedV1.class.getSimpleName(), 1, payload);
    }
}
