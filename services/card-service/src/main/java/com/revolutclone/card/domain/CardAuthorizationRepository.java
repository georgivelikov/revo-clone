package com.revolutclone.card.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardAuthorizationRepository extends JpaRepository<CardAuthorization, UUID> {

    /**
     * The card flow's idempotency lookup. {@code network_ref} is unique in the schema, so this is
     * the read half of a decision the database enforces regardless: the network resends, and a
     * resend must find the decision already made rather than make it again.
     */
    Optional<CardAuthorization> findByNetworkRef(String networkRef);
}
