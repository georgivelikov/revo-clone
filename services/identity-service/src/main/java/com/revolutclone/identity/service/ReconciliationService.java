package com.revolutclone.identity.service;

import com.revolutclone.identity.domain.UserProfile;
import com.revolutclone.identity.domain.UserProfileRepository;
import com.revolutclone.identity.domain.UserStatus;
import com.revolutclone.security.api.IdentityProviderPort;
import com.revolutclone.security.api.ProviderUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Back-fills the one dual-write gap the outbox cannot cover: a provider (Keycloak) user
 * created in {@link com.revolutclone.identity.service.RegistrationService#register} whose
 * local transaction then failed, leaving no {@code user_profile} row.
 *
 * <p>Written against {@link IdentityProviderPort#listAll}, not against Keycloak, so this needs
 * no change if the adapter ever does. Country is unknown for a back-filled user - Keycloak was
 * never told it - so it is recorded as {@code ZZ} (ISO 3166's user-assigned "unknown" code)
 * with {@link UserStatus#PENDING}, flagging the row for review rather than guessing. Each
 * user is reconciled in its own transaction ({@link UserProfileWriter#persistAndPublish} is
 * called once per user, not wrapped in one transaction here) so one bad row does not roll back
 * ones already fixed.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final String UNKNOWN_COUNTRY = "ZZ";
    private static final int PAGE_SIZE = 100;

    private final IdentityProviderPort identityProviderPort;
    private final UserProfileRepository userProfileRepository;
    private final UserProfileWriter writer;

    public ReconciliationService(IdentityProviderPort identityProviderPort,
                                  UserProfileRepository userProfileRepository,
                                  UserProfileWriter writer) {
        this.identityProviderPort = identityProviderPort;
        this.userProfileRepository = userProfileRepository;
        this.writer = writer;
    }

    public List<UserProfile> reconcileOrphanedProviderUsers() {
        List<UserProfile> backfilled = new ArrayList<>();
        int page = 0;
        List<ProviderUser> batch;
        do {
            batch = identityProviderPort.listAll(page, PAGE_SIZE);
            for (ProviderUser providerUser : batch) {
                reconcileOne(providerUser).ifPresent(backfilled::add);
            }
            page++;
        } while (batch.size() == PAGE_SIZE);
        return backfilled;
    }

    private Optional<UserProfile> reconcileOne(ProviderUser providerUser) {
        UUID id;
        try {
            id = UUID.fromString(providerUser.providerUserId());
        } catch (IllegalArgumentException notAUuid) {
            log.warn("skipping reconcile for provider user {} - id is not a UUID and cannot map "
                    + "to user_profile's primary key", providerUser.providerUserId());
            return Optional.empty();
        }

        if (userProfileRepository.existsById(id)) {
            return Optional.empty();
        }

        UserProfile profile = writer.persistAndPublish(
                id,
                providerUser.email(),
                providerUser.firstName(),
                providerUser.lastName(),
                UNKNOWN_COUNTRY,
                UserStatus.PENDING);
        return Optional.of(profile);
    }
}
