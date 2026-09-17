package com.revolutclone.ledger.posting;

import com.revolutclone.contracts.events.EntryPostedV1;
import com.revolutclone.contracts.events.EntryRejectedV1;
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
import com.revolutclone.messaging.api.OutboxAppender;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The actual transactional work {@link PostingEngine} delegates to (task 02.5's retry wrapper).
 * Deliberately a SEPARATE bean, not just a separate method on {@code PostingEngine}: Spring's
 * {@code @Retryable} and {@code @Transactional} are both proxy-based AOP, and a call from one
 * annotated method to another annotated method ON THE SAME BEAN ("self-invocation") bypasses the
 * proxy entirely - the callee's annotation is silently a no-op. Putting the transactional work on
 * a distinct bean that {@link PostingEngine} calls through its injected reference means both
 * proxies - the retry interceptor wrapping {@code PostingEngine.postEntry}, and the transaction
 * interceptor wrapping this class's {@code postEntry} - are genuinely invoked. See the phase's own
 * Risks &amp; gotchas: "The retry must wrap outside the transaction, or you retry inside a
 * transaction already marked rollback-only."
 *
 * <p>All business logic, validation, the sign convention and the balance-column-semantics table
 * live on {@link PostingEngine}'s javadoc, which is the class callers actually depend on; this
 * class is intentionally undocumented beyond this note; read {@link PostingEngine} first.
 */
@Service
class PostingTransactionExecutor {

    private static final Map<JournalEntryType, String> REQUIRED_IDEMPOTENCY_KEY_PREFIX = Map.of(
            JournalEntryType.TRANSFER, "payment:",
            JournalEntryType.FX, "fxquote:",
            JournalEntryType.CARD_SETTLEMENT, "cardauth:");

    private static final String OUTBOX_TOPIC = "ledger.entry.v1";

    private final LedgerAccountRepository ledgerAccountRepository;
    private final AccountBalanceRepository accountBalanceRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final PostingRepository postingRepository;
    private final OutboxAppender outboxAppender;

    PostingTransactionExecutor(LedgerAccountRepository ledgerAccountRepository,
            AccountBalanceRepository accountBalanceRepository, JournalEntryRepository journalEntryRepository,
            PostingRepository postingRepository, OutboxAppender outboxAppender) {
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.accountBalanceRepository = accountBalanceRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.postingRepository = postingRepository;
        this.outboxAppender = outboxAppender;
    }

    @Transactional
    PostEntryResult postEntry(PostEntryCommand command) {
        validateIdempotencyKeyPrefix(command);
        if (command.postings().size() < 2) {
            throw new IllegalArgumentException(
                    "a journal entry needs at least two postings, got " + command.postings().size());
        }
        validatePerCurrencyBalance(command);

        Optional<JournalEntry> existing = journalEntryRepository.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            JournalEntry entry = existing.get();
            return new PostEntryResult.Posted(entry, postingRepository.findByEntryId(entry.getId()));
        }

        // Validate every line's account BEFORE writing anything - a rejected entry must leave
        // no trace in journal_entry/posting/account_balance.
        for (PostingLine line : command.postings()) {
            Optional<EntryRejectionReason> rejection = validateLine(line);
            if (rejection.isPresent()) {
                return reject(command, line.ledgerAccountId(), rejection.get());
            }
        }

        JournalEntry entry = new JournalEntry(UUID.randomUUID(), command.idempotencyKey(), command.type(),
                command.referenceId(), null, null);
        journalEntryRepository.save(entry);

        List<Posting> postings = new ArrayList<>();
        int seq = 1;
        for (PostingLine line : command.postings()) {
            Posting posting = new Posting(UUID.randomUUID(), entry.getId(), line.ledgerAccountId(),
                    line.direction(), line.amountMinor(), line.currency(), line.reservationId(), seq++);
            postingRepository.save(posting);
            postings.add(posting);

            AccountBalance balance = accountBalanceRepository.findById(line.ledgerAccountId())
                    .orElseThrow(() -> new IllegalStateException(
                            "ledger_account " + line.ledgerAccountId() + " has no account_balance row"));
            applyToBalance(balance, line);

            outboxAppender.append(OUTBOX_TOPIC, line.ledgerAccountId().toString(),
                    new EntryPostedV1(
                            entry.getId(),
                            line.ledgerAccountId(),
                            line.direction().name(),
                            signedAmount(line),
                            line.currency(),
                            balance.getAvailableMinor(),
                            entry.getType().name(),
                            referenceIdString(entry.getReferenceId())));
        }

        return new PostEntryResult.Posted(entry, postings);
    }

    /** DEBIT/CREDIT with no reservation, or a capture DEBIT - see PostingEngine's class javadoc's table. */
    private void applyToBalance(AccountBalance balance, PostingLine line) {
        if (line.reservationId() != null) {
            balance.releaseReservedOnCapture(line.amountMinor());
        } else if (line.direction() == PostingDirection.DEBIT) {
            balance.debitAvailable(line.amountMinor());
        } else {
            balance.creditAvailable(line.amountMinor());
        }
    }

    private Optional<EntryRejectionReason> validateLine(PostingLine line) {
        LedgerAccount account = ledgerAccountRepository.findById(line.ledgerAccountId()).orElse(null);
        if (account == null) {
            return Optional.of(EntryRejectionReason.ACCOUNT_NOT_FOUND);
        }
        if (!account.getCurrency().equals(line.currency())) {
            return Optional.of(EntryRejectionReason.CURRENCY_MISMATCH);
        }
        // Available balance must never go negative for USER_WALLET/VAULT; system accounts
        // (FX_POOL, FEE_INCOME, EXTERNAL_CLEARING, ...) are allowed to, since that is what
        // makes them the counterparty. A capturing DEBIT (reservationId set) decrements
        // reserved, not available, so it is exempt from this check - ReservationService (task
        // 02.6) owns ensuring a capture never exceeds what was actually held.
        if (line.reservationId() == null && line.direction() == PostingDirection.DEBIT
                && !account.getType().isSystemAccount()) {
            AccountBalance balance = accountBalanceRepository.findById(line.ledgerAccountId())
                    .orElseThrow(() -> new IllegalStateException(
                            "ledger_account " + line.ledgerAccountId() + " has no account_balance row"));
            long spendable = balance.getAvailableMinor() - balance.getReservedMinor();
            if (spendable < line.amountMinor()) {
                return Optional.of(EntryRejectionReason.INSUFFICIENT_FUNDS);
            }
        }
        return Optional.empty();
    }

    private PostEntryResult reject(PostEntryCommand command, UUID accountId, EntryRejectionReason reason) {
        outboxAppender.append(OUTBOX_TOPIC, accountId.toString(),
                new EntryRejectedV1(command.idempotencyKey(), accountId, reason.name(),
                        referenceIdString(command.referenceId())));
        return new PostEntryResult.Rejected(reason, accountId, command.idempotencyKey());
    }

    /**
     * {@code amount_minor} is always positive in the database - direction carries the sign,
     * per {@link PostingDirection}'s javadoc. {@code EntryPostedV1.amountMinor} is documented
     * as signed per direction instead, which is what a downstream consumer building a running
     * total actually wants.
     */
    private static long signedAmount(PostingLine line) {
        return line.direction() == PostingDirection.DEBIT ? -line.amountMinor() : line.amountMinor();
    }

    private static String referenceIdString(UUID referenceId) {
        return referenceId == null ? null : referenceId.toString();
    }

    /**
     * At least two postings and, for each currency present, {@code sum(debits) == sum(credits)}
     * - the code-level half of the balancing invariant; {@code V4__balanced_trigger.sql} is the
     * database half.
     */
    private void validatePerCurrencyBalance(PostEntryCommand command) {
        Map<String, Long> netByCurrency = new HashMap<>();
        for (PostingLine line : command.postings()) {
            long signed = line.direction() == PostingDirection.CREDIT ? line.amountMinor() : -line.amountMinor();
            netByCurrency.merge(line.currency(), signed, Long::sum);
        }
        for (Map.Entry<String, Long> net : netByCurrency.entrySet()) {
            if (net.getValue() != 0L) {
                throw new IllegalArgumentException("entry is not balanced for currency " + net.getKey()
                        + ": net " + net.getValue() + " minor units");
            }
        }
    }

    /**
     * {@code idempotency_key} must be server-derived, never a client's raw
     * {@code Idempotency-Key} header - see the {@code journal_entry} schema notes in
     * plans/phase-02-ledger.md. {@code TRANSFER}/{@code FX}/{@code CARD_SETTLEMENT} entries are
     * always caused by a specific upstream request and must carry that request's prefixed
     * reference ({@code payment:}/{@code fxquote:}/{@code cardauth:}); {@code FEE},
     * {@code ADJUSTMENT} and {@code REVERSAL} entries are system/admin-initiated (funding,
     * reconciliation corrections, fee sweeps) and carry no per-user collision risk in the same
     * way, so only non-blankness is required of their key.
     */
    private void validateIdempotencyKeyPrefix(PostEntryCommand command) {
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        String requiredPrefix = REQUIRED_IDEMPOTENCY_KEY_PREFIX.get(command.type());
        if (requiredPrefix != null && !command.idempotencyKey().startsWith(requiredPrefix)) {
            throw new IllegalArgumentException("idempotencyKey for a " + command.type()
                    + " entry must start with \"" + requiredPrefix + "\", got \"" + command.idempotencyKey() + "\"");
        }
    }
}
