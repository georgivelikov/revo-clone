package com.revolutclone.card.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CardAuthorizationRepository extends JpaRepository<CardAuthorization, UUID> {

    /**
     * The card flow's idempotency lookup. {@code network_ref} is unique in the schema, so this is
     * the read half of a decision the database enforces regardless: the network resends, and a
     * resend must find the decision already made rather than make it again.
     */
    Optional<CardAuthorization> findByNetworkRef(String networkRef);

    /**
     * How much this card has spent since {@code windowOpenedAt} - the rolling-window read behind step 5.
     *
     * <p><strong>The window is half-open: {@code authorizedAt > windowOpenedAt}.</strong> An
     * authorization exactly one window old has left it. That is the boundary
     * {@code LimitExceededTest} pins down, and strict comparison is the only choice that makes the
     * window a constant width - with {@code >=}, a 24-hour window would contain 24 hours plus one
     * instant, and a limit reached at exactly the boundary would stay reached forever.
     *
     * <p><strong>It sums {@code billingMinor}, not {@code amountMinor}</strong>, and filters on
     * {@code billingCurrency}: a limit is denominated in the wallet's currency, so the comparable
     * figure is what the wallet was charged, not what the merchant asked for. Rows whose billing
     * amount was never established have a null there, and {@code sum} ignores nulls - but they are
     * excluded by state anyway, since the only way to reach a decision with no billing amount is to be
     * declined before step 4.
     *
     * <p><strong>{@code states} is the caller's, not a constant here</strong>, because "spend" is a
     * policy: {@code CardLimitEvaluator} passes the states in which money is either held or gone, and
     * deliberately not {@code DECLINED}, {@code REVERSED} or {@code EXPIRED} - a purchase that was
     * refused, reversed or never captured consumed nothing and must not spend the holder's limit.
     *
     * <p>Returns 0 rather than null when nothing matches, so the caller has no empty case to handle.
     */
    @Query("""
            select coalesce(sum(a.billingMinor), 0)
            from CardAuthorization a
            where a.cardId = :cardId
              and a.billingCurrency = :billingCurrency
              and a.state in :states
              and a.authorizedAt > :windowOpenedAt
            """)
    long sumBillingMinorSince(@Param("cardId") UUID cardId,
            @Param("billingCurrency") String billingCurrency,
            @Param("states") Collection<AuthorizationState> states,
            @Param("windowOpenedAt") Instant windowOpenedAt);
}
