package com.revolutclone.ledger.web;

import com.revolutclone.ledger.domain.AccountBalance;
import com.revolutclone.ledger.domain.AccountBalanceRepository;
import com.revolutclone.ledger.reconciliation.ReconciliationService;
import com.revolutclone.ledger.web.error.AccountBalanceNotFoundException;
import jakarta.validation.Valid;
import java.util.UUID;
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
 * {@code ## Verification} curl example.
 */
@RestController
@RequestMapping("/internal/ledger/admin")
public class AdminController {

    private final ReconciliationService reconciliationService;
    private final AccountBalanceRepository accountBalanceRepository;

    public AdminController(ReconciliationService reconciliationService,
            AccountBalanceRepository accountBalanceRepository) {
        this.reconciliationService = reconciliationService;
        this.accountBalanceRepository = accountBalanceRepository;
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
}
