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
 * Proves {@code V5__system_accounts.sql} (task 02.7) actually applies cleanly and seeds exactly
 * what it claims: one {@link LedgerAccount} per ({@link SystemAccountRefs}, currency) pair for
 * all four system account types across all four supported currencies (EUR/GBP/USD/JPY), each
 * with a matching zero-initialised {@link AccountBalance} row - a system account with no balance
 * row would 500 the first time anything tried to read or post against it.
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

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private AccountBalanceRepository accountBalanceRepository;

    @Test
    void everySystemAccountTypeExistsForEverySupportedCurrencyWithAZeroBalance() {
        for (LedgerAccountType type : List.of(LedgerAccountType.FX_POOL, LedgerAccountType.FEE_INCOME,
                LedgerAccountType.FX_PNL, LedgerAccountType.EXTERNAL_CLEARING)) {
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

    @Test
    void exactlySixteenSystemAccountsExist() {
        long systemAccountCount = ledgerAccountRepository.findAll().stream()
                .filter(a -> a.getType().isSystemAccount())
                .count();
        assertThat(systemAccountCount).isEqualTo(16);
    }

    private static String ownerRefFor(LedgerAccountType type) {
        return switch (type) {
            case FX_POOL -> SystemAccountRefs.FX_POOL;
            case FEE_INCOME -> SystemAccountRefs.FEE_INCOME;
            case FX_PNL -> SystemAccountRefs.FX_PNL;
            case EXTERNAL_CLEARING -> SystemAccountRefs.EXTERNAL_CLEARING;
            default -> throw new IllegalArgumentException("not a system account type: " + type);
        };
    }
}
