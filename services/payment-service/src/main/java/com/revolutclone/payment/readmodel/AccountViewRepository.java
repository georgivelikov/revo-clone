package com.revolutclone.payment.readmodel;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The read side of the local account read model. This is the whole query surface
 * {@code POST /api/payments} validation needs (task 03.3): {@code findById} answers "does this
 * account exist, is it ACTIVE, what currency is it, who owns it" for both the source and the
 * target account, and {@link #findByUserId} answers "which accounts does the caller own" for
 * the accounts feed and the recipients endpoint.
 *
 * <p>Nothing outside {@code AccountOpenedEventHandler} writes through this repository - a
 * projection has exactly one writer, the consumer that replays the topic.
 */
public interface AccountViewRepository extends JpaRepository<AccountView, UUID> {

    List<AccountView> findByUserId(UUID userId);
}
