package com.revolutclone.ledger.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    /** The idempotency check every posting operation starts with - see PostingEngine (task 02.4). */
    Optional<JournalEntry> findByIdempotencyKey(String idempotencyKey);
}
