package com.revolutclone.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the system-account seed migrations actually apply cleanly and seed exactly what they
 * claim: one {@link LedgerAccount} per ({@link SystemAccountRefs}, currency) pair for every system
 * account type across all four supported currencies (EUR/GBP/USD/JPY), each with a matching
 * zero-initialised {@link AccountBalance} row - a system account with no balance row would 500 the
 * first time anything tried to read or post against it.
 *
 * <p>{@code V5__system_accounts.sql} (task 02.7) seeded the first four types;
 * {@code V7__card_settlement_system_account.sql} (task 05.6) added {@code system:card-settlement},
 * which had been a {@link LedgerAccountType} with no rows because until settlement existed nothing
 * could post against it. Both migrations are covered here rather than one test per migration - the
 * claim is about the seeded set as a whole, and a fifth type that V5's test did not know about is
 * exactly the kind of gap a per-migration test would miss.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false"
})
class SystemAccountBootstrapTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void oidcIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://127.0.0.1:8180/realms/revolut");
    }

    private static final List<String> CURRENCIES = List.of("EUR", "GBP", "USD", "JPY");

    /** Every type {@link LedgerAccountType#isSystemAccount()} answers true for, and which is seeded. */
    private static final List<LedgerAccountType> SYSTEM_ACCOUNT_TYPES = List.of(
            LedgerAccountType.FX_POOL, LedgerAccountType.FEE_INCOME, LedgerAccountType.FX_PNL,
            LedgerAccountType.EXTERNAL_CLEARING, LedgerAccountType.CARD_SETTLEMENT);

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private AccountBalanceRepository accountBalanceRepository;

    @Test
    void everySystemAccountTypeExistsForEverySupportedCurrencyWithAZeroBalance() {
        for (LedgerAccountType type : SYSTEM_ACCOUNT_TYPES) {
            String ownerRef = ownerRefFor(type);
            for (String currency : CURRENCIES) {
                LedgerAccount account = ledgerAccountRepository.findByOwnerRefAndTypeAndCurrency(ownerRef, type,
                        currency).orElseThrow(() -> new AssertionError(
                        "missing seeded system account: " + ownerRef + "/" + type + "/" + currency));
                assertThat(account.getType()).isEqualTo(type);
                assertThat(account.getCurrency()).isEqualTo(currency);

                AccountBalance balance = accountBalanceRepository.findById(account.getId()).orElseThrow(
                        () -> new AssertionError("system account " + account.getId() + " has no account_balance row"));
                assertThat(balance.getAvailableMinor()).as("%s/%s available", ownerRef, currency).isZero();
                assertThat(balance.getReservedMinor()).as("%s/%s reserved", ownerRef, currency).isZero();
                assertThat(balance.getCurrency()).isEqualTo(currency);
            }
        }
    }

    /**
     * The count, stated as five types times four currencies rather than as a bare 20, so that adding a
     * sixth system account type forces a decision here instead of a puzzle: the number is derived from
     * the same list the test above walks, and the two cannot drift apart.
     *
     * <p>Exact rather than "at least", which is the point of it - a duplicate seed row is as much a bug
     * as a missing one, and only an exact count catches a migration that inserted twice.
     */
    @Test
    void exactlyOneSystemAccountPerTypeAndCurrencyExists() {
        long systemAccountCount = ledgerAccountRepository.findAll().stream()
                .filter(a -> a.getType().isSystemAccount())
                .count();
        assertThat(systemAccountCount).isEqualTo((long) SYSTEM_ACCOUNT_TYPES.size() * CURRENCIES.size());
    }

    private static String ownerRefFor(LedgerAccountType type) {
        return switch (type) {
            case FX_POOL -> SystemAccountRefs.FX_POOL;
            case FEE_INCOME -> SystemAccountRefs.FEE_INCOME;
            case FX_PNL -> SystemAccountRefs.FX_PNL;
            case EXTERNAL_CLEARING -> SystemAccountRefs.EXTERNAL_CLEARING;
            case CARD_SETTLEMENT -> SystemAccountRefs.CARD_SETTLEMENT;
            default -> throw new IllegalArgumentException("not a system account type: " + type);
        };
    }
}
