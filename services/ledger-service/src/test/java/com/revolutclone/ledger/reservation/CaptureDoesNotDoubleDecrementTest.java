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
 * plans/phase-02-ledger.md's Risks &amp; gotchas: "The capture double-decrement is the bug to
 * fear most in this phase. It passes the zero-sum invariant, passes every single-operation
 * test, and only shows up as balances that are quietly too low once Phase 04 or 05 starts
 * capturing holds." Named explicitly per the phase's own instruction to write this test before
 * {@code PostingEngine} rather than after.
 *
 * <p>Hold 10.00, then capture the full 10.00: {@code available} must move by exactly 10.00
 * ACROSS BOTH OPERATIONS TOGETHER - once at hold, not again at capture. A regression that
 * routes the captured posting through {@code AccountBalance.debitAvailable} instead of
 * {@code releaseReservedOnCapture} would decrement {@code available} a second time at capture,
 * which this test asserts on directly at each step so it fails unambiguously rather than only
 * on the final number.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false"
})
class CaptureDoesNotDoubleDecrementTest {

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
    void fullCaptureDecrementsAvailableExactlyOnceNotTwice() {
        UUID account = openFundedWallet(5_000); // 50.00 EUR
        UUID counterparty = openFundedWallet(0);

        Reservation reservation = reservationService.hold(account, 1_000, "EUR", UUID.randomUUID(),
                "cardauth:" + UUID.randomUUID(), Instant.now().plusSeconds(600));

        AccountBalance afterHold = accountBalanceRepository.findById(account).orElseThrow();
        // Hold moves the money out of available exactly once: 50.00 - 10.00 = 40.00.
        assertThat(afterHold.getAvailableMinor()).as("available after hold").isEqualTo(4_000);
        assertThat(afterHold.getReservedMinor()).as("reserved after hold").isEqualTo(1_000);

        CaptureResult capture = reservationService.capture(reservation.getId(), counterparty, 1_000,
                JournalEntryType.CARD_SETTLEMENT, "cardauth:" + UUID.randomUUID(), UUID.randomUUID());
        assertThat(capture.releasedRemainderMinor()).as("full capture releases no remainder").isZero();

        AccountBalance afterCapture = accountBalanceRepository.findById(account).orElseThrow();
        // The bug to fear most: available must be UNCHANGED from its post-hold value here, not
        // decremented a second time (which would make it 30.00 instead of the correct 40.00).
        assertThat(afterCapture.getAvailableMinor()).as("available after capture - must equal post-hold value")
                .isEqualTo(4_000);
        assertThat(afterCapture.getReservedMinor()).as("reserved after capture").isZero();

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
