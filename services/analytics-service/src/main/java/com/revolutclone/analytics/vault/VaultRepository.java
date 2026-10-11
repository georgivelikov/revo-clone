package com.revolutclone.analytics.vault;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VaultRepository extends JpaRepository<Vault, UUID> {

    List<Vault> findByUserId(UUID userId);

    /** A 404, never a 403, for another user's vault - see {@link VaultNotFoundException}. */
    Optional<Vault> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByAccountId(UUID accountId);
}
