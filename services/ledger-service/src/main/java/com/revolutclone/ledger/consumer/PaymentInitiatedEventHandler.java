package com.revolutclone.ledger.consumer;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.EntryRejectedV1;
import com.revolutclone.contracts.events.PaymentInitiatedV1;
import com.revolutclone.ledger.domain.JournalEntryType;
import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import com.revolutclone.ledger.domain.PostingDirection;
import com.revolutclone.ledger.posting.EntryRejectionReason;
import com.revolutclone.ledger.posting.PostEntryCommand;
import com.revolutclone.ledger.posting.PostEntryResult;
import com.revolutclone.ledger.posting.PostingEngine;
import com.revolutclone.ledger.posting.PostingLine;
import com.revolutclone.messaging.api.EventHandler;
import com.revolutclone.messaging.api.OutboxAppender;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Step 2 of plans/phase-03-payments-and-ui.md's transfer saga: <em>ledger-svc consumes
 * PaymentInitiatedV1 → posts one journal entry: DEBIT sender wallet / CREDIT receiver wallet
 * [one tx] → emits EntryPostedV1 (or EntryRejectedV1 with a reason).</em>
 *
 * <p>DEBIT sender / CREDIT receiver is the sign convention from {@link PostingEngine}'s class
 * javadoc, not a coin flip: a user wallet is a liability of the bank to the customer, so a DEBIT
 * decreases the customer's money. Both events out are appended by
 * {@code PostingTransactionExecutor} inside the posting transaction, so this class emits nothing
 * on either of those paths - the entry and the event it announces cannot disagree.
 *
 * <p><strong>The ledger idempotency key is derived here, from the payment id</strong>
 * ({@code payment:<paymentId>}), and is not carried on the event at all - payment-service
 * deliberately never puts the client's {@code Idempotency-Key} header on the wire, because that
 * key is unique only per initiator while {@code journal_entry.idempotency_key} is globally
 * unique. Both sides derive the same string from {@code paymentId} instead, which is also what
 * makes a redelivered {@code PaymentInitiatedV1} that slips past {@code processed_event} (a
 * different {@code eventId} for the same payment - a republished outbox row) post nothing twice:
 * {@code PostingEngine} returns the existing entry for a key it has already seen.
 *
 * <p><strong>Not {@code @Transactional}, deliberately.</strong> {@link PostingEngine#postEntry}
 * is {@code @Retryable} on optimistic-lock failure and opens its own transaction one bean
 * further in; calling it from inside a transaction of this handler's own would retry inside a
 * transaction already marked rollback-only - the exact ordering bug plans/phase-02-ledger.md's
 * Risks &amp; gotchas warns about. The idempotency insert this handler is wrapped by runs in its
 * own {@code REQUIRES_NEW} transaction for the same reason.
 *
 * <p><strong>A rejection is not an exception.</strong> Insufficient funds, an unknown account, a
 * currency mismatch - all of them commit and emit {@link EntryRejectedV1} so payment-service can
 * move the payment to FAILED with a reason. Throwing would retry-then-dead-letter a message that
 * will never succeed, and leave the payment PENDING forever.
 */
@Component
public class PaymentInitiatedEventHandler implements EventHandler<PaymentInitiatedV1> {

    private static final Logger log = LoggerFactory.getLogger(PaymentInitiatedEventHandler.class);

    private static final String OUTBOX_TOPIC = "ledger.entry.v1";

    private final LedgerAccountRepository ledgerAccounts;
    private final PostingEngine postingEngine;
    private final OutboxAppender outboxAppender;

    public PaymentInitiatedEventHandler(LedgerAccountRepository ledgerAccounts, PostingEngine postingEngine,
            OutboxAppender outboxAppender) {
        this.ledgerAccounts = ledgerAccounts;
        this.postingEngine = postingEngine;
        this.outboxAppender = outboxAppender;
    }

    @Override
    public void handle(EventEnvelope<PaymentInitiatedV1> envelope) {
        PaymentInitiatedV1 event = envelope.payload();
        String idempotencyKey = ledgerIdempotencyKey(event.paymentId());

        Optional<LedgerAccount> sender = ledgerAccounts.findByExternalAccountId(event.senderAccountId());
        if (sender.isEmpty()) {
            rejectUnresolvedAccount(event, event.senderAccountId(), idempotencyKey);
            return;
        }
        Optional<LedgerAccount> receiver = ledgerAccounts.findByExternalAccountId(event.receiverAccountId());
        if (receiver.isEmpty()) {
            rejectUnresolvedAccount(event, event.receiverAccountId(), idempotencyKey);
            return;
        }

        PostEntryCommand command = new PostEntryCommand(idempotencyKey, JournalEntryType.TRANSFER,
                event.paymentId(),
                List.of(
                        new PostingLine(sender.get().getId(), PostingDirection.DEBIT, event.amountMinor(),
                                event.currency(), null),
                        new PostingLine(receiver.get().getId(), PostingDirection.CREDIT, event.amountMinor(),
                                event.currency(), null)));

        PostEntryResult result = postingEngine.postEntry(command);
        if (result instanceof PostEntryResult.Rejected rejected) {
            log.info("payment {} rejected by the ledger: {} on account {}", event.paymentId(), rejected.reason(),
                    rejected.accountId());
        }
    }

    /**
     * The one rejection reason {@link PostingEngine} cannot produce for this saga, because the
     * posting command cannot even be built without both ledger accounts. Emitted in exactly the
     * shape {@code PostingTransactionExecutor} uses for its own rejections, with the
     * <em>account-service</em> account id that failed to resolve - the only identifier
     * payment-service knows this account by.
     *
     * <p>Reachable whenever this service's own {@code AccountOpenedV1} projection lags the
     * transfer, which payment-service's read model has already accepted: an account opened
     * seconds ago can be valid there and still unknown here. Failing the payment with a reason
     * the UI can show beats leaving it PENDING with nothing to look at.
     */
    private void rejectUnresolvedAccount(PaymentInitiatedV1 event, UUID unresolvedAccountId, String idempotencyKey) {
        log.warn("payment {} names account-service account {}, which has no ledger_account - rejecting",
                event.paymentId(), unresolvedAccountId);
        outboxAppender.append(OUTBOX_TOPIC, unresolvedAccountId.toString(),
                new EntryRejectedV1(idempotencyKey, unresolvedAccountId,
                        EntryRejectionReason.ACCOUNT_NOT_FOUND.name(), event.paymentId().toString()));
    }

    /**
     * Mirrors {@code Payment.ledgerIdempotencyKey()} in payment-service - the same string derived
     * independently on both sides of the topic from the only thing that crosses it, the payment
     * id. {@code PostingTransactionExecutor} independently requires this prefix on a
     * {@code TRANSFER} entry.
     */
    private static String ledgerIdempotencyKey(UUID paymentId) {
        return "payment:" + paymentId;
    }
}
