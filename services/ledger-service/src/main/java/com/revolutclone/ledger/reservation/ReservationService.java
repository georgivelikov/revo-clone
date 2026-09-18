package com.revolutclone.ledger.reservation;

import com.revolutclone.contracts.events.ReservationCapturedV1;
import com.revolutclone.contracts.events.ReservationHeldV1;
import com.revolutclone.contracts.events.ReservationReleasedV1;
import com.revolutclone.ledger.domain.AccountBalance;
import com.revolutclone.ledger.domain.AccountBalanceRepository;
import com.revolutclone.ledger.domain.JournalEntry;
import com.revolutclone.ledger.domain.JournalEntryRepository;
import com.revolutclone.ledger.domain.JournalEntryType;
import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import com.revolutclone.ledger.domain.Posting;
import com.revolutclone.ledger.domain.PostingDirection;
import com.revolutclone.ledger.domain.PostingRepository;
import com.revolutclone.ledger.domain.Reservation;
import com.revolutclone.ledger.domain.ReservationRepository;
import com.revolutclone.ledger.domain.ReservationState;
import com.revolutclone.ledger.posting.PostEntryCommand;
import com.revolutclone.ledger.posting.PostEntryResult;
import com.revolutclone.ledger.posting.PostingLine;
import com.revolutclone.ledger.posting.PostingTransactionExecutor;
import com.revolutclone.ledger.reservation.error.CaptureExceedsHoldException;
import com.revolutclone.ledger.reservation.error.InsufficientAvailableBalanceException;
import com.revolutclone.ledger.reservation.error.ReservationNotFoundException;
import com.revolutclone.ledger.reservation.error.ReservationNotHeldException;
import com.revolutclone.messaging.api.OutboxAppender;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * plans/phase-02-ledger.md's {@code ### Reservations}: a reservation moves money from
 * {@code available} to {@code reserved} without a posting - it is not an accounting event, it
 * is a promise.
 *
 * <pre>
 * | Operation | Effect                                                                    |
 * |-----------|----------------------------------------------------------------------------|
 * | hold      | available -= amount, reserved += amount, state HELD, expires_at set        |
 * | capture   | post the entry WITH reservation_id set, decrementing reserved only,        |
 * |           | leaving available untouched; state CAPTURED                                |
 * | release   | reserved -= amount, available += amount; state RELEASED                    |
 * | expiry    | @Scheduled sweep releases HELD rows past expires_at; state EXPIRED         |
 * </pre>
 *
 * <p><b>The capture double-decrement bug (the one this phase's Risks section calls "the bug to
 * fear most"):</b> {@link #capture} builds its posting with {@code reservationId} set, which
 * routes it through {@link PostingTransactionExecutor}'s existing
 * {@code reservationId != null -> releaseReservedOnCapture} branch - {@code available} is never
 * touched a second time. See {@code CaptureDoesNotDoubleDecrementTest}.
 *
 * <p><b>Why {@link #capture} calls {@link PostingTransactionExecutor} directly, not
 * {@code PostingEngine}:</b> capture must insert the posting AND update the {@link Reservation}
 * row's own state (mark {@code CAPTURED}, release any unused remainder) in ONE transaction.
 * {@code PostingEngine.postEntry} is {@code @Retryable} and calling an {@code @Retryable} method
 * from inside an already-open transaction (this method's own) would retry inside a transaction
 * already marked rollback-only on the first conflict - exactly what plans/phase-02-ledger.md's
 * Risks &amp; gotchas warns against. Calling {@link PostingTransactionExecutor} directly makes
 * this method's own {@code @Transactional} the only transaction boundary in the call chain.
 *
 * <p><b>No retry-wrapper split here, unlike {@code PostingEngine} (task 02.5):</b> that split
 * exists because {@code PostingEngine} has an explicit, named 100-thread concurrency
 * requirement (`ConcurrentDebitTest`/`ConcurrentMixedTest`). Nothing in this phase asks the same
 * of holds/captures/releases, and adding the identical two-bean {@code @Retryable}/
 * {@code @Transactional} split here without a concrete need would be exactly the kind of
 * unrequested complexity CLAUDE.md's working style warns against. A version conflict on
 * {@code account_balance} here still fails loudly with {@code ObjectOptimisticLockingFailureException}
 * rather than corrupting anything; if contention is later observed in practice, the identical
 * split PostingEngine already established is a five-minute change, not a redesign.
 *
 * <p>Every write here is idempotent on the same key the caller supplied ({@code idempotencyKey}
 * for {@link #hold}/{@link #capture}, the reservation's own id for {@link #release}) - a
 * redelivered call returns the original result rather than re-mutating anything.
 */
@Service
public class ReservationService {

    private static final String OUTBOX_TOPIC = "ledger.entry.v1";

    private final LedgerAccountRepository ledgerAccountRepository;
    private final AccountBalanceRepository accountBalanceRepository;
    private final ReservationRepository reservationRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final PostingRepository postingRepository;
    private final PostingTransactionExecutor postingTransactionExecutor;
    private final OutboxAppender outboxAppender;
    private final Clock clock;

    public ReservationService(LedgerAccountRepository ledgerAccountRepository,
            AccountBalanceRepository accountBalanceRepository, ReservationRepository reservationRepository,
            JournalEntryRepository journalEntryRepository, PostingRepository postingRepository,
            PostingTransactionExecutor postingTransactionExecutor, OutboxAppender outboxAppender, Clock clock) {
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.accountBalanceRepository = accountBalanceRepository;
        this.reservationRepository = reservationRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.postingRepository = postingRepository;
        this.postingTransactionExecutor = postingTransactionExecutor;
        this.outboxAppender = outboxAppender;
        this.clock = clock;
    }

    /**
     * {@code available -= amount}, {@code reserved += amount}. Rejects (via
     * {@link InsufficientAvailableBalanceException}) if {@code available - reserved < amount} for
     * a {@code USER_WALLET}/{@code VAULT} account - system accounts are exempt, same symmetry
     * {@code PostingTransactionExecutor} applies to a plain debit.
     */
    @Transactional
    public Reservation hold(UUID ledgerAccountId, long amountMinor, String currency, UUID referenceId,
            String idempotencyKey, Instant expiresAt) {
        Optional<Reservation> existing = reservationRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be > 0, got " + amountMinor);
        }
        LedgerAccount account = ledgerAccountRepository.findById(ledgerAccountId).orElseThrow(
                () -> new IllegalArgumentException("no ledger_account " + ledgerAccountId));
        if (!account.getCurrency().equals(currency)) {
            throw new IllegalArgumentException(
                    "account " + ledgerAccountId + " is " + account.getCurrency() + ", not " + currency);
        }
        AccountBalance balance = accountBalanceRepository.findById(ledgerAccountId).orElseThrow(
                () -> new IllegalStateException("ledger_account " + ledgerAccountId + " has no account_balance row"));
        if (!account.getType().isSystemAccount() && balance.getAvailableMinor() < amountMinor) {
            // available >= amount, NOT available - reserved >= amount: a prior hold already did
            // available -= amount at hold time, so available already excludes every currently-held
            // amount. Subtracting reserved again would double-penalize an account with an existing
            // hold - see PostingTransactionExecutor.validateLine's identical fix, found live while
            // building this method, for the worked counter-example.
            throw new InsufficientAvailableBalanceException(ledgerAccountId, amountMinor,
                    balance.getAvailableMinor());
        }

        Reservation reservation = new Reservation(UUID.randomUUID(), ledgerAccountId, amountMinor, currency,
                referenceId, idempotencyKey, expiresAt);
        reservationRepository.save(reservation);
        balance.moveToReserved(amountMinor);

        outboxAppender.append(OUTBOX_TOPIC, ledgerAccountId.toString(),
                new ReservationHeldV1(reservation.getId(), ledgerAccountId, amountMinor, currency,
                        referenceId == null ? null : referenceId.toString()));
        return reservation;
    }

    /**
     * Posts a balanced entry - DEBIT the held account for {@code amountMinor} WITH
     * {@code reservationId} set (decrements {@code reserved} only), CREDIT
     * {@code counterpartyAccountId} - then releases any unused remainder
     * ({@code reservation.amountMinor - amountMinor}) back to {@code available} and marks the
     * reservation {@code CAPTURED}, all in one transaction. {@code amountMinor} must be
     * {@code <=} the amount actually held; a smaller amount is a partial capture.
     */
    @Transactional
    public CaptureResult capture(UUID reservationId, UUID counterpartyAccountId, long amountMinor,
            JournalEntryType type, String idempotencyKey, UUID referenceId) {
        Optional<JournalEntry> existingEntry = journalEntryRepository.findByIdempotencyKey(idempotencyKey);
        if (existingEntry.isPresent()) {
            JournalEntry entry = existingEntry.get();
            List<Posting> postings = postingRepository.findByEntryId(entry.getId());
            Reservation reservation = reservationRepository.findById(reservationId)
                    .orElseThrow(() -> new ReservationNotFoundException(reservationId));
            long captured = postings.stream()
                    .filter(p -> reservationId.equals(p.getReservationId()))
                    .mapToLong(Posting::getAmountMinor)
                    .findFirst()
                    .orElse(amountMinor);
            long remainder = Math.max(reservation.getAmountMinor() - captured, 0);
            return new CaptureResult(entry, postings, captured, remainder);
        }

        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        if (reservation.getState() != ReservationState.HELD) {
            throw new ReservationNotHeldException(reservationId, reservation.getState());
        }
        if (amountMinor <= 0 || amountMinor > reservation.getAmountMinor()) {
            throw new CaptureExceedsHoldException(reservationId, amountMinor, reservation.getAmountMinor());
        }

        PostEntryCommand command = new PostEntryCommand(idempotencyKey, type, referenceId, List.of(
                new PostingLine(reservation.getLedgerAccountId(), PostingDirection.DEBIT, amountMinor,
                        reservation.getCurrency(), reservationId),
                new PostingLine(counterpartyAccountId, PostingDirection.CREDIT, amountMinor,
                        reservation.getCurrency(), null)));
        PostEntryResult result = postingTransactionExecutor.postEntry(command);
        if (!(result instanceof PostEntryResult.Posted posted)) {
            PostEntryResult.Rejected rejected = (PostEntryResult.Rejected) result;
            throw new IllegalStateException("capture posting for reservation " + reservationId
                    + " was rejected: " + rejected.reason()
                    + " - the reservation's account and currency were already validated at hold time");
        }

        long remainder = reservation.getAmountMinor() - amountMinor;
        if (remainder > 0) {
            AccountBalance balance = accountBalanceRepository.findById(reservation.getLedgerAccountId())
                    .orElseThrow(() -> new IllegalStateException(
                            "ledger_account " + reservation.getLedgerAccountId() + " has no account_balance row"));
            balance.moveFromReservedToAvailable(remainder);
        }
        reservation.markCaptured(clock.instant());

        outboxAppender.append(OUTBOX_TOPIC, reservation.getLedgerAccountId().toString(),
                new ReservationCapturedV1(reservationId, reservation.getLedgerAccountId(), amountMinor,
                        reservation.getCurrency()));
        return new CaptureResult(posted.entry(), posted.postings(), amountMinor, Math.max(remainder, 0));
    }

    /**
     * {@code reserved -= amount}, {@code available += amount}; state {@code RELEASED}.
     * Idempotent: releasing an already-{@code RELEASED} reservation is a no-op, not an error -
     * releasing one that is {@code CAPTURED} or {@code EXPIRED} is (a different terminal state
     * was already reached, and undoing that silently would be wrong).
     */
    @Transactional
    public void release(UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        if (reservation.getState() == ReservationState.RELEASED) {
            return;
        }
        if (reservation.getState() != ReservationState.HELD) {
            throw new ReservationNotHeldException(reservationId, reservation.getState());
        }
        AccountBalance balance = accountBalanceRepository.findById(reservation.getLedgerAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "ledger_account " + reservation.getLedgerAccountId() + " has no account_balance row"));
        balance.moveFromReservedToAvailable(reservation.getAmountMinor());
        reservation.markReleased(clock.instant());

        outboxAppender.append(OUTBOX_TOPIC, reservation.getLedgerAccountId().toString(),
                new ReservationReleasedV1(reservationId, reservation.getLedgerAccountId(), "MANUAL"));
    }

    /**
     * Releases every {@code HELD} reservation past its {@code expires_at}, per plans/phase-02-
     * ledger.md's expiry-sweep row. No outbox event: the phase's {@code ### Events out} list
     * names only Held/Captured/Released, and expiry is a housekeeping transition, not something
     * a downstream consumer needs to react to.
     *
     * <p>One transaction per sweep cycle, matching {@code common-messaging}'s
     * {@code OutboxPublisher} - simple, and safe to retry wholesale on the next cycle if a row
     * in the batch fails, since expiring an already-expired-past-due row is naturally repeatable.
     */
    @Scheduled(fixedDelayString = "${revolut.ledger.reservation.sweep-delay-ms:60000}")
    public void sweepExpiredReservations() {
        expireDueReservations(clock.instant());
    }

    /**
     * Exposed with an explicit {@code asOf} instant so tests can drive one sweep cycle
     * deterministically instead of waiting on the scheduler or manipulating the system clock.
     */
    @Transactional
    public int expireDueReservations(Instant asOf) {
        List<Reservation> due = reservationRepository.findByStateAndExpiresAtBefore(ReservationState.HELD, asOf);
        for (Reservation reservation : due) {
            AccountBalance balance = accountBalanceRepository.findById(reservation.getLedgerAccountId())
                    .orElseThrow(() -> new IllegalStateException("ledger_account "
                            + reservation.getLedgerAccountId() + " has no account_balance row"));
            balance.moveFromReservedToAvailable(reservation.getAmountMinor());
            reservation.markExpired(clock.instant());
        }
        return due.size();
    }
}
