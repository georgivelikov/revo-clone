package com.revolutclone.notification.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The two reads this table has, plus the one write path: the feed, the unread badge, and marking a
 * single notification read. Both queries are covered by the indexes in {@code V3__notification.sql}.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(UUID userId);

    /**
     * Scoped by user, not just id: a notification is the only thing this service hands out, and
     * "can I read someone else's" must be answered by the query rather than by a later check that
     * somebody can forget to write.
     */
    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);
}
