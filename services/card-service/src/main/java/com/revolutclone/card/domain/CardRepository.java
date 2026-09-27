package com.revolutclone.card.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardRepository extends JpaRepository<Card, UUID> {

    /**
     * The authorization flow's one lookup: resolve a card from the network's PAN, hashed with
     * the same keyed HMAC every issued card's {@code pan_hash} was stored under. Never look up
     * by {@code panLast4} - see plans/phase-05-cards.md and Card's own javadoc: last4 is not
     * unique across cards.
     */
    Optional<Card> findByPanHash(byte[] panHash);

    /**
     * The reveal endpoint's ownership check: a card id that exists but belongs to somebody else
     * must read as "not found," not "forbidden" - same reasoning as {@code payment-service}'s
     * {@code SourceAccountNotFoundException}, so this endpoint cannot be used to probe which
     * card ids are real.
     */
    Optional<Card> findByIdAndUserId(UUID id, UUID userId);
}
