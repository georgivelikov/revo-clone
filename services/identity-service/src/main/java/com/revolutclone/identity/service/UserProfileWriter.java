package com.revolutclone.identity.service;

import com.revolutclone.contracts.events.UserRegisteredV1;
import com.revolutclone.identity.domain.UserProfile;
import com.revolutclone.identity.domain.UserProfileRepository;
import com.revolutclone.identity.domain.UserStatus;
import com.revolutclone.messaging.api.OutboxAppender;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place that writes {@code user_profile} and appends {@link UserRegisteredV1} to the
 * outbox in a single local transaction - the "step 3" of
 * plans/phase-01-identity-account-gateway.md's registration sequence. A separate Spring bean
 * (rather than a private method on the caller) so {@code @Transactional} actually applies:
 * self-invocation within one class bypasses Spring's proxy.
 */
@Component
public class UserProfileWriter {

    /** Keyed by userId, compacted - see plans/phase-00-foundations.md's topic catalogue. */
    private static final String TOPIC = "identity.user.v1";

    private final UserProfileRepository repository;
    private final OutboxAppender outboxAppender;

    public UserProfileWriter(UserProfileRepository repository, OutboxAppender outboxAppender) {
        this.repository = repository;
        this.outboxAppender = outboxAppender;
    }

    @Transactional
    public UserProfile persistAndPublish(UUID id, String email, String firstName, String lastName,
                                          String country, UserStatus status) {
        UserProfile profile = new UserProfile(id, email, firstName, lastName, country, null, status);
        repository.save(profile);
        outboxAppender.append(TOPIC, id.toString(),
                new UserRegisteredV1(id, email, firstName, lastName, country));
        return profile;
    }
}
