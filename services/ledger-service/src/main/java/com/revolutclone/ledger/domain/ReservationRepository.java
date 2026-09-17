package com.revolutclone.ledger.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    Optional<Reservation> findByIdempotencyKey(String idempotencyKey);

    /** The expiry sweep's query (task 02.6 / ReservationExpiryTest): HELD rows past expiresAt. */
    List<Reservation> findByStateAndExpiresAtBefore(ReservationState state, Instant now);
}
