package com.revolutclone.card.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardLimitRepository extends JpaRepository<CardLimit, CardLimit.Key> {

    /**
     * Every ceiling on one card, in one read. Step 5 of the authorization flow has a real time budget -
     * "steps 1-6 must complete in well under a second" - so it fetches all three periods at once rather
     * than asking per period, and the evaluator orders them in memory.
     */
    List<CardLimit> findByCardId(UUID cardId);
}
