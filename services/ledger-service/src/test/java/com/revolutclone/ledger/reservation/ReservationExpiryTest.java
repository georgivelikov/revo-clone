package com.revolutclone.ledger.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.revolutclone.ledger.domain.AccountBalance;
import com.revolutclone.ledger.domain.AccountBalanceRepository;
import com.revolutclone.ledger.domain.JournalEntryType;
import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import com.revolutclone.ledger.domain.LedgerAccountType;
import com.revolutclone.ledger.domain.Reservation;
import com.revolutclone.ledger.domain.ReservationRepository;
import com.revolutclone.ledger.domain.ReservationState;
import com.revolutclone.ledger.reservation.error.ReservationNotHeldException;
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
 * plans/phase-02-ledger.md's {@code ## Tests} table: "Sweep releases an expired hold and
 * restores available." The hold is created already past its {@code expires_at} - real time
 * cannot be advanced in a test - and {@link ReservationService#expireDueReservations} is called
 * directly rather than waiting on the {@code @Scheduled} timer.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false"
})
class ReservationExpiryTest {

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
    void sweepReleasesAnExpiredHoldAndRestoresAvailable() {
        UUID account = openFundedWallet(10_000); // 100.00 EUR

        Reservation reservation = reservationService.hold(account, 4_000, "EUR", UUID.randomUUID(),
                "cardauth:" + UUID.randomUUID(), Instant.now().minusSeconds(60));

        AccountBalance afterHold = accountBalanceRepository.findById(account).orElseThrow();
        assertThat(afterHold.getAvailableMinor()).isEqualTo(6_000);
        assertThat(afterHold.getReservedMinor()).isEqualTo(4_000);

        int expiredCount = reservationService.expireDueReservations(Instant.now());
        assertThat(expiredCount).isEqualTo(1);

        AccountBalance afterSweep = accountBalanceRepository.findById(account).orElseThrow();
        assertThat(afterSweep.getAvailableMinor()).isEqualTo(10_000);
        assertThat(afterSweep.getReservedMinor()).isZero();

        Reservation resolved = reservationRepository.findById(reservation.getId()).orElseThrow();
        assertThat(resolved.getState()).isEqualTo(ReservationState.EXPIRED);
        assertThat(resolved.getResolvedAt()).isNotNull();
    }

    @Test
    void sweepIgnoresHoldsNotYetExpired() {
        UUID account = openFundedWallet(10_000);
        reservationService.hold(account, 4_000, "EUR", UUID.randomUUID(), "cardauth:" + UUID.randomUUID(),
                Instant.now().plusSeconds(600));

        int expiredCount = reservationService.expireDueReservations(Instant.now());

        assertThat(expiredCount).isZero();
        AccountBalance balance = accountBalanceRepository.findById(account).orElseThrow();
        assertThat(balance.getReservedMinor()).isEqualTo(4_000);
    }

    /**
     * Task 05.7: the hold's owner asks for the expiry by id instead of waiting for the sweep to
     * notice. card-service's seven-day authorization sweep is the caller - it needs the funds back at
     * the deadline, and it needs to know they are back before it tells the holder so.
     */
    @Test
    void expiringAHoldByIdReturnsTheFundsAndIsIdempotent() {
        UUID account = openFundedWallet(10_000);
        Reservation reservation = reservationService.hold(account, 4_000, "EUR", UUID.randomUUID(),
                "cardauth:" + UUID.randomUUID(), Instant.now().plusSeconds(600));

        reservationService.expire(reservation.getId());

        AccountBalance afterExpiry = accountBalanceRepository.findById(account).orElseThrow();
        assertThat(afterExpiry.getAvailableMinor()).isEqualTo(10_000);
        assertThat(afterExpiry.getReservedMinor()).isZero();
        assertThat(reservationRepository.findById(reservation.getId()).orElseThrow().getState())
                .isEqualTo(ReservationState.EXPIRED);

        // The ledger's own sweep and the owner's are due at the same instant, so one arriving after
        // the other is the ordinary case rather than an error - and it must not credit twice.
        reservationService.expire(reservation.getId());
        assertThat(accountBalanceRepository.findById(account).orElseThrow().getAvailableMinor())
                .isEqualTo(10_000);
    }

    @Test
    void expiringAnAlreadyReleasedHoldIsANoOpRatherThanAnError() {
        UUID account = openFundedWallet(10_000);
        Reservation reservation = reservationService.hold(account, 4_000, "EUR", UUID.randomUUID(),
                "cardauth:" + UUID.randomUUID(), Instant.now().plusSeconds(600));
        reservationService.release(reservation.getId());

        reservationService.expire(reservation.getId());

        // RELEASED, not EXPIRED: the funds came back for a reason, and the later deadline does not
        // rewrite it.
        assertThat(reservationRepository.findById(reservation.getId()).orElseThrow().getState())
                .isEqualTo(ReservationState.RELEASED);
        assertThat(accountBalanceRepository.findById(account).orElseThrow().getAvailableMinor())
                .isEqualTo(10_000);
    }

    @Test
    void expiringACapturedHoldIsRefused() {
        UUID account = openFundedWallet(10_000);
        UUID counterparty = openFundedWallet(0);
        Reservation reservation = reservationService.hold(account, 4_000, "EUR", UUID.randomUUID(),
                "cardauth:" + UUID.randomUUID(), Instant.now().plusSeconds(600));
        reservationService.capture(reservation.getId(), counterparty, 4_000,
                JournalEntryType.CARD_SETTLEMENT, "cardauth:" + UUID.randomUUID(), UUID.randomUUID());

        // The money moved. Giving it back on a deadline would credit available with no posting to
        // account for the credit - the one state an expiry must not swallow quietly.
        assertThatThrownBy(() -> reservationService.expire(reservation.getId()))
                .isInstanceOf(ReservationNotHeldException.class);
        AccountBalance afterRefusal = accountBalanceRepository.findById(account).orElseThrow();
        assertThat(afterRefusal.getAvailableMinor()).isEqualTo(6_000);
        assertThat(afterRefusal.getReservedMinor()).isZero();
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
