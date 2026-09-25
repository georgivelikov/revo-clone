package com.revolutclone.ledger.web;

import com.revolutclone.ledger.posting.PostEntryResult;
import com.revolutclone.ledger.posting.PostingEngine;
import com.revolutclone.ledger.web.error.EntryRejectedException;
import com.revolutclone.ledger.web.error.InvalidLedgerRequestException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * plans/phase-02-ledger.md's {@code ### API}: {@code POST /internal/ledger/entries} posts a
 * balanced entry, idempotent on the caller-supplied (server-derived) {@code idempotencyKey}.
 * {@code /internal/**} is not routed by the gateway - callers are other services, still
 * JWT-authenticated per this service's own resource-server config (see
 * {@code config/SecurityConfig}).
 */
@RestController
@RequestMapping("/internal/ledger")
public class LedgerEntryController {

    private final PostingEngine postingEngine;

    public LedgerEntryController(PostingEngine postingEngine) {
        this.postingEngine = postingEngine;
    }

    @PostMapping("/entries")
    public ResponseEntity<PostEntryResponse> postEntry(@Valid @RequestBody PostEntryRequest request) {
        PostEntryResult result;
        try {
            result = postingEngine.postEntry(request.toCommand());
        } catch (IllegalArgumentException ex) {
            throw new InvalidLedgerRequestException(ex.getMessage());
        }

        if (result instanceof PostEntryResult.Rejected rejected) {
            throw new EntryRejectedException(rejected.reason(), rejected.accountId(), rejected.idempotencyKey());
        }

        PostEntryResult.Posted posted = (PostEntryResult.Posted) result;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PostEntryResponse.from(posted.entry(), posted.postings()));
    }
}
