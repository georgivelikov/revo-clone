package com.revolutclone.ledger.web;

import com.revolutclone.ledger.domain.AccountBalanceRepository;
import com.revolutclone.ledger.domain.PostingRepository;
import com.revolutclone.ledger.web.error.AccountBalanceNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * plans/phase-02-ledger.md's {@code ### API}: current balance and a paginated statement. Both
 * are read-only service-to-service queries; a 404 on the balance endpoint means the
 * {@code ledger_account} itself doesn't exist (it always has exactly one {@code account_balance}
 * row - see every account-creation path in this service).
 */
@RestController
@RequestMapping("/internal/ledger/accounts")
public class AccountQueryController {

    private final AccountBalanceRepository accountBalanceRepository;
    private final PostingRepository postingRepository;

    public AccountQueryController(AccountBalanceRepository accountBalanceRepository,
            PostingRepository postingRepository) {
        this.accountBalanceRepository = accountBalanceRepository;
        this.postingRepository = postingRepository;
    }

    @GetMapping("/{id}/balance")
    public BalanceResponse balance(@PathVariable UUID id) {
        return accountBalanceRepository.findById(id)
                .map(BalanceResponse::from)
                .orElseThrow(() -> new AccountBalanceNotFoundException(id));
    }

    /**
     * Most-recent-first by insertion order. {@code posting} carries no timestamp of its own
     * (only {@code seq} within its entry) - see the schema - so {@code id} descending is the
     * best chronological proxy without adding a column, which is out of this task's scope.
     */
    @GetMapping("/{id}/postings")
    public List<PostingResponse> postings(@PathVariable UUID id,
            @PageableDefault(size = 50) Pageable pageable) {
        return postingRepository.findByLedgerAccountIdOrderByIdDesc(id, pageable).stream()
                .map(PostingResponse::from)
                .toList();
    }
}
