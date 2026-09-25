package com.revolutclone.ledger.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.ledger.domain.AccountBalance;
import com.revolutclone.ledger.domain.AccountBalanceRepository;
import com.revolutclone.ledger.domain.JournalEntryType;
import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import com.revolutclone.ledger.domain.LedgerAccountType;
import com.revolutclone.ledger.domain.Reservation;
import com.revolutclone.ledger.domain.ReservationRepository;
import com.revolutclone.ledger.domain.ReservationState;
import java.time.Instant;
import java.util.UUID;
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

/**
 * plans/phase-02-ledger.md's {@code ## Tests} table: "Capture &lt; hold; remainder released;
 * balances exact." Hold 100.00, capture 60.00 - the unused 40.00 must come back to
 * {@code available} in the same operation, not linger in {@code reserved}.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false"
})
class PartialCaptureTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void oidcIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://127.0.0.1:8180/realms/revolut");
    }

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private AccountBalanceRepository accountBalanceRepository;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private ReservationService reservationService;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void captureLessThanHoldReleasesTheRemainderAndLeavesBalancesExact() {
        UUID account = openFundedWallet(20_000); // 200.00 EUR
        UUID counterparty = openFundedWallet(0);

        Reservation reservation = reservationService.hold(account, 10_000, "EUR", UUID.randomUUID(),
                "cardauth:" + UUID.randomUUID(), Instant.now().plusSeconds(600));

        AccountBalance afterHold = accountBalanceRepository.findById(account).orElseThrow();
        assertThat(afterHold.getAvailableMinor()).isEqualTo(10_000);
        assertThat(afterHold.getReservedMinor()).isEqualTo(10_000);

        CaptureResult capture = reservationService.capture(reservation.getId(), counterparty, 6_000,
                JournalEntryType.CARD_SETTLEMENT, "cardauth:" + UUID.randomUUID(), UUID.randomUUID());

        assertThat(capture.capturedMinor()).isEqualTo(6_000);
        assertThat(capture.releasedRemainderMinor()).isEqualTo(4_000);

        AccountBalance afterCapture = accountBalanceRepository.findById(account).orElseThrow();
        // 200.00 starting - 60.00 actually spent = 140.00, NOT 200.00 - 100.00 = 100.00.
        assertThat(afterCapture.getAvailableMinor()).isEqualTo(14_000);
        assertThat(afterCapture.getReservedMinor()).isZero();

        Reservation resolved = reservationRepository.findById(reservation.getId()).orElseThrow();
        assertThat(resolved.getState()).isEqualTo(ReservationState.CAPTURED);
    }

    private UUID openFundedWallet(long startingAvailableMinor) {
        return transactionTemplate.execute(status -> {
            LedgerAccount account = new LedgerAccount(UUID.randomUUID(), "user:" + UUID.randomUUID(),
                    LedgerAccountType.USER_WALLET, "EUR");
            ledgerAccountRepository.save(account);
            AccountBalance balance = new AccountBalance(account.getId(), "EUR");
            balance.creditAvailable(startingAvailableMinor);
            accountBalanceRepository.save(balance);
            return account.getId();
        });
    }
}
