package com.revolutclone.ledger.web;

import com.revolutclone.ledger.domain.AccountBalance;
import com.revolutclone.ledger.domain.AccountBalanceRepository;
import com.revolutclone.ledger.domain.JournalEntryType;
import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import com.revolutclone.ledger.domain.LedgerAccountType;
import com.revolutclone.ledger.domain.PostingDirection;
import com.revolutclone.ledger.domain.SystemAccountRefs;
import com.revolutclone.ledger.posting.PostEntryCommand;
import com.revolutclone.ledger.posting.PostEntryResult;
import com.revolutclone.ledger.posting.PostingEngine;
import com.revolutclone.ledger.posting.PostingLine;
import com.revolutclone.ledger.reconciliation.ReconciliationService;
import com.revolutclone.ledger.web.error.AccountBalanceNotFoundException;
import com.revolutclone.ledger.web.error.EntryRejectedException;
import com.revolutclone.ledger.web.error.InvalidLedgerRequestException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * plans/phase-02-ledger.md's ### Reconciliation: an admin trigger for the reconciliation job, and
 * a deliberate-drift endpoint so the job's safety net can actually be watched catching something.
 * {@code /internal/ledger/admin/reconcile} is the exact path shown in the phase's own
 * {@code ## Verification} curl example. Also task 02.11's funding endpoint, for the same reason
 * every one of these exists: development/testing convenience, not a real bank operation - a real
 * institution never lets anyone conjure money from EXTERNAL_CLEARING on request, but conjuring it
 * IS what a "clearing" account represents at the boundary of this system, and CLAUDE.md scopes
 * regulation/compliance out of this project entirely.
 */
@RestController
@RequestMapping("/internal/ledger/admin")
public class AdminController {

    private final ReconciliationService reconciliationService;
    private final AccountBalanceRepository accountBalanceRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final PostingEngine postingEngine;

    public AdminController(ReconciliationService reconciliationService,
            AccountBalanceRepository accountBalanceRepository, LedgerAccountRepository ledgerAccountRepository,
            PostingEngine postingEngine) {
        this.reconciliationService = reconciliationService;
        this.accountBalanceRepository = accountBalanceRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.postingEngine = postingEngine;
    }

    @PostMapping("/reconcile")
    public ResponseEntity<ReconcileResponse> reconcile() {
        return ResponseEntity.ok(ReconcileResponse.from(reconciliationService.reconcile()));
    }

    /**
     * Deliberately desynchronizes {@code available_minor} from {@code sum(postings)} so the
     * reconciliation job has something to actually catch - see {@link AccountBalance#corruptAvailableForTesting}.
     * Never a real money movement; not reachable through {@code PostingEngine}.
     */
    @PostMapping("/corrupt-balance/{ledgerAccountId}")
    @Transactional
    public ResponseEntity<Void> corruptBalance(@PathVariable UUID ledgerAccountId,
            @Valid @RequestBody CorruptBalanceRequest request) {
        AccountBalance balance = accountBalanceRepository.findById(ledgerAccountId)
                .orElseThrow(() -> new AccountBalanceNotFoundException(ledgerAccountId));
        balance.corruptAvailableForTesting(request.deltaMinor());
        return ResponseEntity.ok().build();
    }

    /**
     * Task 02.11: "A funding endpoint (EXTERNAL_CLEARING -> wallet) so later phases have money
     * to move." Posts a real balanced entry through {@link PostingEngine} - DEBIT the
     * {@code EXTERNAL_CLEARING} system account for this currency, CREDIT the target wallet -
     * exactly the shape the phase's own {@code ## Verification} curl example demonstrates for
     * funding {@code alice}'s wallet. Never a raw balance mutation like {@code corrupt-balance}:
     * this creates a genuine, balanced, reconciliation-safe journal entry. The idempotency key
     * is server-derived (never client-supplied, per the schema's own hard rule) since this is a
     * fire-and-forget convenience call, not a request a caller needs to safely retry with a
     * caller-chosen key.
     */
    @PostMapping("/fund/{ledgerAccountId}")
    public ResponseEntity<PostEntryResponse> fund(@PathVariable UUID ledgerAccountId,
            @Valid @RequestBody FundWalletRequest request) {
        LedgerAccount target = ledgerAccountRepository.findById(ledgerAccountId)
                .orElseThrow(() -> new AccountBalanceNotFoundException(ledgerAccountId));
        if (!target.getCurrency().equals(request.currency())) {
            throw new InvalidLedgerRequestException("account " + ledgerAccountId + " is " + target.getCurrency()
                    + ", not " + request.currency());
        }
        UUID externalClearingId = ledgerAccountRepository
                .findByOwnerRefAndTypeAndCurrency(SystemAccountRefs.EXTERNAL_CLEARING,
                        LedgerAccountType.EXTERNAL_CLEARING, request.currency())
                .orElseThrow(() -> new InvalidLedgerRequestException(
                        "no EXTERNAL_CLEARING system account for currency " + request.currency()))
                .getId();

        PostEntryResult result = postingEngine.postEntry(new PostEntryCommand(
                "adjustment:fund:" + UUID.randomUUID(), JournalEntryType.ADJUSTMENT, ledgerAccountId,
                List.of(
                        new PostingLine(externalClearingId, PostingDirection.DEBIT, request.amountMinor(),
                                request.currency(), null),
                        new PostingLine(ledgerAccountId, PostingDirection.CREDIT, request.amountMinor(),
                                request.currency(), null))));

        if (result instanceof PostEntryResult.Rejected rejected) {
            throw new EntryRejectedException(rejected.reason(), rejected.accountId(), rejected.idempotencyKey());
        }
        PostEntryResult.Posted posted = (PostEntryResult.Posted) result;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PostEntryResponse.from(posted.entry(), posted.postings()));
    }
}
