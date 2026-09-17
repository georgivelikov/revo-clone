package com.revolutclone.ledger.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostingRepository extends JpaRepository<Posting, UUID> {

    List<Posting> findByEntryId(UUID entryId);

    /** Paginated statement - GET /internal/ledger/accounts/{id}/postings (task 02.9). */
    List<Posting> findByLedgerAccountIdOrderByIdDesc(UUID ledgerAccountId, Pageable pageable);
}
